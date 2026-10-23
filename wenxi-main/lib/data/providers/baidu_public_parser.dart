import '../../core/json.dart';
import '../../domain/baidu_share_route.dart';
import '../../domain/models.dart';
import '../http.dart';
import '../state_store.dart';
import 'baidu_public_password.dart';

/// Share-only integration. Requests contain no personal account credentials.
class BaiduPublicParser {
  BaiduPublicParser(
    this.http, {
    CredentialStore? store,
    BaiduPublicPasswords? passwords,
  }) : passwords = passwords ?? BaiduPublicPasswords(http, store: store);

  final JsonHttp http;
  final BaiduPublicPasswords passwords;
  static const site = 'https://mf.dg22.top';
  static const _requestUa = 'Dart/3.13 (dart:io)';
  static const _fallbackDownloadUa =
      'Mozilla/5.0 (Linux; Android 15) Chrome/134.0 Mobile';
  final _initialLists = <String, List<CloudFile>>{};
  final _downloadGate = AsyncGate();

  String _surl(ParsedLink link) {
    require(
      link.platform == CloudPlatform.baidu &&
          link.kind == LinkKind.cloudShare &&
          RegExp(r'^[A-Za-z0-9_-]+$').hasMatch(link.shareId ?? ''),
      '百度分享链接不完整，请重新解析',
    );
    // LinkParser stores the short ID without the /s/1 prefix.
    return '1${link.shareId}';
  }

  Future<Json> _request(String operation, Json body) async {
    RequestScope.checkpoint();
    var password = await passwords.get();
    for (var attempt = 0; attempt < 3; attempt++) {
      final (result, response) = await _send(operation, body, password.value);
      // Live password failures use HTTP 400 + code 20016. This is distinct
      // from a Baidu share's extraction code or the personal account login.
      if (response?.integer('code') == 20016) {
        if (attempt < 2) {
          password = await passwords.refreshRejected(
            password,
            backupOnly: attempt == 1,
          );
          continue;
        }
        await passwords.reject(password);
        throw const AppException('公益解析密码更新后仍未通过验证，请稍后重试或切换为“账号下载”');
      }
      if (!result.successful) {
        throw HttpRequestFailure(
          '百度公益解析暂不可用（HTTP ${result.status}），可切换为“账号下载”',
          kind: 'baidu_public_parser',
          status: result.status,
          retryable: {408, 502, 503, 504}.contains(result.status),
        );
      }
      if (response == null) {
        throw const AppException('百度公益解析响应格式异常，可切换为“账号下载”');
      }
      if (response.integer('code') != 200) {
        var message = response.str('message').trim();
        if (message.isEmpty || message.length > 180 || message.contains('<')) {
          message = '解析站返回错误（${response.str('code').ifEmpty('未知')}）';
        }
        // Do not echo a password if an upstream error happens to contain it.
        message = message.replaceAll(password.value, '[已隐藏]');
        throw AppException('百度公益解析：$message，可切换为“账号下载”');
      }
      return response;
    }
    throw const AppException('公益解析密码更新失败，可切换为“账号下载”');
  }

  Future<(HttpResult, Json?)> _send(
    String operation,
    Json body,
    String password,
  ) async {
    RequestScope.checkpoint();
    final result = await http.request(
      'POST',
      '$site/api/v1/user/parse/$operation',
      body: encoded({...body, 'parse_password': password}),
      headers: const {
        'User-Agent': _requestUa,
        'Accept': 'application/json',
        'Origin': site,
        'Referer': '$site/user/parse',
      },
      contentType: 'application/json;charset=utf-8',
      followRedirects: false,
    );
    RequestScope.checkpoint();
    require(result.body.length <= 8 * 1024 * 1024, '百度公益解析响应过大，请缩小目录范围');
    Json? response;
    try {
      response = result.json;
    } on AppException {
      // Preserve HTTP errors even when the body is a gateway HTML page.
    }
    return (result, response);
  }

  Future<Json> _fileList(ParsedLink link, String parent) async {
    require(parent.startsWith('/'), '百度分享文件夹路径无效，请重新解析');
    final surl = _surl(link);
    final response = await _request('get_file_list', {
      'url': 'https://pan.baidu.com/s/$surl',
      'surl': surl,
      'pwd': link.passcode ?? '',
      'dir': parent,
    });
    final data = response.obj('data');
    require(data['list'] is List, '百度公益解析未返回文件列表，请稍后重试');
    return data;
  }

