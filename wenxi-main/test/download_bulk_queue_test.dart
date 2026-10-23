import 'dart:async';
import 'dart:io';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:path/path.dart' as p;
import 'package:asterlink/core/json.dart';
import 'package:asterlink/data/cleanup_outbox.dart';
import 'package:asterlink/data/http.dart';
import 'package:asterlink/data/state_store.dart';
import 'package:asterlink/domain/downloads.dart';
import 'package:asterlink/domain/models.dart';
import 'package:asterlink/download/download_manager.dart';
import 'package:asterlink/download/transfer_http.dart';
import 'package:asterlink/platform/native_engine.dart';
import 'support.dart';

const _session = BrowseSession(
  platform: CloudPlatform.quark,
  mode: BrowseMode.personal,
  title: '测试文件',
  rootId: 'root',
);

DownloadSpec _spec(int index) => DownloadSpec(
  url: '',
  fileName: 'sample-$index.bin',
  relativePath: 'folder/subfolder',
  expectedSize: 4,
  source: DownloadOrigin(
    _session,
    CloudFile(id: 'file-$index', name: 'sample-$index.bin', size: 4),
    1,
  ).toJson(),
);

class _BulkFixture {
  _BulkFixture(this.root, this.store) {
    native = FakeNative();
    manager = DownloadManager(
      store: store,
      engine: GopeedEngine(
        native,
        store,
        Vault(store),
        Directory(p.join(root.path, 'native')),
        Directory(p.join(root.path, 'cache')),
      ),
      files: FakeFiles(Directory(p.join(root.path, 'saved'))),
      cleanups: CleanupOutbox(store, FakeHttp()),
      http: TransferHttp(),
      refreshSource: (source) async {
        preparing++;
        await RequestScope.wait(const Duration(days: 1));
        throw StateError('fixture transfer was not cancelled');
      },
    );
    store.addListener(() => writes++);
  }

  final Directory root;
  final StateStore store;
  late final FakeNative native;
  late final DownloadManager manager;
  int preparing = 0, writes = 0;

  static Future<_BulkFixture> create(
    int count, {
    DownloadStatus status = DownloadStatus.paused,
    bool durable = false,
  }) async {
    final root = await Directory.systemTemp.createTemp('aster-bulk-queue-');
    final seed = <String, dynamic>{
      'settings': {'concurrent': 1, 'threads': 64},
      'tasks': [
        for (var i = 0; i < count; i++)
          DownloadTask(
            id: 'task-$i',
            spec: _spec(i),
            createdAt: i,
            total: 4,
            connections: 512,
            status: status,
          ).toJson(),
      ],
    };
    final store = durable
        ? await StateStore.open(
            Directory(p.join(root.path, 'state')),
            testKey: Uint8List(32),
          )
        : StateStore.memory(seed);
    if (durable) await store.change((draft) => draft.addAll(seed));
    final fixture = _BulkFixture(root, store);
    await fixture.manager.initialize();
    fixture.writes = 0;
    return fixture;
  }

