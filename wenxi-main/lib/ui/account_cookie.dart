import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../data/state_store.dart';
import '../domain/account_login_info.dart';
import '../domain/models.dart';
import 'common.dart';

AccountLoginInfo savedAccountLoginInfo(
  Vault vault,
  CloudPlatform platform,
  String? accountId,
) {
  final credential = vault.credentialFor(platform, accountId);
  var legacyAccessToken = '';
  if (platform == CloudPlatform.pan123 &&
      credential != null &&
      !{'webToken', 'passwordToken'}.contains(credential.field('authType')) &&
      credential.primary.isNotEmpty &&
      credential.secondary.isNotEmpty) {
    legacyAccessToken = vault.withAccount(
      platform,
      accountId,
      () => vault.secret('pan123.access_token') ?? '',
    );
  }
  return AccountLoginInfo.from(
    platform,
    credential,
    legacyAccessToken: legacyAccessToken,
  );
}

Future<void> copyCloudAccountLoginInfo(
  BuildContext context,
  Vault vault,
  CloudPlatform platform,
  String? accountId, {
  String? fieldKey,
  bool all = false,
}) async {
  // Resolve at click time, using the chosen account even if the active account
  // changed while its menu was open. Never log the value or clipboard errors.
  final credential = vault.credentialFor(platform, accountId);
  if (credential == null) {
    message(context, '此账号未登录或已移除，请重新打开账号菜单');
    return;
  }
  final info = savedAccountLoginInfo(vault, platform, accountId);
  final field = fieldKey == null ? info.preferred : info.field(fieldKey);
  if (field == null) {
    message(context, '此账号暂无可复制的登录信息，请查看账号配置或重新登录');
    return;
  }
  final label = all
      ? '登录信息'
      : field.key == 'cookie'
      ? 'Cookie'
      : field.key == 'authorization'
      ? '授权信息'
      : field.label;
  try {
    await Clipboard.setData(ClipboardData(text: all ? info.json : field.value));
    if (context.mounted) message(context, '$label 已复制');
  } catch (_) {
    if (context.mounted) message(context, '复制失败，请重试');
  }
}