  List<CloudFile> _files(Json data, String parent) {
    final files = <CloudFile>[];
    final seen = <String>{};
    for (final entry in data.list('list')) {
      final id = entry.str('fs_id');
      final name = entry.str('server_filename').ifEmpty(entry.str('filename'));
      final directory =
          entry.integer('isdir') == 1 ||
          entry.boolean('isdir') ||
          entry.integer('is_dir') == 1 ||
          entry.boolean('is_dir');
      final path = entry.str('path');
      require(
        id.isNotEmpty &&
            name.isNotEmpty &&
            (!directory || path.startsWith('/')),
        '百度公益解析文件信息不完整，请重新解析',
      );
      if (!seen.add(id)) continue;
      final hash = entry.str('md5');
      final hasHash = RegExp(r'^[a-fA-F0-9]{32}$').hasMatch(hash);
      files.add(
        CloudFile(
          id: id,
          name: name,
          size: entry.integer('size'),
          isDirectory: directory,
          parentId: parent,
          token: path,
          modifiedAt: entry
              .str('server_mtime')
              .ifEmpty(entry.str('local_mtime')),
          hashType: hasHash ? 'md5' : null,
          hashValue: hasHash ? hash.toLowerCase() : null,
        ),
      );
    }
    return files;
  }

  Future<BrowseSession> openShare(ParsedLink link) async {
    final data = await _fileList(link, '/');
    require(
      data.str('uk').isNotEmpty && data.str('shareid').isNotEmpty,
      '百度公益解析缺少分享信息，请重新解析',
    );
    final initialId = newId();
    _initialLists[initialId] = _files(data, '/');
    while (_initialLists.length > 16) {
      _initialLists.remove(_initialLists.keys.first);
    }
    return BrowseSession(
      platform: CloudPlatform.baidu,
      mode: BrowseMode.share,
      title: data.str('title').ifEmpty('百度分享'),
      rootId: '/',
      metadata: {
        BaiduShareRoute.metadataKey: BaiduShareRoute.publicParser.key,
        'shortId': link.shareId!,
        'shareId': data.str('shareid'),
        'uk': data.str('uk'),
        'sekey': data.str('randsk'),
        'parserInitialList': initialId,
      },
      sourceLink: link,
    );
  }

  ParsedLink _link(BrowseSession session) {
    require(
      BaiduShareRoute.usesPublicParser(session) && session.sourceLink != null,
      '原百度分享信息缺失，请重新解析',
    );
    return session.sourceLink!;
  }

  Future<List<CloudFile>> list(BrowseSession session, String parent) async {
    final link = _link(session);
    if (parent == '/') {
      final cached = _initialLists.remove(session.meta('parserInitialList'));
      if (cached != null) return List.of(cached);
    }
    return _files(await _fileList(link, parent), parent);
  }

  Future<DownloadSpec> download(BrowseSession session, CloudFile file) =>
      _downloadGate.run(() async {
        final link = _link(session);
        require(!file.isDirectory, '文件夹不能直接下载');
        final fileId = int.tryParse(file.id);
        require(fileId != null && fileId > 0, '百度文件标识无效，请重新解析');
        final parent = file.parentId.ifEmpty('/');
        require(parent.startsWith('/'), '百度分享文件夹路径无效，请重新解析');
        final response = await _request('get_download_links', {
          'randsk': session.meta('sekey'),
          'uk': session.meta('uk'),
          'shareid': session.meta('shareId'),
          'fs_id': [fileId],
          'surl': _surl(link),
          'dir': parent,
          'pwd': link.passcode ?? '',
          'token': 'guest',
          'download_folder': 0,
        });
        final entries = response.list('data');
        final item = entries
            .where((entry) => entry.str('fs_id') == file.id)
            .firstOrNull;
        require(item != null, '百度公益解析返回的文件不一致，请重新解析');
        final candidates = item!['urls'];
        final urls = candidates is List
            ? candidates.whereType<String>()
            : const <String>[];
        final uri = urls
            .map(Uri.tryParse)
            .whereType<Uri>()
            .where(
              (uri) =>
                  {'http', 'https'}.contains(uri.scheme) &&
                  uri.userInfo.isEmpty &&
                  !uri.hasFragment &&
                  ['baidu.com', 'baidupcs.com', 'baidubce.com'].any(
                    (domain) =>
                        uri.host == domain || uri.host.endsWith('.$domain'),
                  ),
            )
            .firstOrNull;
        require(uri != null, '百度公益解析没有返回有效下载地址，可切换为“账号下载”');
        // Live responses use "ua"; older site versions used "user_agent".
        final ua = item
            .str('ua')
            .ifEmpty(item.str('user_agent'))
            .ifEmpty(_fallbackDownloadUa);
        require(
          ua.length <= 1024 && !RegExp(r'[\x00-\x1f\x7f]').hasMatch(ua),
          '百度公益解析返回的下载参数无效',
        );
        return DownloadSpec(
          url: uri.toString(),
          fileName: file.name,
          expectedSize: file.size,
          checksumType: file.hashType,
          checksumValue: file.hashValue,
          headers: {'User-Agent': ua, 'Accept-Encoding': 'identity'},
          profile: BaiduShareRoute.publicDownloadProfile,
        );
      });
}
