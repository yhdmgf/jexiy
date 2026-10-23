import 'dart:async';
import 'dart:convert';

import '../../core/json.dart';
import '../../diagnostics/app_log.dart';
import '../../domain/models.dart';
import '../../download/download_request.dart';
import '../cloud_command_resolver.dart';
import '../http.dart';
import '../http_retry.dart';
import '../state_store.dart';
import 'baidu_public_command.dart';
import 'uc.dart';

enum BaiduPublicPasswordSource { uc, gitee }

class BaiduPublicPassword {
  const BaiduPublicPassword(
    this.value,
    this.fetchedAt, {
    this.source = BaiduPublicPasswordSource.uc,
  });

  final String value;
  final DateTime fetchedAt;
  final BaiduPublicPasswordSource source;
}

/// A device-wide daily cache shared by startup prefetch and share downloads.
class BaiduPublicPasswords {
  BaiduPublicPasswords(
    this.http, {
    this.store,
    this.commandKeys = const CloudCommandKeys(),
    DateTime Function()? clock,
    Future<String> Function()? fetch,
    Future<String> Function()? fetchBackup,
  }) : clock = clock ?? DateTime.now,
       _fetch = fetch,
       _fetchBackup = fetchBackup;

  final JsonHttp http;
  final CredentialStore? store;
  final CloudCommandKeys commandKeys;
  final DateTime Function() clock;
  final Future<String> Function()? _fetch;
  final Future<String> Function()? _fetchBackup;
  static const sourceUrl = BaiduPublicCommandSource.pageUrl;
  static const _legacySourceCommand = '執心急如焚核桃仁生效';
  static const backupUrl = 'https://gitee.com/dzys/cs/raw/master/ls.json';
  static const cacheKey = 'publicParser.mfDg22.password.v1';
  static const prefixBytes = 4096;
  static const _retryDelay = Duration(minutes: 2);
  static const _unavailable = AppException('公益解析密码暂时无法自动更新，请稍后重试或切换为“账号下载”');
  static final _han = RegExp(
    r'[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff\u{20000}-\u{2fa1f}\u{30000}-\u{323af}]',
    unicode: true,
  );

  BaiduPublicPassword? _cached;
  Future<BaiduPublicPassword>? _pending;
  bool _loaded = false,
      _rejected = false,
      _legacyCache = false,
      _closed = false;
  RequestScope? _activeScope;
  DateTime? _retryAt;
  final _rejectedValues = <String, DateTime>{};
  final _writes = AsyncGate();

  static bool _valid(String value) =>
      value.isNotEmpty &&
      value.length <= prefixBytes &&
      _han.allMatches(value).map((match) => match[0]!).join() == value;

  static String extract(String prefix) {
    // The source may be named .txt.zip; read it as data, never as an archive.
    final first = prefix
        .split(RegExp(r'\r\n|\n|\r'))
        .first
        .replaceFirst(RegExp(r'^\uFEFF'), '');
    require(
      first.isNotEmpty &&
          first.length <= prefixBytes &&
          !RegExp(r'[\x00-\x08\x0b\x0c\x0e-\x1f\uFFFD<>]').hasMatch(first),
      '公益解析密码文件的第一行格式异常',
    );
    final value = _han.allMatches(first).map((match) => match[0]!).join();
    require(_valid(value), '公益解析密码文件的第一行没有有效中文密码');
    return value;
  }

  static String extractBackup(String text) {
    final content = text.replaceFirst(RegExp(r'^\uFEFF'), '').trim();
    if (content.startsWith('{') ||
        content.startsWith('"') ||
        content.startsWith('[')) {
      try {
        final json = jsonDecode(content);
        final root = asJson(json);
        final data = root.obj('data');
        final value = json is String
            ? json
            : root['password'] ??
                  root['parse_password'] ??
                  root['密码'] ??
                  data['password'] ??
                  data['parse_password'];
        require(value is String, '远程备用密码格式异常');
        return extract(value as String);
      } on FormatException {
        throw const AppException('远程备用密码格式异常');
      }
    }
    // Plain text is supported even when the repository file ends in .json.
    return extract(text);
  }

