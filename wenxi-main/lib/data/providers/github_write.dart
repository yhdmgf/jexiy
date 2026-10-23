part of 'github.dart';

extension _GitHubWrites on GitHubConnector {
  Future<T> _serial<T>(GitHubLocation n, Future<T> Function() action) async {
    final key = n.repository.toLowerCase();
    final previous = _writes[key];
    final gate = Completer<void>();
    final tail = (previous ?? Future<void>.value()).then((_) => gate.future);
    _writes[key] = tail;
    unawaited(
      tail.then((_) {
        if (identical(_writes[key], tail)) _writes.remove(key);
      }),
    );
    try {
      if (previous != null) await RequestScope.cancellable(previous);
      RequestScope.checkpoint();
      return await action();
    } finally {
      gate.complete();
    }
  }

  Future<void> _ensureWrite(
    BrowseSession s,
    GitHubLocation n,
    Credential c,
  ) async {
    require(s.canManageFiles && n.writable, '当前 GitHub 目录只读，请使用有写入权限的账号打开仓库');
    final repo = await _repository(n, c);
    require(
      repo.obj('permissions').boolean('push') &&
          !repo.boolean('archived') &&
          !repo.boolean('disabled'),
      '当前账号没有仓库写入权限，或仓库已归档',
    );
    RequestScope.checkpoint();
  }

  Future<CloudFile> _uploadFile(
    BrowseSession s,
    String parent,
    UploadFile file,
    Credential c, {
    UploadProgressCallback? onProgress,
  }) async {
    require(canUploadTo(s, parent), '请进入可写分支的代码目录或某个 Release 后上传');
    final n = GitHubLocation.parse(parent);
    GitHubLocation.checkName(file.name);
    final code = n.kind == GitHubNodeKind.code;
    require(
      file.size <=
          (code
              ? GitHubConnector.codeUploadLimit
              : GitHubConnector.assetUploadLimit),
      code
          ? '代码文件不能超过 100 MiB，请进入 Releases 上传附件（单文件最多 2 GiB）'
          : 'GitHub 发布附件不能超过 2 GiB',
    );
    return _serial(n, () async {
      await _ensureWrite(s, n, c);
      onProgress?.call(UploadProgress(UploadPhase.preparing, 0, file.size));
      return code
          ? _uploadCode(n, file, c, onProgress)
          : _uploadAsset(n, file, c, onProgress);
    });
  }

  Future<CloudFile> _uploadCode(
    GitHubLocation n,
    UploadFile file,
    Credential c,
    UploadProgressCallback? progress,
  ) async {
    final path = [if (n.path.isNotEmpty) n.path, file.name].join('/');
    require(n.branch, '标签和历史提交只读，请选择分支上传');
    final head = await _snapshot(n, c);
    if (head != null) {
      final parent = await _entryAt(n, head, n.path, c);
      require(
        parent != null && parent.str('type') == 'tree',
        '上传目录已移动或删除，请刷新后重试',
      );
      require(await _entryAt(n, head, path, c) == null, '目录中已有同名文件，上传不会覆盖已有内容');
    } else {
      require(n.path.isEmpty, '仓库为空，请先在代码根目录上传');
    }
    return _putNewFile(n, path, file, c, progress);
  }

