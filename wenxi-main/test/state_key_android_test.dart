import 'dart:convert';
import 'dart:io';
import 'package:flutter/services.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:asterlink/core/crypto_box.dart';
import 'package:asterlink/data/backup.dart';
import 'package:asterlink/data/startup_recovery.dart';
import 'package:asterlink/data/state_key.dart';
import 'package:asterlink/data/state_store.dart';
import 'package:asterlink/diagnostics/app_log.dart';

class _LegacyStorage extends FlutterSecureStorage {
  bool broken = false;
  String failure = 'InvalidKeyException';
  @override
  Future<String?> read({
    required String key,
    AppleOptions? iOptions,
    AndroidOptions? aOptions,
    LinuxOptions? lOptions,
    WebOptions? webOptions,
    AppleOptions? mOptions,
    WindowsOptions? wOptions,
  }) async {
    if (broken) {
      throw PlatformException(code: 'Exception', message: failure);
    }
    return super.read(key: key, aOptions: aOptions);
  }
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late Directory directory;
  late _LegacyStorage legacy;
  // Native disk/keystore are shared between fresh Dart facades. Their real
  // persistence and cold unwrap behavior are separately tested in Kotlin.
  late Map<String, Map<bool, Uint8List>> records;
  late Set<String> blocked;
  late bool failColdRead;
  late int writes;
  LocalStateKey keys() => LocalStateKey(directory, legacy, nativeAndroid: true);
  Future<StateStore> open() => StateStore.open(directory, localKey: keys());
  File file(String name) => File('${directory.path}/$name');
  Future<Map<String, dynamic>> location() async =>
      jsonDecode(await file(LocalStateKey.locationName).readAsString())
          as Map<String, dynamic>;
  StartupRecovery recovery() => StartupRecovery(
    StateRecoveryRequired(
      directory,
      StateRecoveryCause.secureStorageUnavailable,
    ),
    storage: legacy,
    localKey: keys(),
  );
  Future<Uint8List> backup() async {
    final source = StateStore.memory({
      'settings': {'theme': 'Dark'},
    });
    try {
      return await BackupRepository(source).create('fixture-password');
    } finally {
      source.dispose();
    }
  }

