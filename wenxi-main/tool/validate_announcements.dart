import 'dart:convert';
import 'dart:io';
import 'package:asterlink/domain/announcements.dart';

void main(List<String> args) {
  if (args.length > 1) {
    stderr.writeln(
      '用法：dart run tool/validate_announcements.dart [announcements.json]',
    );
    exitCode = 64;
    return;
  }
  try {
    final file = File(args.isEmpty ? 'announcements.json' : args.single);
    if (file.lengthSync() > AnnouncementFeed.maxBytes) {
      throw const FormatException('公告文件不能超过 256 KiB');
    }
    final feed = AnnouncementFeed.decode(file.readAsStringSync());
    if (feed.skippedItems != 0) {
      throw FormatException('有 ${feed.skippedItems} 条公告格式不正确，请检查后发布');
    }
    stdout.writeln(
      jsonEncode({'valid': true, 'announcements': feed.messages.length}),
    );
  } on FormatException catch (error) {
    stderr.writeln('公告无效：${error.message}');
    exitCode = 1;
  } on FileSystemException {
    stderr.writeln('无法读取公告文件，请检查路径和权限。');
    exitCode = 1;
  }
}
