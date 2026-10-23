import 'dart:io';
import 'dart:typed_data';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:asterlink/app_services.dart';
import 'package:asterlink/data/state_store.dart';
import 'package:asterlink/domain/settings.dart';
import 'package:asterlink/main.dart';
import 'package:asterlink/ui/guest_download_prompt.dart';
import 'support.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final active = <AppServices>[];
  void guestTest(String name, WidgetTesterCallback body) {
    testWidgets(name, (tester) async {
      try {
        await body(tester);
      } finally {
        await tester.pumpWidget(const SizedBox.shrink());
        for (final services in active) {
          await services.close();
        }
        active.clear();
      }
    });
  }

  AppServices servicesFor(StateStore store) => AppServices(
    store: store,
    dataDirectory: Directory('test-fixture'),
    cacheDirectory: Directory('test-fixture/cache'),
    transport: FakeNative(),
    files: FakeFiles(Directory('test-fixture/saved')),
    http: FakeHttp(),
    platformFeatures: false,
    controlEnabled: false,
  );

  Future<AppServices> render(WidgetTester tester, {StateStore? store}) async {
    final services = servicesFor(store ?? StateStore.memory({}));
    active.add(services);
    await tester.pumpWidget(
      MaterialApp(
        theme: appTheme(Brightness.light),
        home: Scaffold(body: GuestDownloadPrompt(services)),
      ),
    );
    await tester.pumpAndSettle();
    return services;
  }

  guestTest('Shows the guest warning and merges concurrent download notices', (
    tester,
  ) async {
    final services = await render(tester);
    expect(find.byType(AlertDialog), findsNothing);
    services.requestGuestDownloadNotice();
    services.requestGuestDownloadNotice();
    await tester.pumpAndSettle();
    expect(find.text('游客下载提示'), findsOneWidget);
    expect(find.textContaining('不能保证稳定性和速度'), findsOneWidget);
    expect(find.textContaining('登录对应网盘账号后再下载'), findsOneWidget);
    expect(find.text('不再显示'), findsOneWidget);
    await tester.tap(find.text('知道了'));
    await tester.pumpAndSettle();
    expect(find.byType(AlertDialog), findsNothing);
    expect(services.settings.hideGuestDownloadNotice, isFalse);
    services.requestGuestDownloadNotice();
    await tester.pumpAndSettle();
    expect(find.byType(AlertDialog), findsOneWidget);
    await tester.tap(find.text('知道了'));
    await tester.pumpAndSettle();
  });

  guestTest(
    'The application shell presents notices from the download manager',
    (tester) async {
      final services = await render(tester);
      await tester.pumpWidget(AsterLinkApp(services));
      await tester.pumpAndSettle();
      services.downloads.onGuestDownload!();
      await tester.pumpAndSettle();
      expect(find.text('游客下载提示'), findsOneWidget);
      await tester.tap(find.text('知道了'));
      await tester.pumpAndSettle();
      expect(find.byType(AlertDialog), findsNothing);
    },
  );

  guestTest(
    'Do not show again survives settings updates and app reconstruction',
    (tester) async {
      final store = StateStore.memory({});
      final services = await render(tester, store: store);
      services.requestGuestDownloadNotice();
      await tester.pumpAndSettle();
      await tester.tap(
        find.byKey(const ValueKey('hide-guest-download-notice')),
      );
      await tester.tap(find.text('知道了'));
      await tester.pumpAndSettle();
      expect(services.settings.hideGuestDownloadNotice, isTrue);
      await services.updateSettings({'threads': 32});
      expect(services.settings.hideGuestDownloadNotice, isTrue);
      services.requestGuestDownloadNotice();
      await tester.pumpAndSettle();
      expect(find.byType(AlertDialog), findsNothing);
      final reopened = await render(
        tester,
        store: StateStore.memory(store.data),
      );
      reopened.requestGuestDownloadNotice();
      await tester.pumpAndSettle();
      expect(find.byType(AlertDialog), findsNothing);
    },
  );

  guestTest('Background downloads defer the notice until foreground', (
    tester,
  ) async {
    final services = await render(tester);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
    services.requestGuestDownloadNotice();
    await tester.pumpAndSettle();
    expect(find.byType(AlertDialog), findsNothing);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await tester.pumpAndSettle();
    expect(find.byType(AlertDialog), findsOneWidget);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
    await tester.pumpAndSettle();
    // Backgrounding suspends frames; resume must retain one pending prompt.
    expect(services.guestDownloadNotice.value, isTrue);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await tester.pumpAndSettle();
    expect(find.byType(AlertDialog), findsOneWidget);
    await tester.tap(find.text('知道了'));
    await tester.pumpAndSettle();
  });

  guestTest('Warning fits a small phone with large text', (tester) async {
    tester.view.physicalSize = const Size(320, 568);
    tester.view.devicePixelRatio = 1;
    tester.platformDispatcher.textScaleFactorTestValue = 1.8;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
    final services = await render(tester);
    services.requestGuestDownloadNotice();
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    await tester.tap(find.text('知道了'));
    await tester.pumpAndSettle();
  });

  test('The preference survives encrypted state reopening', () async {
    final directory = await Directory.systemTemp.createTemp(
      'aster-guest-notice-',
    );
    addTearDown(() => directory.delete(recursive: true));
    final key = Uint8List.fromList(List.generate(32, (index) => index));
    final store = await StateStore.open(directory, testKey: key);
    expect(AppSettings.fromJson({}).hideGuestDownloadNotice, isFalse);
    await store.change((draft) {
      draft['settings'] = const AppSettings(
        hideGuestDownloadNotice: true,
      ).toJson();
    });
    await store.flush();
    final reopened = await StateStore.open(directory, testKey: key);
    expect(
      AppSettings.fromJson(
        Map<String, dynamic>.from(reopened.data['settings'] as Map),
      ).hideGuestDownloadNotice,
      isTrue,
    );
  });
}