  static int _day(DateTime time) {
    final beijing = time.toUtc().add(const Duration(hours: 8));
    return beijing.year * 10000 + beijing.month * 100 + beijing.day;
  }

  void _load() {
    if (_loaded) return;
    _loaded = true;
    try {
      final saved = store?.secret(cacheKey);
      if (saved == null || saved.length > prefixBytes + 1024) return;
      final data = asJson(jsonDecode(saved));
      final legacy =
          data.integer('schema') == 1 &&
          data.str('source') == _legacySourceCommand;
      if (!(legacy ||
              (data.integer('schema') == 2 &&
                  data.str('source') == sourceUrl)) ||
          !_valid(data.str('value')) ||
          data.integer('fetchedAt') <= 0) {
        return;
      }
      _cached = BaiduPublicPassword(
        data.str('value'),
        DateTime.fromMillisecondsSinceEpoch(
          data.integer('fetchedAt'),
          isUtc: true,
        ),
        source: data.str('provider') == 'gitee'
            ? BaiduPublicPasswordSource.gitee
            : BaiduPublicPasswordSource.uc,
      );
      _legacyCache = legacy;
      _rejected = data.boolean('rejected');
      if (_rejected) {
        _rejectedValues[_cached!.value] = clock().toUtc().add(_retryDelay);
      }
    } catch (_) {
      // A corrupt optional cache cannot affect account login or app startup.
    }
  }

  Future<void> _persist() => _writes.run(() async {
    final cached = _cached;
    if (cached == null || store == null || _closed) return;
    try {
      await store!.putSecret(
        cacheKey,
        encoded({
          'schema': _legacyCache ? 1 : 2,
          'source': _legacyCache ? _legacySourceCommand : sourceUrl,
          'value': cached.value,
          'fetchedAt': cached.fetchedAt.millisecondsSinceEpoch,
          'rejected': _rejected,
          'provider': cached.source.name,
        }),
      );
    } catch (_) {
      DiagnosticLog.event(
        'baidu.public_password',
        fields: {'stage': 'cache_write_failed'},
      );
    }
  });

  Future<BaiduPublicPassword> get({bool backupOnly = false}) async {
    RequestScope.checkpoint();
    if (_closed) throw _unavailable;
    _load();
    if (_pending case final pending?) {
      return RequestScope.cancellable(pending);
    }
    final now = clock().toUtc(), cached = _cached;
    if (cached != null &&
        !_rejected &&
        !_legacyCache &&
        !now.isBefore(cached.fetchedAt) &&
        _day(now) == _day(cached.fetchedAt)) {
      return cached;
    }
    final retryAt = _retryAt;
    if (retryAt != null &&
        now.isBefore(retryAt) &&
        retryAt.difference(now) <= _retryDelay) {
      if (cached != null && !_rejected) return cached;
      throw _unavailable;
    }

    final done = Completer<BaiduPublicPassword>();
    _pending = done.future;
    final scope = RequestScope();
    _activeScope = scope;
    // One cancelled download must not cancel the shared daily password lookup.
    unawaited(
      scope.run(() async {
        try {
          final lookup =
              ReadRetryScope(
                retries: 1,
                checkpoint: RequestScope.checkpoint,
              ).run(
                () => const DownloadRequestContext(
                  id: 'public-parser-password',
                  retries: 0,
                ).run(() => _lookup(backupOnly: backupOnly)),
              );
          final fresh = await RequestScope.cancellable(lookup).timeout(
            const Duration(seconds: 60),
            onTimeout: () {
              scope.cancel();
              throw _unavailable;
            },
          );
          RequestScope.checkpoint();
          _cached = fresh;
          _legacyCache = false;
          _rejected = false;
          _retryAt = null;
          await _persist();
          DiagnosticLog.event(
            'baidu.public_password',
            fields: {'stage': 'updated', 'source': fresh.source.name},
          );
          done.complete(fresh);
        } catch (error) {
          if (_closed) {
            done.completeError(_unavailable);
            return;
          }
          _retryAt = clock().toUtc().add(_retryDelay);
          final fallback = _cached;
          final usable = fallback != null && !_rejected;
          DiagnosticLog.event(
            'baidu.public_password',
            fields: {
              'stage': usable ? 'using_previous_cache' : 'update_failed',
              'errorType': error.runtimeType.toString(),
            },
          );
          if (usable) {
            done.complete(fallback);
          } else {
            done.completeError(_unavailable);
          }
        } finally {
          _pending = null;
          _activeScope = null;
          scope.cancel();
        }
      }, inheritGuard: false),
    );
    return RequestScope.cancellable(done.future);
  }

