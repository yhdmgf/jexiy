import 'dart:async';

class DownloadRequestContext {
  const DownloadRequestContext({
    required this.id,
    required this.retries,
    this.onRetry,
    this.onGuestDownload,
  });

  static final _key = Object();
  static DownloadRequestContext? get current =>
      Zone.current[_key] as DownloadRequestContext?;
  final String id;
  final int retries;
  final Future<void> Function(Duration)? onRetry;
  final void Function()? onGuestDownload;

  Future<T> run<T>(Future<T> Function() action) =>
      runZoned(action, zoneValues: {_key: this});
}
