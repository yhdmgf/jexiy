import '../domain/playback.dart';

String playlistOrderLabel(PlaybackPlaylistOrder order) => switch (order) {
  PlaybackPlaylistOrder.source => '文件顺序',
  PlaybackPlaylistOrder.ascending => '集数正序',
  PlaybackPlaylistOrder.descending => '集数倒序',
};

List<int> orderedPlaybackIndices(
  List<PlaybackEntry> entries,
  PlaybackPlaylistOrder order,
) {
  final result = List.generate(entries.length, (index) => index);
  if (order == PlaybackPlaylistOrder.source) return List.unmodifiable(result);
  final parts = entries.map((entry) => _parts(entry.name)).toList();
  result.sort((a, b) {
    final compared = _compare(parts[a], parts[b]);
    // Duplicate names retain their source order in either direction.
    if (compared == 0) return a.compareTo(b);
    return order == PlaybackPlaylistOrder.ascending ? compared : -compared;
  });
  return List.unmodifiable(result);
}

List<String> _parts(String name) {
  final normalized = name.toLowerCase().replaceAllMapped(
    RegExp(r'第\s*([零〇一二两三四五六七八九十百千]+)\s*([集话章期部季])'),
    (match) => '第${_chineseNumber(match[1]!)}${match[2]}',
  );
  return RegExp(
    r'\d+|\D+',
  ).allMatches(normalized).map((match) => match[0]!).toList();
}

int _compare(List<String> a, List<String> b) {
  for (var i = 0; i < a.length && i < b.length; i++) {
    final x = a[i], y = b[i];
    final numberX = BigInt.tryParse(x), numberY = BigInt.tryParse(y);
    final result = numberX != null && numberY != null
        ? numberX.compareTo(numberY)
        : x.compareTo(y);
    if (result != 0) return result;
  }
  return a.length.compareTo(b.length);
}

int _chineseNumber(String value) {
  const digits = {
    '零': 0,
    '〇': 0,
    '一': 1,
    '二': 2,
    '两': 2,
    '三': 3,
    '四': 4,
    '五': 5,
    '六': 6,
    '七': 7,
    '八': 8,
    '九': 9,
  };
  const units = {'十': 10, '百': 100, '千': 1000};
  var total = 0, digit = 0;
  for (final char in value.split('')) {
    final unit = units[char];
    if (unit == null) {
      digit = digit * 10 + (digits[char] ?? 0);
    } else {
      total += (digit == 0 ? 1 : digit) * unit;
      digit = 0;
    }
  }
  return total + digit;
}
