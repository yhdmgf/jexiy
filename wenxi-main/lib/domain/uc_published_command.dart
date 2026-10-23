import 'dart:convert';

import 'package:html/parser.dart' as html;

import '../core/json.dart';

/// Parses a published UC command without executing any document scripts.
class UcPublishedCommand {
  static final _bootstrapStart = RegExp(
    r'^window\.appData\s*=\s*JSON\.parse\(\s*decodeURIComponent\(\s*',
  );
  static final _bootstrapEnd = RegExp(r'^\s*\)\s*\)');
  static final _command = RegExp(
    r'[執执][\u3400-\u4dbf\u4e00-\u9fffA-Za-z0-9]{1,128}?生效',
  );

  static String _window(String text, int start) =>
      text.substring(start, (start + 160).clamp(start, text.length));

  static String? _bootstrapLiteral(String page) {
    const marker = 'window.appData';
    var offset = 0;
    while (true) {
      final start = page.indexOf(marker, offset);
      if (start < 0) return null;
      // Match only the short call header. Repeating regex alternatives over
      // the entire encoded document exhausts the AOT interpreter's stack.
      final header = _bootstrapStart.firstMatch(_window(page, start));
      if (header == null) {
        offset = start + marker.length;
        continue;
      }
      final quote = start + header.end;
      require(
        quote < page.length && page.codeUnitAt(quote) == 0x22,
        '每日口令页面数据格式异常',
      );
      var cursor = quote + 1;
      while (cursor < page.length) {
        final char = page.codeUnitAt(cursor);
        if (char == 0x5c) {
          // Skip an escaped code unit, including escaped quotes/backslashes.
          cursor += 2;
        } else if (char == 0x22) {
          final end = cursor + 1;
          require(_bootstrapEnd.hasMatch(_window(page, end)), '每日口令页面数据不完整');
          return page.substring(quote, end);
        } else {
          cursor++;
        }
      }
      throw const AppException('每日口令页面数据不完整');
    }
  }

  static String extract(String page) {
    var content = page;
    final literal = _bootstrapLiteral(page);
    if (literal != null) {
      try {
        final encoded = jsonDecode(literal) as String;
        final doc = asJson(jsonDecode(Uri.decodeComponent(encoded))).obj('doc');
        content = [
          doc.str('body_html'),
          doc.obj('_cachedContent').str('body'),
          doc.str('body'),
        ].firstWhere((body) => body.trim().isNotEmpty, orElse: () => '');
      } on FormatException {
        throw const AppException('每日口令页面数据格式异常');
      } on ArgumentError {
        throw const AppException('每日口令页面数据格式异常');
      }
      require(content.isNotEmpty, '每日口令页面没有公开正文');
    }

    final document = html.parse(content);
    for (final node in document.querySelectorAll(
      'script, style, noscript, template, img, svg, [hidden], '
      '[aria-hidden="true"]',
    )) {
      node.remove();
    }
    final body =
        document.querySelector(
          'article, .markdown-body, .ne-viewer-body, main',
        ) ??
        document.body;
    require(body != null, '每日口令页面没有有效正文');
    Set<String> commands(String text) => _command
        .allMatches(text.replaceAll(RegExp(r'[\u200b-\u200d\u2060\uFEFF]'), ''))
        .map((match) => match[0]!)
        .toSet();

    // The copyable code block takes precedence over old instructional text.
    final candidates = <String>{
      for (final code in body!.querySelectorAll('pre, code'))
        ...commands(code.text),
    };
    if (candidates.isEmpty) candidates.addAll(commands(body.text));
    require(candidates.length == 1, '每日口令页面没有唯一的 UC 口令');
    return candidates.single;
  }
}
