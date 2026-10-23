import 'package:flutter_test/flutter_test.dart';
import 'package:asterlink/core/json.dart';
import 'package:asterlink/data/cleanup_outbox.dart';
import 'package:asterlink/data/cloud_repository.dart';
import 'package:asterlink/data/http.dart';
import 'package:asterlink/data/providers/baidu.dart';
import 'package:asterlink/data/state_store.dart';
import 'package:asterlink/domain/auth.dart';
import 'package:asterlink/domain/baidu_share_route.dart';
import 'package:asterlink/domain/downloads.dart';
import 'package:asterlink/domain/models.dart';
import 'package:asterlink/domain/settings.dart';
import 'baidu_public_parser_support.dart';
import 'support.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late BaiduPublicFixture fixture;
  late FakeHttp http;
  late BaiduConnector connector;
  final credential = Credential('fixture', {
    'primary': 'BDUSS=private-account-cookie; STOKEN=private-account-token',
  }, updatedAt: 1);

  setUp(() {
    fixture = BaiduPublicFixture();
    http = FakeHttp(fixture.respond);
    connector = BaiduConnector(
      http,
      store: Vault(StateStore.memory({'secrets': fixture.passwordSecrets})),
    );
  });

  Future<BrowseSession> open([Credential? account]) => connector.openShare(
    fixture.link,
    account,
    route: BaiduShareRoute.publicParser,
  );

  test(
    'public parsing fills its password without disclosing account credentials',
    () async {
      final session = await open(credential);
      final files = await connector.list(session, '/', credential);
      final spec = await connector.download(session, files.first, credential);
      expect(http.calls, hasLength(2));
      for (final call in http.calls) {
        expect(call.uri.host, 'mf.dg22.top');
        expect(call.headers['User-Agent'], 'Dart/3.13 (dart:io)');
        expect(call.json['parse_password'], BaiduPublicFixture.password);
        expect(call.json['pwd'], 'a1b2');
        expect(call.json['surl'], '1PublicFixture');
        expect(
          call.headers.keys.map((key) => key.toLowerCase()),
          isNot(anyElement(isIn(['cookie', 'authorization']))),
        );
        expect(call.body.toString(), isNot(contains('private-account')));
        expect(call.headers.toString(), isNot(contains('private-account')));
      }
      expect(http.calls.last.json['token'], 'guest');
      expect(http.calls.last.json['fs_id'], [9007199254740993]);
      expect(http.calls.last.json['randsk'], 'a%2Bb%2Fc%3D');
      expect(session.metadata.containsKey('cookie'), isFalse);
      expect(spec.fileName, BaiduPublicFixture.name);
      expect(spec.expectedSize, 4);
      expect(spec.headers, {
        'User-Agent': BaiduPublicFixture.ua,
        'Accept-Encoding': 'identity',
      });
      expect(spec.cleanup, isNull);
      expect(
        const AppSettings(
          threads: 64,
        ).connectionsFor(CloudPlatform.baidu, spec.profile),
        64,
      );
    },
  );

  test(
    'is_dir folders retain their path and nested downloads use that parent',
    () async {
      final session = await open();
      final root = await connector.list(session, '/', null);
      expect(root.last.isDirectory, isTrue);
      expect(root.last.token, BaiduPublicFixture.folder);
      final children = await connector.list(session, root.last.token, null);
      expect(http.calls.last.json['dir'], BaiduPublicFixture.folder);
      expect(children.single.parentId, BaiduPublicFixture.folder);
      expect(children.single.modifiedAt, '1791720000');
      expect(children.single.hashType, 'md5');
      await connector.download(session, children.single, null);
      expect(http.calls.last.json['dir'], BaiduPublicFixture.folder);
    },
  );

  test(
    'the opening list is consumed once and explicit refresh fetches again',
    () async {
      final session = await open();
      await connector.list(session, '/', null);
      expect(http.calls, hasLength(1));
      await connector.list(session, '/', null);
      expect(http.calls, hasLength(2));
    },
  );

  for (final key in ['ua', 'user_agent', 'missing']) {
    test('download honors the $key user-agent response variant', () async {
      fixture.uaKey = key;
      if (key == 'missing') fixture.userAgent = null;
      final session = await open();
      final file = (await connector.list(session, '/', null)).first;
      final spec = await connector.download(session, file, null);
      expect(
        spec.headers['User-Agent'],
        key == 'missing'
            ? 'Mozilla/5.0 (Linux; Android 15) Chrome/134.0 Mobile'
            : BaiduPublicFixture.ua,
      );
    });
  }

  test(
    'a response for a different file cannot be downloaded under the selected name',
    () async {
      fixture.returnedId = '12345';
      final session = await open();
      final file = (await connector.list(session, '/', null)).first;
      await expectLater(
        connector.download(session, file, null),
        throwsA(
          isA<AppException>().having(
            (e) => e.message,
            'message',
            contains('文件不一致'),
          ),
        ),
      );
    },
  );

  for (final url in [
    'file:///C:/Windows/win.ini',
    'http://127.0.0.1/file',
    'https://baidupcs.com.attacker.invalid/file',
    'https://user:password@download.baidupcs.com/file',
  ]) {
    test('untrusted or local parser download URL is rejected: $url', () async {
      fixture.urls = [url];
      final session = await open();
      final file = (await connector.list(session, '/', null)).first;
      await expectLater(
        connector.download(session, file, null),
        throwsA(isA<AppException>()),
      );
    });
  }

  test(
    'download rejects line breaks in the site-provided user agent',
    () async {
      fixture.userAgent = 'fixture\r\nCookie: injected';
      final session = await open();
      final file = (await connector.list(session, '/', null)).first;
      await expectLater(
        connector.download(session, file, null),
        throwsA(isA<AppException>()),
      );
    },
  );

  test(
    'site errors stay distinct from expiry of the personal Baidu account',
    () async {
      fixture.publicFailure = true;
      await expectLater(
        open(credential),
        throwsA(
          allOf(
            isA<AppException>().having(
              (e) => e.message,
              'message',
              contains('账号下载'),
            ),
            isNot(isA<AccountLoginRequired>()),
          ),
        ),
      );
    },
  );

  test(
    'HTML or malformed file-list responses produce a clear parser error',
    () async {
      connector = BaiduConnector(
        FakeHttp((_) => const HttpResult(200, '<html>unavailable</html>')),
        store: Vault(StateStore.memory({'secrets': fixture.passwordSecrets})),
      );
      await expectLater(
        open(),
        throwsA(
          isA<AppException>().having(
            (e) => e.message,
            'message',
            contains('公益解析响应格式异常'),
          ),
        ),
      );
    },
  );

  test(
    'public sessions save into an account through a freshly verified official share',
    () async {
      final session = await open(credential);
      final file = (await connector.list(session, '/', credential)).first;
      final before = http.calls.length;
      await connector.saveShare(session, [file], '/destination', credential);
      final calls = http.calls.skip(before).toList();
      expect(calls.every((call) => call.uri.host == 'pan.baidu.com'), isTrue);
      expect(calls.first.uri.path, '/share/verify');
      expect(calls.last.uri.path, '/api/filemanager');
    },
  );

  test(
    'default anonymous share downloads and expired links keep the public route',
    () async {
      final state = StateStore.memory(),
          vault = Vault(
            StateStore.memory({'secrets': fixture.passwordSecrets}),
          );
      final repository = CloudRepository(
        http,
        vault,
        CleanupOutbox(state, http),
        preparationRetries: () => 0,
      );
      final session = await repository.share(fixture.link);
      expect(
        BaiduShareRoute.fromSession(session),
        BaiduShareRoute.publicParser,
      );
      expect(CloudPlatform.baidu.shareRequiresAccount, isFalse);
      final file = (await repository.list(session, '/')).first;
      final planned = repository.planDownload(session, file);
      final prepared = await repository.refresh(planned);
      final refreshed = await repository.refresh(
        DownloadSpec.fromJson(prepared.toJson()),
      );
      expect(refreshed.url, fixture.urls.first);
      expect(refreshed.fileName, BaiduPublicFixture.name);
      expect(
        BaiduShareRoute.fromSession(
          DownloadOrigin.fromJson(refreshed.source!).session,
        ),
        BaiduShareRoute.publicParser,
      );
      expect(
        http.calls.every((call) => call.uri.host == 'mf.dg22.top'),
        isTrue,
      );
    },
  );

  test(
    'legacy saved share tasks reopen through the original account route',
    () async {
      final state = StateStore.memory(), vault = Vault(StateStore.memory());
      final repository = CloudRepository(
        http,
        vault,
        CleanupOutbox(state, http),
      );
      final legacy = BrowseSession(
        platform: CloudPlatform.baidu,
        mode: BrowseMode.share,
        title: 'legacy',
        rootId: '/',
        sourceLink: fixture.link,
      );
      final original = CloudFile(
        id: BaiduPublicFixture.fileId,
        name: BaiduPublicFixture.name,
        size: 4,
        parentId: '/',
        token: '/${BaiduPublicFixture.name}',
      );
      final restored = await repository.restoreOrigin(
        DownloadOrigin(legacy, original, null),
      );
      expect(
        BaiduShareRoute.fromSession(restored.session),
        BaiduShareRoute.account,
      );
      expect(
        http.calls.every((call) => call.uri.host == 'pan.baidu.com'),
        isTrue,
      );
    },
  );
}