  Future<CloudFile> _putNewFile(
    GitHubLocation n,
    String path,
    UploadFile file,
    Credential c,
    UploadProgressCallback? progress,
  ) async {
    // Contents expects base64 in JSON. Encode the stream instead of keeping a
    // 100 MiB file and its base64/JSON copies in Android memory simultaneously.
    final prefix = utf8.encode(
      '{"message":${jsonEncode('上传 $path')},"branch":${jsonEncode(n.ref)},"content":"',
    );
    final suffix = utf8.encode('"}');
    final encodedLength = ((file.size + 2) ~/ 3) * 4;
    Stream<List<int>> open() async* {
      RequestScope.checkpoint();
      yield prefix;
      await for (final part in base64.encoder.bind(file.openRead())) {
        RequestScope.checkpoint();
        yield utf8.encode(part);
      }
      yield suffix;
    }

    final response = await _request(
      'PUT',
      '${GitHubConnector._base(n)}/contents/${GitHubConnector._path(path)}',
      c,
      body: HttpUpload(
        open: open,
        length: prefix.length + encodedLength + suffix.length,
        onProgress: (sent, total) {
          final bytes =
              (((sent - prefix.length).clamp(0, encodedLength)) * 3 ~/ 4).clamp(
                0,
                file.size,
              );
          progress?.call(
            UploadProgress(UploadPhase.uploading, bytes, file.size),
          );
        },
      ),
    );
    require(response.status == 201, 'GitHub 未确认新文件创建，请刷新目录核对');
    final content = response.json.obj('content');
    require(
      content.str('sha').isNotEmpty && content.str('path') == path,
      'GitHub 上传已提交，但返回信息不完整，请刷新文件列表',
    );
    progress?.call(UploadProgress(UploadPhase.finishing, file.size, file.size));
    return CloudFile(
      id: n.copy(kind: GitHubNodeKind.file, path: path).id,
      name: file.name,
      parentId: n.id,
      size: file.size,
      token: jsonEncode({
        'sha': content.str('sha'),
        'type': 'blob',
        'mode': '100644',
      }),
    );
  }

  Future<Json> _writableRelease(GitHubLocation n, Credential c) async {
    require(n.releaseId.isNotEmpty, '请先打开一个已创建的 Release');
    final release = await _get(
      '${GitHubConnector._base(n)}/releases/${n.releaseId}',
      c,
    );
    require(!release.boolean('immutable'), '此 Release 已锁定，不能修改发布附件');
    return release;
  }

  Future<CloudFile> _uploadAsset(
    GitHubLocation n,
    UploadFile file,
    Credential c,
    UploadProgressCallback? progress,
  ) async {
    final release = await _writableRelease(n, c);
    final files = await _pages(
      '${GitHubConnector._base(n)}/releases/${n.releaseId}/assets',
      c,
    );
    require(!files.any((f) => f.str('name') == file.name), '此发布中已有同名附件，请改名后上传');
    final template = release.str('upload_url');
    final uri = Uri.tryParse(template.split('{').first);
    require(
      uri != null &&
          uri.scheme == 'https' &&
          uri.host == 'uploads.github.com' &&
          uri.port == 443 &&
          uri.userInfo.isEmpty &&
          uri.path.toLowerCase() ==
              '${GitHubConnector._base(n)}/releases/${n.releaseId}/assets'
                  .toLowerCase(),
      'GitHub 返回的附件上传地址无效',
    );
    final response = await http.request(
      'POST',
      uri!.replace(queryParameters: {'name': file.name}).toString(),
      headers: _headers(c),
      contentType: file.mimeType,
      followRedirects: false,
      body: HttpUpload(
        open: () => file.openRead(),
        length: file.size,
        onProgress: (sent, _) => progress?.call(
          UploadProgress(
            UploadPhase.uploading,
            sent.clamp(0, file.size),
            file.size,
          ),
        ),
      ),
    );
    _check(response);
    final data = response.json;
    require(
      data.str('id').isNotEmpty &&
          data.str('state') == 'uploaded' &&
          data.integer('size') == file.size,
      'GitHub 附件上传未完成，请刷新后检查',
    );
    progress?.call(UploadProgress(UploadPhase.finishing, file.size, file.size));
    return _asset(n, data);
  }

  Future<CloudFile> _createFolder(
    BrowseSession s,
    String parent,
    String name,
    Credential c,
  ) async {
    require(canCreateFolderIn(s, parent), '请进入可写的代码目录后创建文件夹');
    GitHubLocation.checkName(name);
    final n = GitHubLocation.parse(parent),
        path = [
          GitHubLocation.parse(parent).path,
          name,
        ].where((s) => s.isNotEmpty).join('/');
    return _serial(n, () async {
      await _ensureWrite(s, n, c);
      final head = await _snapshot(n, c);
      if (head != null) {
        require(await _entryAt(n, head, path, c) == null, '目录中已有同名项目，请换一个名称');
        final entry = await _entryAt(n, head, n.path, c);
        require(entry != null && entry.str('type') == 'tree', '父目录已变化，请刷新后重试');
      } else {
        require(n.path.isEmpty, '仓库为空，请在代码根目录创建文件夹');
      }
      await _putNewFile(
        n,
        '$path/.gitkeep',
        UploadFile(
          name: '.gitkeep',
          size: 0,
          read: (start, end) => const Stream<List<int>>.empty(),
        ),
        c,
        null,
      );
      final fresh = await _snapshot(n, c);
      final created = fresh == null ? null : await _entryAt(n, fresh, path, c);
      require(created != null, '文件夹已提交，请刷新查看');
      return _folder(
        n.copy(kind: GitHubNodeKind.code, path: path),
        name,
        parent,
        info: {'sha': created!.str('sha'), 'type': 'tree', 'mode': '040000'},
      );
    });
  }

