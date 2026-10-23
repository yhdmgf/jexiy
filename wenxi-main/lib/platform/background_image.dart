import 'dart:math' as math;
import 'dart:typed_data';
import 'dart:ui' as ui;
import '../core/json.dart';
import '../domain/background.dart';

/// Decode at a bounded size before allocating pixels. Only a static frame is
/// stored, without the source image's location or metadata.
Future<Uint8List> prepareBackgroundImage(Uint8List bytes) async {
  require(
    bytes.isNotEmpty && bytes.length <= backgroundSourceMaxBytes,
    '请选择不超过 24 MB 的图片',
  );
  final buffer = await ui.ImmutableBuffer.fromUint8List(bytes);
  ui.ImageDescriptor? descriptor;
  try {
    descriptor = await ui.ImageDescriptor.encoded(buffer);
    final width = descriptor.width, height = descriptor.height;
    require(
      width > 0 &&
          height > 0 &&
          width * height <= 80000000 &&
          width <= 32768 &&
          height <= 32768,
      '图片尺寸过大，请先缩小后再选择',
    );
    var scale = math.min(1.0, backgroundMaxSide / math.max(width, height));
    scale = math.min(scale, math.sqrt(3000000 / (width * height)));
    for (var attempt = 0; attempt < 6; attempt++) {
      final codec = await descriptor.instantiateCodec(
        targetWidth: math.max(1, (width * scale).floor()),
        targetHeight: math.max(1, (height * scale).floor()),
      );
      ui.Image? image;
      try {
        image = (await codec.getNextFrame()).image;
        final data = await image.toByteData(format: ui.ImageByteFormat.png);
        require(data != null, '无法处理这张图片，请换一张重试');
        if (data!.lengthInBytes <= backgroundMaxBytes) {
          return data.buffer.asUint8List(
            data.offsetInBytes,
            data.lengthInBytes,
          );
        }
      } finally {
        image?.dispose();
        codec.dispose();
      }
      scale *= .72;
    }
    throw const AppException('图片处理后仍过大，请先缩小后再选择');
  } on AppException {
    rethrow;
  } catch (_) {
    throw const AppException('无法读取这张图片，请选择 JPG、PNG 或 WebP 图片');
  } finally {
    descriptor?.dispose();
    buffer.dispose();
  }
}

(int, int) backgroundPngSize(Uint8List bytes) {
  const signature = [137, 80, 78, 71, 13, 10, 26, 10];
  require(bytes.length >= 33 && bytes.length <= backgroundMaxBytes, '背景图片大小无效');
  require(
    List.generate(8, (i) => bytes[i] == signature[i]).every((v) => v) &&
        bytes[12] == 73 &&
        bytes[13] == 72 &&
        bytes[14] == 68 &&
        bytes[15] == 82,
    '背景图片格式无效',
  );
  final data = ByteData.sublistView(bytes);
  final width = data.getUint32(16), height = data.getUint32(20);
  require(
    width > 0 &&
        height > 0 &&
        width <= backgroundMaxSide &&
        height <= backgroundMaxSide,
    '背景图片尺寸无效',
  );
  return (width, height);
}

Future<void> validateBackgroundImage(Uint8List bytes) async {
  backgroundPngSize(bytes);
  ui.Codec? codec;
  ui.Image? image;
  try {
    codec = await ui.instantiateImageCodec(
      bytes,
      targetWidth: 32,
      targetHeight: 32,
      allowUpscaling: false,
    );
    image = (await codec.getNextFrame()).image;
  } catch (_) {
    throw const AppException('备份中的背景图片已损坏');
  } finally {
    image?.dispose();
    codec?.dispose();
  }
}
