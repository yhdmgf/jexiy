import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:ui' as ui;
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:asterlink/data/announcement_service.dart';
import 'package:asterlink/data/remote_control_http.dart';
import 'package:asterlink/data/state_store.dart';
import 'package:asterlink/main.dart';
import 'package:asterlink/ui/announcements_page.dart';
import 'package:asterlink/ui/remote_control_dialogs.dart';
import 'announcements_support.dart';
import 'remote_control_support.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  WidgetController.hitTestWarningShouldBeFatal = true;
  late StateStore store;
  late AnnouncementService service;
  late FakeControlFetcher fetcher;
  var systemFont = false;

  setUpAll(() async {
    for (final (family, asset) in [
      ('MaterialIcons', 'fonts/MaterialIcons-Regular.otf'),
      (
        'packages/cupertino_icons/CupertinoIcons',
        'packages/cupertino_icons/assets/CupertinoIcons.ttf',
      ),
    ]) {
      await (FontLoader(family)..addFont(rootBundle.load(asset))).load();
    }
    final font = File('C:/Windows/Fonts/msyh.ttc');
    if (await font.exists()) {
      await (FontLoader('AnnouncementFixture')..addFont(
            Future.value(ByteData.sublistView(await font.readAsBytes())),
          ))
          .load();
      systemFont = true;
    }
  });

  Future<void> render(
    WidgetTester tester, {
    bool cached = true,
    bool dark = false,
    double scale = 1,
    Size size = const Size(393, 852),
    RemoteLinkLauncher? launcher,
    FutureOr<String> Function(Uri)? respond,
  }) async {
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = size;
    tester.platformDispatcher.textScaleFactorTestValue = scale;
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
    store = StateStore.memory({
      if (cached)
        AnnouncementService.cacheKey: {
          'endpoint': announcementsEndpoint,
          'fetchedAt': announcementsNow.millisecondsSinceEpoch,
          'feed': announcementsFixture(),
          'read': <String>[],
        },
    });
    fetcher = FakeControlFetcher(announcementsFixture())..respond = respond;
    service = AnnouncementService(
      store,
      url: announcementsEndpoint,
      fetcher: fetcher,
      clock: () => announcementsNow,
    );
    await tester.pumpWidget(
      RepaintBoundary(
        key: const Key('announcement-capture'),
        child: MaterialApp(
          debugShowCheckedModeBanner: false,
          theme:
              appTheme(
                dark ? Brightness.dark : Brightness.light,
                fontFamily: systemFont ? 'AnnouncementFixture' : null,
              ).copyWith(
                platform: size.width > 800
                    ? TargetPlatform.windows
                    : TargetPlatform.android,
              ),
          home: Scaffold(
            appBar: AppBar(
              title: const Text('分享解析'),
              actions: [AnnouncementButton(service, linkLauncher: launcher)],
            ),
            body: const SizedBox(),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
  }

  void scenario(String name, Future<void> Function(WidgetTester) body) {
    testWidgets(name, (tester) async {
      try {
        await body(tester);
      } finally {
        await tester.pumpWidget(const SizedBox());
        await service.close();
        store.dispose();
      }
    });
  }

  Future<void> openList(WidgetTester tester) async {
    await tester.tap(find.byKey(const Key('parse-announcements')));
    await tester.pumpAndSettle();
  }

  Future<void> capture(WidgetTester tester, String name) async {
    if (!const bool.fromEnvironment('ANNOUNCEMENT_CAPTURE_PREVIEWS')) return;
    final boundary = tester.renderObject<RenderRepaintBoundary>(
      find.byKey(const Key('announcement-capture')),
    );
    await tester.runAsync(() async {
      final picture = await boundary.toImage(pixelRatio: 1);
      final data = await picture.toByteData(format: ui.ImageByteFormat.png);
      final file = File('.local/announcements-review/$name.png');
      await file.parent.create(recursive: true);
      await file.writeAsBytes(data!.buffer.asUint8List());
      picture.dispose();
    });
  }

  scenario('Bell opens a multi-message list and read state updates its badge', (
    tester,
  ) async {
    await render(tester);
    expect(tester.widget<Badge>(find.byType(Badge)).isLabelVisible, isTrue);
    await openList(tester);
    expect(find.text('共 3 条公告'), findsOneWidget);
    expect(find.text('3 条未读'), findsOneWidget);
    await capture(tester, 'phone-list');
    await tester.tap(find.text('设备互联使用提示'));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('announcement-detail-page')), findsOneWidget);
    for (final paragraph in deviceAnnouncementContent.split('\n\n')) {
      expect(find.text(paragraph), findsOneWidget);
    }
    expect(service.unreadCount, 2);
    await capture(tester, 'phone-detail');
    await tester.pageBack();
    await tester.pumpAndSettle();
    await tester.tap(find.byKey(const Key('announcements-read-all')));
    await tester.pumpAndSettle();
    expect(find.text('已全部阅读'), findsOneWidget);
    await tester.pageBack();
    await tester.pumpAndSettle();
    expect(tester.widget<Badge>(find.byType(Badge)).isLabelVisible, isFalse);
    expect(tester.takeException(), isNull);
  });

  scenario('Cached messages remain readable during a failed refresh', (
    tester,
  ) async {
    await render(
      tester,
      respond: (_) => throw const ControlAccessException('offline'),
    );
    await openList(tester);
    expect(find.textContaining('上次保存'), findsOneWidget);
    expect(find.text('共 3 条公告'), findsOneWidget);
    await tester.tap(find.text('欢迎使用文析助手'));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('announcement-detail-page')), findsOneWidget);
    expect(tester.takeException(), isNull);
  });

  scenario(
    'Missing publication has a working retry without blocking navigation',
    (tester) async {
      await render(
        tester,
        cached: false,
        respond: (_) =>
            throw const ControlAccessException('missing', statusCode: 404),
      );
      await openList(tester);
      expect(find.text('公告文件尚未发布，请稍后再试'), findsOneWidget);
      fetcher.respond = null;
      await tester.tap(find.text('重新获取'));
      await tester.pumpAndSettle();
      expect(find.text('共 3 条公告'), findsOneWidget);
      expect(find.text('重新获取'), findsNothing);
      expect(tester.takeException(), isNull);
    },
  );

  scenario('Announcement links open only after an explicit detail action', (
    tester,
  ) async {
    final opened = <Uri>[];
    await render(
      tester,
      launcher: (url) async {
        opened.add(url);
        return true;
      },
    );
    await openList(tester);
    await tester.tap(find.text('问题反馈与维护通知'));
    await tester.pumpAndSettle();
    expect(opened, isEmpty);
    await tester.tap(find.byKey(const Key('announcement-detail-link')));
    await tester.pumpAndSettle();
    expect(opened, [Uri.parse('https://github.com/z7786/wenxi/issues')]);
  });

  scenario('Returning while refresh is pending never reopens the page', (
    tester,
  ) async {
    final pending = Completer<String>();
    await render(tester, cached: false, respond: (_) => pending.future);
    await tester.tap(find.byKey(const Key('parse-announcements')));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 400));
    await tester.pageBack();
    await tester.pump(const Duration(milliseconds: 400));
    pending.complete(jsonEncode(announcementsFixture()));
    await tester.pumpAndSettle();
    expect(find.byKey(const Key('announcements-page')), findsNothing);
    expect(find.text('分享解析'), findsOneWidget);
    expect(service.unreadCount, 3);
    expect(tester.takeException(), isNull);
  });

  for (final (name, size, dark, scale) in [
    ('phone-dark', const Size(393, 852), true, 1.0),
    ('phone-large-text', const Size(320, 700), false, 1.8),
    ('desktop-list', const Size(1080, 760), false, 1.0),
  ]) {
    scenario('$name message list and detail fit the viewport', (tester) async {
      await render(tester, size: size, dark: dark, scale: scale);
      await openList(tester);
      expect(find.text('欢迎使用文析助手'), findsOneWidget);
      expect(tester.takeException(), isNull);
      await capture(tester, name);
      await tester.tap(find.text('欢迎使用文析助手'));
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('announcement-detail-page')), findsOneWidget);
      expect(tester.takeException(), isNull);
    });
  }
}