  Future<({GitHubLocation node, _GitHubSnapshot head, List<Json> entries})>
  _editContext(BrowseSession s, List<CloudFile> files, Credential c) async {
    require(files.isNotEmpty, '请先选择文件');
    final n = GitHubLocation.parse(files.first.id);
    require(n.isCodeItem, '只能修改分支中的代码文件或文件夹');
    await _ensureWrite(s, n, c);
    final head = await _snapshot(n, c);
    require(head != null, 'GitHub 分支不存在或为空，请刷新');
    final paths = <String>{}, entries = <Json>[];
    for (final file in files) {
      final target = GitHubLocation.parse(file.id);
      require(
        target.repository == n.repository &&
            target.ref == n.ref &&
            target.isCodeItem,
        '一次操作只能修改同一仓库、同一分支的文件',
      );
      require(paths.add(target.path), '不能重复选择同一文件');
      final current = await _entryAt(n, head!, target.path, c);
      final expected = GitHubConnector._info(file).str('sha');
      require(
        current != null &&
            expected.isNotEmpty &&
            current.str('sha') == expected,
        '文件内容已变化或已被移动，请刷新后重新选择，原文件未修改',
      );
      entries.add({...current!, 'path': target.path});
    }
    require(
      !paths.any(
        (path) =>
            paths.any((other) => other != path && path.startsWith('$other/')),
      ),
      '请勿同时选择文件夹和它的子文件',
    );
    return (node: n, head: head!, entries: entries);
  }

  Future<void> _commitTree(
    GitHubLocation n,
    _GitHubSnapshot head,
    List<Json> changes,
    String message,
    Credential c,
  ) async {
    final tree = (await _request(
      'POST',
      '${GitHubConnector._base(n)}/git/trees',
      c,
      body: {'base_tree': head.tree, 'tree': changes},
    )).json.str('sha');
    require(tree.isNotEmpty, 'GitHub 未生成新的目录记录');
    final commit = (await _request(
      'POST',
      '${GitHubConnector._base(n)}/git/commits',
      c,
      body: {
        'message': message,
        'tree': tree,
        'parents': [head.commit],
      },
    )).json.str('sha');
    require(commit.isNotEmpty, 'GitHub 未生成新的提交记录');
    try {
      await _request(
        'PATCH',
        '${GitHubConnector._base(n)}/git/refs/heads/${GitHubConnector._path(n.ref)}',
        c,
        body: {'sha': commit, 'force': false},
      );
    } on HttpRequestFailure {
      // A lost response is not proof that the commit failed. Confirm before
      // reporting failure; never retry a force update or overwrite a new head.
      final current = await _snapshot(n, c);
      if (current?.commit != commit) rethrow;
    }
  }

  Json _treeChange(Json entry, String path, {bool remove = false}) => {
    'path': path,
    'mode': entry.str('mode'),
    'type': entry.str('type'),
    'sha': remove ? null : entry.str('sha'),
  };

  Future<void> _rename(
    BrowseSession s,
    CloudFile file,
    String name,
    Credential c,
  ) async {
    require(canRenameFile(s, file), '此项目不能重命名，请选择代码文件或发布附件');
    GitHubLocation.checkName(name);
    if (file.name == name) return;
    final n = GitHubLocation.parse(file.id);
    await _serial(n, () async {
      if (n.kind == GitHubNodeKind.asset) {
        await _ensureWrite(s, n, c);
        await _writableRelease(n, c);
        final assets = await _checkedAssets(n, [file], c);
        require(
          !assets.any((a) => a.str('name') == name && a.str('id') != n.assetId),
          '此发布中已有同名附件，请换一个名称',
        );
        await _request(
          'PATCH',
          '${GitHubConnector._base(n)}/releases/assets/${n.assetId}',
          c,
          body: {'name': name},
        );
        return;
      }
      final edit = await _editContext(s, [file], c);
      final parts = n.path.split('/')..removeLast();
      final target = [...parts, name].join('/');
      require(
        await _entryAt(n, edit.head, target, c) == null,
        '已有同名文件或文件夹，请换一个名称',
      );
      await _commitTree(
        n,
        edit.head,
        [
          _treeChange(edit.entries.single, n.path, remove: true),
          _treeChange(edit.entries.single, target),
        ],
        '重命名 ${n.path} 为 $target',
        c,
      );
    });
  }

