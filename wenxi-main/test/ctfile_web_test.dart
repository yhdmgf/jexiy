import 'package:crypto/crypto.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:asterlink/core/json.dart';
import 'package:asterlink/data/http.dart';
import 'package:asterlink/data/providers/ctfile.dart';
import 'package:asterlink/data/providers/ctfile_web.dart';
import 'package:asterlink/domain/models.dart';
import 'package:asterlink/domain/uploads.dart';
import 'support.dart';

void main() {
  final credential = Credential('城通', {
    'primary': 'ctfile_session=web-refresh; ct_uid=12',
    'userId': '12',
  });
  const session = BrowseSession(
    platform: CloudPlatform.ctfile,
    mode: BrowseMode.personal,
    title: '公开空间',
    rootId: 'd0',
    metadata: {'driveId': '45'},
  );
  const file = CloudFile(
    id: 'f7',
    name: 'sample.bin',
    size: 400,
    parentId: 'd0',
  );
  HttpResult ok(Json data) => jsonResponse({'code': 200, 'data': data});
  HttpResult exchange([String token = 'access']) =>
      ok({'token': token, 'userid': 12, 'expires_in': 900});
  HttpResult workspaces() => ok({
    'workspaces': [
      {
        'id': 45,
        'name': '公开空间',
        'is_private': 0,
        'owner_id': 12,
        'space_used': 400,
        'space_quota': 5000,
      },
      {
        'id': 46,
        'name': '私有空间',
        'is_private': 1,
        'owner_id': 12,
        'space_used': 100,
        'space_quota': 2000,
      },
      {
        'id': 47,
        'name': '协作空间',
        'is_private': 0,
        'owner_id': 99,
        'space_used': 9000,
        'space_quota': 10000,
      },
    ],
    'has_more': false,
  });

  test(
    'Web login exchanges cookies and reads v4 identity, owned quota and spaces',
    () async {
      final http = FakeHttp((r) {
        if (r.uri.path.endsWith('/login/login')) {
          return HttpResult(200, '{"code":200,"message":"Login successful"}', {
            'set-cookie': [
              'ctfile_session=web-refresh; Domain=.ctfile.com; Path=/; Secure',
              'ct_uid=12; Path=/',
            ],
          });
        }
        if (r.uri.path.endsWith('/exchange-token')) {
          expect(r.headers['Cookie'], contains('ctfile_session=web-refresh'));
          expect(r.json['rotate_token'], isFalse);
          return exchange();
        }
        expect(r.uri.host, 'api.ctfile.com');
        expect(r.headers['Authorization'], 'Bearer access');
        expect(r.headers.containsKey('Cookie'), isFalse);
        if (r.uri.path.endsWith('/profile/info')) {
          return ok({'userid': 12, 'username': '本次账号'});
        }
        return workspaces();
      });
      final connector = CtfileConnector(http);
      final login = await connector.password(
        'test@example.com',
        'fixture-password',
      );
      expect(login.account.nickname, '本次账号');
      expect(login.account.used, 500);
      expect(login.account.total, 7000);
      expect(login.credential.field('userId'), '12');
      final personal = await connector.openPersonal(login.credential);
      expect(personal.personalSpaceId, '45');
      final private = await connector.openPersonalSpace(
        const CloudSpace('46', ''),
        login.credential,
      );
      expect(private.personalSpaceId, '46');
      await expectLater(
        connector.openPersonalSpace(
          const CloudSpace('999', ''),
          login.credential,
        ),
        throwsA(isA<AppException>()),
      );
      expect(
        http.calls.where((r) => r.uri.path.endsWith('/exchange-token')),
        hasLength(1),
      );
    },
  );

  test(
    'Web listing retains scope and reads pages until the declared total',
    () async {
      final http = FakeHttp((r) {
        if (r.uri.path.endsWith('/exchange-token')) return exchange();
        expect(r.json['workspace_id'], '45');
        expect(r.json['folder_id'], 'd0');
        return ok({
          'totalNum': 2,
          'results': r.json['start'] == 0
              ? [
                  {
                    'fid': 'f7',
                    'name': 'file.bin',
                    'size': 400,
                    'date': 1791100243,
                  },
                ]
              : [
                  {'fid': 'd8', 'name': 'folder', 'ext': 'folder'},
                ],
        });
      });
      final files = await CtfileConnector(http).list(session, 'd0', credential);
      expect(files.map((f) => f.id), ['f7', 'd8']);
      expect(files.last.isDirectory, isTrue);
      expect(http.calls, hasLength(3));
    },
  );

  test(
    'Explicit token expiry refreshes once without mixing different accounts',
    () async {
      var exchanges = 0, requests = 0;
      final http = FakeHttp((r) {
        if (r.uri.path.endsWith('/exchange-token')) {
          return exchange('access-${++exchanges}');
        }
        if (++requests == 1) return jsonResponse({'code': 401}, 401);
        expect(r.headers['Authorization'], 'Bearer access-2');
        return ok({'totalNum': 0, 'results': []});
      });
      expect(
        await CtfileConnector(http).list(session, 'd0', credential),
        isEmpty,
      );
      expect(exchanges, 2);
      expect(requests, 2);
      final wrong = CtfileConnector(
        FakeHttp((_) => ok({'token': 'other', 'userid': 99})),
      );
      await expectLater(
        wrong.list(session, 'd0', credential),
        throwsA(isA<AppException>()),
      );
    },
  );

  test('Incomplete lists and authentication failures remain errors', () async {
    for (final body in [
      ok({'totalNum': 2, 'results': []}),
      ok({'totalNum': 2}),
      jsonResponse({'code': 401}),
    ]) {
      final http = FakeHttp(
        (r) => r.uri.path.endsWith('/exchange-token') ? exchange() : body,
      );
      await expectLater(
        CtfileConnector(http).list(session, 'd0', credential),
        throwsA(isA<AppException>()),
      );
      expect(http.calls.length, lessThanOrEqualTo(4));
    }
  });

  test(
    'Moves preserve destination workspace and wait for the background job',
    () async {
      final http = FakeHttp((r) {
        if (r.uri.path.endsWith('/exchange-token')) return exchange();
        if (r.uri.path.endsWith('/manage/move')) {
          expect(r.json, {
            'ids': 'f7',
            'target_folder_id': 'd9',
            'workspace_id': '45',
            'target_workspace_id': '46',
          });
          return ok({
            'job_id': 'job-1',
            'background': true,
            'operation_status': 'queued',
          });
        }
        expect(r.uri.path, '/v4/file/bulk/status');
        expect(r.json['job_id'], 'job-1');
        return ok({'operation_status': 'completed', 'errors': []});
      });
      await CtfileConnector(
        http,
      ).move(session, [file], 'ctfile:46:d9', credential);
      expect(
        http.calls.where((r) => r.uri.path.endsWith('/manage/move')),
        hasLength(1),
      );
      expect(http.calls.last.uri.path, endsWith('/bulk/status'));
    },
  );

  test(
    'Failed or partial background operations never report success',
    () async {
      for (final status in [
        {'operation_status': 'failed'},
        {
          'operation_status': 'completed',
          'errors': ['one file failed'],
        },
      ]) {
        final web = CtfileWeb(
          FakeHttp(
            (r) => r.uri.path.endsWith('/exchange-token')
                ? exchange()
                : ok(status),
          ),
          pollDelay: Duration.zero,
        );
        await expectLater(
          web.complete(credential, {'job_id': 'job'}),
          throwsA(isA<AppException>()),
        );
      }
    },
  );

  test(
    'Personal downloads never forward the login token or cookie to the file host',
    () async {
      final http = FakeHttp((r) {
        if (r.uri.path.endsWith('/exchange-token')) return exchange();
        expect(r.json, {'file_id': 'f7', 'workspace_id': '45'});
        return ok({'download_url': 'https://download.ctfile.com/test'});
      });
      final spec = await CtfileConnector(
        http,
      ).download(session, file, credential);
      expect(spec.expectedSize, 400);
      expect(spec.headers.containsKey('Cookie'), isFalse);
      expect(spec.headers.containsKey('Authorization'), isFalse);
    },
  );

  test(
    'v4 upload hashes the first million bytes and streams the complete original',
    () async {
      final payload = List.generate(1000123, (i) => i % 251);
      var uploaded = false;
      final http = FakeHttp((r) async {
        if (r.uri.path.endsWith('/exchange-token')) return exchange();
        if (r.uri.path.endsWith('/browse/list')) {
          return ok({
            'totalNum': uploaded ? 1 : 0,
            'results': uploaded
                ? [
                    {'fid': 'f7', 'name': 'sample.bin', 'size': payload.length},
                  ]
                : [],
          });
        }
        if (r.uri.path.endsWith('/manage/upload')) {
          expect(
            r.json['checksum'],
            md5.convert(payload.sublist(0, 1000000)).toString(),
          );
          expect(r.json['size'], payload.length);
          return ok({
            'exists': 0,
            'file_name': 'sample.bin',
            'uploadUrl': 'https://upload.ctfile.com/upload?ticket=keep',
          });
        }
        expect(r.method, 'PUT');
        expect(r.headers.containsKey('Authorization'), isFalse);
        expect(r.headers.containsKey('Cookie'), isFalse);
        expect(r.uri.queryParameters['ticket'], 'keep');
        expect(r.uri.queryParameters['contentlength'], '${payload.length}');
        final body = r.body as HttpUpload;
        expect(body.fields, isNull);
        expect(await body.open().expand((c) => c).toList(), payload);
        uploaded = true;
        return jsonResponse({
          'code': 200,
          'message': 'upload complete',
          'id': 7,
          'name': 'sample.bin',
          'size': payload.length,
        });
      });
      final result = await CtfileConnector(http).upload(
        session,
        'd0',
        UploadFile(
          name: 'sample.bin',
          size: payload.length,
          read: (a, b) => Stream.value(payload.sublist(a, b)),
        ),
        credential,
      );
      expect(result.id, 'f7');
      expect(result.size, payload.length);
    },
  );
}