  /// Background failures must never prevent opening the app or show a dialog.
  Future<void> prefetch() async {
    if (_closed) return;
    try {
      await get();
    } catch (_) {
      // get() records the stage and keeps any usable previous password.
    }
  }

  Future<void> close() async {
    if (_closed) return;
    _closed = true;
    _activeScope?.cancel();
    try {
      await _pending;
    } catch (_) {
      // Cancelling the shared request completes all waiting callers.
    }
    await _writes.run(() async {});
  }

  Future<BaiduPublicPassword> refreshRejected(
    BaiduPublicPassword used, {
    bool backupOnly = false,
  }) async {
    await reject(used, deferRetry: false);
    return get(backupOnly: backupOnly);
  }

  Future<void> reject(
    BaiduPublicPassword used, {
    bool deferRetry = true,
  }) async {
    // Late responses cannot invalidate a newer password fetched by another task.
    if (!identical(_cached, used)) return;
    _rejected = true;
    _rejectedValues[used.value] = clock().toUtc().add(_retryDelay);
    while (_rejectedValues.length > 8) {
      _rejectedValues.remove(_rejectedValues.keys.first);
    }
    if (deferRetry) _retryAt = clock().toUtc().add(_retryDelay);
    await _persist();
  }

  Future<BaiduPublicPassword> _lookup({required bool backupOnly}) async {
    for (final source in BaiduPublicPasswordSource.values) {
      if (backupOnly && source == BaiduPublicPasswordSource.uc) continue;
      final scope = RequestScope(), parent = RequestScope.current;
      var finished = false;
      unawaited(
        parent?.whenCancel.then((_) {
          if (!finished) scope.cancel();
        }),
      );
      try {
        final value = await scope
            .run(() async {
              final value = await RequestScope.cancellable(
                source == BaiduPublicPasswordSource.uc
                    ? (_fetch?.call() ?? _readSource())
                    : (_fetchBackup?.call() ?? _readBackup()),
              );
              RequestScope.checkpoint();
              require(_valid(value), '公益解析密码文件格式异常');
              return value;
            })
            .timeout(
              Duration(
                seconds: source == BaiduPublicPasswordSource.uc ? 45 : 12,
              ),
              onTimeout: () {
                scope.cancel();
                throw _unavailable;
              },
            );
        RequestScope.checkpoint();
        final rejectedUntil = _rejectedValues[value];
        if (rejectedUntil != null && clock().toUtc().isBefore(rejectedUntil)) {
          DiagnosticLog.event(
            'baidu.public_password',
            fields: {'stage': 'source_still_rejected', 'source': source.name},
          );
          continue;
        }
        return BaiduPublicPassword(value, clock().toUtc(), source: source);
      } catch (error) {
        RequestScope.checkpoint();
        DiagnosticLog.event(
          'baidu.public_password',
          fields: {
            'stage': 'source_unavailable',
            'source': source.name,
            'errorType': error.runtimeType.toString(),
          },
        );
      } finally {
        finished = true;
        scope.cancel();
      }
    }
    throw _unavailable;
  }

