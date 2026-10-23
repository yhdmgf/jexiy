import 'package:flutter_test/flutter_test.dart';
import 'package:asterlink/download/aliyun_transfer_recovery.dart';

void main() {
  bool observe(
    AliyunTransferRecovery policy,
    int second, {
    int bytes = 1024,
    bool eligible = true,
    int retries = 3,
    int limit = 4,
  }) => policy.observe(
    now: Duration(seconds: second),
    downloaded: bytes,
    total: 1024 * 1024,
    currentLimit: limit,
    retries: retries,
    canRecover: eligible,
  );

  test('Aliyun reconnects only after 45 seconds without saved progress', () {
    final policy = AliyunTransferRecovery()..start(Duration.zero, 1024);
    for (final second in [10, 20, 30, 40]) {
      expect(observe(policy, second), isFalse);
    }
    expect(observe(policy, 45), isTrue);
    expect(policy.connections(8), 2);
    expect(policy.connections(1), 1);
    policy.start(const Duration(seconds: 50), 1024);
    for (final second in [60, 70, 80, 90]) {
      expect(observe(policy, second, limit: 2), isFalse);
    }
    expect(observe(policy, 95, limit: 2), isTrue);
    expect(policy.connections(8), 1);
    for (var second = 100; second <= 300; second += 10) {
      expect(observe(policy, second), isFalse);
    }
    expect(policy.attempts, 2);
  });

  test('Slow progress is not treated as a stuck download', () {
    final policy = AliyunTransferRecovery()..start(Duration.zero, 1024);
    for (var second = 10; second <= 300; second += 10) {
      expect(observe(policy, second, bytes: 1024 + second), isFalse);
    }
    expect(policy.attempts, 0);
  });

  test('Server cooldown and queued connections do not consume stall time', () {
    final policy = AliyunTransferRecovery()..start(Duration.zero, 1024);
    for (var second = 10; second <= 120; second += 10) {
      expect(observe(policy, second, eligible: false), isFalse);
    }
    for (final second in [130, 140, 150, 160]) {
      expect(observe(policy, second), isFalse);
    }
    expect(observe(policy, 165), isTrue);
  });

  test('App suspension and cancelled or limited retries do not loop', () {
    final policy = AliyunTransferRecovery()..start(Duration.zero, 1024);
    expect(observe(policy, 120), isFalse);
    for (final second in [130, 140, 150, 160, 170]) {
      expect(observe(policy, second, retries: 0), isFalse);
    }
    expect(policy.attempts, 0);
    expect(observe(policy, 180, retries: 1), isTrue);
    policy.start(const Duration(seconds: 180), 1024);
    for (var second = 190; second < 300; second += 10) {
      expect(observe(policy, second, retries: 1), isFalse);
    }
    expect(policy.attempts, 1);
  });
}
