import 'dart:typed_data';
import 'dart:ui' as ui;

Future<Uint8List> backgroundFixture({
  int width = 960,
  int height = 1280,
  bool night = false,
}) async {
  final recorder = ui.PictureRecorder();
  final canvas = ui.Canvas(recorder);
  canvas.drawRect(
    ui.Rect.fromLTWH(0, 0, width.toDouble(), height.toDouble()),
    ui.Paint()
      ..shader = ui.Gradient.linear(
        ui.Offset.zero,
        ui.Offset(width.toDouble(), height.toDouble()),
        night
            ? const [
                ui.Color(0xff101e47),
                ui.Color(0xff47396f),
                ui.Color(0xffa67694),
              ]
            : const [
                ui.Color(0xff418ca7),
                ui.Color(0xffbdd6d3),
                ui.Color(0xffedcda5),
              ],
        const [0, .55, 1],
      ),
  );
  canvas.drawCircle(
    ui.Offset(width * .73, height * .28),
    width * .13,
    ui.Paint()..color = const ui.Color(0x75ffffff),
  );
  for (var i = 0; i < 4; i++) {
    final path = ui.Path()
      ..moveTo(0, height * (.65 + i * .08))
      ..quadraticBezierTo(
        width * .46,
        height * (.45 + i * .14),
        width.toDouble(),
        height * (.8 + i * .03),
      )
      ..lineTo(width.toDouble(), height.toDouble())
      ..lineTo(0, height.toDouble())
      ..close();
    canvas.drawPath(
      path,
      ui.Paint()
        ..color = [
          0x99778386,
          0x997b9295,
          0x9989aaa8,
          0x9987b5b2,
        ].map(ui.Color.new).toList()[i],
    );
  }
  final picture = recorder.endRecording();
  final image = await picture.toImage(width, height);
  try {
    final data = (await image.toByteData(format: ui.ImageByteFormat.png))!;
    return data.buffer.asUint8List(data.offsetInBytes, data.lengthInBytes);
  } finally {
    image.dispose();
    picture.dispose();
  }
}