  Future<String> _readSource() async {
    // A separate connector with no store or credential stays anonymous even
    // when the app has UC accounts or has disabled guest downloads in settings.
    final command = await BaiduPublicCommandSource(http, clock: clock).read();
    RequestScope.checkpoint();
    final uc = UcConnector(http);
    final link = await CloudCommandResolver(
      http,
      keys: commandKeys,
    ).resolve(CloudPlatform.uc, command);
    final session = await uc.openShare(link, null);
    final files = (await uc.list(
      session,
      session.rootId,
      null,
    )).where((file) => !file.isDirectory).toList();
    final textFiles = files.where(
      (file) =>
          RegExp(r'\.txt(?:\.zip)?$', caseSensitive: false).hasMatch(file.name),
    );
    final candidates = files.length == 1 ? files : textFiles.toList();
    require(candidates.length == 1, '公益解析密码来源没有唯一的文本文件');
    final spec = await uc.download(session, candidates.single, null);
    final response = await http.peek(
      spec.url,
      {
        ...spec.headers,
        'Accept-Encoding': 'identity',
        'Cache-Control': 'no-cache',
      },
      maxBytes: prefixBytes,
      followRedirects: false,
    );
    RequestScope.checkpoint();
    final contentType = response.header('content-type').toLowerCase();
    require(
      {200, 206}.contains(response.status) &&
          !contentType.contains('html') &&
          !contentType.contains('json') &&
          !contentType.contains('xml'),
      '公益解析密码文件读取失败',
    );
    if (response.status == 206) {
      final range = RegExp(
        r'^bytes 0-(\d+)/(\d+)$',
      ).firstMatch(response.header('content-range'));
      require(
        range != null &&
            int.parse(range[1]!) < prefixBytes &&
            (spec.expectedSize <= 0 ||
                int.parse(range[2]!) == spec.expectedSize),
        '公益解析密码文件内容不完整',
      );
    }
    require(
      spec.expectedSize <= prefixBytes ||
          response.body.contains(RegExp(r'[\r\n]')),
      '公益解析密码文件的第一行过长',
    );
    return extract(response.body);
  }

  Future<String> _readBackup() async {
    var url = Uri.parse(
      backupUrl,
    ).replace(queryParameters: {'_t': '${clock().millisecondsSinceEpoch}'});
    late HttpResult response;
    for (var redirects = 0; ; redirects++) {
      response = await http.peek(
        url.toString(),
        const {
          'Accept': 'text/plain, application/json;q=0.9',
          'Cache-Control': 'no-cache',
          'Accept-Encoding': 'identity',
        },
        maxBytes: prefixBytes,
        followRedirects: false,
      );
      RequestScope.checkpoint();
      if (!{301, 302, 303, 307, 308}.contains(response.status)) break;
      final location = response.header('location');
      require(redirects < 4 && location.isNotEmpty, '远程备用密码跳转异常');
      final next = url.resolve(location);
      // Gitee serves raw repository files through its own content domain.
      require(
        next.scheme == 'https' &&
            next.userInfo.isEmpty &&
            (next.host == 'gitee.com' ||
                next.host == 'giteeusercontent.com' ||
                next.host.endsWith('.giteeusercontent.com')),
        '远程备用密码跳转地址无效',
      );
      url = next;
    }
    RequestScope.checkpoint();
    require(
      {200, 206}.contains(response.status) &&
          !response.header('content-type').toLowerCase().contains('html') &&
          utf8.encode(response.body).length < prefixBytes,
      '远程备用密码暂不可用',
    );
    if (response.status == 206) {
      final range = RegExp(
        r'^bytes 0-(\d+)/(\d+)$',
      ).firstMatch(response.header('content-range'));
      require(
        range != null && int.parse(range[2]!) < prefixBytes,
        '远程备用密码内容不完整',
      );
    }
    return extractBackup(response.body);
  }
}
