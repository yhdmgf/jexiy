import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math' as math;

import 'package:flutter_test/flutter_test.dart';
import 'package:asterlink/core/json.dart';
import 'package:asterlink/core/operation_progress.dart';
import 'package:asterlink/data/cleanup_outbox.dart';
import 'package:asterlink/data/cloud_repository.dart';
import 'package:asterlink/data/http.dart';
import 'package:asterlink/data/http_retry.dart';
import 'package:asterlink/data/providers/aliyun.dart';
import 'package:asterlink/data/state_store.dart';
import 'package:asterlink/domain/auth.dart';
import 'package:asterlink/domain/models.dart';
import 'package:asterlink/domain/uploads.dart';
import 'support.dart';
import 'token_cloud_support.dart';

const _platform = CloudPlatform.aliyun;

AliyunConnector _connector(
  JsonHttp http,
  Vault vault, {
  Future<void> Function(Duration)? wait,
}) => AliyunConnector(
  http,
  vault,
  now: () => tokenClock,
  listRequestInterval: Duration.zero,
  listWait: wait ?? (_) async {},
);

Future<List<CloudFile>> _list(AliyunConnector connector, Vault vault) =>
    connector.list(
      tokenPersonal(_platform),
      'root',
      vault.credential(_platform),
    );

Matcher _failure(String text) => throwsA(
  isA<AppException>().having(
    (error) => error.message,
    'message',
    contains(text),
  ),
);

