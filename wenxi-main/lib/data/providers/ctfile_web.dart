import 'dart:math';
import 'package:crypto/crypto.dart';
import '../../core/json.dart';
import '../../domain/auth.dart';
import '../../domain/links.dart';
import '../../domain/models.dart';
import '../../domain/uploads.dart';
import '../http.dart';
import '../uploads/upload_io.dart';
import 'token_session.dart' show checkedCloudUrl;

/// The current website exchanges its login cookie for a short-lived v4 token.
/// Legacy OpenAPI session tokens use a different API and cannot substitute it.
class CtfileWeb {
  CtfileWeb(this.http, {this.pollDelay = const Duration(milliseconds: 500)});
  final JsonHttp http;
  final Duration pollDelay;
  static const base = 'https://api.ctfile.com/v4';
  static const headers = {
    'User-Agent': WebLoginTarget.desktopUserAgent,
    'Referer': 'https://my.ctfile.com/',
    'Accept-Language': 'zh-CN',
  };
  final _sessions = <String, Future<_WebSession>>{};

  static bool accepts(Credential? c) =>
      c != null &&
      (c.field('authType') == 'webCookie' ||
          LoginCredentials.cookiePairs(
            c.primary,
          ).containsKey('ctfile_session'));

  Json _checked(HttpResult response) {
    RequestScope.checkpoint();
    if (response.status == 401) {
      throw const AccountLoginRequired('城通登录已过期，请重新登录');
    }
    final data = response.json;
    final code = data.integer('code');
    final message = data.str('message');
    if (code == 401) {
      throw AccountLoginRequired(message.ifEmpty('城通登录已过期，请重新登录'));
    }
    require(
      response.successful && code == 200,
      message.ifEmpty('城通请求失败（$code）'),
    );
    return {...data, ...data.obj('data')};
  }

  Future<_WebSession> _exchange(Credential c) async {
    final data = _checked(
      await http.postJsonRead(
        '$base/user/auth/exchange-token',
        {'rotate_token': false},
        {...headers, 'Cookie': c.primary},
      ),
    );
    final token = data.str('token');
    final id = data.str('userid').ifEmpty(data.obj('user').str('userid'));
    require(token.isNotEmpty && id.isNotEmpty, '城通未返回有效登录信息，请重新登录');
    require(
      c.field('userId').isEmpty || c.field('userId') == id,
      '城通登录身份已变化，请重新登录',
    );
    return _WebSession(
      token,
      DateTime.now().add(
        Duration(seconds: max(1, data.integer('expires_in', 900) - 30)),
      ),
    );
  }

  Future<_WebSession> _session(Credential c) async {
    final pending = _sessions[c.primary];
    if (pending != null) {
      final result = await pending;
      if (DateTime.now().isBefore(result.expires)) return result;
      if (identical(_sessions[c.primary], pending)) _sessions.remove(c.primary);
    }
    if (_sessions.length >= 32) _sessions.remove(_sessions.keys.first);
    final next = _sessions.putIfAbsent(c.primary, () => _exchange(c));
    try {
      return await next;
    } catch (_) {
      if (identical(_sessions[c.primary], next)) _sessions.remove(c.primary);
      rethrow;
    }
  }

  Future<Json> request(
    Credential c,
    String path,
    Json body, {
    bool read = true,
  }) async {
    for (var attempt = 0; ; attempt++) {
      final session = await _session(c);
      RequestScope.checkpoint();
      try {
        return _checked(
          await (read ? http.postJsonRead : http.postJson)(
            '$base/$path',
            body,
            {...headers, 'Authorization': 'Bearer ${session.token}'},
          ),
        );
      } on AccountLoginRequired {
        if (attempt > 0) rethrow;
        // An explicit authentication rejection permits a single fresh-token retry.
        _sessions.remove(c.primary);
      }
    }
  }

  Future<List<Json>> _workspaces(Credential c) async {
    final result = <Json>[];
    final seen = <String>{};
    var start = 0;
    for (var page = 0; page < 1000; page++) {
      final data = await request(c, 'workspace/manage/list', {
        'start': start,
        'pagesize': 100,
      });
      require(data['workspaces'] is List, '城通未返回存储空间');
      final rows = data.list('workspaces');
      for (final row in rows) {
        require(
          RegExp(r'^[1-9]\d*$').hasMatch(row.str('id')) &&
              seen.add(row.str('id')),
          '城通存储空间列表异常',
        );
        result.add(row);
      }
      if (!data.boolean('has_more')) return result;
      final next = data.integer('next_start', start + rows.length);
      require(rows.isNotEmpty && next > start, '城通存储空间分页未前进');
      start = next;
    }
    throw const AppException('城通存储空间过多，请稍后重试');
  }

