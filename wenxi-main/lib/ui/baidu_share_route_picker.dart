import 'package:flutter/material.dart';
import '../domain/baidu_share_route.dart';
import 'common.dart';

class BaiduShareRoutePicker extends StatelessWidget {
  const BaiduShareRoutePicker({
    super.key,
    required this.value,
    required this.onChanged,
  });

  final BaiduShareRoute value;
  final ValueChanged<BaiduShareRoute>? onChanged;

  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.start,
    children: [
      Wrap(
        spacing: 8,
        runSpacing: 4,
        crossAxisAlignment: WrapCrossAlignment.center,
        children: [
          Text(
            '解析方式',
            style: TextStyle(fontSize: 12, color: secondary(context)),
          ),
          for (final route in BaiduShareRoute.values)
            ChoiceChip(
              key: ValueKey('baidu-route-${route.key}'),
              label: Text(route.label),
              selected: value == route,
              onSelected: onChanged == null ? null : (_) => onChanged!(route),
              selectedColor: themeAccent(context).withValues(alpha: .14),
              side: BorderSide(
                color: value == route
                    ? themeAccent(context).withValues(alpha: .3)
                    : border(context),
              ),
              labelStyle: TextStyle(
                color: value == route ? themeAccent(context) : null,
                fontSize: 13,
                fontWeight: value == route ? FontWeight.w600 : FontWeight.w400,
              ),
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(12),
              ),
            ),
        ],
      ),
      const SizedBox(height: 2),
      Text(
        value == BaiduShareRoute.publicParser
            ? '无需登录百度账号，支持多线程下载'
            : '使用已登录的百度网盘账号下载',
        style: TextStyle(fontSize: 12, height: 1.5, color: secondary(context)),
      ),
    ],
  );
}