  Future<List<Json>> _checkedAssets(
    GitHubLocation n,
    List<CloudFile> files,
    Credential c,
  ) async {
    final assets = await _pages(
      '${GitHubConnector._base(n)}/releases/${n.releaseId}/assets',
      c,
    );
    for (final file in files) {
      final target = GitHubLocation.parse(file.id);
      require(
        target.kind == GitHubNodeKind.asset &&
            target.repository == n.repository &&
            target.releaseId == n.releaseId,
        '请在同一个 Release 中选择附件',
      );
      final current = assets
          .where((a) => a.str('id') == target.assetId)
          .firstOrNull;
      require(
        current != null &&
            current.str('name') == file.name &&
            current.integer('size') == file.size,
        '发布附件已变化，请刷新后重试',
      );
    }
    return assets;
  }

  Future<void> _delete(
    BrowseSession s,
    List<CloudFile> files,
    Credential c,
  ) async {
    require(
      files.isNotEmpty && files.every((f) => canDeleteFile(s, f)),
      '请选择可删除的代码文件或发布附件',
    );
    final n = GitHubLocation.parse(files.first.id);
    await _serial(n, () async {
      if (n.kind == GitHubNodeKind.asset) {
        await _ensureWrite(s, n, c);
        await _writableRelease(n, c);
        await _checkedAssets(n, files, c);
        var removed = 0;
        try {
          for (final file in files) {
            await _request(
              'DELETE',
              '${GitHubConnector._base(n)}/releases/assets/${GitHubLocation.parse(file.id).assetId}',
              c,
            );
            removed++;
          }
        } catch (_) {
          if (removed > 0) throw AppException('已删除 $removed 个附件，其余未完成，请刷新后重试');
          rethrow;
        }
        return;
      }
      final edit = await _editContext(s, files, c);
      await _commitTree(
        n,
        edit.head,
        [
          for (final entry in edit.entries)
            _treeChange(entry, entry.str('path'), remove: true),
        ],
        '删除 ${files.length} 个文件或文件夹',
        c,
      );
    });
  }

  Future<void> _move(
    BrowseSession s,
    List<CloudFile> files,
    String target,
    Credential c,
  ) async {
    require(
      files.isNotEmpty && files.every((f) => canMoveFile(s, f)),
      '请选择可移动的代码文件',
    );
    final to = GitHubLocation.parse(target),
        n = GitHubLocation.parse(files.first.id);
    require(
      to.kind == GitHubNodeKind.code &&
          to.repository == n.repository &&
          to.ref == n.ref &&
          to.branch,
      'GitHub 文件只能移动到同一仓库、同一分支的代码目录',
    );
    await _serial(n, () async {
      final edit = await _editContext(s, files, c);
      final folder = await _entryAt(n, edit.head, to.path, c);
      require(
        folder != null && folder.str('type') == 'tree',
        '目标文件夹已不存在，请重新选择',
      );
      final changes = <Json>[], destinations = <String>{};
      for (var i = 0; i < files.length; i++) {
        final entry = edit.entries[i], source = entry.str('path');
        final destination = [
          if (to.path.isNotEmpty) to.path,
          files[i].name,
        ].join('/');
        require(
          destination != source && !destination.startsWith('$source/'),
          '不能移动到原目录或自身的子目录',
        );
        require(
          destinations.add(destination) &&
              await _entryAt(n, edit.head, destination, c) == null,
          '目标目录存在同名项目，请先改名',
        );
        changes.addAll([
          _treeChange(entry, source, remove: true),
          _treeChange(entry, destination),
        ]);
      }
      await _commitTree(n, edit.head, changes, '移动 ${files.length} 个文件或文件夹', c);
    });
  }
}
