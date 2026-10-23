import 'dart:convert';
import 'package:flutter_test/flutter_test.dart';
import 'package:asterlink/domain/account_login_info.dart';
import 'package:asterlink/domain/models.dart';

void main() {
  test(
    'Cookie export preserves bytes and excludes login passwords and app secrets',
    () {
      const cookie =
          'BDUSS=value%2B%3D; empty=; repeat=a; repeat=b; padded=abc==';
      final info = AccountLoginInfo.from(
        CloudPlatform.baidu,
        Credential('账号', {
          'primary': cookie,
          'password': 'private',
          'clientSecret': 'private',
          'loginPassword': 'private',
          'secondary': 'private',
        }),
      );
      expect(info.copyLabel, '复制 Cookie');
      expect(info.preferred!.value, cookie);
      expect(jsonDecode(info.json), {'cookie': cookie});
    },
  );

  for (final platform in [CloudPlatform.aliyun, CloudPlatform.wopan]) {
    test(
      '${platform.key} exports the renewable token while exposing both token fields',
      () {
        final info = AccountLoginInfo.from(
          platform,
          Credential('账号', {
            'primary': 'old-refresh-token',
            'refreshToken': 'latest-refresh-token',
            'accessToken': 'latest-access-token',
            'password': 'private',
          }),
        );
        expect(info.copyLabel, '复制 Token');
        expect(info.preferred!.key, 'refreshToken');
        expect(info.preferred!.value, 'latest-refresh-token');
        expect(info.field('accessToken')!.value, 'latest-access-token');
        expect(info.hasCookie, isFalse);
      },
    );
  }

  test(
    'Aliyun exports login results without local device or client settings',
    () {
      final credential = Credential('账号', {
        'primary': 'stored-refresh-token',
        'accessToken': 'saved-access-token',
        'userId': 'saved-user-id',
        'deviceId': 'local-device-id',
        'devicePrivateKey': 'local-signing-key',
        'deviceSessionId': 'local-session-id',
        'signature': 'local-signature',
        'clientId': 'default-client-id',
        'clientSecret': 'default-client-secret',
        'userAgent': 'default-user-agent',
        'authorization': 'Bearer saved-access-token',
        'uuid': 'local-uuid',
        'captchaToken': 'local-captcha-token',
      });
      final before = Map<String, String>.of(credential.fields);
      final info = AccountLoginInfo.from(CloudPlatform.aliyun, credential);

      expect(jsonDecode(info.json), {
        'accessToken': 'saved-access-token',
        'refreshToken': 'stored-refresh-token',
        'userId': 'saved-user-id',
      });
      expect(info.preferred!.value, 'stored-refresh-token');
      expect(credential.fields, before);
    },
  );

  for (final platform in [
    CloudPlatform.xunlei,
    CloudPlatform.guangya,
    CloudPlatform.pikpak,
  ]) {
    test(
      '${platform.key} token authentication is available without a Cookie',
      () {
        final info = AccountLoginInfo.from(
          platform,
          Credential('账号', {
            'primary': 'stored-access-token',
            'accessToken': 'latest-access-token',
            'refreshToken': 'latest-refresh-token',
            'username': 'private',
            'password': 'private',
          }),
        );
        expect(info.preferred!.value, 'latest-access-token');
        expect(info.field('refreshToken')!.value, 'latest-refresh-token');
        expect(info.fields.map((f) => f.key), ['accessToken', 'refreshToken']);
      },
    );
  }

  test(
    '123 password credentials export the acquired token and never the username',
    () {
      final info = AccountLoginInfo.from(
        CloudPlatform.pan123,
        Credential('账号', {
          'primary': 'account-name',
          'secondary': 'private-password',
          'accessToken': 'latest-access-token',
          'authType': 'password',
        }),
      );
      expect(info.preferred!.value, 'latest-access-token');
      expect(jsonDecode(info.json), {'accessToken': 'latest-access-token'});
      expect(
        AccountLoginInfo.from(
          CloudPlatform.pan123,
          Credential('账号', {
            'primary': 'account-name',
            'secondary': 'private-password',
          }),
        ).preferred,
        isNull,
      );
    },
  );

  for (final platform in [CloudPlatform.feijipan, CloudPlatform.ilanzou]) {
    test('${platform.key} exposes appToken and its saved UUID', () {
      final info = AccountLoginInfo.from(
        platform,
        Credential('账号', {
          'primary': 'stored-app-token',
          'accessToken': 'latest-app-token',
          'uuid': 'device-uuid',
        }),
      );
      expect(info.preferred!.value, 'latest-app-token');
      expect(jsonDecode(info.json), {
        'appToken': 'latest-app-token',
        'uuid': 'device-uuid',
      });
    });
  }

  test(
    'Legacy token JSON is read without exporting unrelated browser state',
    () {
      final info = AccountLoginInfo.from(
        CloudPlatform.aliyun,
        Credential('账号', {
          'primary': jsonEncode({
            'data': {
              'access_token': 'legacy-access-token',
              'refresh_token': 'legacy-refresh-token',
              'user_id': 'legacy-user-id',
              'device_id': 'local-device-id',
              'uuid': 'local-uuid',
              'Authorization': 'Bearer legacy-access-token',
              'password': 'private',
              'client_secret': 'private',
            },
          }),
        }),
      );
      expect(jsonDecode(info.json), {
        'accessToken': 'legacy-access-token',
        'refreshToken': 'legacy-refresh-token',
        'userId': 'legacy-user-id',
      });
    },
  );

  test('Explicitly saved Cookies remain available alongside tokens', () {
    final info = AccountLoginInfo.from(
      CloudPlatform.aliyun,
      Credential('账号', {
        'primary': 'stored-refresh-token',
        'cookie': 'session=web-cookie',
      }),
    );
    expect(info.preferred!.key, 'cookie');
    expect(info.preferred!.value, 'session=web-cookie');
    expect(info.field('refreshToken')!.value, 'stored-refresh-token');
    final quark = AccountLoginInfo.from(
      CloudPlatform.quark,
      Credential('账号', {'cookie': '__pus=first; __puus=second'}),
    );
    expect(quark.preferred!.value, '__pus=first; __puus=second');
  });

  test('GitHub and Ctfile keep their native token type', () {
    final github = AccountLoginInfo.from(
      CloudPlatform.github,
      Credential('账号', {'primary': 'github_pat_fixture00000000'}),
    );
    expect(github.preferred!.key, 'token');
    final ctfile = AccountLoginInfo.from(
      CloudPlatform.ctfile,
      Credential('账号', {'primary': 'ctfile-session-fixture0000'}),
    );
    expect(ctfile.preferred!.key, 'sessionToken');
    expect(ctfile.hasCookie, isFalse);
  });

  test('Missing credentials have no copyable value', () {
    final info = AccountLoginInfo.from(CloudPlatform.baidu, null);
    expect(info.fields, isEmpty);
    expect(info.preferred, isNull);
  });

  test('123 passwordToken primary is a token rather than a username', () {
    final info = AccountLoginInfo.from(
      CloudPlatform.pan123,
      Credential('账号', {
        'authType': 'passwordToken',
        'primary': 'stored-login-token',
        'secondary': 'private-password',
      }),
    );
    expect(info.preferred!.value, 'stored-login-token');
  });

  test('Weiyun captured request login can be inspected without a Cookie', () {
    final info = AccountLoginInfo.from(
      CloudPlatform.weiyun,
      Credential('账号', {
        'primary': '',
        'weiyunTokenInfo': jsonEncode({
          'token_type': 1,
          'login_key_type': 1,
          'login_key_value': 'request-login-token',
          'password': 'private',
        }),
        'weiyunCsrf': '12345',
      }),
    );
    expect(info.preferred!.key, 'tokenInfo');
    expect(jsonDecode(info.preferred!.value), {
      'token_type': 1,
      'login_key_type': 1,
      'login_key_value': 'request-login-token',
    });
    expect(info.field('csrf')!.value, '12345');
    expect(info.json, isNot(contains('private')));
    expect(info.copyLabel, '复制登录信息');
  });
}
