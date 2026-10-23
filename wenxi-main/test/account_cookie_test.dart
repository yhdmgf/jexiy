import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:asterlink/domain/auth.dart';
import 'package:asterlink/domain/models.dart';
import 'package:asterlink/data/state_store.dart';
import 'package:asterlink/main.dart';
import 'package:asterlink/ui/account_cookie.dart';
import 'package:asterlink/ui/account_info_page.dart';
import 'package:asterlink/ui/cloud_accounts_page.dart';
import 'package:asterlink/ui/login_page.dart';
import 'browser_test_support.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  final cookies = {
    CloudPlatform.baidu: 'BDUSS=fixture; STOKEN=fixture%3D%3D',
    CloudPlatform.quark: '__pus=fixture; __puus=fixture==',
    CloudPlatform.uc: '__pus=uc-fixture; __puus=uc-fixture==',
    CloudPlatform.tianyi: 'COOKIE_LOGIN_USER=fixture; JSESSIONID=session',
    CloudPlatform.c139: 'auth_token=fixture; ud_id=001',
    CloudPlatform.lanzou: 'ylogin=fixture; phpdisk_info=fixture%2Bvalue',
    CloudPlatform.weiyun: 'p_uin=o1234; p_skey=fixture; wyctoken=fixture',
    CloudPlatform.pan115: 'UID=fixture; CID=fixture; SEID=fixture',
    CloudPlatform.ctfile: 'ctfile_session=fixture; remember=1',
  };
  for (final entry in cookies.entries) {
    test('copies only the saved cookie for ${entry.key.key}', () {
      final credential = Credential('私有账号名称', {
        'primary': 'Cookie: ${entry.value}',
        'secondary': 'password-not-a-cookie',
        'password': 'private-password',
        'accessToken': 'private-token',
        'clientSecret': 'private-client-secret',
      });
      expect(LoginCredentials.savedCookie(entry.key, credential), entry.value);
    });
  }
  for (final platform in CloudPlatform.values.where(
    (p) => !cookies.containsKey(p),
  )) {
    test(
      'token or password primary is not copied as cookie for ${platform.key}',
      () {
        for (final primary in [
          'encoded-token==',
          'name=password',
          '{"access_token":"fixture"}',
        ]) {
          expect(
            LoginCredentials.savedCookie(
              platform,
              Credential('账号', {
                'primary': primary,
                'secondary': 'password',
                'accessToken': 'fixture',
              }),
            ),
            isNull,
          );
        }
      },
    );
  }
  test('legacy browser captures export cookies without captured metadata', () {
    for (final platform in [CloudPlatform.weiyun, CloudPlatform.tianyi]) {
      expect(
        LoginCredentials.savedCookie(
          platform,
          Credential('账号', {
            'primary': jsonEncode({
              'cookie': cookies[platform],
              'browserId': 'private-browser',
              'tokenInfo': {'access_token': 'private-token'},
            }),
          }),
        ),
        cookies[platform],
      );
    }
  });
  test('copy keeps empty values repeated names and encoding intact', () {
    const value =
        'BDUSS=fixture; empty=; duplicate=a; duplicate=b; padded=abc==; encoded=%2B%3D';
    expect(
      LoginCredentials.savedCookie(
        CloudPlatform.baidu,
        Credential('账号', {'primary': value}),
      ),
      value,
    );
  });
  test('missing or malformed cookies and ctfile API tokens are not copied', () {
    expect(LoginCredentials.savedCookie(CloudPlatform.baidu, null), isNull);
    for (final value in [
      '',
      'Bearer abc==',
      '{"password":"abc=def"}',
      'bad name=value',
      'BDUSS=fixture\r\nAuthorization: private',
    ]) {
      expect(
        LoginCredentials.savedCookie(
          CloudPlatform.baidu,
          Credential('账号', {'primary': value}),
        ),
        isNull,
      );
    }
    expect(
      LoginCredentials.savedCookie(
        CloudPlatform.ctfile,
        Credential('账号', {'primary': 'ctfile-api-session-token'}),
      ),
      isNull,
    );
  });
  test('an explicitly saved cookie is usable alongside token login', () {
    expect(
      LoginCredentials.savedCookie(
        CloudPlatform.aliyun,
        Credential('账号', {
          'primary': 'refresh-token',
          'cookie': 'session=real-cookie',
        }),
      ),
      'session=real-cookie',
    );
  });

  late String? copied;
  late bool failClipboard;
  setUp(() {
    copied = null;
    failClipboard = false;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(SystemChannels.platform, (call) async {
          if (call.method == 'Clipboard.setData') {
            if (failClipboard) {
              throw PlatformException(
                code: 'clipboard',
                message: 'BDUSS=private-error',
              );
            }
            copied = (call.arguments as Map)['text'] as String;
          }
          return null;
        });
  });
  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(SystemChannels.platform, null);
  });

  Future<String> addAccount(
    BrowserUiFixture fixture,
    String name,
    String cookie,
  ) async {
    final vault = fixture.services.vault;
    final id = await vault.createAccount(CloudPlatform.tianyi);
    await vault.withAccount(
      CloudPlatform.tianyi,
      id,
      () => vault.putCredential(
        CloudPlatform.tianyi,
        Credential(name, {'primary': cookie}),
      ),
    );
    return id;
  }

  Future<void> tap(WidgetTester tester, Finder finder) async {
    await tester.ensureVisible(finder);
    await tester.tap(finder);
    await tester.pumpAndSettle();
  }

  test(
    'Legacy 123 tokens are read from the selected account secret store',
    () async {
      final state = StateStore.memory();
      try {
        final vault = Vault(state);
        await vault.putCredential(
          CloudPlatform.pan123,
          Credential('主账号', {'primary': 'main-user', 'secondary': 'password'}),
        );
        final first = vault.activeAccountId(CloudPlatform.pan123);
        await vault.putSecret('pan123.access_token', 'main-token');
        final second = await vault.createAccount(CloudPlatform.pan123);
        await vault.withAccount(CloudPlatform.pan123, second, () async {
          await vault.putCredential(
            CloudPlatform.pan123,
            Credential('子账号', {
              'primary': 'other-user',
              'secondary': 'password',
            }),
          );
          await vault.putSecret('pan123.access_token', 'other-token');
        });
        expect(
          savedAccountLoginInfo(
            vault,
            CloudPlatform.pan123,
            first,
          ).preferred!.value,
          'main-token',
        );
        expect(
          savedAccountLoginInfo(
            vault,
            CloudPlatform.pan123,
            second,
          ).preferred!.value,
          'other-token',
        );
        expect(vault.activeAccountId(CloudPlatform.pan123), first);
        await vault.removeAccount(CloudPlatform.pan123, second);
        expect(
          savedAccountLoginInfo(vault, CloudPlatform.pan123, second).preferred,
          isNull,
        );
      } finally {
        state.dispose();
      }
    },
  );

  for (final size in [const Size(393, 864), const Size(1100, 780)]) {
    testWidgets('account manager copies the chosen inactive account at $size', (
      tester,
    ) async {
      final fixture = await BrowserUiFixture.create();
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      try {
        final vault = fixture.services.vault;
        final active = vault.activeAccountId(CloudPlatform.tianyi);
        final second = await addAccount(
          fixture,
          '家庭账号',
          'COOKIE_LOGIN_USER=second',
        );
        tester.view.physicalSize = size;
        tester.view.devicePixelRatio = 1;
        await tester.pumpWidget(
          MaterialApp(
            theme: appTheme(Brightness.light),
            home: CloudAccountsPage(
              fixture.services,
              platform: CloudPlatform.tianyi,
            ),
          ),
        );
        await tester.pumpAndSettle();
        // A quick copy must use a freshly renewed cookie of this account.
        await vault.withAccount(
          CloudPlatform.tianyi,
          second,
          () => vault.putCredential(
            CloudPlatform.tianyi,
            Credential('家庭账号', {'primary': 'COOKIE_LOGIN_USER=renewed'}),
          ),
        );
        await tester.pumpAndSettle();
        await tap(tester, find.byKey(ValueKey('account-copy-$second')));
        expect(copied, 'COOKIE_LOGIN_USER=renewed');
        expect(vault.activeAccountId(CloudPlatform.tianyi), active);
        expect(find.text('Cookie 已复制'), findsOneWidget);
        expect(find.textContaining('COOKIE_LOGIN_USER='), findsNothing);
        expect(tester.takeException(), isNull);
      } finally {
        await tester.pumpWidget(const SizedBox.shrink());
        await fixture.close();
      }
    });
  }

  testWidgets('cloud menu copy stays bound to the account shown when opened', (
    tester,
  ) async {
    final fixture = await BrowserUiFixture.create();
    try {
      final vault = fixture.services.vault;
      await vault.putCredential(
        CloudPlatform.tianyi,
        Credential('工作账号', {'primary': 'COOKIE_LOGIN_USER=work'}),
      );
      final second = await addAccount(
        fixture,
        '家庭账号',
        'COOKIE_LOGIN_USER=family',
      );
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: Builder(
              builder: (context) => TextButton(
                onPressed: () => accountMenu(
                  context,
                  fixture.services,
                  CloudPlatform.tianyi,
                ),
                child: const Text('账号菜单'),
              ),
            ),
          ),
        ),
      );
      await tap(tester, find.text('账号菜单'));
      await vault.activate(CloudPlatform.tianyi, second);
      await tester.pumpAndSettle();
      await tap(tester, find.text('复制 Cookie'));
      expect(copied, 'COOKIE_LOGIN_USER=work');
      expect(vault.activeAccountId(CloudPlatform.tianyi), second);
      expect(tester.takeException(), isNull);
    } finally {
      await tester.pumpWidget(const SizedBox.shrink());
      await fixture.close();
    }
  });

  testWidgets(
    'token-only accounts copy the login token and removed accounts never fall back',
    (tester) async {
      final fixture = await BrowserUiFixture.create();
      try {
        final vault = fixture.services.vault;
        await vault.putCredential(
          CloudPlatform.aliyun,
          Credential('阿里账号', {
            'primary': 'refresh-token',
            'accessToken': 'access-token',
            'loginUsername': 'private-name',
            'loginPassword': 'private-password',
          }),
        );
        final id = vault.activeAccountId(CloudPlatform.aliyun);
        await tester.pumpWidget(
          MaterialApp(
            home: Scaffold(
              body: Builder(
                builder: (context) => TextButton(
                  onPressed: () => copyCloudAccountLoginInfo(
                    context,
                    vault,
                    CloudPlatform.aliyun,
                    id,
                  ),
                  child: const Text('复制'),
                ),
              ),
            ),
          ),
        );
        await tap(tester, find.text('复制'));
        expect(copied, 'refresh-token');
        expect(find.textContaining('刷新令牌'), findsOneWidget);
        await vault.removeAccount(CloudPlatform.aliyun, id!);
        copied = null;
        await tap(tester, find.text('复制'));
        expect(copied, isNull);
        expect(tester.takeException(), isNull);
      } finally {
        await tester.pumpWidget(const SizedBox.shrink());
        await fixture.close();
      }
    },
  );

  testWidgets(
    'clipboard errors show a generic message without exposing the cookie',
    (tester) async {
      final fixture = await BrowserUiFixture.create();
      try {
        final vault = fixture.services.vault;
        await vault.putCredential(
          CloudPlatform.baidu,
          Credential('百度账号', {'primary': 'BDUSS=private-cookie'}),
        );
        final id = vault.activeAccountId(CloudPlatform.baidu);
        failClipboard = true;
        await tester.pumpWidget(
          MaterialApp(
            home: Scaffold(
              body: Builder(
                builder: (context) => TextButton(
                  onPressed: () => copyCloudAccountLoginInfo(
                    context,
                    vault,
                    CloudPlatform.baidu,
                    id,
                  ),
                  child: const Text('复制'),
                ),
              ),
            ),
          ),
        );
        await tap(tester, find.text('复制'));
        expect(copied, isNull);
        expect(find.text('复制失败，请重试'), findsOneWidget);
        expect(find.textContaining('BDUSS='), findsNothing);
        expect(tester.takeException(), isNull);
      } finally {
        await tester.pumpWidget(const SizedBox.shrink());
        await fixture.close();
      }
    },
  );

  testWidgets(
    'browser account configuration stays bound and can inspect another account without activation',
    (tester) async {
      final fixture = await BrowserUiFixture.create();
      try {
        final vault = fixture.services.vault;
        final first = vault.activeAccountId(CloudPlatform.tianyi)!;
        final second = await addAccount(
          fixture,
          '家庭账号',
          'COOKIE_LOGIN_USER=family',
        );
        await fixture.render(tester);
        expect(find.text('账号'), findsOneWidget);
        await vault.activate(CloudPlatform.tianyi, second);
        await tester.pumpAndSettle();
        await tap(tester, find.byKey(const Key('browser-account-menu')));
        await tap(tester, find.text('账号配置'));
        expect(find.byType(CloudAccountInfoPage), findsOneWidget);
        expect(
          tester
              .widget<DropdownButton<String>>(
                find.byKey(const Key('account-info-selector')),
              )
              .value,
          first,
        );
        expect(find.textContaining('COOKIE_LOGIN_USER='), findsNothing);
        await tap(tester, find.byKey(const Key('account-info-copy-primary')));
        expect(copied, 'COOKIE_LOGIN_USER=fixture');
        await tap(tester, find.byKey(const Key('account-info-selector')));
        await tap(
          tester,
          find.byKey(ValueKey('account-info-select-$second')).last,
        );
        await tap(tester, find.byKey(const Key('account-info-copy-primary')));
        expect(copied, 'COOKIE_LOGIN_USER=family');
        expect(vault.activeAccountId(CloudPlatform.tianyi), second);
        await tap(tester, find.byKey(const Key('account-info-selector')));
        await tap(
          tester,
          find.byKey(ValueKey('account-info-select-$first')).last,
        );
        expect(vault.activeAccountId(CloudPlatform.tianyi), second);
        expect(tester.takeException(), isNull);
      } finally {
        await tester.pumpWidget(const SizedBox.shrink());
        await fixture.close();
      }
    },
  );

  for (final (size, scale) in [
    (const Size(320, 740), 1.8),
    (const Size(1100, 800), 1.0),
  ]) {
    testWidgets('token configuration can reveal and copy each field at $size', (
      tester,
    ) async {
      final fixture = await BrowserUiFixture.create();
      addTearDown(tester.view.resetPhysicalSize);
      addTearDown(tester.view.resetDevicePixelRatio);
      addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
      try {
        final vault = fixture.services.vault;
        await vault.putCredential(
          CloudPlatform.aliyun,
          Credential('阿里账号', {
            'primary': 'original-refresh-token',
            'accessToken': 'original-access-token',
            'deviceId': 'do-not-export-device-id',
            'signature': 'do-not-export-signature',
            'loginPassword': 'do-not-export-password',
            'clientSecret': 'do-not-export-client-secret',
          }),
        );
        final id = vault.activeAccountId(CloudPlatform.aliyun)!;
        tester.view.devicePixelRatio = 1;
        tester.view.physicalSize = size;
        tester.platformDispatcher.textScaleFactorTestValue = scale;
        await tester.pumpWidget(
          MaterialApp(
            theme: appTheme(Brightness.dark),
            home: CloudAccountInfoPage(
              vault,
              CloudPlatform.aliyun,
              accountId: id,
            ),
          ),
        );
        await tester.pumpAndSettle();
        expect(find.textContaining('未保存网页 Cookie'), findsOneWidget);
        expect(find.text('original-access-token'), findsNothing);
        await tap(
          tester,
          find.byKey(const Key('account-info-reveal-accessToken')),
        );
        expect(find.text('original-access-token'), findsOneWidget);
        await vault.putCredential(
          CloudPlatform.aliyun,
          Credential('阿里账号', {
            'primary': 'renewed-refresh-token',
            'accessToken': 'renewed-access-token',
            'deviceId': 'do-not-export-device-id',
            'signature': 'do-not-export-signature',
            'loginPassword': 'do-not-export-password',
            'clientSecret': 'do-not-export-client-secret',
          }),
        );
        await tester.pumpAndSettle();
        await tap(
          tester,
          find.byKey(const Key('account-info-copy-accessToken')),
        );
        expect(copied, 'renewed-access-token');
        await tester.drag(
          find.byKey(const Key('account-info-page')),
          const Offset(0, 700),
        );
        await tester.pumpAndSettle();
        await tap(tester, find.byKey(const Key('account-info-copy-all')));
        expect(jsonDecode(copied!), {
          'accessToken': 'renewed-access-token',
          'refreshToken': 'renewed-refresh-token',
        });
        expect(copied, isNot(contains('password')));
        expect(copied, isNot(contains('client-secret')));
        await vault.removeAccount(CloudPlatform.aliyun, id);
        await tester.pumpAndSettle();
        expect(
          find.byKey(const Key('account-info-field-accessToken')),
          findsNothing,
        );
        expect(find.text('renewed-access-token'), findsNothing);
        expect(tester.takeException(), isNull);
      } finally {
        await tester.pumpWidget(const SizedBox.shrink());
        await fixture.close();
      }
    });
  }
}
