import 'package:flutter/material.dart';
import '../domain/playback.dart';

/// Shared by the Flutter fallback and the appearance preview. Native libass
/// uses the same ARGB background and a one-pixel outline in viewport units.
class SubtitleCaption extends StatelessWidget {
  const SubtitleCaption({
    super.key,
    required this.text,
    required this.size,
    this.backgroundColor,
    this.textKey,
  });

  final String text;
  final double size;
  final int? backgroundColor;
  final Key? textKey;

  @override
  Widget build(BuildContext context) {
    final style = TextStyle(fontSize: size, height: 1.35);
    Widget caption(TextStyle style, {Key? key}) => Text(
      text,
      key: key,
      textAlign: TextAlign.center,
      textScaler: TextScaler.noScaling,
      maxLines: 5,
      overflow: TextOverflow.fade,
      style: style,
    );
    return Align(
      alignment: Alignment.bottomCenter,
      child: Container(
        padding: backgroundColor == null
            ? EdgeInsets.zero
            : const EdgeInsets.symmetric(horizontal: 6, vertical: 3),
        color: backgroundColor == null ? null : Color(backgroundColor!),
        child: Stack(
          alignment: Alignment.center,
          children: [
            ExcludeSemantics(
              child: caption(
                style.copyWith(
                  foreground: Paint()
                    ..style = PaintingStyle.stroke
                    ..strokeWidth = 2 * size / 22
                    ..color = const Color(0xcc000000),
                ),
              ),
            ),
            caption(style.copyWith(color: Colors.white), key: textKey),
          ],
        ),
      ),
    );
  }
}

class SubtitleAppearanceSettings extends StatelessWidget {
  const SubtitleAppearanceSettings({
    super.key,
    required this.preferences,
    required this.onChanged,
  });

  final PlaybackPreferences preferences;
  final void Function(PlaybackPreferences preferences, bool save) onChanged;

  @override
  Widget build(BuildContext context) {
    final mode = preferences.subtitleBackground;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Text('字幕底色'),
        const SizedBox(height: 8),
        Wrap(
          spacing: 8,
          runSpacing: 4,
          children: [
            for (final (value, label) in [
              (SubtitleBackground.none, '无底色'),
              (SubtitleBackground.black, '黑色'),
              (SubtitleBackground.custom, '自定义'),
            ])
              ChoiceChip(
                label: Text(label),
                selected: mode == value,
                onSelected: (_) => onChanged(
                  preferences.copyWith(subtitleBackground: value),
                  true,
                ),
              ),
          ],
        ),
        if (mode == SubtitleBackground.custom)
          ListTile(
            contentPadding: EdgeInsets.zero,
            leading: _ColorSwatch(preferences.subtitleBackgroundColor),
            title: const Text('选择颜色'),
            subtitle: Text(_hex(preferences.subtitleBackgroundColor)),
            trailing: const Icon(Icons.chevron_right),
            onTap: () async {
              final color = await showDialog<int>(
                context: context,
                builder: (_) => _SubtitleColorDialog(
                  color: preferences.subtitleBackgroundColor,
                  opacity: preferences.subtitleBackgroundOpacity,
                ),
              );
              if (color != null && context.mounted) {
                onChanged(
                  preferences.copyWith(subtitleBackgroundColor: color),
                  true,
                );
              }
            },
          ),
        if (mode != SubtitleBackground.none) ...[
          const SizedBox(height: 8),
          Text(
            '底色透明度  ${((1 - preferences.subtitleBackgroundOpacity) * 100).round()}%',
          ),
          Slider(
            key: const Key('subtitle-background-transparency'),
            value: 1 - preferences.subtitleBackgroundOpacity,
            divisions: 100,
            label:
                '${((1 - preferences.subtitleBackgroundOpacity) * 100).round()}%',
            onChanged: (value) => onChanged(
              preferences.copyWith(subtitleBackgroundOpacity: 1 - value),
              false,
            ),
            onChangeEnd: (value) => onChanged(
              preferences.copyWith(subtitleBackgroundOpacity: 1 - value),
              true,
            ),
          ),
        ],
        const SizedBox(height: 12),
        _SubtitlePreview(backgroundColor: preferences.subtitleBackgroundArgb),
        const SizedBox(height: 8),
        const Text(
          '自带样式或图片字幕保留原有效果',
          style: TextStyle(fontSize: 12, color: Colors.white54),
        ),
      ],
    );
  }
}

