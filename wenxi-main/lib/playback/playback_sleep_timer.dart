import 'dart:async';
import 'package:flutter/foundation.dart';

enum PlaybackSleepMode { off, duration, episode }

/// Session-only state. Reopening the player never restores a stale timer.
class PlaybackSleepTimer extends ChangeNotifier {
  PlaybackSleepTimer({required this.onExpired, DateTime Function()? now})
    : _now = now ?? DateTime.now;
  final Future<void> Function() onExpired;
  final DateTime Function() _now;
  PlaybackSleepMode mode = PlaybackSleepMode.off;
  DateTime? deadline;
  bool stopped = false, _disposed = false;
  int stopRevision = 0;
  Timer? _deadlineTimer, _tick;
  Future<void>? _stopping;
  Duration? get remaining => deadline == null
      ? null
      : Duration(
          milliseconds: deadline!
              .difference(_now())
              .inMilliseconds
              .clamp(0, 43200000),
        );

  void schedule(Duration duration) {
    if (_disposed ||
        duration <= Duration.zero ||
        duration > const Duration(hours: 12)) {
      return;
    }
    _clearTimers();
    stopped = false;
    mode = PlaybackSleepMode.duration;
    deadline = _now().add(duration);
    _deadlineTimer = Timer(duration, _fire);
    _tick = Timer.periodic(const Duration(seconds: 1), (_) {
      checkDeadline();
      if (!_disposed) notifyListeners();
    });
    notifyListeners();
  }

  void afterEpisode() {
    if (_disposed) return;
    _clearTimers();
    stopped = false;
    mode = PlaybackSleepMode.episode;
    notifyListeners();
  }

  void cancel() {
    if (_disposed) return;
    _clearTimers();
    mode = PlaybackSleepMode.off;
    // Cancelling a timer is not a command to resume already stopped playback.
    notifyListeners();
  }

  void acknowledgePlay() {
    if (_disposed || !stopped) return;
    stopped = false;
    notifyListeners();
  }

  void checkDeadline() {
    if (mode == PlaybackSleepMode.duration &&
        deadline != null &&
        !_now().isBefore(deadline!)) {
      _fire();
    }
  }

  Future<void> finishEpisode() async {
    if (mode == PlaybackSleepMode.episode) _fire();
    await _stopping;
  }

  void _fire() {
    if (_disposed || mode == PlaybackSleepMode.off) return;
    _clearTimers();
    mode = PlaybackSleepMode.off;
    stopped = true;
    stopRevision++;
    _stopping = onExpired();
    notifyListeners();
  }

  void _clearTimers() {
    _deadlineTimer?.cancel();
    _tick?.cancel();
    _deadlineTimer = _tick = null;
    deadline = null;
  }

  Future<void> settle() async => await _stopping;

  @override
  void dispose() {
    _disposed = true;
    _clearTimers();
    super.dispose();
  }
}
