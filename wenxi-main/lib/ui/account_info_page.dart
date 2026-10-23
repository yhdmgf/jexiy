import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import '../data/state_store.dart';
import '../domain/models.dart';
import 'account_cookie.dart';
import 'common.dart';

Future<void> openCloudAccountInfo(
  BuildContext context,
  Vault vault,
  CloudPlatform platform,
  String? accountId,
) => Navigator.push<void>(
  context,
  MaterialPageRoute(
    builder: (_) => CloudAccountInfoPage(
      vault,
      platform,
      accountId: accountId ?? vault.activeAccountId(platform),
    ),
  ),
);

class CloudAccountInfoPage extends StatefulWidget {
  const CloudAccountInfoPage(
    this.vault,
    this.platform, {
    super.key,
    this.accountId,
  });
  final Vault vault;
  final CloudPlatform platform;
  final String? accountId;

  @override
  State<CloudAccountInfoPage> createState() => _CloudAccountInfoPageState();
}

class _CloudAccountInfoPageState extends State<CloudAccountInfoPage> {
  late String? _accountId = widget.accountId;
  final _visible = <String>{};

  Future<void> _copy({String? fieldKey, bool all = false}) =>
      copyCloudAccountLoginInfo(
        context,
        widget.vault,
        widget.platform,
        _accountId,
        fieldKey: fieldKey,
        all: all,
      );