String _hex(int rgb) =>
    '#${rgb.toRadixString(16).padLeft(6, '0').toUpperCase()}';

class _ColorSwatch extends StatelessWidget {
  const _ColorSwatch(this.rgb);
  final int rgb;

  @override
  Widget build(BuildContext context) => Container(
    width: 28,
    height: 28,
    decoration: BoxDecoration(
      color: Color(0xff000000 | rgb),
      borderRadius: BorderRadius.circular(8),
      border: Border.all(color: Colors.white38),
    ),
  );
}

class _SubtitlePreview extends StatelessWidget {
  const _SubtitlePreview({this.backgroundColor});
  final int? backgroundColor;

  @override
  Widget build(BuildContext context) => Container(
    width: double.infinity,
    padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 24),
    decoration: BoxDecoration(
      borderRadius: BorderRadius.circular(12),
      gradient: const LinearGradient(
        colors: [Color(0xff708396), Color(0xff243b50)],
      ),
    ),
    child: SubtitleCaption(
      text: '字幕效果预览',
      size: 20,
      backgroundColor: backgroundColor,
    ),
  );
}

class _SubtitleColorDialog extends StatefulWidget {
  const _SubtitleColorDialog({required this.color, required this.opacity});
  final int color;
  final double opacity;

  @override
  State<_SubtitleColorDialog> createState() => _SubtitleColorDialogState();
}

class _SubtitleColorDialogState extends State<_SubtitleColorDialog> {
  late int _color = widget.color;
  late final _hexInput = TextEditingController(text: _hex(_color));
  String? _error;

  @override
  void dispose() {
    _hexInput.dispose();
    super.dispose();
  }

  void _select(int color) => setState(() {
    _color = color;
    _hexInput.text = _hex(color);
    _error = null;
  });

  @override
  Widget build(BuildContext context) => AlertDialog(
    title: const Text('自定义字幕底色'),
    content: SizedBox(
      width: 320,
      child: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _SubtitlePreview(
              backgroundColor: ((widget.opacity * 255).round() << 24) | _color,
            ),
            const SizedBox(height: 12),
            Wrap(
              spacing: 4,
              children: [
                for (final color in [
                  0x000000,
                  0x202020,
                  0x243b53,
                  0x3b2f50,
                  0x214538,
                  0xffffff,
                ])
                  IconButton(
                    tooltip: _hex(color),
                    onPressed: () => _select(color),
                    icon: _ColorSwatch(color),
                  ),
              ],
            ),
            for (final (label, shift, color) in [
              ('红', 16, Colors.redAccent),
              ('绿', 8, Colors.greenAccent),
              ('蓝', 0, Colors.lightBlueAccent),
            ])
              Row(
                children: [
                  Text(label),
                  Expanded(
                    child: Slider(
                      value: ((_color >> shift) & 255).toDouble(),
                      max: 255,
                      divisions: 255,
                      activeColor: color,
                      label: '${(_color >> shift) & 255}',
                      semanticFormatterCallback: (value) =>
                          '$label ${value.round()}',
                      onChanged: (value) => _select(
                        (_color & ~(255 << shift)) | (value.round() << shift),
                      ),
                    ),
                  ),
                ],
              ),
            TextField(
              key: const Key('subtitle-background-hex'),
              controller: _hexInput,
              autocorrect: false,
              enableSuggestions: false,
              maxLength: 7,
              decoration: InputDecoration(
                labelText: '颜色值',
                hintText: '#RRGGBB',
                errorText: _error,
                counterText: '',
              ),
              onChanged: (text) => setState(() {
                final hex = text.trim().replaceFirst(RegExp(r'^#'), '');
                if (RegExp(r'^[0-9a-fA-F]{6}$').hasMatch(hex)) {
                  _color = int.parse(hex, radix: 16);
                  _error = null;
                } else {
                  _error = '请输入 6 位颜色值，例如 #243B53';
                }
              }),
            ),
          ],
        ),
      ),
    ),
    actions: [
      TextButton(
        onPressed: () => Navigator.pop(context),
        child: const Text('取消'),
      ),
      FilledButton(
        onPressed: _error == null ? () => Navigator.pop(context, _color) : null,
        child: const Text('确定'),
      ),
    ],
  );
}
