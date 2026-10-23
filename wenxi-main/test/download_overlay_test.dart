import 'package:fake_async/fake_async.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:asterlink/domain/models.dart';
import 'package:asterlink/domain/appearance.dart';
import 'package:asterlink/domain/downloads.dart';
import 'package:asterlink/download/download_overlay.dart';
import 'package:asterlink/domain/uploads.dart';
import 'package:asterlink/upload/upload_task.dart';

UploadTask uploadTask(
  String id, {
  int size = 100,
  int sent = 50,
  int created = 2,
  UploadStatus status = UploadStatus.running,
  UploadPhase phase = UploadPhase.uploading,
}) => UploadTask(
  id: id,
  session: const BrowseSession(
    platform: CloudPlatform.quark,
    mode: BrowseMode.personal,
    title: 'private space',
    rootId: '0',
    metadata: {'accountId': 'private-account'},
  ),
  parent: 'private-folder',
  folderName: 'Documents',
  accountName: 'private account',
  path: 'private/local/file',
  name: '$id.txt',
  size: size,
  modifiedAt: 1,
  createdAt: created,
  status: status,
  phase: phase,
  sent: sent,
)..speed = 20;

DownloadTask task(
  String id, {
  int size = 100,
  int received = 50,
  DownloadStatus status = DownloadStatus.running,
  int created = 1,
}) => DownloadTask(
  id: id,
  spec: DownloadSpec(
    url: 'https://fixture.invalid/private?secret=never-copy',
    fileName: '$id.zip',
  ),
  createdAt: created,
  downloaded: received,
  total: size,
  status: status,
  speed: 100,
);

