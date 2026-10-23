import 'dart:async';
import 'package:flutter_test/flutter_test.dart';
import 'package:asterlink/core/json.dart';
import 'package:asterlink/data/cloud_search.dart';
import 'package:asterlink/data/http.dart';
import 'package:asterlink/data/http_retry.dart';
import 'package:asterlink/domain/auth.dart';
import 'package:asterlink/domain/models.dart';
import 'package:asterlink/domain/cloud_search_options.dart';
import 'support.dart';

const _session = BrowseSession(
  platform: CloudPlatform.quark,
  mode: BrowseMode.personal,
  title: '搜索测试',
  rootId: 'root',
  metadata: {'accountId': 'first', 'driveId': 'space-1'},
);
const _trail = [('root', '全部文件')];

CloudFile _folder(String id) => CloudFile(id: id, name: id, isDirectory: true);

CloudDirectorySearch _search(
  Future<List<CloudFile>> Function(BrowseSession, String) read, {
  int maxFolders = 2000,
  int maxEntries = 50000,
  int maxResults = 5000,
  void Function(BrowseSession)? validate,
  DateTime Function()? clock,
}) => CloudDirectorySearch(
  read: read,
  directoryId: (_, file) => file.token.isEmpty ? file.id : file.token,
  validate: validate ?? (_) {},
  maxFolders: maxFolders,
  maxEntries: maxEntries,
  maxResults: maxResults,
  clock: clock,
);

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test(
    'A type-only search still walks folders and type changes reuse only directory data',
    () async {
      var reads = 0;
      final search = _search((_, parent) async {
        reads++;
        return parent == 'root'
            ? [_folder('child')]
            : [
                const CloudFile(id: 'doc', name: '报告.DOCX'),
                const CloudFile(id: 'image', name: '报告.png'),
              ];
      });
      addTearDown(search.dispose);
      await search.start(
        _session,
        _trail,
        '',
        matches: CloudSearchCategory.document.matches,
      );
      expect(search.phase, CloudSearchPhase.completed);
      expect(search.hits.single.file.id, 'doc');
      expect(search.hits.single.trail.last.$1, 'child');
      expect(reads, 2);
      await search.start(
        _session,
        _trail,
        '报告',
        matches: CloudSearchCategory.image.matches,
      );
      expect(search.hits.single.file.id, 'image');
      expect(reads, 2);
      await search.start(
        _session,
        _trail,
        '不匹配',
        matches: CloudSearchCategory.document.matches,
      );
      expect(search.hits, isEmpty);
    },
  );

  test(
    'Search streams nested hits with paths and at most two directory reads',
    () async {
      final first = Completer<List<CloudFile>>();
      final second = Completer<List<CloudFile>>();
      const rootFile = CloudFile(id: 'root-file', name: '目标说明.txt');
      const leftFile = CloudFile(
        id: 'same-id',
        name: '目标.txt',
        parentId: 'a',
        token: 'left-download-token',
        hashType: 'sha1',
        hashValue: 'fixture-hash',
      );
      const rightFile = CloudFile(
        id: 'same-id',
        name: '目标.txt',
        parentId: 'b',
        token: 'right-download-token',
      );
      const nestedFile = CloudFile(
        id: 'deep',
        name: '目标附件.pdf',
        parentId: 'grand',
      );
      final reads = <String>[];
      var active = 0, peak = 0;
      final search = _search((session, parent) async {
        expect(session, same(_session));
        reads.add(parent);
        active++;
        if (active > peak) peak = active;
        try {
          return switch (parent) {
            'root' => [rootFile, _folder('a'), _folder('b'), _folder('c')],
            'a' => await first.future,
            'b' => await second.future,
            'grand' => [nestedFile],
            'c' => [],
            _ => throw StateError('Unexpected folder: $parent'),
          };
        } finally {
          active--;
        }
      });
      addTearDown(search.dispose);
      final run = search.start(_session, _trail, '目标');
      await until(() => reads.length == 3);
      expect(search.hits.single.file, same(rootFile));
      expect(search.running, isTrue);
      first.complete([leftFile, _folder('grand')]);
      await until(() => search.hits.length == 2);
      expect(reads, ['root', 'a', 'b']);
      second.complete([rightFile]);
      await run;
      expect(peak, 2);
      expect(reads, ['root', 'a', 'b', 'c', 'grand']);
      expect(search.phase, CloudSearchPhase.completed);
      expect(search.searchedFolders, 5);
      final left = search.hitFor(leftFile)!;
      final right = search.hitFor(rightFile)!;
      expect(left.file, same(leftFile));
      expect(left.file.token, 'left-download-token');
      expect(left.file.hashValue, 'fixture-hash');
      expect(left.key, isNot(right.key));
      expect(left.path, '全部文件 / a');
      expect(right.path, '全部文件 / b');
      expect(search.hitFor(nestedFile)!.trail, [
        ..._trail,
        ('a', 'a'),
        ('grand', 'grand'),
      ]);
    },
  );

  test(
    'Only the selected subtree is searched and provider directory tokens prevent cycles',
    () async {
      final reads = <String>[];
      final tree = {
        '/selected': [
          const CloudFile(
            id: 'numeric-1',
            name: 'child',
            token: '/selected/child',
            isDirectory: true,
          ),
          const CloudFile(id: 'f1', name: 'Annual REPORT.pdf'),
        ],
        '/selected/child': [
          const CloudFile(
            id: 'numeric-2',
            name: 'loop',
            token: '/selected',
            isDirectory: true,
          ),
          const CloudFile(
            id: 'f2',
            name: 'Report.txt',
            parentId: '/selected/child',
          ),
        ],
      };
      final search = _search((_, parent) async {
        reads.add(parent);
        return tree[parent] ?? (throw StateError('Searched outside subtree'));
      });
      addTearDown(search.dispose);
      await search.start(_session, [
        ('root', '全部文件'),
        ('/selected', '选择的目录'),
      ], ' rEpOrT ');
      expect(reads, ['/selected', '/selected/child']);
      expect(search.hits, hasLength(2));
      expect(search.hits.last.path, '全部文件 / 选择的目录 / child');
      expect(search.phase, CloudSearchPhase.completed);
    },
  );

  test(
    'Stop completes promptly and late results cannot contaminate a new query',
    () async {
      final pending = Completer<List<CloudFile>>();
      var reads = 0;
      final search = _search((_, parent) {
        reads++;
        return reads == 1
            ? pending.future
            : Future.value([const CloudFile(id: 'new', name: '新结果.txt')]);
      });
      addTearDown(search.dispose);
      final old = search.start(_session, _trail, '旧');
      await until(() => reads == 1);
      search.stop();
      await old.timeout(const Duration(seconds: 1));
      expect(search.phase, CloudSearchPhase.stopped);
      await search.start(_session, _trail, '新');
      pending.complete([const CloudFile(id: 'old', name: '旧结果.txt')]);
      await Future<void>.delayed(Duration.zero);
      expect(search.hits.single.file.id, 'new');
      expect(search.cached(_session, 'root')!.single.id, 'new');
      expect(search.phase, CloudSearchPhase.completed);
    },
  );

  test(
    'A replacement query cancels pending reads without needing an explicit stop',
    () async {
      final pending = Completer<List<CloudFile>>();
      var reads = 0;
      final search = _search(
        (_, parent) => ++reads == 1
            ? pending.future
            : Future.value([const CloudFile(id: 'new', name: 'new')]),
      );
      addTearDown(search.dispose);
      final old = search.start(_session, _trail, 'old');
      await until(() => reads == 1);
      await search.start(_session, _trail, 'new');
      await old.timeout(const Duration(seconds: 1));
      pending.complete([const CloudFile(id: 'old', name: 'old')]);
      await Future<void>.delayed(Duration.zero);
      expect(search.hits.single.file.id, 'new');
    },
  );

  test('Disposing cancels reads and suppresses late notifications', () async {
    final pending = Completer<List<CloudFile>>();
    var reads = 0, notifications = 0;
    final search = _search((_, parent) {
      reads++;
      return pending.future;
    })..addListener(() => notifications++);
    final run = search.start(_session, _trail, 'file');
    await until(() => reads == 1);
    search.dispose();
    final before = notifications;
    await run.timeout(const Duration(seconds: 1));
    pending.complete([const CloudFile(id: 'late', name: 'file')]);
    await Future<void>.delayed(Duration.zero);
    expect(notifications, before);
    expect(search.hits, isEmpty);
  });

  test(
    'Credential changes discard outstanding results and report interruption',
    () async {
      var valid = true, waiting = false;
      final pending = Completer<List<CloudFile>>();
      final search = _search((_, parent) async {
        if (parent == 'root') {
          return [
            const CloudFile(id: 'before', name: 'file-before'),
            _folder('child'),
          ];
        }
        waiting = true;
        return pending.future;
      }, validate: (_) => require(valid, '账号已变化'));
      addTearDown(search.dispose);
      final run = search.start(_session, _trail, 'file');
      await until(() => waiting);
      valid = false;
      pending.complete([const CloudFile(id: 'after', name: 'file-after')]);
      await run;
      expect(search.phase, CloudSearchPhase.failed);
      expect(search.error, '账号已变化');
      expect(search.hits.single.file.id, 'before');
      expect(search.cached(_session, 'child'), isNull);
    },
  );

  test(
    'Unreadable folders keep valid hits and a truthful partial status',
    () async {
      final search = _search(
        (_, parent) async => switch (parent) {
          'root' => [_folder('good'), _folder('bad')],
          'good' => [const CloudFile(id: 'good-file', name: 'file.txt')],
          _ => throw const AppException('文件夹暂时不可用'),
        },
      );
      addTearDown(search.dispose);
      await search.start(_session, _trail, 'file');
      expect(search.phase, CloudSearchPhase.partial);
      expect(search.hits.single.file.id, 'good-file');
      expect(search.searchedFolders, 3);
      expect(search.failedFolders, 1);
      expect(search.issues.single.path, '全部文件 / bad');
      expect(search.issues.single.message, '文件夹暂时不可用');
    },
  );

  test('Expired login stops subsequent traversal', () async {
    final reads = <String>[];
    final search = _search((_, parent) async {
      reads.add(parent);
      if (parent == 'root') return [_folder('a'), _folder('b'), _folder('c')];
      throw const AccountLoginRequired('请重新登录');
    });
    addTearDown(search.dispose);
    await search.start(_session, _trail, 'file');
    expect(search.phase, CloudSearchPhase.failed);
    expect(search.error, '请重新登录');
    expect(reads, isNot(contains('c')));
  });

  for (final limit in ['folders', 'entries', 'results']) {
    test('The $limit limit reports incomplete results', () async {
      final reads = <String>[];
      final search = _search(
        (_, parent) async {
          reads.add(parent);
          return parent == 'root'
              ? [
                  const CloudFile(id: 'a-file', name: 'file-a'),
                  const CloudFile(id: 'b-file', name: 'file-b'),
                  _folder('a'),
                  _folder('b'),
                ]
              : [CloudFile(id: '$parent-file', name: 'file-child')];
        },
        maxFolders: limit == 'folders' ? 2 : 2000,
        maxEntries: limit == 'entries' ? 1 : 50000,
        maxResults: limit == 'results' ? 1 : 5000,
      );
      addTearDown(search.dispose);
      await search.start(_session, _trail, 'file');
      expect(search.phase, CloudSearchPhase.limited);
      expect(search.hits, isNotEmpty);
      if (limit == 'folders') {
        expect(reads, ['root', 'a']);
      } else {
        expect(search.hits, hasLength(1));
        expect(reads, ['root']);
      }
    });
  }

  test(
    'Memory cache respects refresh, expiry, account, share and space boundaries',
    () async {
      var now = DateTime(2026, 10, 9);
      var reads = 0;
      final search = _search((session, parent) async {
        reads++;
        return [CloudFile(id: 'file-$reads', name: 'file-$reads')];
      }, clock: () => now);
      addTearDown(search.dispose);
      await search.start(_session, _trail, 'file');
      await search.start(_session, _trail, 'FILE');
      expect(reads, 1);
      await search.start(_session, _trail, 'file', refresh: true);
      expect(reads, 2);
      now = now.add(const Duration(seconds: 46));
      await search.start(_session, _trail, 'file');
      expect(reads, 3);
      for (final session in [
        _session.withAccount('second'),
        const BrowseSession(
          platform: CloudPlatform.quark,
          mode: BrowseMode.personal,
          title: 'another space',
          rootId: 'root',
          metadata: {'accountId': 'first', 'driveId': 'space-2'},
        ),
        const BrowseSession(
          platform: CloudPlatform.quark,
          mode: BrowseMode.share,
          title: 'a share',
          rootId: 'root',
          metadata: {'shareId': 'share-fixture'},
        ),
      ]) {
        final before = reads;
        await search.start(session, _trail, 'file');
        expect(reads, before + 1);
        expect(search.hits.single.file.id, 'file-$reads');
        expect(search.cached(_session, 'root'), isNull);
      }
    },
  );

  test('Empty queries clear results without fetching directories', () async {
    var reads = 0;
    final search = _search((_, parent) async {
      reads++;
      return [const CloudFile(id: 'file', name: 'file')];
    });
    addTearDown(search.dispose);
    await search.start(_session, _trail, 'file');
    await search.start(_session, _trail, '  ');
    expect(search.phase, CloudSearchPhase.idle);
    expect(search.hits, isEmpty);
    expect(reads, 1);
  });

  test(
    'Rate limiting retries reads and reduces subsequent directory concurrency',
    () async {
      final events = <String>[];
      var attempts = 0;
      final search = _search((_, parent) async {
        if (parent == 'root') return ['a', 'b', 'c', 'd'].map(_folder).toList();
        events.add('start-$parent');
        try {
          await ReadRetryScope.current!.execute(() async {
            if (parent == 'a' && attempts++ == 0) {
              return const HttpResult(429, '');
            }
            await Future<void>.delayed(const Duration(milliseconds: 5));
            return const HttpResult(200, '');
          });
          return [CloudFile(id: parent, name: 'file-$parent')];
        } finally {
          events.add('end-$parent');
        }
      });
      addTearDown(search.dispose);
      await search.start(_session, _trail, 'file');
      expect(search.phase, CloudSearchPhase.completed);
      expect(attempts, 2);
      expect(
        events.where((event) => event.endsWith('-c') || event.endsWith('-d')),
        ['start-c', 'end-c', 'start-d', 'end-d'],
      );
    },
  );
}
