import 'dart:async';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:asterlink/app_services.dart';
import 'package:asterlink/core/json.dart';
import 'package:asterlink/data/cloud_command_resolver.dart';
import 'package:asterlink/data/state_store.dart';
import 'package:asterlink/domain/models.dart';
import 'package:asterlink/main.dart';
import 'package:asterlink/ui/browser_page.dart';
import 'package:asterlink/ui/cloud_command_page.dart';
import 'package:asterlink/ui/parse_page.dart';
import 'cloud_command_resolver_test.dart'
    show commandResponse, fixtureCommandKeys;
import 'support.dart';

class _Files extends CloudConnector {
  _Files(this.platform);
  @override
  final CloudPlatform platform;
  final opened = <ParsedLink>[];
  int reads = 0;
  @override
  Future<BrowseSession> openShare(
    ParsedLink link,
    Credential? credential,
  ) async {
    opened.add(link);
    return BrowseSession(
      platform: platform,
      mode: BrowseMode.share,
      title: '口令分享文件',
      rootId: '0',
    );
  }

  @override
  Future<List<CloudFile>> list(
    BrowseSession session,
    String parentId,
    Credential? credential,
  ) async {
    reads++;
    return const [CloudFile(id: 'file', name: '测试文档.pdf', size: 1234)];
  }

  @override
  dynamic noSuchMethod(Invocation invocation) => super.noSuchMethod(invocation);
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late AppServices services;
  late FakeHttp http;
  late Map<CloudPlatform, _Files> connectors;

  Future<void> render(
    WidgetTester tester, {
    bool home = false,
    Size size = const Size(393, 864),
    double scale = 1,
    Future<ParsedLink> Function(CloudPlatform, String)? resolve,
  }) async {
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = size;
    tester.platformDispatcher.textScaleFactorTestValue = scale;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
    http = FakeHttp(
      (r) => commandResponse(
        r.uri.host.contains('quark')
            ? CloudPlatform.quark
            : r.uri.host.contains('xunlei')
            ? CloudPlatform.xunlei
            : CloudPlatform.uc,
      ),
    );
    services = AppServices(
      store: StateStore.memory(),
      dataDirectory: Directory('command-fixture'),
      cacheDirectory: Directory('command-fixture/cache'),
      transport: FakeNative(),
      files: FakeFiles(Directory('command-fixture/files')),
      http: http,
      platformFeatures: false,
      controlEnabled: false,
    );
    connectors = {for (final p in CloudCommandResolver.platforms) p: _Files(p)};
    services.cloud.connectors.addAll(connectors);
    await tester.pumpWidget(
      MaterialApp(
        theme: appTheme(Brightness.light),
        home: home
            ? Scaffold(body: ParsePage(services))
            : CloudCommandPage(
                services,
                resolveCommand:
                    resolve ??
                    CloudCommandResolver(
                      http,
                      keys: fixtureCommandKeys,
                    ).resolve,
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
        var closed = false;
        services.close().then((_) => closed = true);
        for (var i = 0; i < 100 && !closed; i++) {
          await tester.pump(const Duration(milliseconds: 100));
          await tester.runAsync(
            () => Future<void>.delayed(const Duration(milliseconds: 10)),
          );
        }
        expect(closed, isTrue);
      }
    });
  }

  Future<void> tap(WidgetTester tester, Finder finder) async {
    await tester.ensureVisible(finder);
    await tester.pumpAndSettle();
    await tester.tap(finder);
    await tester.pumpAndSettle();
  }

  scenario(
    'Plain Chinese never triggers automatic Xunlei requests and the explicit entry retains input',
    (tester) async {
      await render(tester, home: true);
      await tester.enterText(find.byKey(const Key('parse-input')), '中文分享口令');
      await tap(tester, find.byKey(const Key('parse-start')));
      expect(http.calls, isEmpty);
      expect(find.textContaining('口令解析'), findsWidgets);
      await tap(tester, find.byKey(const Key('parse-command')));
      expect(find.byType(CloudCommandPage), findsOneWidget);
      expect(
        tester
            .widget<TextField>(find.byKey(const Key('command-input')))
            .controller!
            .text,
        '中文分享口令',
      );
      expect(http.calls, isEmpty);
    },
  );

