import 'dart:convert';
import 'package:crypto/crypto.dart';
import '../core/json.dart';
import '../domain/links.dart';
import '../domain/models.dart';
import '../domain/xunlei_kouling.dart';
import 'http.dart';

class CloudCommandKeys {
  const CloudCommandKeys({
    this.quark = const String.fromEnvironment('ASTERLINK_QUARK_COMMAND_KEY'),
    this.uc = const String.fromEnvironment('ASTERLINK_UC_COMMAND_KEY'),
  });
  final String quark, uc;
}

/// Resolves only the explicitly selected provider. Account authentication and
/// downloading remain the responsibility of the normal share connector.
class CloudCommandResolver {
  CloudCommandResolver(
    this.http, {
    this.keys = const CloudCommandKeys(),
    DateTime Function()? clock,
  }) : clock = clock ?? DateTime.now;

  final JsonHttp http;
  final CloudCommandKeys keys;
  final DateTime Function() clock;
  static const platforms = [
    CloudPlatform.quark,
    CloudPlatform.xunlei,
    CloudPlatform.uc,
  ];
  static const maximumLength = 2000;

  Future<ParsedLink> resolve(CloudPlatform platform, String text) async {
    require(platforms.contains(platform), '请选择夸克、迅雷或 UC 网盘');
    require(text.trim().isNotEmpty, '请先粘贴${platform.shortName}口令');
    require(text.length <= maximumLength, '口令内容过长，请只复制本次分享文案');
    RequestScope.checkpoint();
    if (platform == CloudPlatform.xunlei) {
      require(XunleiKouling.looksLike(text), '请粘贴迅雷中文口令；分享链接请使用链接解析');
      final response = await http
          .get(XunleiKouling.jumpUrl(XunleiKouling.normalize(text)), {
            'User-Agent': XunleiKouling.userAgent,
            'Accept': '*/*',
            'Origin': XunleiKouling.origin,
            'Referer': XunleiKouling.referer,
          });
      RequestScope.checkpoint();
      final url = XunleiKouling.shareUrlFromResponse(
        response.status,
        response.json,
      )!;
      return LinkParser.parse(url).single;
    }
    final body = buildRequest(platform, text, clock().millisecondsSinceEpoch);
    final response = await http.postJsonRead(
      'https://utoken2.${platform == CloudPlatform.quark ? 'quark' : 'uc'}.cn/utoken/v2/parse',
      body,
      {'Accept': 'application/json'},
    );
    RequestScope.checkpoint();
    require(
      response.successful,
      '${platform.shortName}口令服务暂不可用（HTTP ${response.status}），请稍后重试',
    );
    return decodeResponse(platform, response.json);
  }

  Json buildRequest(CloudPlatform platform, String clipboard, int timestamp) {
    require(
      platform == CloudPlatform.quark || platform == CloudPlatform.uc,
      '不支持此网盘口令',
    );
    require(
      clipboard.trim().isNotEmpty && clipboard.length <= maximumLength,
      '请粘贴有效口令',
    );
    var identifier = '', secret = '';
    final quark = platform == CloudPlatform.quark;
    if (quark) {
      final matches = RegExp(
        r'(咐置)(.+?)叩苓|(/~)([A-Za-z0-9_-]{6,128})~:/',
      ).allMatches(clipboard).toList();
      require(matches.isNotEmpty, '请粘贴「咐置…叩苓」或「/~…~:/」格式的夸克口令');
      require(matches.length == 1, '一次只能解析一条口令，请只保留本次分享文案');
      final match = matches.single;
      identifier = (match[1] ?? match[3])!;
      secret = (match[2] ?? match[4])!;
    }
    final key = utf8.encode(quark ? keys.quark : keys.uc);
    require(
      key.length == (quark ? 88 : 24),
      '当前安装包未配置${platform.shortName}口令解析，请使用分享链接',
    );
    final app = quark ? 'QUARK' : 'UC';
    // Clipboard bytes are preserved. No Unicode normalization, trimming,
    // JSON escaping, or Base64 decoding of the UTF-8 signing material.
    final input = '$app$clipboard$identifier$secret$timestamp';
    final signature = Hmac(sha1, key).convert(utf8.encode(input)).toString();
    return {
      'app': app,
      'clipboard': clipboard,
      'identifier': identifier,
      'shareSecret': secret,
      'timestamp': '$timestamp',
      'sign': '${quark ? '2ee1' : '03e9'}$signature',
      'kps': '',
    };
  }

  static ParsedLink decodeResponse(CloudPlatform platform, Json response) {
    require(
      platform == CloudPlatform.quark || platform == CloudPlatform.uc,
      '不支持此网盘口令',
    );
    require(
      response.boolean('success') && response.str('code') == 'OK',
      '${platform.shortName}口令解析失败，口令可能已失效，请重新复制或使用分享链接',
    );
    final data = response.obj('data');
    for (final target in [data.str('androidUrl'), data.str('iosUrl')]) {
      try {
        final uri = Uri.parse(target);
        final quark = platform == CloudPlatform.quark;
        // Some short commands return a web share rather than an app deep link.
        if (uri.scheme == 'https' &&
            uri.host == (quark ? 'pan.quark.cn' : 'drive.uc.cn') &&
            uri.userInfo.isEmpty) {
          final links = LinkParser.parse(target);
          if (links.length == 1 &&
              links.single.platform == platform &&
              links.single.isCloudShare) {
            return links.single;
          }
        }
        // Uri.queryParameters already decodes percent escapes once.
        final query = uri.queryParameters;
        final payload = asJson(
          jsonDecode(query[quark ? 'qk_params' : 'clouddrive_params'] ?? ''),
        );
        final share = quark
            ? payload.obj('params').obj('query')
            : payload.obj('additionProps');
        final id = share.str('pwd_id');
        final code = share['passcode'] ?? '';
        if (!RegExp(r'^[A-Za-z0-9_-]{1,128}$').hasMatch(id) ||
            code is! String ||
            code.length > 128) {
          continue;
        }
        final url = 'https://${quark ? 'pan.quark.cn' : 'drive.uc.cn'}/s/$id';
        return ParsedLink(
          source: code.isEmpty ? url : '$url\n提取码：$code',
          url: url,
          kind: LinkKind.cloudShare,
          platform: platform,
          shareId: id,
          passcode: code.isEmpty ? null : code,
        );
      } on FormatException {
        continue;
      }
    }
    throw AppException('${platform.shortName}口令响应中没有有效分享，请改用分享链接');
  }
}
