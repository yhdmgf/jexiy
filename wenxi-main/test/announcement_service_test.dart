import 'dart:async';
import 'dart:convert';
import 'package:fake_async/fake_async.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:asterlink/data/announcement_service.dart';
import 'package:asterlink/data/remote_control_http.dart';
import 'package:asterlink/data/state_store.dart';
import 'announcements_support.dart';
import 'remote_control_support.dart';

void main() {
  late StateStore store;
  late FakeControlFetcher fetcher;
  late AnnouncementService service;
  setUp(() {
    store = StateStore.memory();
    fetcher = FakeControlFetcher(announcementsFixture());
    service = AnnouncementService(
      store,
      url: announcementsEndpoint,
      fetcher: fetcher,
      clock: () => announcementsNow,
    );
  });
  tearDown(() async {
    await service.close();
    store.dispose();
  });

  test(
    'Coalesces requests and keeps update configuration independent',
    () async {
      const existing = {
        'announcementMute': {'contentKey': 'old'},
        'ignoredUpdates': {'android': 300},
      };
      await store.put('remoteControlSeen', existing);
      final pending = Completer<String>();
      fetcher.respond = (_) => pending.future;
      final first = service.refresh(force: true);
      expect(identical(first, service.refresh(force: true)), isTrue);
      expect(service.checking, isTrue);
      pending.complete(jsonEncode(announcementsFixture()));
      expect(await first, isTrue);
      expect(fetcher.calls, [Uri.parse(announcementsEndpoint)]);
      expect(service.messages, hasLength(3));
      expect(service.unreadCount, 3);
      await service.markAllRead();
      expect(service.unreadCount, 0);
      expect(store.data['remoteControlSeen'], existing);
      expect(store.data['remoteControlCache'], isNull);
    },
  );

  test(
    'Cached list and read state survive restart and offline fetching',
    () async {
      await service.refresh();
      await service.markRead(service.messages.first);
      await service.flush();
      final restoredStore = StateStore.memory(
        jsonDecode(jsonEncode(store.data)),
      );
      final offline = FakeControlFetcher()
        ..respond = (_) => throw const ControlAccessException('offline');
      final restored = AnnouncementService(
        restoredStore,
        url: announcementsEndpoint,
        fetcher: offline,
        clock: () => announcementsNow,
      );
      addTearDown(() async {
        await restored.close();
        restoredStore.dispose();
      });
      expect(restored.messages, hasLength(3));
      expect(restored.isRead(restored.messages.first), isTrue);
      expect(restored.unreadCount, 2);
      expect(await restored.refresh(force: true), isFalse);
      expect(restored.hasCache, isTrue);
      expect(restored.messages, hasLength(3));
      expect(restored.error, contains('网络'));
    },
  );

  test(
    'Same ID with edited body becomes unread; metadata-only edits remain read',
    () async {
      final fixture = announcementsFixture();
      await service.refresh();
      await service.markAllRead();
      final row = (fixture['announcements'] as List).first as Map;
      row['id'] = 'new-id';
      row['publishedAt'] = '2026-10-10T01:00:00Z';
      row['pinned'] = false;
      fixture['revision'] = -1;
      fetcher.text = jsonEncode(fixture);
      await service.refresh(force: true);
      expect(service.unreadCount, 0);
      row['content'] = '更新后的内容';
      fetcher.text = jsonEncode(fixture);
      await service.refresh(force: true);
      expect(service.unreadCount, 1);
      expect(
        service.messages.singleWhere((item) => !service.isRead(item)).content,
        '更新后的内容',
      );
    },
  );

  test(
    'Malformed JSON retains cache; empty publications withdraw every notice',
    () async {
      await service.refresh();
      for (final text in [
        '<html>error</html>',
        '{"announcements":[{"title":"bad"}]}',
        '{}',
      ]) {
        fetcher.text = text;
        expect(await service.refresh(force: true), isFalse);
        expect(service.messages, hasLength(3));
      }
      fetcher.text = '{"announcements":[]}';
      expect(await service.refresh(force: true), isTrue);
      expect(service.messages, isEmpty);
      expect(service.unreadCount, 0);
      expect(service.error, isNull);
    },
  );

  test(
    'Only invalid entries are skipped when the remainder is usable',
    () async {
      final fixture = announcementsFixture();
      (fixture['announcements'] as List).add(null);
      fetcher.text = jsonEncode(fixture);
      expect(await service.refresh(), isTrue);
      expect(service.skippedItems, 1);
      expect(service.messages, hasLength(3));
    },
  );

  test(
    'Unconfigured or different endpoints cannot borrow another feed cache',
    () async {
      await service.refresh();
      await service.flush();
      for (final endpoint in [
        '',
        'http://insecure.test/announcements.json',
        'https://other.test/news.json',
      ]) {
        final otherFetcher = FakeControlFetcher();
        final other = AnnouncementService(
          store,
          url: endpoint,
          fetcher: otherFetcher,
        );
        expect(other.messages, isEmpty);
        if (!endpoint.startsWith('https:')) {
          expect(await other.refresh(force: true), isFalse);
          expect(otherFetcher.calls, isEmpty);
        }
        await other.close();
      }
    },
  );

  test('Closing ignores a late response and never persists it', () async {
    final response = Completer<String>();
    fetcher.respond = (_) => response.future;
    final refresh = service.refresh(force: true);
    await service.close();
    response.complete(jsonEncode(announcementsFixture()));
    expect(await refresh, isFalse);
    expect(service.messages, isEmpty);
    expect(store.data[AnnouncementService.cacheKey], isNull);
    expect(fetcher.closed, isTrue);
  });

  test('Foreground polling backs off on failure and stops in background', () {
    fakeAsync((time) {
      final localStore = StateStore.memory();
      final localFetcher = FakeControlFetcher(announcementsFixture());
      final local = AnnouncementService(
        localStore,
        url: announcementsEndpoint,
        fetcher: localFetcher,
        clock: () => announcementsNow.add(time.elapsed),
      );
      local.setForeground(true);
      time.flushMicrotasks();
      expect(localFetcher.calls, hasLength(1));
      time.elapse(const Duration(minutes: 14));
      expect(localFetcher.calls, hasLength(1));
      localFetcher.respond = (_) =>
          throw const ControlAccessException('offline');
      time.elapse(const Duration(minutes: 1));
      time.flushMicrotasks();
      expect(localFetcher.calls, hasLength(2));
      time.elapse(const Duration(minutes: 1));
      time.flushMicrotasks();
      expect(localFetcher.calls, hasLength(3));
      local.setForeground(false);
      time.elapse(const Duration(hours: 1));
      expect(localFetcher.calls, hasLength(3));
      unawaited(local.close());
      time.flushMicrotasks();
      localStore.dispose();
      expect(time.pendingTimers, isEmpty);
    });
  });
}