  Future<LoginResult> authenticate(Credential c) async {
    final profile = await request(c, 'user/profile/info', {});
    final spaces = await _workspaces(c);
    final id = profile.str('userid');
    require(id.isNotEmpty, '城通未返回账号标识，请重新登录');
    final owned = spaces.where((s) => s.str('owner_id') == id);
    final account = CloudAccount(
      profile
          .str('nick_name')
          .ifEmpty(
            profile
                .str('display_name')
                .ifEmpty(profile.str('username').ifEmpty('城通用户')),
          ),
      used: owned.fold<int>(0, (sum, row) => sum + row.integer('space_used')),
      total: owned.fold<int>(0, (sum, row) => sum + row.integer('space_quota')),
    );
    return LoginResult(
      c.withFields({
        'userId': id,
        'nickname': account.nickname,
        'authType': 'webCookie',
      }),
      account,
    );
  }

  Future<List<CloudSpace>> spaces(Credential c) async {
    final rows = await _workspaces(c);
    rows.sort((a, b) {
      final defaultOrder = (b.boolean('is_default') ? 1 : 0).compareTo(
        a.boolean('is_default') ? 1 : 0,
      );
      return defaultOrder != 0
          ? defaultOrder
          : a.integer('is_private').compareTo(b.integer('is_private'));
    });
    require(rows.isNotEmpty, '城通账号暂无可访问的存储空间');
    return [
      for (final row in rows)
        CloudSpace(
          row.str('id'),
          row
              .str('name')
              .ifEmpty(row.integer('is_private') == 1 ? '私有空间' : '公开空间'),
        ),
    ];
  }

  Future<BrowseSession> open(Credential c, [CloudSpace? space]) async {
    final available = await spaces(c);
    final selected = space == null
        ? available.first
        : available.where((s) => s.id == space.id).firstOrNull;
    require(selected != null, '城通存储空间不可用，请重新选择');
    return BrowseSession(
      platform: CloudPlatform.ctfile,
      mode: BrowseMode.personal,
      title: '城通网盘 · ${selected!.name}',
      rootId: 'd0',
      metadata: {'driveId': selected.id, 'driveName': selected.name},
    );
  }

  Future<String> _workspace(BrowseSession s, Credential c) async {
    final id = s.personalSpaceId;
    if (id.isEmpty) return (await spaces(c)).first.id;
    require(RegExp(r'^[1-9]\d*$').hasMatch(id), '城通存储空间标识无效');
    return id;
  }

  Future<List<CloudFile>> list(
    BrowseSession s,
    String parent,
    Credential c,
  ) async {
    final workspace = await _workspace(s, c);
    final result = <CloudFile>[];
    final seen = <String>{};
    require(RegExp(r'^d\d+$').hasMatch(parent), '城通目录标识无效');
    for (var page = 0; page < 1000; page++) {
      final data = await request(c, 'file/browse/list', {
        'workspace_id': workspace,
        'folder_id': parent,
        'start': result.length,
        'pagesize': 100,
        'orderby': 'old',
      });
      require(data['results'] is List, '城通文件列表响应不完整');
      final rows = data.list('results');
      for (final row in rows) {
        final id = row.str('fid').ifEmpty(row.str('key'));
        require(
          RegExp(r'^[fd][1-9]\d*$').hasMatch(id) && row.str('name').isNotEmpty,
          '城通文件列表缺少有效标识或名称',
        );
        require(seen.add(id), '城通分页未前进，请刷新文件列表');
        result.add(
          CloudFile(
            id: id,
            name: row.str('name'),
            size: row.integer('size'),
            isDirectory: id.startsWith('d'),
            parentId: parent,
            modifiedAt: row.str('date'),
            thumbnailUrl: row.str('imgsrc'),
          ),
        );
      }
      final total = data.integer('totalNum', -1);
      if (rows.isEmpty) {
        require(total < 0 || result.length >= total, '城通目录分页不完整，请刷新');
        return result;
      }
      if (total >= 0 && result.length >= total) return result;
    }
    throw const AppException('城通目录过大，请分目录浏览');
  }

  Future<Json> inSpace(
    BrowseSession s,
    Credential c,
    String path,
    Json body, {
    bool read = true,
  }) async => request(c, path, {
    ...body,
    'workspace_id': await _workspace(s, c),
  }, read: read);