  Future<void> close() async {
    await manager.close();
    manager.dispose();
    final resolved = await root.resolveSymbolicLinks();
    final temp = await Directory.systemTemp.resolveSymbolicLinks();
    require(
      p.isWithin(temp, resolved) &&
          p.basename(resolved).startsWith('aster-bulk-queue-'),
      'unexpected test directory',
    );
    await root.delete(recursive: true);
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test(
    '2274 recovered tasks open paused and a second launch does not rewrite history',
    () async {
      final fixture = await _BulkFixture.create(
        2274,
        status: DownloadStatus.running,
      );
      addTearDown(fixture.close);
      expect(fixture.manager.tasks, hasLength(2274));
      expect(
        fixture.manager.tasks.every(
          (task) => task.status == DownloadStatus.paused,
        ),
        isTrue,
      );
      expect(fixture.store.data['downloadRecovery'], hasLength(2274));
      expect(fixture.preparing, 0);
      expect(fixture.native.openCount, 0);
      final reopened = _BulkFixture(
        fixture.root,
        StateStore.memory(fixture.store.data),
      );
      addTearDown(() async {
        await reopened.manager.close();
        reopened.manager.dispose();
      });
      await reopened.manager.initialize();
      expect(reopened.writes, 0);
      expect(reopened.manager.tasks, hasLength(2274));
      expect(reopened.preparing, 0);
    },
  );

  test(
    '2274-file resume and pause use bounded commits and one active preparation',
    () async {
      final fixture = await _BulkFixture.create(2274);
      addTearDown(fixture.close);
      await fixture.manager.resumeAll();
      await until(() => fixture.preparing == 1);
      expect(
        fixture.manager.tasks.where((task) => task.active),
        hasLength(2274),
      );
      expect(fixture.manager.activeCount, 1);
      expect(fixture.writes, lessThanOrEqualTo(4));
      expect(
        fixture.manager.tasks.every((task) => task.connections == 512),
        isTrue,
      );
      fixture.writes = 0;
      await fixture.manager.pauseAll();
      expect(
        fixture.manager.tasks.every(
          (task) => task.status == DownloadStatus.paused,
        ),
        isTrue,
      );
      expect(fixture.manager.activeCount, 0);
      expect(fixture.preparing, 1);
      expect(fixture.writes, lessThanOrEqualTo(3));
      expect(fixture.store.data['downloadRecovery'], isEmpty);
      fixture.writes = 0;
      await fixture.manager.pauseAll();
      expect(fixture.writes, 0);
    },
  );

  test(
    'cancelling a large selection before commit leaves all tasks paused',
    () async {
      final fixture = await _BulkFixture.create(2274);
      addTearDown(fixture.close);
      final scope = RequestScope();
      final work = scope.run(
        () => fixture.manager.batch(
          fixture.manager.tasks.map((task) => task.id),
          DownloadBatchAction.resume,
        ),
      );
      Timer.run(scope.cancel);
      final result = await work;
      expect(result.cancelled, isTrue);
      expect(result.succeeded, isEmpty);
      expect(
        fixture.manager.tasks.every(
          (task) => task.status == DownloadStatus.paused,
        ),
        isTrue,
      );
      expect(fixture.writes, 0);
      expect(fixture.preparing, 0);
      // Cancellation must release the controls already acquired by the batch.
      await fixture.manager.resume('task-0');
      await until(() => fixture.preparing == 1);
      await fixture.manager.pause('task-0');
      expect(fixture.manager.task('task-0')!.status, DownloadStatus.paused);
    },
  );

  test('a single pause after a large resume keeps that task paused', () async {
    final fixture = await _BulkFixture.create(2274);
    addTearDown(fixture.close);
    final resumed = fixture.manager.resumeAll();
    final paused = Completer<void>();
    Timer.run(() {
      fixture.manager
          .pause('task-0')
          .then((_) => paused.complete(), onError: paused.completeError);
    });
    await resumed;
    await paused.future;
    await until(() => fixture.preparing == 1);
    expect(fixture.manager.task('task-0')!.status, DownloadStatus.paused);
    expect(fixture.manager.activeTasks, hasLength(2273));
    expect(fixture.manager.activeCount, 1);
  });

  test(
    'pause-all during a large resume prevents any transfer from starting',
    () async {
      final fixture = await _BulkFixture.create(2274);
      addTearDown(fixture.close);
      final resumed = fixture.manager.resumeAll();
      final paused = Completer<void>();
      Timer.run(() {
        fixture.manager.pauseAll().then(
          (_) => paused.complete(),
          onError: paused.completeError,
        );
      });
      await resumed;
      await paused.future;
      expect(
        fixture.manager.tasks.every(
          (task) => task.status == DownloadStatus.paused,
        ),
        isTrue,
      );
      expect(fixture.preparing, 0);
      expect(fixture.manager.activeCount, 0);
      expect(fixture.store.data['downloadRecovery'], isEmpty);
    },
  );

  test(
    'failed bulk resume retains the durable queue and remains retryable',
    () async {
      final fixture = await _BulkFixture.create(
        256,
        status: DownloadStatus.running,
        durable: true,
      );
      addTearDown(fixture.close);
      final blocked = Directory('${fixture.store.file!.path}.tmp');
      await blocked.create();
      try {
        final result = await fixture.manager.batch(
          fixture.manager.tasks.map((task) => task.id),
          DownloadBatchAction.resume,
        );
        expect(result.succeeded, isEmpty);
        expect(result.failed, hasLength(256));
        expect(fixture.preparing, 0);
        expect(
          fixture.manager.tasks.every(
            (task) => task.status == DownloadStatus.paused,
          ),
          isTrue,
        );
        final reopened = await StateStore.open(
          fixture.store.file!.parent,
          testKey: Uint8List(32),
        );
        expect(reopened.data['downloadRecovery'], hasLength(256));
        expect(
          reopened.data
              .list('tasks')
              .every((task) => task.str('status') == 'paused'),
          isTrue,
        );
        expect(fixture.writes, 0);
      } finally {
        await blocked.delete();
      }
      await fixture.manager.recoverInterrupted();
      await until(() => fixture.preparing == 1);
      expect(fixture.manager.activeTasks, hasLength(256));
      expect(fixture.store.data['downloadRecovery'], isEmpty);
      expect(fixture.manager.activeCount, 1);
    },
  );

  test('cancelling a large enqueue leaves no partial queue', () async {
    final fixture = await _BulkFixture.create(0);
    addTearDown(fixture.close);
    final scope = RequestScope();
    final work = scope.run(
      () =>
          fixture.manager.enqueueAll([for (var i = 0; i < 2274; i++) _spec(i)]),
    );
    final cancelled = expectLater(work, throwsA(isA<AppException>()));
    Timer.run(scope.cancel);
    await cancelled;
    expect(fixture.manager.tasks, isEmpty);
    expect(fixture.store.data.list('tasks'), isEmpty);
    expect(fixture.writes, 0);
    expect(fixture.preparing, 0);
  });

  test(
    '10000-file enqueue keeps paths, yields to the event loop and limits concurrency',
    () async {
      final fixture = await _BulkFixture.create(0);
      addTearDown(fixture.close);
      var ticks = 0;
      final timer = Timer.periodic(
        const Duration(milliseconds: 1),
        (_) => ticks++,
      );
      addTearDown(timer.cancel);
      final ids = await fixture.manager.enqueueAll([
        for (var i = 0; i < 10000; i++) _spec(i),
      ]);
      await until(() => fixture.preparing == 1);
      expect(ids.toSet(), hasLength(10000));
      expect(fixture.manager.tasks, hasLength(10000));
      expect(
        fixture.manager.tasks.every(
          (task) => task.spec.relativePath == p.join('folder', 'subfolder'),
        ),
        isTrue,
      );
      expect(fixture.manager.activeCount, 1);
      expect(fixture.writes, lessThanOrEqualTo(4));
      expect(ticks, greaterThan(10));
      await fixture.manager.pauseAll();
      expect(fixture.manager.activeCount, 0);
      expect(fixture.preparing, 1);
      final reopened = _BulkFixture(
        fixture.root,
        StateStore.memory(fixture.store.data),
      );
      addTearDown(() async {
        await reopened.manager.close();
        reopened.manager.dispose();
      });
      await reopened.manager.initialize();
      expect(reopened.manager.tasks, hasLength(10000));
      expect(reopened.writes, 0);
      expect(reopened.preparing, 0);
      await reopened.manager.resumeAll();
      await until(() => reopened.preparing == 1);
      expect(reopened.manager.activeTasks, hasLength(10000));
      expect(reopened.manager.activeCount, 1);
      await reopened.manager.pauseAll();
      expect(reopened.manager.activeCount, 0);
      expect(
        reopened.manager.tasks.every(
          (task) => task.status == DownloadStatus.paused,
        ),
        isTrue,
      );
    },
    timeout: const Timeout(Duration(minutes: 2)),
  );
}
