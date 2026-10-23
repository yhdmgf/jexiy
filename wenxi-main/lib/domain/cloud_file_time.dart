// Providers return local dates, ISO dates, compact dates, or Unix timestamps.
DateTime? cloudFileDate(String value) {
  final raw = value.trim();
  if (raw.isEmpty || raw.length > 64) return null;
  final compact = RegExp(
    r'^(\d{4})(\d{2})(\d{2})(?:(\d{2})(\d{2})(\d{2}))?$',
  ).firstMatch(raw);
  if (compact != null) {
    final year = int.parse(compact[1]!);
    if (year >= 1970 && year <= 9999) {
      return _calendarDate(
        year,
        int.parse(compact[2]!),
        int.parse(compact[3]!),
        int.parse(compact[4] ?? '0'),
        int.parse(compact[5] ?? '0'),
        int.parse(compact[6] ?? '0'),
      );
    }
  }
  if (RegExp(r'^-?\d+(?:\.\d+)?$').hasMatch(raw)) {
    final stamp = num.tryParse(raw);
    if (stamp == null || !stamp.isFinite || stamp <= 0) return null;
    final milliseconds = stamp < 100000000000
        ? stamp * 1000
        : stamp >= 100000000000000
        ? stamp / 1000
        : stamp;
    // Bound corrupt values before calling DateTime's platform constructor.
    if (milliseconds > 253402214400000) return null;
    return DateTime.fromMillisecondsSinceEpoch(milliseconds.round()).toLocal();
  }
  final normalized = raw.replaceAll('/', '-');
  final fields = RegExp(
    r'^(\d{4})-(\d{1,2})-(\d{1,2})(?:[ T](\d{1,2}):(\d{2})(?::(\d{2})(?:\.\d+)?)?)?(Z|[+-]\d{2}:?\d{2})?$',
    caseSensitive: false,
  ).firstMatch(normalized);
  if (fields == null) return null;
  final year = int.parse(fields[1]!),
      month = int.parse(fields[2]!),
      day = int.parse(fields[3]!);
  final hour = int.parse(fields[4] ?? '0'),
      minute = int.parse(fields[5] ?? '0'),
      second = int.parse(fields[6] ?? '0');
  final local = _calendarDate(year, month, day, hour, minute, second);
  if (local == null) return null;
  if (fields[7] == null) return local;
  final zone = fields[7]!.toUpperCase();
  if (zone != 'Z') {
    final digits = zone.substring(1).replaceAll(':', '');
    if (int.parse(digits.substring(0, 2)) > 23 ||
        int.parse(digits.substring(2)) > 59) {
      return null;
    }
  }
  return DateTime.tryParse(
    '${_date(local)}T${_two(hour)}:${_two(minute)}:${_two(second)}$zone',
  )?.toLocal();
}

DateTime? _calendarDate(
  int year,
  int month,
  int day,
  int hour,
  int minute,
  int second,
) {
  if (year < 1970 ||
      year > 9999 ||
      month < 1 ||
      month > 12 ||
      day < 1 ||
      day > 31 ||
      hour < 0 ||
      hour > 23 ||
      minute < 0 ||
      minute > 59 ||
      second < 0 ||
      second > 59) {
    return null;
  }
  final date = DateTime(year, month, day, hour, minute, second);
  return date.year == year && date.month == month && date.day == day
      ? date
      : null;
}

String _two(int value) => '$value'.padLeft(2, '0');
String _date(DateTime date) =>
    '${date.year}-${_two(date.month)}-${_two(date.day)}';

String formatCloudFileTime(String value) {
  final raw = value.trim();
  final date = cloudFileDate(raw);
  if (date != null) {
    if (RegExp(r'^\d{4}[-/]\d{1,2}[-/]\d{1,2}$|^\d{8}$').hasMatch(raw)) {
      return _date(date);
    }
    return '${_date(date)} ${_two(date.hour)}:${_two(date.minute)}';
  }
  // Keep the provider's relative label without inventing an exact timestamp.
  return RegExp(
        r'^(?:刚刚|今天|昨天|前天|\d+\s*(?:秒|分钟|小时|天|周|个月|月|年)前)(?:\s+\d{1,2}:\d{2})?$',
      ).hasMatch(raw)
      ? raw
      : '';
}
