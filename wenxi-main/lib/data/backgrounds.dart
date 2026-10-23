import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';
import 'package:crypto/crypto.dart';
import 'package:file_selector/file_selector.dart';
import 'package:path/path.dart' as p;
import '../core/json.dart';
import '../domain/background.dart';
import '../platform/background_image.dart';

class BackgroundRepository {
  BackgroundRepository(Directory dataDirectory, {required this.current})
    : directory = Directory(p.join(dataDirectory.path, 'backgrounds'));

  final Directory directory;
  final BackgroundSettings Function() current;
  final _gate = AsyncGate();
  final _drafts = <Object, Set<String>>{};

  Object beginDraft() {
    final token = Object();
    _drafts[token] = {...current().ids};
    return token;
  }

  File file(String id) {
    require(validBackgroundId(id), '背景图片标识无效');
    return File(p.join(directory.path, id));
  }

  Future<BackgroundPicture> importFile(XFile source, Object draft) async {
    require(
      await source.length() <= backgroundSourceMaxBytes,
      '请选择不超过 24 MB 的图片',
    );
    // Cap reads as well as metadata; some Android document providers omit size.
    final builder = BytesBuilder(copy: false);
    await for (final chunk in source.openRead()) {
      require(
        builder.length + chunk.length <= backgroundSourceMaxBytes,
        '请选择不超过 24 MB 的图片',
      );
      builder.add(chunk);
    }
    final bytes = await prepareBackgroundImage(builder.takeBytes());
    return _save(bytes, draft);
  }

  Future<BackgroundPicture> _save(Uint8List bytes, Object draft) =>
      _gate.run(() async {
        require(_drafts.containsKey(draft), '已取消背景设置');
        final (width, height) = backgroundPngSize(bytes);
        final id = '${sha256.convert(bytes)}.png';
        await directory.create(recursive: true);
        final target = file(id);
        final intact =
            await target.exists() &&
            await target.length() == bytes.length &&
            '${sha256.convert(await target.readAsBytes())}.png' == id;
        if (!intact) {
          final pending = File('${target.path}.tmp');
          try {
            await pending.writeAsBytes(bytes, flush: true);
            await pending.rename(target.path);
          } finally {
            if (await pending.exists()) await pending.delete();
          }
        }
        // The user may close the editor while the file write is finishing.
        _drafts[draft]?.add(id);
        return BackgroundPicture(id: id, width: width, height: height);
      });

  Future<Json> exportImages(BackgroundSettings settings) => _gate.run(() async {
    final result = <String, dynamic>{};
    for (final id in settings.ids) {
      final source = file(id);
      require(await source.exists(), '背景图片已丢失，请重新选择或取消携带背景图');
      require(await source.length() <= backgroundMaxBytes, '背景图片过大');
      final bytes = await source.readAsBytes();
      require('${sha256.convert(bytes)}.png' == id, '背景图片已损坏，请重新选择');
      backgroundPngSize(bytes);
      result[id] = base64Encode(bytes);
    }
    return result;
  });

  Future<BackgroundSettings> restoreImages(
    BackgroundSettings settings,
    Object? raw,
    Object draft,
  ) async {
    if (raw == null) return settings.withoutImages();
    require(raw is Map && raw.length <= 2, '备份背景图片格式错误');
    final images = asJson(raw);
    require(
      images.keys.every((id) => settings.ids.contains(id)),
      '备份包含未使用的背景图片',
    );
    final pictures = <String, BackgroundPicture>{};
    // Validate every image before writing any of them.
    final decoded = <String, Uint8List>{};
    for (final id in settings.ids) {
      final encoded = images[id];
      require(
        encoded is String &&
            encoded.length <= ((backgroundMaxBytes + 2) ~/ 3) * 4,
        '备份中的背景图片缺失或过大',
      );
      Uint8List bytes;
      try {
        bytes = base64Decode(encoded as String);
      } catch (_) {
        throw const AppException('备份背景图片格式错误');
      }
      require('${sha256.convert(bytes)}.png' == id, '备份中的背景图片校验失败');
      await validateBackgroundImage(bytes);
      decoded[id] = bytes;
    }
    for (final entry in decoded.entries) {
      pictures[entry.key] = await _save(entry.value, draft);
    }
    BackgroundPicture? restore(BackgroundPicture? old) {
      if (old == null) return null;
      final restored = pictures[old.id]!;
      return restored
          .withCrop(old.phone, wide: false)
          .withCrop(old.desktop, wide: true);
    }

    return settings
        .withPicture(restore(settings.day))
        .withPicture(restore(settings.night), dark: true);
  }

  Future<void> endDraft(Object token) {
    _drafts.remove(token);
    return collectUnused();
  }

  Future<void> collectUnused() => _gate.run(() async {
    final keep = {...current().ids, for (final ids in _drafts.values) ...ids};
    try {
      if (!await directory.exists()) return;
      // Only our content-addressed files are eligible; never follow directories.
      await for (final entry in directory.list(followLinks: false)) {
        final name = p.basename(entry.path);
        if (entry is File && validBackgroundId(name) && !keep.contains(name)) {
          try {
            await entry.delete();
          } on FileSystemException {
            /* Retry next edit. */
          }
        }
      }
    } on FileSystemException {
      /* Cleanup must not interrupt applying a theme. */
    }
  });
}
