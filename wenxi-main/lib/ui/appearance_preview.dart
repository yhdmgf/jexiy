import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import '../data/backgrounds.dart';
import '../domain/settings.dart';
import 'app_background.dart';
import 'app_navigation.dart';
import 'app_theme.dart';

class AppearancePreview extends StatefulWidget {
  const AppearancePreview({
    super.key,
    required this.settings,
    required this.repository,
    this.dark,
    this.desktop = false,
    this.expanded = false,
  });
  final AppSettings settings;
  final BackgroundRepository repository;
  final bool? dark;
  final bool desktop, expanded;

  @override
  State<AppearancePreview> createState() => _AppearancePreviewState();
}

class _AppearancePreviewState extends State<AppearancePreview> {
  int selected = 0;
  @override
  Widget build(BuildContext context) {
    final settings = widget.settings;
    final brightness = widget.dark == null
        ? switch (settings.theme) {
            'Light' => Brightness.light,
            'Dark' => Brightness.dark,
            _ => MediaQuery.platformBrightnessOf(context),
          }
        : widget.dark!
        ? Brightness.dark
        : Brightness.light;
    return RepaintBoundary(
      key: const Key('appearance-preview'),
      child: AnimatedTheme(
        data: appTheme(
          brightness,
          palette: settings.themePalette,
          glassStyle: settings.glassStyle,
          fontFamily: Theme.of(context).textTheme.bodyMedium?.fontFamily,
        ),
        duration: MediaQuery.disableAnimationsOf(context)
            ? Duration.zero
            : const Duration(milliseconds: 240),
        curve: Curves.easeOutCubic,
        child: Builder(
          builder: (context) {
            final colors = Theme.of(context).colorScheme;
            return Container(
              clipBehavior: Clip.antiAlias,
              decoration: BoxDecoration(
                color: colors.surface,
                borderRadius: BorderRadius.circular(22),
                border: Border.all(color: colors.outlineVariant),
              ),
              child: AppBackground(
                repository: widget.repository,
                settings: settings.background,
                desktop: widget.desktop,
                child: Builder(
                  builder: (context) {
                    final body = Column(
                      mainAxisSize: widget.expanded
                          ? MainAxisSize.max
                          : MainAxisSize.min,
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        Container(
                          color: backgroundSurface(
                            context,
                            fallback: Colors.transparent,
                          ),
                          padding: const EdgeInsets.fromLTRB(18, 18, 18, 14),
                          child: Row(
                            children: [
                              Expanded(
                                child: Text(
                                  '文析助手',
                                  style: TextStyle(
                                    fontSize: 17,
                                    fontWeight: FontWeight.w700,
                                    color: colors.onSurface,
                                  ),
                                ),
                              ),
                              Icon(
                                CupertinoIcons.bell,
                                size: 20,
                                color: colors.primary,
                              ),
                            ],
                          ),
                        ),
                        if (widget.expanded) const SizedBox(height: 32),
                        Padding(
                          padding: const EdgeInsets.symmetric(horizontal: 16),
                          child: Container(
                            padding: const EdgeInsets.all(14),
                            decoration: BoxDecoration(
                              color: backgroundSurface(
                                context,
                                color: colors.surfaceContainerLow,
                              ),
                              borderRadius: BorderRadius.circular(12),
                            ),
                            child: Text(
                              '粘贴网盘分享链接',
                              style: TextStyle(
                                fontSize: 12,
                                color: colors.onSurfaceVariant,
                              ),
                            ),
                          ),
                        ),
                        Padding(
                          padding: const EdgeInsets.fromLTRB(16, 10, 16, 16),
                          child: DecoratedBox(
                            decoration: BoxDecoration(
                              color: colors.primary,
                              borderRadius: BorderRadius.circular(10),
                            ),
                            child: Padding(
                              padding: const EdgeInsets.symmetric(vertical: 11),
                              child: Text(
                                '解析链接',
                                textAlign: TextAlign.center,
                                style: TextStyle(
                                  color: colors.onPrimary,
                                  fontSize: 13,
                                  fontWeight: FontWeight.w600,
                                ),
                              ),
                            ),
                          ),
                        ),
                        Container(
                          margin: const EdgeInsets.symmetric(horizontal: 16),
                          padding: const EdgeInsets.all(12),
                          decoration: BoxDecoration(
                            color: backgroundSurface(
                              context,
                              color: colors.surfaceContainerLow,
                            ),
                            borderRadius: BorderRadius.circular(12),
                          ),
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Row(
                                children: [
                                  Icon(
                                    CupertinoIcons.arrow_down_circle,
                                    size: 22,
                                    color: colors.primary,
                                  ),
                                  const SizedBox(width: 8),
                                  Expanded(
                                    child: Text(
                                      '项目资料.zip',
                                      style: TextStyle(
                                        fontSize: 13,
                                        color: colors.onSurface,
                                      ),
                                    ),
                                  ),
                                ],
                              ),
                              const SizedBox(height: 10),
                              LinearProgressIndicator(
                                value: .62,
                                minHeight: 4,
                                borderRadius: BorderRadius.circular(3),
                                color: colors.primary,
                                backgroundColor: colors.outlineVariant,
                              ),
                              const SizedBox(height: 7),
                              Text(
                                '正在下载 · 5.6 MB/s',
                                style: TextStyle(
                                  fontSize: 11,
                                  color: colors.onSurfaceVariant,
                                ),
                              ),
                            ],
                          ),
                        ),
                        if (widget.expanded) const Spacer(),
                        if (!widget.desktop)
                          MediaQuery.removePadding(
                            context: context,
                            removeTop: true,
                            removeBottom: true,
                            child: AppNavigation(
                              selectedIndex: selected,
                              onSelected: (index) =>
                                  setState(() => selected = index),
                            ),
                          ),
                      ],
                    );
                    return widget.desktop
                        ? Row(
                            children: [
                              Material(
                                color: backgroundSurface(context),
                                child: AppNavigation(
                                  rail: true,
                                  selectedIndex: selected,
                                  onSelected: (index) =>
                                      setState(() => selected = index),
                                ),
                              ),
                              Expanded(child: body),
                            ],
                          )
                        : body;
                  },
                ),
              ),
            );
          },
        ),
      ),
    );
  }
}
