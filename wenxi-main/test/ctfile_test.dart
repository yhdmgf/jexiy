import 'package:flutter_test/flutter_test.dart';
import 'package:asterlink/core/json.dart';
import 'package:asterlink/data/http.dart';
import 'package:asterlink/data/providers/ctfile.dart';
import 'package:asterlink/data/state_store.dart';
import 'package:asterlink/domain/auth.dart';
import 'package:asterlink/domain/links.dart';
import 'package:asterlink/domain/models.dart';
import 'package:asterlink/domain/uploads.dart';
import 'support.dart';

void main() {
  final credential = Credential('城通', {'primary': 'fixture-session-token'});
  final personal = BrowseSession(
    platform: CloudPlatform.ctfile,
    mode: BrowseMode.personal,
    title: '城通',
    rootId: 'd0',
  );
  final link = LinkParser.parse(
    'https://url69.ctfile.com/f/66485969-17569901563698-90650b?p=1664',
  ).single;
  const file = CloudFile(id: 'f17569901563698', name: 'sample.bin', size: 400);
  HttpResult ok(Json data) => jsonResponse({'code': 200, ...data});
  HttpResult info({
    String id = '17569901563698',
    Object size = 400,
    int wait = 0,
  }) => ok({
    'file': {
      'file_id': id,
      'file_name': 'sample.bin',
      'file_size': size,
      'userid': 66485969,
      'file_chk': 'fresh-check',
      'wait_seconds': wait,
      'start_time': 100,
    },
  });
  const row = {'key': 'f17569901563698', 'name': 'sample.bin', 'size': 400};

  test('Recognizes file, folder, hash routes, aliases and p password', () {
    expect(link.kind, LinkKind.cloudShare);
    expect(link.platform, CloudPlatform.ctfile);
    expect(link.passcode, '1664');
    expect(link.shareId, 'f/66485969-17569901563698-90650b');
    for (final url in [
      'https://url69.ctfile.com/d/123-456-abcdef',
      'https://url.ctfile.com/#/s/123-456-abcdef',
      'url.400gb.com/f/123-456-abcdef',
    ]) {
      expect(
        LinkParser.parse('$url 密码:1234').single.platform,
        CloudPlatform.ctfile,
      );
      expect(LinkParser.parse(url).single.kind, LinkKind.cloudShare);
    }
    expect(CloudPlatform.fromHost('ctfile.com.evil.test'), isNull);
    expect(CloudPlatform.ctfile.shareRequiresAccount, isFalse);
  });

  test('Password login validates identity and uses public quota', () async {
    final http = FakeHttp((r) {
      if (r.uri.path.endsWith('/login')) {
        expect(r.json['email'], 'test@example.com');
        return ok({'token': 'fixture-session-token'});
      }
      expect(r.json['session'], credential.primary);
      if (r.uri.path.endsWith('/profile')) {
        return ok({'userid': 12, 'nick_name': '本次账号'});
      }
      return ok({
        'space_used': 123,
        'max_storage': 456,
        'private_space_used': 999,
      });
    });
    final result = await CtfileConnector(
      http,
    ).password('test@example.com', 'test-password');
    expect(result.account.nickname, '本次账号');
    expect(result.account.used, 123);
    expect(result.credential.field('userId'), '12');
    expect(result.credential.field('password'), isEmpty);
  });

  test('Manual token validation does not accept website cookies', () {
    expect(
      LoginCredentials.plausible(CloudPlatform.ctfile, credential.primary),
      isTrue,
    );
    expect(
      LoginCredentials.plausible(
        CloudPlatform.ctfile,
        'session=website-cookie',
      ),
      isFalse,
    );
  });

  test(
    'Expired token is actionable and failed login cannot replace old account',
    () async {
      final store = StateStore.memory();
      final vault = Vault(store);
      await vault.putCredential(CloudPlatform.ctfile, credential);
      final connector = CtfileConnector(
        FakeHttp((_) => jsonResponse({'code': 401, 'message': '会话过期'})),
      );
      final login = AccountLoginService(
        vault,
        (p, c) => connector.account(c),
        webAuthenticators: {CloudPlatform.ctfile: connector.authenticate},
      );
      await expectLater(
        login.submitWeb(CloudPlatform.ctfile, 'new-session-token'),
        throwsA(isA<AccountLoginRequired>()),
      );
      expect(
        vault.credential(CloudPlatform.ctfile)!.primary,
        credential.primary,
      );
    },
  );

  test(
    'Personal list reads every page and preserves directory prefix',
    () async {
      final http = FakeHttp((r) {
        expect(r.json['folder_id'], 'd12');
        final start = r.json['start'];
        return ok({
          'results': start == 0
              ? [row]
              : start == 1
              ? [
                  {'key': 'd7', 'name': 'folder'},
                ]
              : [],
        });
      });
      final files = await CtfileConnector(
        http,
      ).list(personal, 'd12', credential);
      expect(files.map((f) => f.id), ['f17569901563698', 'd7']);
      expect(files.last.isDirectory, isTrue);
      expect(files.first.parentId, 'd12');
      expect(http.calls, hasLength(3));
    },
  );

  test(
    'Repeated and malformed pages fail instead of returning incomplete list',
    () async {
      for (final response in [
        ok({
          'results': [row],
        }),
        ok({}),
      ]) {
        final http = FakeHttp((_) => response);
        await expectLater(
          CtfileConnector(http).list(personal, 'd0', credential),
          throwsA(isA<AppException>()),
        );
        expect(http.calls.length, lessThanOrEqualTo(2));
      }
    },
  );

  test(
    'Guest single-file download refreshes checksum without account secrets',
    () async {
      final http = FakeHttp((r) {
        expect(r.headers.containsKey('Cookie'), isFalse);
        expect(r.json.containsKey('session'), isFalse);
        if (r.uri.path == '/getfile.php') {
          expect(r.uri.queryParameters['passcode'], '1664');
          return info(size: '400 B');
        }
        expect(r.uri.path, '/get_down_url.php');
        expect(r.uri.queryParameters['file_chk'], 'fresh-check');
        return ok({
          'downurl': 'https://download.ctfile.com/original',
          'file_size': 400,
        });
      });
      final connector = CtfileConnector(http);
      final session = await connector.openShare(link, null);
      final files = await connector.list(session, session.rootId, null);
      final spec = await connector.download(session, files.single, credential);
      expect(spec.expectedSize, 400);
      expect(spec.cleanup, isNull);
      expect(spec.headers.containsKey('Cookie'), isFalse);
      expect(http.calls, hasLength(3));
    },
  );

  test('Share restrictions preserve real server error', () async {
    final http = FakeHttp(
      (_) => jsonResponse({
        'code': 503,
        'file': {'message': '分享者需要绑定手机号后才能下载'},
      }),
    );
    await expectLater(
      CtfileConnector(http).openShare(link, null),
      throwsA(
        isA<AppException>().having(
          (e) => e.message,
          'message',
          contains('绑定手机号'),
        ),
      ),
    );
    expect(http.calls, hasLength(1));
  });

  test('Wrong password has a specific prompt', () async {
    await expectLater(
      CtfileConnector(
        FakeHttp((_) => jsonResponse({'code': 423})),
      ).openShare(link, null),
      throwsA(
        isA<AppException>().having((e) => e.message, 'message', contains('密码')),
      ),
    );
  });

  test('Missing status code cannot report a successful mutation', () async {
    final connector = CtfileConnector(FakeHttp((_) => jsonResponse({})));
    await expectLater(
      connector.delete(personal, [file], credential),
      throwsA(isA<AppException>()),
    );
  });

  test('Replacement identity and unsafe download URLs are rejected', () async {
    for (final badIdentity in [true, false]) {
      var requests = 0;
      final http = FakeHttp((r) {
        requests++;
        if (r.uri.path == '/getfile.php') {
          return info(
            id: requests > 1 && badIdentity ? '999' : '17569901563698',
          );
        }
        return ok({'downurl': 'file:///private', 'file_size': 400});
      });
      final connector = CtfileConnector(http);
      final session = await connector.openShare(link, null);
      await expectLater(
        connector.download(session, file, null),
        throwsA(isA<AppException>()),
      );
    }
  });

  test('Cancelling the official wait prevents download link request', () async {
    final scope = RequestScope();
    var count = 0;
    final http = FakeHttp((_) {
      if (++count == 2) scope.cancel();
      return info(wait: 30);
    });
    final connector = CtfileConnector(http);
    final session = await connector.openShare(link, null);
    await expectLater(
      scope.run(() => connector.download(session, file, null)),
      throwsA(isA<AppException>()),
    );
    expect(count, 2);
  });

  test(
    'Personal download uses REST without forwarding session to CDN',
    () async {
      final http = FakeHttp((r) {
        expect(r.uri.path, '/v1/public/file/fetch_url');
        expect(r.json['file_id'], '17569901563698');
        expect(r.json['session'], credential.primary);
        return ok({'download_url': 'https://download.ctfile.com/file'});
      });
      final spec = await CtfileConnector(
        http,
      ).download(personal, file, credential);
      expect(spec.expectedSize, 400);
      expect(spec.headers.toString(), isNot(contains(credential.primary)));
      expect(spec.cleanup, isNull);
    },
  );

  test('CRUD retains IDs, names and unrelated metadata', () async {
    final http = FakeHttp(
      (r) => r.uri.path.endsWith('/create') ? ok({'folder_id': 'd45'}) : ok({}),
    );
    final connector = CtfileConnector(http);
    final folder = await connector.createFolder(
      personal,
      'd0',
      '测试',
      credential,
    );
    await connector.rename(personal, folder, '新名字', credential);
    await connector.rename(personal, file, 'new.bin', credential);
    await connector.move(personal, [file], folder.id, credential);
    await connector.delete(personal, [file, folder], credential);
    expect(http.calls[1].json, {
      'session': credential.primary,
      'folder_id': '45',
      'name': '新名字',
      'is_rename': true,
    });
    expect(http.calls[2].json.containsKey('description'), isFalse);
    expect(http.calls[3].json['folder_id'], '45');
    expect(http.calls[4].json['ids'], 'f17569901563698,d45');
  });

  test(
    'Share creation preserves default passcode from returned text',
    () async {
      final connector = CtfileConnector(
        FakeHttp(
          (_) => ok({
            'results': [
              {'key': file.id, 'weblink': '${link.url} (访问密码: 1664)'},
            ],
          }),
        ),
      );
      final result = await connector.createShare(
        personal,
        [file],
        const ShareOptions('sample'),
        credential,
      );
      expect(result.passcode, '1664');
      expect(result.url, link.url);
    },
  );

  test('Upload streams multipart and confirms ID and original size', () async {
    var uploaded = false;
    final http = FakeHttp((r) async {
      if (r.uri.path.endsWith('/list')) {
        return ok({
          'results': uploaded && r.json['start'] == 0 ? [row] : [],
        });
      }
      if (r.uri.path.endsWith('/upload')) {
        return ok({
          'upload_url': 'https://upload.ctfile.com/web/upload.do?maxsize=1000',
        });
      }
      expect(r.body, isA<HttpUpload>());
      final body = r.body as HttpUpload;
      final bytes = await body.open().expand((e) => e).toList();
      expect(bytes.length, 400);
      expect(body.fields, isEmpty);
      expect(r.headers.toString(), isNot(contains(credential.primary)));
      uploaded = true;
      return ok({'file_id': '17569901563698'});
    });
    final source = UploadFile(
      name: 'sample.bin',
      size: 400,
      read: (start, end) => Stream.value(List.filled(end - start, 42)),
    );
    final result = await CtfileConnector(
      http,
    ).upload(personal, 'd0', source, credential);
    expect(result.size, 400);
    expect(result.id, file.id);
  });

  test(
    'Folder share HTML rows paginate and child links are preserved',
    () async {
      final folderLink = LinkParser.parse(
        'https://url.ctfile.com/d/12-34-abcdef?p=1234',
      ).single;
      final http = FakeHttp(
        (r) => r.uri.path == '/getdir.php'
            ? ok({
                'file': {'folder_id': 34, 'url': '/list.php?share=abc'},
              })
            : jsonResponse({
                'iTotalDisplayRecords': 1,
                'aaData': [
                  [
                    '<input value="f17569901563698">',
                    '<a href="${link.url}">sample.bin</a>',
                    '400 B',
                  ],
                ],
              }),
      );
      final connector = CtfileConnector(http);
      final session = await connector.openShare(folderLink, null);
      final files = await connector.list(session, session.rootId, null);
      expect(files.single.token, link.url);
      expect(files.single.id, file.id);
      expect(http.calls.last.uri.queryParameters['share'], 'abc');
    },
  );

  test(
    'Folder share navigation handles onclick directories and temporary file links',
    () async {
      final folderLink = LinkParser.parse(
        'https://url.ctfile.com/d/12-34-abcdef?p=1234',
      ).single;
      final http = FakeHttp((r) {
        if (r.uri.path == '/getdir.php') {
          return ok({
            'file': {
              'folder_id': 34,
              'url': '/api.php?ids=d34&folder_id=0&k=signature',
            },
          });
        }
        expect(r.uri.queryParameters['ids'], 'd34');
        expect(r.uri.queryParameters['k'], 'signature');
        final parent = r.uri.queryParameters['folder_id'];
        expect(parent, isIn(['34', '56']));
        return jsonResponse({
          'iTotalDisplayRecords': 1,
          'aaData': [
            parent == '34'
                ? [
                    '<input value="d56">',
                    '<a href="javascript:void(0)" onclick="load_subdir(56, \'abc123\')">子目录</a>',
                  ]
                : [
                    '<input value="f78">',
                    '<a href="#/f/tempdir-ABc123_456">sample.bin</a>',
                  ],
          ],
        });
      });
      final connector = CtfileConnector(http);
      final session = await connector.openShare(folderLink, null);
      final folder = (await connector.list(
        session,
        session.rootId,
        null,
      )).single;
      expect(folder.id, 'd56');
      expect(folder.isDirectory, isTrue);
      final file = (await connector.list(session, folder.id, null)).single;
      expect(file.id, 'f78');
      expect(file.isDirectory, isFalse);
      expect(
        LinkParser.shareId(CloudPlatform.ctfile, file.token),
        'f/tempdir-ABc123_456',
      );
      expect(
        http.calls.where((r) => r.uri.path == '/getdir.php'),
        hasLength(1),
      );
    },
  );
}