  for (final p in CloudCommandResolver.platforms) {
    scenario(
      '${p.key} resolves directly into a preloaded share and preserves the returned code',
      (tester) async {
        await render(tester);
        await tap(tester, find.byKey(ValueKey('command-provider-${p.key}')));
        if (p == CloudPlatform.xunlei) {
          await services.store.put('credentials', {
            'Xunlei': Credential('fixture', {
              'accessToken': 'fixture-token',
            }, updatedAt: 1).toJson(),
          });
          await tester.enterText(
            find.byKey(const Key('command-input')),
            '中文分享口令',
          );
        } else {
          await tester.enterText(
            find.byKey(const Key('command-input')),
            p == CloudPlatform.quark ? '咐置松君杂货铺叩苓' : '~U_Cfc773YARWW~',
          );
        }
        await tap(tester, find.byKey(const Key('command-start')));
        expect(find.byType(BrowserPage), findsOneWidget);
        expect(find.text('测试文档.pdf'), findsOneWidget);
        expect(find.text('解析成功'), findsNothing);
        final expectedCode = p == CloudPlatform.xunlei ? '1234' : 'A%2B+B';
        expect(connectors[p]!.opened.single.passcode, expectedCode);
        expect(connectors[p]!.reads, 1);
        expect(http.calls, hasLength(1));
        expect(
          services.store.data
              .list('history')
              .single
              .obj('link')
              .str('passcode'),
          expectedCode,
        );
        await tester.pageBack();
        await tester.pumpAndSettle();
        expect(find.byType(CloudCommandPage), findsOneWidget);
        expect(
          tester
              .widget<TextField>(find.byKey(const Key('command-input')))
              .controller!
              .text,
          isNotEmpty,
        );
      },
    );
  }

  scenario('Quark short tokens open the share page after manual submission', (
    tester,
  ) async {
    await render(tester);
    await tester.enterText(
      find.byKey(const Key('command-input')),
      '/~62003YrHLe~:/',
    );
    expect(http.calls, isEmpty);
    await tap(tester, find.byKey(const Key('command-start')));
    expect(find.byType(BrowserPage), findsOneWidget);
    expect(find.text('测试文档.pdf'), findsOneWidget);
    expect(http.calls, hasLength(1));
    expect(http.calls.single.uri.host, 'utoken2.quark.cn');
    expect(connectors[CloudPlatform.quark]!.reads, 1);
  });

  scenario(
    'Manual selection keeps separate drafts and remembers the last platform without network calls',
    (tester) async {
      await render(tester);
      await tester.enterText(find.byKey(const Key('command-input')), '夸克输入');
      await tap(tester, find.byKey(const ValueKey('command-provider-Uc')));
      await tester.enterText(find.byKey(const Key('command-input')), 'UC输入');
      await tap(tester, find.byKey(const ValueKey('command-provider-Quark')));
      expect(
        tester
            .widget<TextField>(find.byKey(const Key('command-input')))
            .controller!
            .text,
        '夸克输入',
      );
      await tap(tester, find.byKey(const ValueKey('command-provider-Uc')));
      expect(
        tester
            .widget<TextField>(find.byKey(const Key('command-input')))
            .controller!
            .text,
        'UC输入',
      );
      expect(services.store.data.str('commandParsePlatform'), 'Uc');
      expect(http.calls, isEmpty);
    },
  );

  scenario(
    'Cancelled commands cannot open late results or store successful history',
    (tester) async {
      final done = Completer<ParsedLink>();
      await render(tester, resolve: (_, _) => done.future);
      await tester.enterText(
        find.byKey(const Key('command-input')),
        '咐置松君杂货铺叩苓',
      );
      final start = find.byKey(const Key('command-start'));
      await tester.ensureVisible(start);
      await tester.tap(start);
      await tester.pump(const Duration(milliseconds: 100));
      await tester.ensureVisible(find.text('取消解析'));
      await tester.tap(find.text('取消解析'));
      await tester.pumpAndSettle();
      done.complete(
        ParsedLink(
          source: 'https://pan.quark.cn/s/fixture',
          url: 'https://pan.quark.cn/s/fixture',
          kind: LinkKind.cloudShare,
          platform: CloudPlatform.quark,
          shareId: 'fixture',
        ),
      );
      await tester.pumpAndSettle();
      expect(find.byType(BrowserPage), findsNothing);
      expect(connectors[CloudPlatform.quark]!.opened, isEmpty);
      expect(services.store.data.list('history'), isEmpty);
      expect(find.text('解析已取消，口令已保留'), findsOneWidget);
    },
  );

  for (final (size, scale) in [
    (const Size(320, 700), 1.8),
    (const Size(844, 390), 1.0),
    (const Size(1280, 800), 1.0),
  ]) {
    scenario('Manual command controls fit $size at text scale $scale', (
      tester,
    ) async {
      await render(tester, size: size, scale: scale);
      await tap(tester, find.byKey(const ValueKey('command-provider-Uc')));
      await tester.enterText(
        find.byKey(const Key('command-input')),
        '~U_Cfc773YARWW~',
      );
      await tester.scrollUntilVisible(
        find.byKey(const Key('command-start')),
        160,
        scrollable: find.byType(Scrollable).first,
      );
      await tap(tester, find.byKey(const Key('command-start')));
      expect(find.byType(BrowserPage), findsOneWidget);
      expect(tester.takeException(), isNull);
    });
  }
}
