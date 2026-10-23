import 'package:asterlink/data/providers/personal_cloud.dart';
import 'package:asterlink/domain/cloud_file_time.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('Unix units and ISO time zones resolve to the same local time', () {
    final instant = DateTime.utc(2026, 10, 4, 7, 8, 9);
    for (final raw in [
      '${instant.millisecondsSinceEpoch ~/ 1000}',
      '${instant.millisecondsSinceEpoch}',
      '${instant.microsecondsSinceEpoch}',
      instant.toIso8601String(),
      '2026-10-04T15:08:09+08:00',
      '2026-10-04T15:08:09+0800',
    ]) {
      expect(cloudFileDate(raw), instant.toLocal(), reason: raw);
      expect(
        formatCloudFileTime(raw),
        formatCloudFileTime(instant.toLocal().toIso8601String()),
        reason: raw,
      );
    }
  });

  test('Local and compact dates retain their available precision', () {
    for (final raw in [
      '20261004070809',
      '2026/10/4 7:08:09',
      '2026-10-04 07:08:09',
    ]) {
      expect(cloudFileDate(raw), DateTime(2026, 10, 4, 7, 8, 9));
      expect(formatCloudFileTime(raw), '2026-10-04 07:08');
    }
    for (final raw in ['20261004', '2026/10/4', '2026-10-04']) {
      expect(cloudFileDate(raw), DateTime(2026, 10, 4));
      expect(formatCloudFileTime(raw), '2026-10-04');
    }
    expect(cloudFileDate('2024-02-29'), DateTime(2024, 2, 29));
  });

  test('Missing and corrupt timestamps never display an invented date', () {
    for (final raw in [
      '',
      '   ',
      '0',
      '-1',
      'null',
      'not a date',
      '2026-02-30',
      '20260230070809',
      '2026-13-01',
      '2026-10-04 25:00',
      '2026-10-04T07:08:09+25:00',
      '999999999999999999999999',
    ]) {
      expect(cloudFileDate(raw), isNull, reason: raw);
      expect(formatCloudFileTime(raw), isEmpty, reason: raw);
    }
    for (final raw in ['刚刚', '昨天 12:30', '3天前', '2 小时前']) {
      expect(cloudFileDate(raw), isNull);
      expect(formatCloudFileTime(raw), raw);
    }
  });

  test(
    'Personal providers handle Unix seconds without producing 1970 dates',
    () {
      final instant = DateTime(2026, 10, 4, 7, 8, 9);
      expect(
        personalCloudDate('${instant.millisecondsSinceEpoch ~/ 1000}'),
        '2026-10-04 07:08:09',
      );
      expect(
        personalCloudDate('${instant.millisecondsSinceEpoch}'),
        '2026-10-04 07:08:09',
      );
      expect(personalCloudDate('20261004070809'), '2026-10-04 07:08:09');
      expect(personalCloudDate('20261004'), '2026-10-04');
      expect(personalCloudDate('2026-10-04'), '2026-10-04');
      expect(personalCloudDate('昨天'), '昨天');
      expect(personalCloudDate('0'), '0');
    },
  );
}