  Future<void> complete(Credential c, Json result) async {
    final job = result.str('job_id');
    require(
      result['success'] != false,
      result.str('message').ifEmpty('城通文件操作失败'),
    );
    if (job.isEmpty) {
      require(!result.boolean('background'), '城通后台操作缺少任务标识，请刷新确认');
      return;
    }
    for (var attempt = 0; attempt < 120; attempt++) {
      final data = await request(c, 'file/bulk/status', {'job_id': job});
      final state = data.str('operation_status').ifEmpty(data.str('status'));
      if (state == 'completed') {
        require(
          data['errors'] == null ||
              (data['errors'] is List && (data['errors'] as List).isEmpty),
          '城通部分文件操作失败，请刷新后检查',
        );
        return;
      }
      require(
        {'queued', 'running', 'processing'}.contains(state),
        '城通文件操作未成功（$state），请刷新后检查',
      );
      await RequestScope.wait(pollDelay);
    }
    throw const AppException('城通仍在后台处理，请稍后刷新确认');
  }

  Future<DownloadSpec> download(
    BrowseSession s,
    CloudFile file,
    Credential c,
  ) async {
    final data = await inSpace(s, c, 'file/browse/fetch-url', {
      'file_id': file.id,
    });
    final url = data.str('download_url');
    checkedCloudUrl(url, '城通未返回有效下载地址');
    return DownloadSpec(
      url: url,
      fileName: file.name,
      expectedSize: file.size,
      headers: headers,
      profile: 'ctfile',
    );
  }

  Future<ShareCreation> share(
    BrowseSession s,
    CloudFile file,
    Credential c,
  ) async {
    final data = await inSpace(s, c, 'file/share/create-individual-links', {
      'ids': file.id,
    }, read: false);
    final rows = data.list('share_links');
    require(
      rows.length == 1 && rows.single.str('id') == file.id,
      '城通分享结果与所选文件不一致',
    );
    final row = rows.single;
    final links = LinkParser.parse(row.str('url'));
    require(
      links.length == 1 &&
          links.single.platform == CloudPlatform.ctfile &&
          links.single.kind == LinkKind.cloudShare,
      '城通未返回有效分享链接',
    );
    return ShareCreation(
      links.single.url,
      row
          .str('passcode')
          .ifEmpty(data.str('passcode').ifEmpty(links.single.passcode ?? '')),
      file.name,
    );
  }

  Future<CloudFile> upload(
    BrowseSession s,
    String parent,
    UploadFile source,
    Credential c,
    UploadProgressCallback? progress,
  ) async {
    final io = UploadIO(http, source, progress);
    io.progress(UploadPhase.preparing);
    require(
      !(await list(s, parent, c)).any((f) => f.name == source.name),
      '城通目录已有同名文件，请重命名后上传',
    );
    final checksum = await io.digest(md5, end: min(source.size, 1000000));
    final data = await inSpace(s, c, 'file/manage/upload', {
      'folder_id': parent,
      'path': source.name,
      'size': source.size,
      'checksum': checksum,
      'carrier': 0,
    }, read: false);
    require(
      data.str('file_name', source.name) == source.name,
      '城通已调整上传文件名，请刷新目录后重试',
    );
    var id = '';
    if (data.integer('exists') != 1 && !data.boolean('instant_upload')) {
      final url = data.str('uploadUrl');
      final uri = Uri.tryParse(url);
      require(
        uri != null &&
            uri.scheme == 'https' &&
            uri.userInfo.isEmpty &&
            uri.host.endsWith('.ctfile.com'),
        '城通上传地址无效',
      );
      final random = Random.secure();
      final hash = md5
          .convert(List.generate(32, (_) => random.nextInt(256)))
          .toString();
      final response = await io.send(
        query(url, {
          'hash': hash,
          'path': source.name,
          'contentlength': source.size,
        }),
        method: 'PUT',
        headers: headers,
        contentType: 'application/octet-stream',
        retry: false,
      );
      final uploaded = _checked(response);
      require(
        uploaded.str('message') == 'upload complete' &&
            uploaded.str('name') == source.name &&
            uploaded.integer('size', -1) == source.size,
        '城通上传未完整完成，请刷新目录后确认',
      );
      final rawId = uploaded.str('id');
      require(RegExp(r'^[1-9]\d*$').hasMatch(rawId), '城通上传结果缺少文件标识');
      id = 'f$rawId';
    }
    return io.confirm(() => list(s, parent, c), id: id);
  }
}

class _WebSession {
  const _WebSession(this.token, this.expires);
  final String token;
  final DateTime expires;
}
