import 'dart:math' as math;
import 'package:flutter/cupertino.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import '../data/backgrounds.dart';
import '../domain/background.dart';
import 'app_background.dart';
import 'common.dart';

double backgroundPreviewRatio(BuildContext context, bool desktop) {
  final size = MediaQuery.sizeOf(context);
  if (desktop == desktopBackground(context) &&
      size.width > 0 &&
      size.height > 0) {
    return size.aspectRatio;
  }
  return desktop ? 16 / 10 : 9 / 19.5;
}

class BackgroundCropPage extends StatefulWidget {
  const BackgroundCropPage({
    super.key,
    required this.repository,
    required this.picture,
    required this.desktop,
  });
  final BackgroundRepository repository;
  final BackgroundPicture picture;
  final bool desktop;
  @override
  State<BackgroundCropPage> createState() => _BackgroundCropPageState();
}

class _BackgroundCropPageState extends State<BackgroundCropPage> {
  late BackgroundPicture _picture = widget.picture;
  late bool _desktop = widget.desktop;
  BackgroundCrop _start = const BackgroundCrop();
  Offset _anchor = Offset.zero;
  Size _viewport = Size.zero;
  BackgroundCrop get crop => _picture.cropFor(_desktop);
  Size get imageSize =>
      Size(_picture.width.toDouble(), _picture.height.toDouble());

  void _set(BackgroundCrop value) =>
      setState(() => _picture = _picture.withCrop(value, wide: _desktop));

  void _move(double zoom, Offset focal, Offset anchor) {
    final scale =
        math.max(
          _viewport.width / imageSize.width,
          _viewport.height / imageSize.height,
        ) *
        zoom;
    final width = imageSize.width * scale, height = imageSize.height * scale;
    _set(
      BackgroundCrop(
        x: width - _viewport.width < .01
            ? .5
            : ((anchor.dx * width - focal.dx) / (width - _viewport.width))
                  .clamp(0, 1),
        y: height - _viewport.height < .01
            ? .5
            : ((anchor.dy * height - focal.dy) / (height - _viewport.height))
                  .clamp(0, 1),
        zoom: zoom,
      ),
    );
  }

  void _zoom(double value) {
    final rect = backgroundImageRect(imageSize, _viewport, crop);
    final center = _viewport.center(Offset.zero);
    _move(
      value.clamp(1, 3),
      center,
      Offset(
        (center.dx - rect.left) / rect.width,
        (center.dy - rect.top) / rect.height,
      ),
    );
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('调整背景画面')),
    body: SafeArea(
      top: false,
      child: Column(
        children: [
          Padding(
            padding: const EdgeInsets.all(12),
            child: CupertinoSlidingSegmentedControl<bool>(
              groupValue: _desktop,
              children: const {
                false: Padding(
                  padding: EdgeInsets.symmetric(horizontal: 20),
                  child: Text('手机'),
                ),
                true: Padding(
                  padding: EdgeInsets.symmetric(horizontal: 20),
                  child: Text('电脑'),
                ),
              },
              onValueChanged: (v) {
                if (v != null) setState(() => _desktop = v);
              },
            ),
          ),
          Expanded(
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 20),
              child: Center(
                child: AspectRatio(
                  aspectRatio: backgroundPreviewRatio(context, _desktop),
                  child: ClipRRect(
                    borderRadius: BorderRadius.circular(_desktop ? 14 : 24),
                    child: LayoutBuilder(
                      builder: (context, constraints) {
                        _viewport = constraints.biggest;
                        return Listener(
                          onPointerSignal: (event) {
                            if (event is PointerScrollEvent) {
                              GestureBinding.instance.pointerSignalResolver
                                  .register(
                                    event,
                                    (_) => _zoom(
                                      crop.zoom - event.scrollDelta.dy * .003,
                                    ),
                                  );
                            }
                          },
                          child: GestureDetector(
                            key: const Key('background-crop-gesture'),
                            behavior: HitTestBehavior.opaque,
                            onScaleStart: (details) {
                              _start = crop;
                              final rect = backgroundImageRect(
                                imageSize,
                                _viewport,
                                crop,
                              );
                              _anchor = Offset(
                                (details.localFocalPoint.dx - rect.left) /
                                    rect.width,
                                (details.localFocalPoint.dy - rect.top) /
                                    rect.height,
                              );
                            },
                            onScaleUpdate: (details) => _move(
                              (_start.zoom * details.scale).clamp(1, 3),
                              details.localFocalPoint,
                              _anchor,
                            ),
                            child: Stack(
                              fit: StackFit.expand,
                              children: [
                                BackgroundImageView(
                                  repository: widget.repository,
                                  picture: _picture,
                                  crop: crop,
                                ),
                                const IgnorePointer(
                                  child: CustomPaint(painter: _CropGuides()),
                                ),
                              ],
                            ),
                          ),
                        );
                      },
                    ),
                  ),
                ),
              ),
            ),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 14, 20, 0),
            child: Text(
              '拖动画面调整位置，双指或滑块缩放。手机和电脑分别记忆。',
              textAlign: TextAlign.center,
              style: TextStyle(
                fontSize: 12,
                height: 1.5,
                color: secondary(context),
              ),
            ),
          ),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 12),
            child: Row(
              children: [
                TextButton(
                  onPressed: () => _set(const BackgroundCrop()),
                  child: const Text('居中重置'),
                ),
                Expanded(
                  child: Slider(
                    key: const Key('background-crop-zoom'),
                    value: crop.zoom,
                    min: 1,
                    max: 3,
                    divisions: 40,
                    label: '${crop.zoom.toStringAsFixed(1)} 倍',
                    onChanged: _zoom,
                  ),
                ),
                Text('${crop.zoom.toStringAsFixed(1)}×'),
              ],
            ),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 0, 20, 14),
            child: SizedBox(
              width: double.infinity,
              child: FilledButton(
                key: const Key('background-crop-done'),
                onPressed: () => Navigator.pop(context, _picture),
                child: const Text('确认位置'),
              ),
            ),
          ),
        ],
      ),
    ),
  );
}

class _CropGuides extends CustomPainter {
  const _CropGuides();
  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = Colors.white.withValues(alpha: .55)
      ..strokeWidth = .8;
    for (var i = 1; i <= 2; i++) {
      canvas.drawLine(
        Offset(size.width * i / 3, 0),
        Offset(size.width * i / 3, size.height),
        paint,
      );
      canvas.drawLine(
        Offset(0, size.height * i / 3),
        Offset(size.width, size.height * i / 3),
        paint,
      );
    }
    canvas.drawRect(
      Offset.zero & size,
      paint
        ..style = PaintingStyle.stroke
        ..strokeWidth = 2,
    );
  }

  @override
  bool shouldRepaint(_CropGuides oldDelegate) => false;
}
