/// A stalled original-file transfer may reconnect twice without discarding its
/// checkpoint. Waiting for an account slot or server cooldown is not a stall.
class AliyunTransferRecovery {
  static const stallWindow = Duration(seconds: 45);
  static const _maximumPollGap = Duration(seconds: 20);
  Duration _sampleAt = Duration.zero, _progressAt = Duration.zero;
  int _downloaded = 0, _maximum = 8;
  int attempts = 0;

  int connections(int requested) => requested.clamp(1, _maximum);

  void start(Duration now, int downloaded) {
    _sampleAt = _progressAt = now;
    _downloaded = downloaded;
  }

  bool observe({
    required Duration now,
    required int downloaded,
    required int total,
    required int currentLimit,
    required int retries,
    required bool canRecover,
  }) {
    final gap = now - _sampleAt;
    _sampleAt = now;
    if (gap.isNegative ||
        gap > _maximumPollGap ||
        downloaded != _downloaded ||
        !canRecover) {
      _progressAt = now;
      _downloaded = downloaded;
      return false;
    }
    if (total <= downloaded ||
        total <= 0 ||
        now - _progressAt < stallWindow ||
        attempts >= retries.clamp(0, 2)) {
      return false;
    }
    attempts++;
    _maximum = (currentLimit.clamp(1, _maximum) ~/ 2).clamp(1, 8);
    _progressAt = now;
    return true;
  }
}
