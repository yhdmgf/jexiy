import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:asterlink/core/json.dart';
import 'package:asterlink/data/http.dart';
import 'package:asterlink/data/providers/aliyun.dart';
import 'package:asterlink/data/state_store.dart';
import 'package:asterlink/domain/models.dart';
import 'support.dart';
import 'token_cloud_support.dart';

const _platform = CloudPlatform.aliyun;
const _info = '/adrive/v1/user/albums_info';

HttpResult _album(String id) => jsonResponse({
  'code': 200,
  'data': {'driveId': id, 'driveName': 'album'},
});

AliyunConnector _connector(
  JsonHttp http,
  Vault vault, {
  int Function()? clock,
  Duration timeout = const Duration(seconds: 1),
}) => AliyunConnector(
  http,
  vault,
  now: clock ?? () => tokenClock,
  listRequestInterval: Duration.zero,
  albumLookupTimeout: timeout,
);

void main() {
  test(
    'album discovery preserves the default drive and routes files to the album',
    () async {
      final vault = await tokenVault(_platform);
      var discoveries = 0;
      final http = FakeHttp((request) {
        if (request.uri.path == _info) {
          discoveries++;
          expect(JsonHttp.isReadRequest(request.method, request.url), isTrue);
          return _album('album-drive');
        }
        if (request.uri.path.endsWith('/file/list')) {
          expect(request.json.str('drive_id'), 'album-drive');
          return jsonResponse({
            'items': [aliFile('photo', name: '海边.jpg')],
          });
        }
        if (request.uri.path.endsWith('/get_download_url')) {
          expect(request.json.str('drive_id'), 'album-drive');
          expect(request.json.str('file_id'), 'photo');
          return jsonResponse({
            'url': 'https://files.example.test/original.jpg',
          });
        }
        return aliDefaultResponse(request);
      });
      final connector = _connector(http, vault),
          credential = vault.credential(_platform)!;
      final spaces = await connector.personalSpaces(credential);
      expect(spaces.map((e) => e.name), ['资源库', '备份盘', '相册']);
      expect(
        (await connector.openPersonal(credential)).personalSpaceId,
        'resource',
      );
      final album = await connector.openPersonalSpace(spaces.last, credential);
      expect(album.meta('driveName'), '相册');
      final file = (await connector.list(album, 'root', credential)).single;
      final download = await connector.download(album, file, credential);
      expect(download.fileName, '海边.jpg');
      expect(download.url, 'https://files.example.test/original.jpg');
      expect(
        connector.destinationId(album, 'folder'),
        'aliyun:album-drive:folder',
      );
      expect(discoveries, 1);
    },
  );

  test(
    'a saved album remains available after reopening with a refreshed token',
    () async {
      final vault = await tokenVault(
        _platform,
        tokenCredential(
          _platform,
          expired: true,
          fields: {
            'albumDriveId': 'album-drive',
            'albumDriveCheckedAt': '$tokenClock',
          },
        ),
      );
      final http = FakeHttp((request) {
        expect(request.uri.path, isNot(_info));
        return aliDefaultResponse(request);
      });
      final spaces = await _connector(
        http,
        vault,
      ).personalSpaces(vault.credential(_platform)!);
      expect(spaces.last.id, 'album-drive');
      expect(vault.credential(_platform)!.field('accessToken'), renewedAccess);
    },
  );

  test(
    'concurrent discovery runs once and an expired discovery refreshes the ID',
    () async {
      final vault = await tokenVault(_platform);
      var now = tokenClock, calls = 0;
      final http = FakeHttp(
        (request) => request.uri.path == _info
            ? _album('album-${++calls}')
            : aliDefaultResponse(request),
      );
      final connector = _connector(http, vault, clock: () => now);
      final credential = vault.credential(_platform)!;
      final results = await Future.wait(
        List.generate(4, (_) => connector.personalSpaces(credential)),
      );
      expect(results.every((spaces) => spaces.last.id == 'album-1'), isTrue);
      expect(calls, 1);
      now += const Duration(minutes: 6).inMilliseconds;
      expect((await connector.personalSpaces(credential)).last.id, 'album-2');
      expect(calls, 2);
    },
  );

  for (final saved in [false, true]) {
    test(
      'unavailable album service preserves ${saved ? 'saved album and' : ''} ordinary drives',
      () async {
        final vault = await tokenVault(
          _platform,
          tokenCredential(
            _platform,
            fields: {if (saved) 'albumDriveId': 'saved-album'},
          ),
        );
        var calls = 0;
        final http = FakeHttp((request) {
          if (request.uri.path == _info) {
            calls++;
            return jsonResponse({'code': 'ServiceUnavailable'}, 503);
          }
          return aliDefaultResponse(request);
        });
        final connector = _connector(http, vault),
            credential = vault.credential(_platform)!;
        for (var i = 0; i < 2; i++) {
          expect(
            (await connector.personalSpaces(credential)).map((s) => s.id),
            ['resource', 'backup', if (saved) 'saved-album'],
          );
        }
        expect(calls, 1);
      },
    );
  }

  test('an optional lookup times out without saving a late result', () async {
    final vault = await tokenVault(_platform);
    final late = Completer<HttpResult>();
    final http = FakeHttp(
      (request) =>
          request.uri.path == _info ? late.future : aliDefaultResponse(request),
    );
    final spaces = await _connector(
      http,
      vault,
      timeout: const Duration(milliseconds: 20),
    ).personalSpaces(vault.credential(_platform)!);
    expect(spaces.map((e) => e.id), ['resource', 'backup']);
    late.complete(_album('late-album'));
    await Future<void>.delayed(Duration.zero);
    expect(vault.credential(_platform)!.field('albumDriveId'), isEmpty);
  });

  test('parent cancellation is propagated and cannot save an album', () async {
    final vault = await tokenVault(_platform),
        pending = Completer<HttpResult>();
    var started = false;
    final http = FakeHttp((request) {
      if (request.uri.path == _info) {
        started = true;
        return pending.future;
      }
      return aliDefaultResponse(request);
    });
    final scope = RequestScope();
    final operation = scope.run(
      () =>
          _connector(http, vault).personalSpaces(vault.credential(_platform)!),
    );
    final assertion = expectLater(
      operation,
      throwsA(
        isA<AppException>().having((e) => e.message, 'message', contains('取消')),
      ),
    );
    await until(() => started);
    scope.cancel();
    await assertion;
    pending.complete(_album('cancelled-album'));
    await Future<void>.delayed(Duration.zero);
    expect(vault.credential(_platform)!.field('albumDriveId'), isEmpty);
  });

  test(
    'removing an account during discovery cannot attach its album to a replacement',
    () async {
      final vault = await tokenVault(_platform),
          pending = Completer<HttpResult>();
      var started = false;
      final http = FakeHttp((request) {
        if (request.uri.path == _info) {
          started = true;
          return pending.future;
        }
        return aliDefaultResponse(request);
      });
      final operation = _connector(
        http,
        vault,
      ).personalSpaces(vault.credential(_platform)!);
      final assertion = expectLater(operation, throwsA(isA<AppException>()));
      await until(() => started);
      await vault.removeCredential(_platform);
      await vault.putCredential(
        _platform,
        tokenCredential(
          _platform,
          revision: tokenClock + 1,
          fields: {'userId': 'replacement-user'},
        ),
      );
      pending.complete(_album('wrong-owner'));
      await assertion;
      expect(vault.credential(_platform)!.field('albumDriveId'), isEmpty);
    },
  );

  test('malformed album metadata never overwrites a saved space', () async {
    final vault = await tokenVault(
      _platform,
      tokenCredential(_platform, fields: {'albumDriveId': 'saved-album'}),
    );
    final http = FakeHttp(
      (request) => request.uri.path == _info
          ? jsonResponse({'code': 200, 'data': {}})
          : aliDefaultResponse(request),
    );
    final spaces = await _connector(
      http,
      vault,
    ).personalSpaces(vault.credential(_platform)!);
    expect(spaces.last.id, 'saved-album');
  });

  test(
    'an explicitly missing album removes its cached entry and duplicate IDs stay unique',
    () async {
      final vault = await tokenVault(
        _platform,
        tokenCredential(_platform, fields: {'albumDriveId': 'previous-album'}),
      );
      var id = '', now = tokenClock;
      final http = FakeHttp(
        (request) => request.uri.path == _info
            ? _album(id)
            : aliDefaultResponse(request),
      );
      final connector = _connector(http, vault, clock: () => now);
      final credential = vault.credential(_platform)!;
      expect((await connector.personalSpaces(credential)).map((e) => e.id), [
        'resource',
        'backup',
      ]);
      id = 'resource';
      now += const Duration(minutes: 6).inMilliseconds;
      expect((await connector.personalSpaces(credential)).map((e) => e.id), [
        'resource',
        'backup',
      ]);
    },
  );

  test(
    'album success codes do not relax validation of ordinary file endpoints',
    () async {
      final vault = await tokenVault(_platform);
      final http = FakeHttp((_) => jsonResponse({'code': 200, 'items': []}));
      await expectLater(
        _connector(
          http,
          vault,
        ).list(tokenPersonal(_platform), 'root', vault.credential(_platform)),
        throwsA(isA<AliyunApiException>()),
      );
    },
  );
}
