import 'dart:math' as math;
import 'dart:ui' as ui;
import 'package:flutter/material.dart';
import '../data/backgrounds.dart';
import '../domain/background.dart';

bool desktopBackground(BuildContext context) =>
    switch (Theme.of(context).platform) {
      TargetPlatform.windows ||
      TargetPlatform.linux ||
      TargetPlatform.macOS => true,
      _ => false,
    };

class BackgroundScope extends InheritedWidget {
  const BackgroundScope({
    super.key,
    required this.settings,
    required this.active,
    required super.child,
  });
  final BackgroundSettings settings;
  final bool active;
  static BackgroundScope? of(BuildContext context) =>
      context.dependOnInheritedWidgetOfExactType<BackgroundScope>();
  @override
  bool updateShouldNotify(BackgroundScope oldWidget) =>
      active != oldWidget.active || settings != oldWidget.settings;
}

Color backgroundSurface(BuildContext context, {Color? color, Color? fallback}) {
  final scope = BackgroundScope.of(context);
  final surface = color ?? Theme.of(context).colorScheme.surface;
  return scope?.active == true
      ? surface.withValues(alpha: scope!.settings.cardOpacity)
      : fallback ?? surface;
}

/// Give standalone labels the same readable backing as content cards.
class BackgroundReadable extends StatelessWidget {
  const BackgroundReadable({
    super.key,
    required this.child,
    this.padding = const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
    this.radius = 10,
  });
  final Widget child;
  final EdgeInsetsGeometry padding;
  final double radius;
  @override
  Widget build(BuildContext context) {
    final active = BackgroundScope.of(context)?.active == true;
    return Container(
      padding: active ? padding : EdgeInsets.zero,
      decoration: BoxDecoration(
        color: backgroundSurface(context, fallback: Colors.transparent),
        borderRadius: BorderRadius.circular(radius),
      ),
      child: child,
    );
  }
}

class AppBackground extends StatelessWidget {
  const AppBackground({
    super.key,
    required this.repository,
    required this.settings,
    required this.child,
    this.desktop,
  });
  final BackgroundRepository repository;
  final BackgroundSettings settings;
  final Widget child;
  final bool? desktop;

  @override
  Widget build(BuildContext context) {
    final dark = Theme.of(context).brightness == Brightness.dark;
    final picture = settings.pictureFor(dark);
    final active = picture != null && !MediaQuery.highContrastOf(context);
    // Keep the widget tree stable when a wallpaper is added or removed: the
    // shell's navigators, scroll positions and transfer views retain state.
    return Stack(
      fit: StackFit.passthrough,
      children: [
        Positioned.fill(
          child: RepaintBoundary(
            key: const Key('app-background-layer'),
            child: ColoredBox(
              color: Theme.of(context).colorScheme.surface,
              child: active
                  ? BackgroundImageView(
                      repository: repository,
                      picture: picture,
                      crop: picture.cropFor(
                        desktop ?? desktopBackground(context),
                      ),
                      blur: settings.blur,
                      brightness: settings.brightness * (dark ? .58 : 1),
                    )
                  : const SizedBox.expand(),
            ),
          ),
        ),
        BackgroundScope(settings: settings, active: active, child: child),
      ],
    );
  }
}

/// The same geometry is used by the editor and by all four main pages.
Rect backgroundImageRect(Size image, Size viewport, BackgroundCrop crop) {
  final scale =
      math.max(viewport.width / image.width, viewport.height / image.height) *
      crop.zoom;
  final width = image.width * scale, height = image.height * scale;
  return Rect.fromLTWH(
    -(width - viewport.width) * crop.x,
    -(height - viewport.height) * crop.y,
    width,
    height,
  );
}

class BackgroundImageView extends StatelessWidget {
  const BackgroundImageView({
    super.key,
    required this.repository,
    required this.picture,
    required this.crop,
    this.blur = 0,
    this.brightness = 1,
  });
  final BackgroundRepository repository;
  final BackgroundPicture picture;
  final BackgroundCrop crop;
  final double blur, brightness;

  @override
  Widget build(BuildContext context) => LayoutBuilder(
    builder: (context, box) {
      final viewport = Size(box.maxWidth, box.maxHeight);
      final imageSize = Size(
        picture.width.toDouble(),
        picture.height.toDouble(),
      );
      final rect = backgroundImageRect(imageSize, viewport, crop);
      // Discrete decode sizes avoid accumulating cache entries during window
      // resizing. The source is already bounded; scrolling never reprocesses it.
      final needed =
          math.max(
            viewport.width / picture.width,
            viewport.height / picture.height,
          ) *
          math.max(picture.width, picture.height) *
          MediaQuery.devicePixelRatioOf(context);
      final edge = [
        512,
        1024,
        1536,
        2048,
      ].firstWhere((n) => n >= needed, orElse: () => 2048);
      final surface = Theme.of(context).colorScheme.surface;
      // Clip before the filter so zooming cannot allocate a raster as large as
      // the overflowing, scaled image. The blur only processes the viewport.
      Widget image = ClipRect(
        child: Stack(
          clipBehavior: Clip.none,
          children: [
            Positioned.fromRect(
              rect: rect,
              child: Image(
                image: ResizeImage(
                  repositoryImage(),
                  width: edge,
                  height: edge,
                  policy: ResizeImagePolicy.fit,
                ),
                fit: BoxFit.fill,
                excludeFromSemantics: true,
                filterQuality: FilterQuality.medium,
                errorBuilder: (_, _, _) => ColoredBox(color: surface),
              ),
            ),
          ],
        ),
      );
      if (blur > 0) {
        image = ImageFiltered(
          imageFilter: ui.ImageFilter.blur(
            sigmaX: blur,
            sigmaY: blur,
            tileMode: TileMode.clamp,
          ),
          child: image,
        );
      }
      return ClipRect(
        child: Stack(
          fit: StackFit.expand,
          children: [
            image,
            if (brightness < 1)
              ColoredBox(color: Colors.black.withValues(alpha: 1 - brightness)),
          ],
        ),
      );
    },
  );

  FileImage repositoryImage() => FileImage(repository.file(picture.id));
}