  @override
  Widget build(BuildContext context) => PageFrame(
    title: '账号配置',
    child: AnimatedBuilder(
      animation: widget.vault.store,
      builder: (context, _) {
        final profiles = widget.vault.profiles(widget.platform);
        final selected = profiles
            .where((profile) => profile.id == _accountId)
            .firstOrNull;
        final credential = widget.vault.credentialFor(
          widget.platform,
          _accountId,
        );
        final info = savedAccountLoginInfo(
          widget.vault,
          widget.platform,
          _accountId,
        );
        return Align(
          alignment: Alignment.topCenter,
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 640),
            child: ListView(
              key: const Key('account-info-page'),
              padding: const EdgeInsets.all(20),
              children: [
                Row(
                  children: [
                    PlatformMark(platform: widget.platform, size: 42),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            widget.platform.label,
                            style: const TextStyle(
                              fontSize: 18,
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                          const SizedBox(height: 4),
                          Text(
                            info.method,
                            style: TextStyle(
                              fontSize: 13,
                              color: secondary(context),
                            ),
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 24),
                InputDecorator(
                  decoration: InputDecoration(
                    labelText: '查看账号',
                    border: OutlineInputBorder(
                      borderRadius: BorderRadius.circular(14),
                    ),
                    contentPadding: const EdgeInsets.symmetric(
                      horizontal: 14,
                      vertical: 8,
                    ),
                  ),
                  child: DropdownButtonHideUnderline(
                    child: DropdownButton<String>(
                      key: const Key('account-info-selector'),
                      value: selected?.id,
                      isExpanded: true,
                      hint: Text(_accountId == null ? '暂无已登录账号' : '此账号已移除'),
                      items: [
                        for (final account in profiles)
                          DropdownMenuItem(
                            value: account.id,
                            child: Text(
                              '${account.name}${account.active ? '（当前使用）' : ''}',
                              key: ValueKey(
                                'account-info-select-${account.id}',
                              ),
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                            ),
                          ),
                      ],
                      onChanged: (id) => setState(() {
                        _accountId = id;
                        _visible.clear();
                      }),
                    ),
                  ),
                ),
                const SizedBox(height: 10),
                Text(
                  '选择要查看或复制登录信息的账号。',
                  style: TextStyle(
                    fontSize: 12,
                    height: 1.5,
                    color: secondary(context),
                  ),
                ),
                if (selected?.nickname.isNotEmpty == true &&
                    selected!.nickname != selected.name) ...[
                  const SizedBox(height: 6),
                  Text(
                    '网盘昵称：${selected.nickname}',
                    style: TextStyle(fontSize: 12, color: secondary(context)),
                  ),
                ],
                const SizedBox(height: 20),
                FilledButton.icon(
                  key: const Key('account-info-copy-primary'),
                  onPressed: info.preferred == null ? null : () => _copy(),
                  icon: const Icon(CupertinoIcons.doc_on_doc, size: 18),
                  label: Text(info.copyLabel),
                ),
                if (info.preferred != null)
                  TextButton.icon(
                    key: const Key('account-info-copy-all'),
                    onPressed: () => _copy(all: true),
                    icon: const Icon(CupertinoIcons.doc_text, size: 17),
                    label: const Text(
                      '复制全部登录信息（JSON）',
                      textAlign: TextAlign.center,
                    ),
                  ),
                if (!info.hasCookie && info.preferred != null)
                  Padding(
                    padding: const EdgeInsets.symmetric(vertical: 12),
                    child: Text(
                      '该账号使用 Token 等凭据登录，未保存网页 Cookie，可直接复制下方登录信息。',
                      style: TextStyle(
                        fontSize: 13,
                        height: 1.6,
                        color: secondary(context),
                      ),
                    ),
                  ),
                if (info.preferred == null)
                  Padding(
                    padding: const EdgeInsets.symmetric(vertical: 20),
                    child: Text(
                      credential == null
                          ? '此账号未登录或已移除，请返回账号管理重新登录。'
                          : '此账号暂未保存 Cookie 或 Token，请重新登录后查看。',
                      style: TextStyle(
                        fontSize: 14,
                        height: 1.6,
                        color: secondary(context),
                      ),
                    ),
                  ),
                const SizedBox(height: 6),
                for (final field in info.fields)
                  Container(
                    key: ValueKey('account-info-field-${field.key}'),
                    margin: const EdgeInsets.only(bottom: 12),
                    padding: const EdgeInsets.fromLTRB(14, 6, 8, 14),
                    decoration: BoxDecoration(
                      color: fill(context),
                      borderRadius: BorderRadius.circular(16),
                      border: Border.all(color: border(context)),
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          children: [
                            Expanded(
                              child: Text(
                                field.label,
                                style: const TextStyle(
                                  fontSize: 13,
                                  fontWeight: FontWeight.w600,
                                ),
                              ),
                            ),
                            IconButton(
                              key: ValueKey('account-info-reveal-${field.key}'),
                              tooltip: _visible.contains(field.key)
                                  ? '隐藏${field.label}'
                                  : '显示${field.label}',
                              onPressed: () => setState(() {
                                if (!_visible.remove(field.key)) {
                                  _visible.add(field.key);
                                }
                              }),
                              icon: Icon(
                                _visible.contains(field.key)
                                    ? CupertinoIcons.eye_slash
                                    : CupertinoIcons.eye,
                                size: 19,
                              ),
                            ),
                            IconButton(
                              key: ValueKey('account-info-copy-${field.key}'),
                              tooltip: '复制${field.label}',
                              onPressed: () => _copy(fieldKey: field.key),
                              icon: Icon(
                                CupertinoIcons.doc_on_doc,
                                size: 19,
                                color: themeAccent(context),
                              ),
                            ),
                          ],
                        ),
                        Padding(
                          padding: const EdgeInsets.only(right: 6),
                          child: _visible.contains(field.key)
                              ? SelectableText(
                                  field.value,
                                  maxLines: 6,
                                  style: const TextStyle(
                                    fontSize: 13,
                                    height: 1.5,
                                  ),
                                )
                              : Text(
                                  '••••••••••••',
                                  style: TextStyle(
                                    fontSize: 16,
                                    color: secondary(context),
                                    letterSpacing: 2,
                                  ),
                                ),
                        ),
                      ],
                    ),
                  ),
              ],
            ),
          ),
        );
      },
    ),
  );
}
