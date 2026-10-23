import 'package:path/path.dart' as p;
import 'models.dart';
import 'playback.dart';
import 'playback_source.dart';

/// A folder identity must not depend on expiring URLs or credential refreshes.
String? playbackFolder(PlaybackEntry entry) =>
    entry.settingsFolder ?? playbackSourceFolder(entry.source);

String? playbackSourceFolder(PlaybackSource? source) {
  if (source == null) return null;
  switch (source.kind) {
    case PlaybackSourceKind.cloud:
      final origin = source.cloud!;
      final session = origin.session;
      if (origin.file.parentId.isEmpty) return null;
      final account = session.accountId?.trim() ?? '';
      // An unbound personal origin must never share settings with another
      // account. Legacy sources can fall back to their credential revision.
      if (session.mode == BrowseMode.personal &&
          account.isEmpty &&
          origin.accountRevision == null) {
        return null;
      }
      return playbackKey([
        'folder-v1',
        session.platform.key,
        session.mode.name,
        account.isNotEmpty ? account : origin.accountRevision,
        session.rootId,
        session.familyId,
        session.personalSpaceId,
        session.sourceLink?.shareId,
        origin.file.parentId,
      ]);
    case PlaybackSourceKind.local:
      final path = source.path;
      if (path.isEmpty) return null;
      final uri = Uri.tryParse(path);
      if (uri?.scheme == 'content') {
        // Only document IDs describe directories; media-store row IDs do not.
        final segments = uri!.pathSegments;
        final at = segments.indexOf('document');
        if (at < 0 || at + 1 >= segments.length) return null;
        final document = segments[at + 1];
        if (!document.contains('/')) return null;
        return playbackKey([
          'document-folder',
          uri.authority,
          p.posix.dirname(document),
        ]);
      }
      if (uri?.scheme == 'file') {
        return _localFolder(
          uri!.toFilePath(windows: RegExp(r'^/[A-Za-z]:').hasMatch(uri.path)),
        );
      }
      return _localFolder(path);
    case PlaybackSourceKind.torrent:
      return playbackKey([
        'torrent-folder',
        source.torrentHash,
        p.posix.dirname(source.path.replaceAll('\\', '/')),
      ]);
    case PlaybackSourceKind.download:
      return null;
  }
}

String? _localFolder(String path) {
  final windows = RegExp(r'^[A-Za-z]:[\\/]|^\\\\').hasMatch(path);
  final context = p.Context(style: windows ? p.Style.windows : p.Style.posix);
  if (!context.isAbsolute(path)) return null;
  final folder = context.normalize(context.dirname(path));
  return playbackKey(['local-folder', windows ? folder.toLowerCase() : folder]);
}
