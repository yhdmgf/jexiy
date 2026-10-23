import 'dart:async';
import 'dart:convert';

import '../../core/json.dart';
import '../../diagnostics/app_log.dart';
import '../../domain/auth.dart';
import '../../domain/uc_published_command.dart';
import '../http.dart';

/// Reads the published document as data; page scripts are never executed.
class BaiduPublicCommandSource {
  BaiduPublicCommandSource(this.http, {DateTime Function()? clock})
    : clock = clock ?? DateTime.now;

  final JsonHttp http;
  final DateTime Function() clock;
  static const pageUrl = 'https://www.yuque.com/xwpzs/fbdtqk/tc7tfdy07d1cerae';
  static const maximumBytes = 1024 * 1024;
  static const _unavailable = AppException('每日口令页面暂不可用');
  static String extract(String page) => UcPublishedCommand.extract(page);

  Future<String> read() async {
    RequestScope.checkpoint();
    final scope = RequestScope(), parent = RequestScope.current;
    var finished = false;
    unawaited(
      parent?.whenCancel.then((_) {
        if (!finished) scope.cancel();
      }),
    );
    try {
      return await scope
          .run(() => RequestScope.cancellable(_read()))
          .timeout(
            const Duration(seconds: 12),
            onTimeout: () => throw _unavailable,
          );
    } finally {
      finished = true;
      scope.cancel();
    }
  }

  Future<String> _read() async {
    // The public markdown export also carries the full document in appData
    // when Yuque serves an HTML shell instead of a plain markdown response.
    var url = Uri.parse(
      '$pageUrl/markdown',
    ).replace(queryParameters: {'_t': '${clock().millisecondsSinceEpoch}'});
    late HttpResult response;
    for (var redirects = 0; ; redirects++) {
      response = await http.peek(
        url.toString(),
        const {
          'User-Agent': WebLoginTarget.desktopUserAgent,
          'Accept': 'text/html, text/markdown;q=0.9, text/plain;q=0.8',
          'Accept-Encoding': 'identity',
          'Cache-Control': 'no-cache',
        },
        maxBytes: maximumBytes,
        followRedirects: false,
      );
      RequestScope.checkpoint();
      if (!{301, 302, 303, 307, 308}.contains(response.status)) break;
      final location = response.header('location');
      require(redirects < 3 && location.isNotEmpty, '每日口令页面跳转异常');
      final next = url.resolve(location);
      require(
        next.scheme == 'https' &&
            next.userInfo.isEmpty &&
            {'www.yuque.com', 'yuque.com'}.contains(next.host),
        '每日口令页面跳转地址无效',
      );
      url = next;
    }
    require(
      {200, 206}.contains(response.status) &&
          utf8.encode(response.body).length < maximumBytes,
      '每日口令页面读取失败',
    );
    if (response.status == 206) {
      final range = RegExp(
        r'^bytes 0-(\d+)/(\d+)$',
      ).firstMatch(response.header('content-range'));
      require(
        range != null &&
            int.parse(range[1]!) + 1 == int.parse(range[2]!) &&
            int.parse(range[2]!) < maximumBytes,
        '每日口令页面内容不完整',
      );
    }
    try {
      final command = extract(response.body);
      DiagnosticLog.event(
        'baidu.public_password',
        fields: {'stage': 'command_resolved', 'source': 'yuque'},
      );
      return command;
    } catch (error) {
      DiagnosticLog.event(
        'baidu.public_password',
        fields: {
          'stage': 'command_parse_failed',
          'source': 'yuque',
          'errorType': error.runtimeType.toString(),
        },
      );
      rethrow;
    }
  }
}
