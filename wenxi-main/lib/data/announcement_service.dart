import 'dart:async';
import 'package:flutter/foundation.dart';
import '../core/json.dart';
import '../diagnostics/app_log.dart';
import '../domain/announcements.dart';
import '../domain/remote_control.dart';
import 'remote_control_http.dart';
import 'state_store.dart';

class AnnouncementService extends ChangeNotifier {
  AnnouncementService(
    this.store, {
    String url = buildUrl,
    RemoteControlFetcher? fetcher,
    this.clock = DateTime.now,
  }) : _fetcher = fetcher ?? DioRemoteControlFetcher() {
    if (url.trim().isNotEmpty) {
      try {
        final uri = httpsUri(url.trim());
        if (uri.hasFragment) throw const FormatException('公告地址不能含片段');
        _endpoint = uri;
      } on FormatException {
        _error = '公告地址无效，请联系维护者';
      }
    }
    _restore();
  }

  static const buildUrl = String.fromEnvironment('ASTERLINK_ANNOUNCEMENTS_URL');
  static const cacheKey = 'announcementFeed';
  static const refreshInterval = Duration(minutes: 15);
  static const retryInterval = Duration(minutes: 1);
  static const maxReadHistory = 512;
  final StateStore store;
  final RemoteControlFetcher _fetcher;
  final DateTime Function() clock;
  Uri? _endpoint;
  List<AnnouncementMessage> _messages = const [];
  final _read = <String>{};
  DateTime? _lastSuccess, _lastAttempt;
  Future<bool>? _pending;
  Future<void>? _writing;
  Timer? _timer;
  bool _foreground = false, _closed = false, _dirty = false;
  String? _error, _saveError;
  int _skippedItems = 0;

  bool get configured => _endpoint != null;
  bool get checking => _pending != null;
  bool get hasCache => _lastSuccess != null;
  DateTime? get lastSuccess => _lastSuccess;
  String? get error => _error;
  String? get saveError => _saveError;
  int get skippedItems => _skippedItems;
  List<AnnouncementMessage> get messages => _messages;
  int get unreadCount => _messages.where((item) => !isRead(item)).length;
  bool isRead(AnnouncementMessage message) =>
      _read.contains(message.contentKey);

  void _restore() {
    if (!configured) return;
    try {
      final saved = store.data.obj(cacheKey);
      if (saved['endpoint'] != _endpoint.toString()) return;
      final stamp = saved['fetchedAt'];
      if (stamp is! int || stamp <= 0) return;
      final feed = AnnouncementFeed.fromJson(saved['feed']);
      _messages = feed.messages;
      _lastSuccess = DateTime.fromMillisecondsSinceEpoch(stamp);
      final read = saved['read'];
      if (read is List) {
        _read.addAll(
          read
              .whereType<String>()
              .where((key) => RegExp(r'^[a-f0-9]{64}$').hasMatch(key))
              .take(maxReadHistory),
        );
      }
    } catch (_) {
      DiagnosticLog.event('announcements.invalid_cache');
    }
  }

  void setForeground(bool value) {
    if (_closed) return;
    _foreground = value;
    _timer?.cancel();
    if (!value || !configured) return;
    unawaited(refresh());
    _schedule();
  }

  void _schedule() {
    _timer?.cancel();
    if (_closed || !_foreground || !configured || checking) return;
    final interval = _error == null ? refreshInterval : retryInterval;
    final remaining = (_lastAttempt ?? clock())
        .add(interval)
        .difference(clock());
    _timer = Timer(
      remaining > Duration.zero ? remaining : const Duration(seconds: 1),
      () => unawaited(refresh()),
    );
  }

  Future<bool> refresh({bool force = false}) {
    if (_closed || !configured) return Future.value(false);
    if (_pending case final pending?) return pending;
    final interval = _error == null ? refreshInterval : retryInterval;
    final last = _lastAttempt;
    if (!force && last != null) {
      final age = clock().difference(last);
      if (age >= Duration.zero && age < interval) {
        return Future.value(_error == null);
      }
    }
    final completer = Completer<bool>();
    _pending = completer.future;
    _lastAttempt = clock();
    notifyListeners();
    unawaited(
      _fetch().then((success) {
        _pending = null;
        if (!_closed) {
          _schedule();
          notifyListeners();
        }
        completer.complete(success);
      }),
    );
    return completer.future;
  }

  Future<bool> _fetch() async {
    try {
      final text = await _fetcher.fetch(_endpoint!);
      if (_closed) return false;
      final feed = AnnouncementFeed.decode(text);
      _messages = feed.messages;
      _skippedItems = feed.skippedItems;
      _lastSuccess = clock();
      _error = null;
      _queueSave();
      return true;
    } catch (error) {
      if (_closed) return false;
      _error = error is ControlAccessException && error.statusCode == 404
          ? '公告文件尚未发布，请稍后再试'
          : error is FormatException && error is! ControlAccessException
          ? '公告文件格式有误，请稍后再试'
          : '暂时无法获取公告，请检查网络后重试';
      DiagnosticLog.event(
        'announcements.fetch_failed',
        fields: {'type': error.runtimeType.toString(), 'hasCache': hasCache},
      );
      return false;
    }
  }

  Future<void> markRead(AnnouncementMessage message) => _markRead([message]);
  Future<void> markAllRead() => _markRead(_messages);

  Future<void> _markRead(Iterable<AnnouncementMessage> messages) async {
    if (_closed) return;
    var changed = false;
    for (final message in messages) {
      changed = _read.add(message.contentKey) || changed;
    }
    if (!changed) return;
    while (_read.length > maxReadHistory) {
      _read.remove(_read.first);
    }
    notifyListeners();
    _queueSave();
    await _writing;
  }

  void _queueSave() {
    _dirty = true;
    _startSave();
  }

  void _startSave() {
    if (_writing != null || !_dirty || !hasCache) return;
    _writing = _save().whenComplete(() {
      _writing = null;
      if (_dirty && _saveError == null) _startSave();
      if (!_closed) notifyListeners();
    });
  }

  Future<void> _save() async {
    while (_dirty) {
      _dirty = false;
      final snapshot = {
        'endpoint': _endpoint.toString(),
        'fetchedAt': _lastSuccess!.millisecondsSinceEpoch,
        'feed': AnnouncementFeed(_messages).toJson(),
        'read': _read.toList(),
      };
      try {
        await store.put(cacheKey, snapshot);
        _saveError = null;
      } catch (_) {
        _dirty = true;
        _saveError = '公告已读取，暂时无法保存到本机';
        DiagnosticLog.event('announcements.save_failed');
        return;
      }
    }
  }

  Future<void> flush() async {
    _startSave();
    await _writing;
  }

  Future<void> close() async {
    dispose();
    await flush();
  }

  @override
  void dispose() {
    if (_closed) return;
    _closed = true;
    _timer?.cancel();
    _fetcher.close();
    super.dispose();
  }
}
