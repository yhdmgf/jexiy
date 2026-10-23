import '../core/json.dart';

const backgroundMaxSide = 2048;
const backgroundMaxBytes = 2 * 1024 * 1024;
const backgroundSourceMaxBytes = 24 * 1024 * 1024;

bool validBackgroundId(String id) =>
    RegExp(r'^[a-f0-9]{64}\.png$').hasMatch(id);

double _bounded(
  Json json,
  String key,
  double fallback,
  double min,
  double max,
) {
  final value = json.number(key, fallback);
  return value.isFinite ? value.clamp(min, max) : fallback;
}

/// Position within the available overflow, independent of screen resolution.
class BackgroundCrop {
  const BackgroundCrop({this.x = .5, this.y = .5, this.zoom = 1});
  final double x, y, zoom;

  factory BackgroundCrop.fromJson(Json json) => BackgroundCrop(
    x: _bounded(json, 'x', .5, 0, 1),
    y: _bounded(json, 'y', .5, 0, 1),
    zoom: _bounded(json, 'zoom', 1, 1, 3),
  );

  Json toJson() => {'x': x, 'y': y, 'zoom': zoom};

  @override
  bool operator ==(Object other) =>
      other is BackgroundCrop &&
      x == other.x &&
      y == other.y &&
      zoom == other.zoom;
  @override
  int get hashCode => Object.hash(x, y, zoom);
}

class BackgroundPicture {
  const BackgroundPicture({
    required this.id,
    required this.width,
    required this.height,
    this.phone = const BackgroundCrop(),
    this.desktop = const BackgroundCrop(),
  });

  final String id;
  final int width, height;
  final BackgroundCrop phone, desktop;

  static BackgroundPicture? parse(Object? value) {
    final json = asJson(value);
    final id = json.str('id');
    final width = json.integer('width'), height = json.integer('height');
    if (!validBackgroundId(id) ||
        width < 1 ||
        height < 1 ||
        width > backgroundMaxSide ||
        height > backgroundMaxSide) {
      return null;
    }
    return BackgroundPicture(
      id: id,
      width: width,
      height: height,
      phone: BackgroundCrop.fromJson(json.obj('phone')),
      desktop: BackgroundCrop.fromJson(json.obj('desktop')),
    );
  }

  BackgroundCrop cropFor(bool wide) => wide ? desktop : phone;
  BackgroundPicture withCrop(BackgroundCrop crop, {required bool wide}) =>
      BackgroundPicture(
        id: id,
        width: width,
        height: height,
        phone: wide ? phone : crop,
        desktop: wide ? crop : desktop,
      );

  Json toJson() => {
    'id': id,
    'width': width,
    'height': height,
    'phone': phone.toJson(),
    'desktop': desktop.toJson(),
  };

  @override
  bool operator ==(Object other) =>
      other is BackgroundPicture &&
      id == other.id &&
      width == other.width &&
      height == other.height &&
      phone == other.phone &&
      desktop == other.desktop;
  @override
  int get hashCode => Object.hash(id, width, height, phone, desktop);
}

class BackgroundSettings {
  const BackgroundSettings({
    this.day,
    this.night,
    this.brightness = .9,
    this.blur = 6,
    this.cardOpacity = .86,
  });

  final BackgroundPicture? day, night;
  final double brightness, blur, cardOpacity;
  bool get hasImage => day != null || night != null;
  Set<String> get ids => {
    if (day != null) day!.id,
    if (night != null) night!.id,
  };
  BackgroundPicture? pictureFor(bool dark) => dark ? night ?? day : day;

  factory BackgroundSettings.fromJson(Json json) => BackgroundSettings(
    day: BackgroundPicture.parse(json['day']),
    night: BackgroundPicture.parse(json['night']),
    brightness: _bounded(json, 'brightness', .9, .35, 1),
    blur: _bounded(json, 'blur', 6, 0, 20),
    cardOpacity: _bounded(json, 'cardOpacity', .86, .6, 1),
  );

  BackgroundSettings withPicture(
    BackgroundPicture? image, {
    bool dark = false,
  }) => BackgroundSettings(
    day: dark ? day : image,
    night: dark ? image : night,
    brightness: brightness,
    blur: blur,
    cardOpacity: cardOpacity,
  );

  BackgroundSettings copyWith({
    double? brightness,
    double? blur,
    double? cardOpacity,
  }) => BackgroundSettings(
    day: day,
    night: night,
    brightness: brightness ?? this.brightness,
    blur: blur ?? this.blur,
    cardOpacity: cardOpacity ?? this.cardOpacity,
  );

  BackgroundSettings withoutImages() => BackgroundSettings(
    brightness: brightness,
    blur: blur,
    cardOpacity: cardOpacity,
  );

  Json toJson() => {
    'day': day?.toJson(),
    'night': night?.toJson(),
    'brightness': brightness,
    'blur': blur,
    'cardOpacity': cardOpacity,
  };

  @override
  bool operator ==(Object other) =>
      other is BackgroundSettings &&
      day == other.day &&
      night == other.night &&
      brightness == other.brightness &&
      blur == other.blur &&
      cardOpacity == other.cardOpacity;
  @override
  int get hashCode => Object.hash(day, night, brightness, blur, cardOpacity);
}