void main() {
  test(
    'theme changes reach an open overlay without changing progress or actions',
    () {
      fakeAsync((async) {
        final changes = ChangeNotifier();
        var palette = AppPalette.blue;
        var dark = false;
        final snapshots = <Map<String, dynamic>>[];
        final controller = DownloadOverlay(
          supported: true,
          changes: changes,
          tasks: () => [task('themed')],
          dark: () => dark,
          appearance: (isDark) =>
              palette.colors(isDark).overlay(GlassStyle.solid),
          pause: (_) async => fail('Theme must not pause transfers'),
          resume: (_) async => fail('Theme must not restart transfers'),
          pauseAll: () async => fail('Theme must not pause transfers'),
          resumeAll: () async => fail('Theme must not restart transfers'),
          openDownloads: () => fail('Theme must not navigate'),
          invoke: (method, data) async {
            if (method != 'downloadOverlayClose') snapshots.add(data);
            return method == 'downloadOverlayShow' ? {'visible': true} : null;
          },
        );
        controller.toggle();
        async.flushMicrotasks();
        async.elapse(const Duration(seconds: 1));
        final first = snapshots.first;
        palette = AppPalette.mint;
        changes.notifyListeners();
        async.elapse(const Duration(seconds: 1));
        expect(
          (snapshots.last['appearance'] as Map)['accent'],
          AppPalette.mint.lightAccent,
        );
        dark = true;
        controller.refreshAppearance();
        async.elapse(const Duration(seconds: 1));
        expect(
          (snapshots.last['appearance'] as Map)['accent'],
          AppPalette.mint.darkAccent,
        );
        expect(snapshots.last['runId'], first['runId']);
        expect(snapshots.last['progress'], first['progress']);
        expect(snapshots.last['tasks'], first['tasks']);
        controller.close();
        async.flushMicrotasks();
        changes.dispose();
      });
    },
  );
  test('Uploads are included without exposing account or local file data', () {
    final value = DownloadOverlayBatch().snapshot(
      [],
      uploads: [uploadTask('one')],
    );
    expect(value['progress'], 50);
    expect(value['direction'], 'upload');
    expect(value['status'], '正在上传');
    expect(value['uploadSpeed'], 20);
    expect(value['canPause'], false);
    expect((value['tasks'] as List).single['action'], 'openUploads');
    expect(value.toString(), isNot(contains('private')));
  });
  test('Mixed queues weight byte counts and namespace colliding task IDs', () {
    final value = DownloadOverlayBatch().snapshot(
      [task('same', received: 100)],
      uploads: [uploadTask('same', size: 900, sent: 0)],
    );
    expect(value['progress'], 10);
    expect(value['status'], '正在传输');
    expect(value['direction'], 'mixed');
    expect(value['downloadActive'], 1);
    expect(value['uploadActive'], 1);
    expect(value['downloadSpeed'], 100);
    expect(value['uploadSpeed'], 20);
    final rows = value['tasks'] as List;
    expect(rows.map((r) => r['id']).toSet(), {'download:same', 'upload:same'});
    expect(rows.map((r) => r['taskId']).toSet(), {'same'});
  });
  test(
    'File validation and provider confirmation are not completed uploads',
    () {
      final batch = DownloadOverlayBatch();
      final upload = uploadTask('one', sent: 100, phase: UploadPhase.preparing);
      var value = batch.snapshot([], uploads: [upload]);
      expect(value['progress'], 0);
      expect(value['speed'], 0);
      upload.phase = UploadPhase.finishing;
      value = batch.snapshot([], uploads: [upload]);
      expect(value['progress'], 99);
      expect(value['completed'], 0);
      expect((value['tasks'] as List).single['label'], '等待网盘确认');
      upload.status = UploadStatus.completed;
      value = batch.snapshot([], uploads: [upload]);
      expect(value['progress'], 100);
      expect(value['status'], '全部完成');
    },
  );
  test(
    'New uploads replace old download failures; current failures remain visible',
    () {
      final batch = DownloadOverlayBatch();
      final old = task('old', status: DownloadStatus.failed);
      batch.snapshot([old]);
      final upload = uploadTask('new');
      expect(batch.snapshot([old], uploads: [upload])['failed'], 0);
      upload.status = UploadStatus.completed;
      expect(batch.snapshot([old], uploads: [upload])['status'], '全部完成');
      final next = uploadTask('next', created: 3);
      batch.snapshot([old, task('down', created: 3)], uploads: [upload, next]);
      next.status = UploadStatus.failed;
      final result = batch.snapshot(
        [old, task('down', created: 3, status: DownloadStatus.completed)],
        uploads: [upload, next],
      );
      expect(result['status'], '部分失败');
      expect(result['count'], 2);
      expect(result['failed'], 1);
    },
  );
  test(
    'Cancelled uploads retain their result and retry does not resume downloads',
    () {
      final batch = DownloadOverlayBatch();
      final upload = uploadTask('one');
      batch.snapshot([], uploads: [upload]);
      upload.status = UploadStatus.cancelled;
      final value = batch.snapshot([], uploads: [upload]);
      expect(value['status'], '已取消');
      expect(value['progress'], lessThan(100));
      expect((value['tasks'] as List).single['action'], 'retryUpload');
      expect(value['canResume'], false);
    },
  );
  test(
    'Upload-only queue changes refresh the visible overlay and route upload actions',
    () {
      fakeAsync((async) {
        final downs = ChangeNotifier(), ups = ChangeNotifier();
        final upload = uploadTask('one');
        final snapshots = <Map<String, dynamic>>[], actions = <String>[];
        final controller = DownloadOverlay(
          supported: true,
          changes: Listenable.merge([downs, ups]),
          tasks: () => [],
          uploadTasks: () => [upload],
          dark: () => false,
          pause: (_) async => actions.add('wrong-pause'),
          resume: (_) async => actions.add('wrong-resume'),
          pauseAll: () async {},
          resumeAll: () async {},
          openDownloads: () => actions.add('wrong-open'),
          retryUpload: (id) async => actions.add('retry:$id'),
          openUploads: () => actions.add('open-upload'),
          invoke: (method, data) async {
            snapshots.add(data);
            return method == 'downloadOverlayShow' ? {'visible': true} : null;
          },
        );
        controller.toggle();
        async.flushMicrotasks();
        upload.sent = 80;
        ups.notifyListeners();
        async.elapse(const Duration(seconds: 1));
        async.flushMicrotasks();
        expect(snapshots.last['progress'], 80);
        controller.action({'action': 'retryUpload', 'id': 'one'});
        async.flushMicrotasks();
        expect(actions, isEmpty);
        upload.status = UploadStatus.failed;
        controller.action({'action': 'retryUpload', 'id': 'one'});
        async.flushMicrotasks();
        controller.action({'action': 'openUploads'});
        async.flushMicrotasks();
        expect(actions, ['retry:one', 'open-upload']);
        controller.close();
        async.flushMicrotasks();
        downs.dispose();
        ups.dispose();
      });
    },
  );
  test(
    'Progress is weighted by size and old completed history is excluded',
    () {
      final batch = DownloadOverlayBatch();
      final value = batch.snapshot([
        task('small', received: 100),
        task('large', size: 900, received: 0),
        task('old', size: 10000, status: DownloadStatus.completed),
      ]);
      expect(value['progress'], 10);
      expect(value['count'], 2);
      expect(value.toString(), isNot(contains('secret')));
      expect(value.toString(), isNot(contains('https://')));
    },
  );
  test(
    'Unknown lengths never produce a made-up percentage; finished group stays at 100',
    () {
      final batch = DownloadOverlayBatch();
      expect(batch.snapshot([task('a', size: 0)])['progress'], -1);
      final complete = batch.snapshot([
        task('a', size: 0, status: DownloadStatus.completed),
      ]);
      expect(complete['progress'], 100);
      expect(complete['status'], '全部完成');
      expect(
        batch.snapshot([
          task('a', status: DownloadStatus.completed),
          task('next', received: 5),
        ])['progress'],
        5,
      );
    },
  );
  test('Paused, removed and failed tasks do not masquerade as complete', () {
    final batch = DownloadOverlayBatch();
    expect(
      batch.snapshot([task('a', status: DownloadStatus.paused)])['status'],
      '已暂停',
    );
    expect(
      batch.snapshot([task('a', status: DownloadStatus.failed)])['status'],
      '下载失败',
    );
    expect(batch.snapshot([])['status'], '暂无任务');
  });
  test('Old failures and paused tasks do not contaminate a new download', () {
    final batch = DownloadOverlayBatch();
    final history = [
      task('old-failed', status: DownloadStatus.failed, size: 900),
      task('old-paused', status: DownloadStatus.paused, size: 900),
    ];
    final running = batch.snapshot([...history, task('new', created: 2)]);
    expect(running['count'], 1);
    expect(running['progress'], 50);
    expect(running['failed'], 0);
    final done = batch.snapshot([
      ...history,
      task('new', created: 2, status: DownloadStatus.completed, received: 100),
    ]);
    expect(done['status'], '全部完成');
    expect(done['progress'], 100);
    expect(done['failed'], 0);
  });
  test('Starting another run replaces the previous failed run', () {
    final batch = DownloadOverlayBatch();
    batch.snapshot([task('first')]);
    final failed = task('first', status: DownloadStatus.failed);
    expect(batch.snapshot([failed])['status'], '下载失败');
    expect(batch.snapshot([failed, task('next', created: 2)])['count'], 1);
    final done = batch.snapshot([
      failed,
      task('next', created: 2, status: DownloadStatus.completed),
    ]);
    expect(done['status'], '全部完成');
    expect(done['progress'], 100);
  });
  test('Reopening with no active downloads shows the latest task result', () {
    final batch = DownloadOverlayBatch();
    expect(
      batch.snapshot([
        task('old', status: DownloadStatus.failed),
        task('new', created: 2, status: DownloadStatus.completed),
      ])['status'],
      '全部完成',
    );
    batch.reset();
    expect(
      batch.snapshot([
        task('old', status: DownloadStatus.completed),
        task('new', created: 2, status: DownloadStatus.failed),
      ])['status'],
      '下载失败',
    );
  });
  test('Failures within the current concurrent run remain visible', () {
    final batch = DownloadOverlayBatch();
    batch.snapshot([task('a'), task('b', created: 2)]);
    final failed = task('a', status: DownloadStatus.failed, received: 25);
    expect(batch.snapshot([failed, task('b', created: 2)])['status'], '正在下载');
    final done = batch.snapshot([
      failed,
      task('b', created: 2, status: DownloadStatus.completed, received: 100),
    ]);
    expect(done['count'], 2);
    expect(done['status'], '部分失败');
    expect(done['failed'], 1);
    expect(done['progress'], lessThan(100));
  });
  test('Retrying a historical failure joins the active run', () {
    final batch = DownloadOverlayBatch();
    batch.snapshot([
      task('old', status: DownloadStatus.failed),
      task('new', created: 2),
    ]);
    expect(batch.snapshot([task('old'), task('new', created: 2)])['count'], 2);
    expect(
      batch.snapshot([
        task('old', status: DownloadStatus.completed),
        task('new', created: 2, status: DownloadStatus.completed),
      ])['status'],
      '全部完成',
    );
  });
  test(
    'Fast downloads and retries finishing between refreshes are included',
    () {
      final batch = DownloadOverlayBatch();
      final failed = task('old', status: DownloadStatus.failed);
      batch.snapshot([failed]);
      expect(
        batch.snapshot([
          failed,
          task('fast', created: 2, status: DownloadStatus.completed),
        ])['status'],
        '全部完成',
      );
      expect(
        batch.snapshot([
          task('old', status: DownloadStatus.completed),
          task('fast', created: 2, status: DownloadStatus.completed),
        ])['count'],
        1,
      );
    },
  );
  test(
    'Deleting or cancelling the current task does not revive failed history',
    () {
      final batch = DownloadOverlayBatch();
      final history = task('old', status: DownloadStatus.failed);
      batch.snapshot([history, task('new', created: 2)]);
      expect(
        batch.snapshot([
          history,
          task('new', created: 2, status: DownloadStatus.cancelled),
        ])['status'],
        '暂无任务',
      );
      expect(batch.snapshot([history])['status'], '暂无任务');
    },
  );
  test(
    'Manually toggled overlay coalesces updates and keeps control in the download manager',
    () {
      fakeAsync((async) {
        final changes = ChangeNotifier(),
            calls = <String>[],
            actions = <String>[];
        final controller = DownloadOverlay(
          supported: true,
          changes: changes,
          tasks: () => [task('one')],
          dark: () => false,
          pause: (id) async {
            actions.add('pause:$id');
          },
          resume: (id) async {
            actions.add('resume:$id');
          },
          pauseAll: () async {
            actions.add('pauseAll');
          },
          resumeAll: () async {
            actions.add('resumeAll');
          },
          openDownloads: () {
            actions.add('open');
          },
          invoke: (method, data) async {
            calls.add(method);
            return method == 'downloadOverlayShow' ? {'visible': true} : null;
          },
        );
        changes.notifyListeners();
        async.elapse(const Duration(seconds: 2));
        expect(calls, isEmpty);
        controller.toggle();
        async.flushMicrotasks();
        expect(controller.visible, isTrue);
        for (var i = 0; i < 50; i++) {
          changes.notifyListeners();
        }
        async.elapse(const Duration(milliseconds: 800));
        expect(calls.where((c) => c == 'downloadOverlayUpdate'), hasLength(1));
        controller.action({'action': 'pause', 'id': 'one'});
        async.flushMicrotasks();
        controller.action({'action': 'pause', 'id': 'removed'});
        async.flushMicrotasks();
        controller.action({'action': 'openDownloads'});
        async.flushMicrotasks();
        expect(actions, ['pause:one', 'open']);
        controller.toggle();
        async.flushMicrotasks();
        expect(controller.visible, isFalse);
        controller.close();
        async.flushMicrotasks();
        changes.dispose();
      });
    },
  );
}