void main() {
  test(
    'large directory resumes the same marker after a business rate limit',
    () async {
      final vault = await tokenVault(_platform);
      const total = 5201;
      var limited = false;
      final http = FakeHttp((request) {
        expect(request.uri.path, '/adrive/v3/file/list');
        expect(request.json.integer('limit'), 200);
        expect(request.json.str('drive_id'), 'resource');
        final start = int.tryParse(request.json.str('marker')) ?? 0;
        if (start == 600 && !limited) {
          limited = true;
          return jsonResponse({
            'code': 'TooManyRequests',
            'message': 'rule:TooManyRequests',
          }, 400);
        }
        final end = math.min(start + 200, total);
        return jsonResponse({
          'items': [for (var i = start; i < end; i++) aliFile('file-$i')],
          'next_marker': end == total ? '' : '$end',
        });
      });
      final progress = OperationProgress();
      addTearDown(progress.dispose);
      final updates = <OperationStep>[];
      progress.addListener(() => updates.addAll(progress.value));
      final waits = <Duration>[];
      final connector = _connector(
        http,
        vault,
        wait: (duration) async => waits.add(duration),
      );
      final result = await progress.run(
        () => OperationProgress.step(
          OperationStage.readFiles,
          () => _list(connector, vault),
        ),
      );
      expect(result.map((file) => file.id), [
        for (var i = 0; i < total; i++) 'file-$i',
      ]);
      expect(http.calls.length, 28);
      expect(http.calls[3].body, http.calls[4].body);
      expect(waits, hasLength(1));
      expect(waits.single.inMilliseconds, greaterThan(1800));
      expect(
        updates.any((step) => step.itemsRead == 600 && step.waitingForRetry),
        isTrue,
      );
      expect(progress.value.single.itemsRead, total);
      expect(progress.value.single.waitingForRetry, isFalse);
      expect(progress.value.single.status, OperationStepStatus.completed);
    },
  );

  for (final (status, body) in [
    (200, jsonEncode({'code': 'TooManyRequests', 'message': 'retry later'})),
    (
      400,
      jsonEncode({
        'code': 'TooManyRequests',
        'message': 'rule:TooManyRequests',
      }),
    ),
    (403, jsonEncode({'code': 'Forbidden', 'message': 'rule:TooManyRequests'})),
    (429, jsonEncode({'message': 'request throttled'})),
    (429, '<html>Too many requests</html>'),
  ]) {
    test(
      'list retries rate limit with HTTP $status and ${body.startsWith('<') ? 'HTML' : 'JSON'}',
      () async {
        final vault = await tokenVault(_platform);
        var attempts = 0;
        final http = FakeHttp(
          (_) => ++attempts == 1
              ? HttpResult(status, body)
              : jsonResponse({
                  'items': [aliFile('ready')],
                }),
        );
        final result = await _list(_connector(http, vault), vault);
        expect(result.single.id, 'ready');
        expect(attempts, 2);
      },
    );
  }

  test(
    'persistent rate limiting has a finite budget and a readable error',
    () async {
      final vault = await tokenVault(_platform);
      final http = FakeHttp(
        (_) => jsonResponse({'code': 'TooManyRequests'}, 400),
      );
      final waits = <Duration>[];
      await expectLater(
        _list(
          _connector(
            http,
            vault,
            wait: (duration) async => waits.add(duration),
          ),
          vault,
        ),
        _failure('读取文件列表过于频繁'),
      );
      expect(http.calls, hasLength(4));
      expect(waits, hasLength(3));
      expect(
        waits.map((duration) => duration.inMilliseconds),
        everyElement(greaterThan(1800)),
      );
      expect(waits[1], greaterThan(waits[0]));
      expect(waits[2], greaterThan(waits[1]));
    },
  );

  test(
    'the directory retry budget is shared by every page and never returns a partial list',
    () async {
      final vault = await tokenVault(_platform);
      final seen = <String>{};
      final http = FakeHttp((request) {
        final marker = request.json.str('marker');
        if (seen.add(marker)) {
          return jsonResponse({'code': 'TooManyRequests'}, 400);
        }
        final page = int.tryParse(marker) ?? 0;
        return jsonResponse({
          'items': [aliFile('file-$page')],
          'next_marker': '${page + 1}',
        });
      });
      await expectLater(
        _list(_connector(http, vault), vault),
        _failure('请稍后再试'),
      );
      expect(http.calls, hasLength(7));
      expect(seen, hasLength(4));
    },
  );

  for (final date in [false, true]) {
    test(
      'Retry-After ${date ? 'HTTP date' : 'seconds'} is respected',
      () async {
        final vault = await tokenVault(_platform);
        var attempts = 0;
        final after = date
            ? HttpDate.format(
                DateTime.now().toUtc().add(const Duration(seconds: 8)),
              )
            : '7';
        final http = FakeHttp(
          (_) => ++attempts == 1
              ? HttpResult(429, '{}', {
                  'retry-after': [after],
                })
              : jsonResponse({'items': []}),
        );
        final waits = <Duration>[];
        await _list(
          _connector(
            http,
            vault,
            wait: (duration) async => waits.add(duration),
          ),
          vault,
        );
        expect(attempts, 2);
        expect(waits.single.inMilliseconds, greaterThan(6000));
      },
    );
  }

  test(
    'a long server cooldown fails promptly and refresh does not bypass it',
    () async {
      final vault = await tokenVault(_platform);
      final http = FakeHttp(
        (_) => const HttpResult(429, '{}', {
          'retry-after': ['3600'],
        }),
      );
      final waits = <Duration>[];
      final connector = _connector(
        http,
        vault,
        wait: (duration) async => waits.add(duration),
      );
      await expectLater(_list(connector, vault), _failure('请稍后再试'));
      await expectLater(_list(connector, vault), _failure('请稍后再试'));
      expect(http.calls, hasLength(1));
      expect(waits, isEmpty);
    },
  );

  test(
    'HTTP retries already exhausted by preparation are not replayed',
    () async {
      final vault = await tokenVault(_platform);
      final transport = FakeHttp((_) => const HttpResult(429, '{}'));
      final connector = _connector(RetryingJsonHttp(transport), vault);
      final waits = <Duration>[];
      final scope = ReadRetryScope(
        retries: 2,
        checkpoint: RequestScope.checkpoint,
        wait: (duration) async => waits.add(duration),
      );
      await expectLater(
        scope.run(() => _list(connector, vault)),
        _failure('请稍后再试'),
      );
      expect(transport.calls, hasLength(3));
      expect(waits, hasLength(2));
    },
  );

  test(
    'concurrent folder requests are serialized and paced after each response',
    () async {
      final vault = await tokenVault(_platform);
      final watch = Stopwatch()..start();
      final starts = <int>[];
      var active = 0, peak = 0;
      final http = FakeHttp((request) async {
        peak = math.max(peak, ++active);
        starts.add(watch.elapsedMilliseconds);
        await Future<void>.delayed(const Duration(milliseconds: 10));
        active--;
        return jsonResponse({'items': []});
      });
      final connector = AliyunConnector(
        http,
        vault,
        now: () => tokenClock,
        listRequestInterval: const Duration(milliseconds: 30),
      );
      await Future.wait([for (var i = 0; i < 3; i++) _list(connector, vault)]);
      expect(peak, 1);
      expect(starts, hasLength(3));
      expect(starts[1] - starts[0], greaterThanOrEqualTo(35));
      expect(starts[2] - starts[1], greaterThanOrEqualTo(35));
    },
  );

  test(
    'cancelling a queued request prevents it from contacting the service later',
    () async {
      final vault = await tokenVault(_platform);
      final entered = Completer<void>(), release = Completer<void>();
      final http = FakeHttp((request) async {
        if (!entered.isCompleted) entered.complete();
        await release.future;
        return jsonResponse({'items': []});
      });
      final connector = _connector(http, vault);
      final first = _list(connector, vault);
      await entered.future;
      final scope = RequestScope();
      final queued = scope.run(() => _list(connector, vault));
      final cancelled = expectLater(queued, _failure('取消'));
      await Future<void>.delayed(Duration.zero);
      scope.cancel();
      await cancelled;
      release.complete();
      await first;
      await _list(connector, vault);
      expect(http.calls, hasLength(2));
    },
  );

  for (final throttled in [false, true]) {
    test(
      'cancelling ${throttled ? 'rate limit backoff' : 'pagination wait'} sends no later page',
      () async {
        final vault = await tokenVault(_platform);
        final waiting = Completer<void>();
        final http = FakeHttp(
          (_) => throttled
              ? jsonResponse({'code': 'TooManyRequests'}, 400)
              : jsonResponse({
                  'items': [aliFile('first')],
                  'next_marker': 'next',
                }),
        );
        final connector = AliyunConnector(
          http,
          vault,
          now: () => tokenClock,
          listWait: (duration) async {
            waiting.complete();
            await RequestScope.wait(duration);
          },
        );
        final scope = RequestScope();
        final work = scope.run(() => _list(connector, vault));
        final cancelled = expectLater(work, _failure('取消'));
        await waiting.future;
        scope.cancel();
        await cancelled;
        await Future<void>.delayed(Duration.zero);
        expect(http.calls, hasLength(1));
      },
    );
  }

  test(
    'account changes during a pagination wait stop the old account request',
    () async {
      final vault = await tokenVault(_platform);
      final waiting = Completer<void>(), release = Completer<void>();
      final http = FakeHttp(
        (_) => jsonResponse({
          'items': [aliFile('first')],
          'next_marker': 'next',
        }),
      );
      final connector = AliyunConnector(
        http,
        vault,
        now: () => tokenClock,
        listWait: (_) async {
          waiting.complete();
          await release.future;
        },
      );
      final work = _list(connector, vault);
      final changed = expectLater(work, _failure('账号已变化'));
      await waiting.future;
      await vault.putCredential(
        _platform,
        tokenCredential(
          _platform,
          revision: 999,
          fields: {
            'userId': 'different-owner',
            'refreshToken': 'different-refresh-token',
          },
        ),
      );
      release.complete();
      await changed;
      expect(http.calls, hasLength(1));
    },
  );

  for (final (code, status) in [
    ('AccessDenied', 403),
    ('NotFound.FileId', 404),
    ('InvalidParameter', 400),
  ]) {
    test('non-throttling error $code is not retried', () async {
      final vault = await tokenVault(_platform);
      final http = FakeHttp((_) => jsonResponse({'code': code}, status));
      await expectLater(
        _list(_connector(http, vault), vault),
        throwsA(isA<AliyunApiException>()),
      );
      expect(http.calls, hasLength(1));
    });
  }

  test(
    'expired login remains a login error, not a list throttling loop',
    () async {
      final vault = await tokenVault(
        _platform,
        tokenCredential(_platform, fields: {'refreshToken': ''}),
      );
      final http = FakeHttp(
        (_) => jsonResponse({'code': 'AccessTokenInvalid'}, 401),
      );
      await expectLater(
        _list(_connector(http, vault), vault),
        throwsA(isA<AccountLoginRequired>()),
      );
      expect(http.calls, hasLength(1));
    },
  );

  test(
    'folder mutations are never replayed by list throttling handling',
    () async {
      final vault = await tokenVault(_platform);
      final http = FakeHttp(
        (_) => jsonResponse({'code': 'TooManyRequests'}, 400),
      );
      await expectLater(
        _connector(http, vault).createFolder(
          tokenPersonal(_platform),
          'root',
          'new-folder',
          vault.credential(_platform)!,
        ),
        throwsA(isA<AliyunApiException>()),
      );
      expect(http.calls, hasLength(1));
      expect(http.calls.single.uri.path, '/adrive/v2/file/createWithFolders');
    },
  );

  test(
    'upload name checks recover pagination and still reject an existing file',
    () async {
      final vault = await tokenVault(_platform);
      var pages = 0;
      final http = FakeHttp((request) {
        expect(request.uri.path, '/adrive/v3/file/list');
        final next = request.json.str('marker') == 'next';
        if (++pages == 2) return jsonResponse({'code': 'TooManyRequests'}, 400);
        return jsonResponse({
          'items': [
            aliFile(
              next ? 'existing' : 'another',
              name: next ? 'sample.txt' : 'another.txt',
            ),
          ],
          'next_marker': next ? '' : 'next',
        });
      });
      final repository = CloudRepository(
        http,
        vault,
        CleanupOutbox(StateStore.memory(), http),
      );
      repository.connectors[_platform] = _connector(repository.http, vault);
      var fileReads = 0;
      final source = UploadFile(
        name: 'sample.txt',
        size: 1,
        read: (start, end) {
          fileReads++;
          return Stream.value([1]);
        },
      );
      await expectLater(
        repository.upload(tokenPersonal(_platform), 'root', source),
        _failure('已有同名项目'),
      );
      expect(pages, 3);
      expect(fileReads, 0);
      expect(
        http.calls.every((request) => request.uri.path.endsWith('/file/list')),
        isTrue,
      );
    },
  );

  test(
    'a cancelled reader leaves the cooldown in place for the next reader',
    () async {
      final vault = await tokenVault(_platform);
      final waiting = Completer<void>();
      var requests = 0;
      final http = FakeHttp(
        (_) => ++requests == 1
            ? jsonResponse({'code': 'TooManyRequests'}, 400)
            : jsonResponse({'items': []}),
      );
      final delays = <Duration>[];
      final connector = _connector(
        http,
        vault,
        wait: (duration) async {
          delays.add(duration);
          if (delays.length == 1) {
            waiting.complete();
            await RequestScope.wait(duration);
          }
        },
      );
      final scope = RequestScope();
      final first = scope.run(() => _list(connector, vault));
      final cancelled = expectLater(first, _failure('取消'));
      await waiting.future;
      scope.cancel();
      await cancelled;
      await _list(connector, vault);
      expect(delays, hasLength(2));
      expect(delays.last.inMilliseconds, greaterThan(1800));
      expect(requests, 2);
    },
  );

  test(
    'anonymous share pagination retries without adding account authentication',
    () async {
      final vault = await tokenVault(_platform);
      var reads = 0;
      final http = FakeHttp((request) {
        expect(request.headers.containsKey('Authorization'), isFalse);
        if (request.uri.path.endsWith('/get_share_token')) {
          return aliDefaultResponse(request);
        }
        expect(request.uri.path, '/adrive/v2/file/list_by_share');
        expect(request.headers['X-Share-Token'], 'fixture-share-token');
        expect(request.json.str('share_id'), 'Share123');
        final second = request.json.str('marker') == 'next';
        if (++reads == 2) return jsonResponse({'code': 'TooManyRequests'}, 400);
        return jsonResponse({
          'items': [aliFile(second ? 'second' : 'first')],
          'next_marker': second ? '' : 'next',
        });
      });
      final result = await _connector(
        http,
        vault,
      ).list(tokenShare(_platform), 'root', null);
      expect(result.map((file) => file.id), ['first', 'second']);
      expect(reads, 3);
    },
  );
}