  setUp(() async {
    directory = await Directory.systemTemp.createTemp('state-native-');
    legacy = _LegacyStorage();
    FlutterSecureStorage.setMockInitialValues({});
    records = {};
    blocked = {};
    failColdRead = false;
    writes = 0;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(LocalStateKey.channel, (call) async {
          final args = Map<String, dynamic>.from(call.arguments as Map);
          final namespace = args['namespace'] as String;
          if (call.method == 'writeStateKey') {
            writes++;
            final value = args['value'] as Uint8List;
            records[namespace] = {
              false: Uint8List.fromList(value),
              true: Uint8List.fromList(value),
            };
            if (failColdRead) blocked.add(namespace);
            return true;
          }
          if (call.method == 'readStateKey') {
            if (blocked.contains(namespace)) {
              throw PlatformException(
                code: 'state_key_storage',
                details: 'UnrecoverableKeyException',
              );
            }
            return records[namespace]?[args['copy'] == true];
          }
          fail('Unexpected native method ${call.method}');
        });
  });
  tearDown(() async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(LocalStateKey.channel, null);
    await directory.delete(recursive: true);
  });

  test(
    'storage diagnostics retain category without logging secret payload',
    () async {
      final log = DiagnosticLog.open(null);
      DiagnosticLog.active = log;
      try {
        legacy.broken = true;
        legacy.failure = 'InvalidKeyException: sensitive-fixture-key-value';
        final saved = await keys().read();
        expect(saved.unavailable, isTrue);
        final events = jsonEncode(
          log.entries().map((entry) => entry.data).toList(),
        );
        expect(events, contains('InvalidKeyException'));
        expect(events, isNot(contains('sensitive-fixture-key-value')));
      } finally {
        DiagnosticLog.active = null;
        log.close();
      }
    },
  );

  test(
    'missing records behind a native pointer cannot silently initialize',
    () async {
      final first = await open();
      first.dispose();
      final pointer = await file(LocalStateKey.locationName).readAsBytes();
      records.clear();
      await expectLater(open(), throwsA(isA<StateRecoveryRequired>()));
      expect(writes, 1);
      expect(await file(LocalStateKey.locationName).readAsBytes(), pointer);
    },
  );

  test(
    'first launch survives repeated process recreation without legacy backend',
    () async {
      final first = await open();
      await first.put('favorite', 'kept');
      first.dispose();
      expect((await location())['backend'], LocalStateKey.nativeBackend);
      legacy.broken = true;
      for (var i = 0; i < 4; i++) {
        final next = await open();
        expect(next.data['favorite'], 'kept');
        await next.put('restart', i);
        next.dispose();
      }
      expect(writes, 1);
    },
  );

  test(
    'first write failing cold read publishes neither pointer nor state',
    () async {
      failColdRead = true;
      await expectLater(open(), throwsA(isA<PlatformException>()));
      expect(await file(LocalStateKey.locationName).exists(), isFalse);
      expect(await file('state-v1.enc').exists(), isFalse);
      failColdRead = false;
      final next = await open();
      await next.put('favorite', 'retry');
      next.dispose();
      final restarted = await open();
      expect(restarted.data['favorite'], 'retry');
      restarted.dispose();
    },
  );

  test(
    'healthy legacy users keep original key and storage across repeated launches',
    () async {
      final old = await StateStore.open(directory, storage: legacy);
      await old.put('favorite', 'legacy');
      old.dispose();
      final oldKey = await legacy.read(key: StateStore.keyName);
      final encrypted = await file('state-v1.enc').readAsBytes();
      failColdRead = true;
      for (var i = 0; i < 4; i++) {
        final next = await open();
        expect(next.data['favorite'], 'legacy');
        next.dispose();
      }
      expect(await legacy.read(key: StateStore.keyName), oldKey);
      expect(await file('state-v1.enc').readAsBytes(), encrypted);
      expect(writes, 0);
      expect(await file(LocalStateKey.locationName).exists(), isFalse);
    },
  );

  test(
    'healthy namespaced legacy users keep pointer and import without migration',
    () async {
      final old = await StateStore.open(directory, storage: legacy);
      await old.put('favorite', 'legacy');
      old.dispose();
      final oldKey = base64Decode(
        (await legacy.read(key: StateStore.keyName))!,
      );
      await LocalStateKey(
        directory,
        legacy,
        nativeAndroid: false,
      ).saveFresh(oldKey);
      final pointer = await file(LocalStateKey.locationName).readAsBytes();
      failColdRead = true;
      final readable = await open();
      expect(readable.data['favorite'], 'legacy');
      await BackupRepository(
        readable,
      ).restore(await backup(), 'fixture-password');
      readable.dispose();
      final next = await open();
      expect(next.data['favorite'], 'legacy');
      expect((next.data['settings'] as Map)['theme'], 'Dark');
      next.dispose();
      expect(await file(LocalStateKey.locationName).readAsBytes(), pointer);
      expect(writes, 0);
    },
  );

  test('wrong legacy key cannot migrate or overwrite encrypted data', () async {
    final old = await StateStore.open(directory, storage: legacy);
    await old.put('favorite', 'old');
    old.dispose();
    final encrypted = await file('state-v1.enc').readAsBytes();
    final wrongKey = base64Encode(CryptoBox.random(32));
    FlutterSecureStorage.setMockInitialValues({
      StateStore.keyName: wrongKey,
      '${StateStore.keyName}.copy': wrongKey,
    });
    await expectLater(open(), throwsA(isA<StateRecoveryRequired>()));
    expect(writes, 0);
    expect(await file('state-v1.enc').readAsBytes(), encrypted);
  });

  for (final namespaced in [false, true]) {
    test(
      'native failure falls back to authenticated legacy data (namespace=$namespaced)',
      () async {
        final old = await StateStore.open(directory, storage: legacy);
        await old.put('favorite', 'old');
        await old.put('favorite', 'latest');
        old.dispose();
        final key = base64Decode((await legacy.read(key: StateStore.keyName))!);
        if (namespaced) {
          await LocalStateKey(
            directory,
            legacy,
            nativeAndroid: false,
          ).saveFresh(key);
        }
        final oldNamespace = namespaced
            ? (await location())['namespace']
            : null;
        // Explicitly simulate a completed switch. Ordinary startup never switches.
        await keys().saveFresh(key);
        final nativeNamespace = (await location())['namespace'] as String;
        expect((await location())['fallback'], {'namespace': oldNamespace});
        final encrypted = await file('state-v1.enc').readAsBytes();
        blocked.add(nativeNamespace);
        final next = await open();
        expect(next.data['favorite'], 'latest');
        await next.put('after-fallback', true);
        next.dispose();
        expect((await location())['namespace'], oldNamespace);
        expect((await location())['backend'], isNull);
        expect(records.containsKey(nativeNamespace), isTrue);
        for (var i = 0; i < 3; i++) {
          final restarted = await open();
          expect(restarted.data['after-fallback'], true);
          restarted.dispose();
        }
        expect(writes, 1);
        expect(await file('state-v1.enc.bak').readAsBytes(), encrypted);
      },
    );
  }

  test(
    'wrong native key falls back before considering older state backup',
    () async {
      final old = await StateStore.open(directory, storage: legacy);
      await old.put('favorite', 'latest');
      old.dispose();
      final key = base64Decode((await legacy.read(key: StateStore.keyName))!);
      await keys().saveFresh(key);
      final namespace = (await location())['namespace'] as String;
      records[namespace] = {
        false: CryptoBox.random(32),
        true: CryptoBox.random(32),
      };
      final recovered = await open();
      expect(recovered.data['favorite'], 'latest');
      recovered.dispose();
      expect((await location())['backend'], isNull);
    },
  );

  test(
    'stale fallback cannot roll newer state back to an older backup',
    () async {
      final old = await StateStore.open(directory, storage: legacy);
      await old.put('favorite', 'old');
      old.dispose();
      final older = await file('state-v1.enc').readAsBytes();
      final differentKey = CryptoBox.random(32);
      await keys().saveFresh(differentKey);
      // A different key must never automatically retain the old fallback.
      expect((await location())['fallback'], isNull);
      final pointer = {
        ...await location(),
        'fallback': {'namespace': null},
      };
      await file(
        LocalStateKey.locationName,
      ).writeAsString(jsonEncode(pointer), flush: true);
      final current = CryptoBox.seal(
        differentKey,
        utf8.encode('{"schema":1,"favorite":"latest"}'),
      );
      await file('state-v1.enc').writeAsBytes(current, flush: true);
      await file('state-v1.enc.bak').writeAsBytes(older, flush: true);
      blocked.add(pointer['namespace'] as String);
      await expectLater(open(), throwsA(isA<StateRecoveryRequired>()));
      expect(await file('state-v1.enc').readAsBytes(), current);
      expect(await file('state-v1.enc.bak').readAsBytes(), older);
      expect(await location(), pointer);
      // An interrupted write has a newer .tmp but no primary: still reject the
      // old .bak even though its key remains readable in the fallback store.
      await file('state-v1.enc').rename(file('state-v1.enc.tmp').path);
      await expectLater(open(), throwsA(isA<StateRecoveryRequired>()));
      expect(await file('state-v1.enc.tmp').readAsBytes(), current);
      expect(await file('state-v1.enc').exists(), isFalse);
      expect(writes, 1);
    },
  );

  test(
    'failed native switch leaves a healthy legacy pointer and state untouched',
    () async {
      final old = await StateStore.open(directory, storage: legacy);
      await old.put('favorite', 'kept');
      old.dispose();
      final key = base64Decode((await legacy.read(key: StateStore.keyName))!);
      await LocalStateKey(
        directory,
        legacy,
        nativeAndroid: false,
      ).saveFresh(key);
      final pointer = await file(LocalStateKey.locationName).readAsBytes();
      final encrypted = await file('state-v1.enc').readAsBytes();
      failColdRead = true;
      await expectLater(
        keys().saveFresh(key),
        throwsA(isA<PlatformException>()),
      );
      expect(await file(LocalStateKey.locationName).readAsBytes(), pointer);
      expect(await file('state-v1.enc').readAsBytes(), encrypted);
      final next = await open();
      expect(next.data['favorite'], 'kept');
      next.dispose();
    },
  );

  test(
    'legacy copy repair keeps the same backend when native storage fails',
    () async {
      final old = await StateStore.open(directory, storage: legacy);
      await old.put('favorite', 'kept');
      old.dispose();
      await legacy.delete(key: StateStore.keyName);
      failColdRead = true;
      final next = await open();
      expect(next.data['favorite'], 'kept');
      next.dispose();
      expect(
        await legacy.read(key: StateStore.keyName),
        await legacy.read(key: '${StateStore.keyName}.copy'),
      );
      expect(writes, 0);
      expect(await file(LocalStateKey.locationName).exists(), isFalse);
    },
  );

  test(
    'invalid optional fallback cannot break a healthy current store',
    () async {
      final first = await open();
      await first.put('favorite', 'kept');
      first.dispose();
      await file(LocalStateKey.locationName).writeAsString(
        jsonEncode({
          ...await location(),
          'fallback': {'namespace': '../invalid'},
        }),
        flush: true,
      );
      final next = await open();
      expect(next.data['favorite'], 'kept');
      next.dispose();
      expect(writes, 1);
    },
  );

  test(
    'temporary native key failure preserves data and retry reopens it',
    () async {
      final first = await open();
      await first.put('favorite', 'kept');
      first.dispose();
      final pointer = await file(LocalStateKey.locationName).readAsBytes();
      final encrypted = await file('state-v1.enc').readAsBytes();
      blocked.add((await location())['namespace'] as String);
      await expectLater(
        open(),
        throwsA(
          isA<StateRecoveryRequired>().having(
            (e) => e.cause,
            'cause',
            StateRecoveryCause.secureStorageUnavailable,
          ),
        ),
      );
      expect(writes, 1);
      expect(await file(LocalStateKey.locationName).readAsBytes(), pointer);
      expect(await file('state-v1.enc').readAsBytes(), encrypted);
      blocked.clear();
      final next = await open();
      expect(next.data['favorite'], 'kept');
      next.dispose();
    },
  );

  test(
    'damaged native record repairs from authenticated copy into fresh namespace',
    () async {
      final first = await open();
      await first.put('favorite', 'kept');
      first.dispose();
      final previous = (await location())['namespace'] as String;
      records[previous]![false] = CryptoBox.random(32);
      final repaired = await open();
      expect(repaired.data['favorite'], 'kept');
      repaired.dispose();
      expect((await location())['namespace'], isNot(previous));
      expect(records.containsKey(previous), isTrue);
      final next = await open();
      expect(next.data['favorite'], 'kept');
      next.dispose();
    },
  );

  test(
    'reinitialize bypasses broken legacy store and survives repeated restarts',
    () async {
      final old = await StateStore.open(directory, storage: legacy);
      await old.put('favorite', 'old');
      old.dispose();
      final original = await file('state-v1.enc').readAsBytes();
      legacy.broken = true;
      final result = await recovery().reinitialize();
      expect(
        await File('${result.archive.path}/state-v1.enc').readAsBytes(),
        original,
      );
      for (var i = 0; i < 3; i++) {
        final next = await open();
        expect(next.data['favorite'], isNull);
        next.dispose();
      }
      expect(writes, 1);
    },
  );

  test(
    'backup import and password unlock rebind durable native key once',
    () async {
      final first = await open();
      await BackupRepository(first).restore(await backup(), 'fixture-password');
      await first.put('after-import', true);
      first.dispose();
      blocked.add((await location())['namespace'] as String);
      await recovery().unlock('fixture-password');
      legacy.broken = true;
      for (var i = 0; i < 3; i++) {
        final next = await open();
        expect(next.data['after-import'], true);
        next.dispose();
      }
      expect(writes, 2);
    },
  );

  test(
    'failed reinitialize rolls back state, pointer and recovery envelope',
    () async {
      final first = await open();
      await BackupRepository(first).restore(await backup(), 'fixture-password');
      first.dispose();
      final originals = {
        for (final name in [
          'state-v1.enc',
          LocalStateKey.locationName,
          LocalStateKey.recoveryName,
        ])
          name: await file(name).readAsBytes(),
      };
      blocked.add((await location())['namespace'] as String);
      failColdRead = true;
      await expectLater(
        recovery().reinitialize(),
        throwsA(isA<PlatformException>()),
      );
      for (final entry in originals.entries) {
        expect(await file(entry.key).readAsBytes(), entry.value);
      }
      failColdRead = false;
      await recovery().unlock('fixture-password');
      final next = await open();
      next.dispose();
    },
  );
}
