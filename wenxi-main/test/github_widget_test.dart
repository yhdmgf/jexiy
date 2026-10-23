import 'dart:convert';
import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:asterlink/app_services.dart';
import 'package:asterlink/core/json.dart';
import 'package:asterlink/data/state_store.dart';
import 'package:asterlink/domain/github.dart';
import 'package:asterlink/domain/models.dart';
import 'package:asterlink/ui/browser_page.dart';
import 'package:asterlink/ui/github_login_page.dart';
import 'support.dart';

const _root = GitHubLocation(
  kind: GitHubNodeKind.code,
  owner: 'sample',
  repo: 'files',
  ref: 'main',
  writable: true,
);
const _token = 'github_pat_fixture01234567890123456789';

void main() {
  late AppServices services;
  Future<void> render(
    WidgetTester tester, {
    Size size = const Size(393, 852),
    double scale = 1,
    bool dark = false,
    bool login = false,
    bool public = false,
    GitHubLocation root = _root,
    List<CloudFile> items = const [],
    FakeHttp? http,
  }) async {
    tester.view.physicalSize = size;
    tester.view.devicePixelRatio = 1;
    tester.platformDispatcher.textScaleFactorTestValue = scale;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
    services = AppServices(
      store: StateStore.memory({
        'credentials': {
          if (!public)
            'GitHub': Credential('GitHub', {
              'primary': _token,
              'username': 'sample',
              'userId': '123',
            }, updatedAt: 10).toJson(),
        },
      }),
      dataDirectory: Directory('github-ui-fixture'),
      cacheDirectory: Directory('github-ui-fixture/cache'),
      transport: FakeNative(),
      files: FakeFiles(Directory('github-ui-fixture/files')),
      http: http ?? FakeHttp(),
      platformFeatures: false,
      controlEnabled: false,
    );
    await tester.pumpWidget(
      MaterialApp(
        theme: ThemeData(
          useMaterial3: true,
          brightness: dark ? Brightness.dark : Brightness.light,
        ),
        home: login
            ? GitHubLoginPage(services)
            : BrowserPage(
                services,
                BrowseSession(
                  platform: CloudPlatform.github,
                  mode: public ? BrowseMode.share : BrowseMode.personal,
                  title: 'GitHub',
                  rootId: root.id,
                  metadata: {'githubAuthenticated': '${!public}'},
                ),
                initialItems: items,
              ),
      ),
    );
    await tester.pumpAndSettle();
  }

  Future<void> finish(WidgetTester tester) async {
    await tester.pumpWidget(const SizedBox());
    var done = false;
    services.close().then((_) => done = true);
    for (var i = 0; i < 100 && !done; i++) {
      await tester.pump(const Duration(milliseconds: 100));
      await tester.runAsync(
        () => Future<void>.delayed(const Duration(milliseconds: 5)),
      );
    }
    expect(done, isTrue);
  }

  for (final (size, scale, dark) in [
    (const Size(360, 780), 1.0, false),
    (const Size(393, 852), 1.5, true),
    (const Size(1100, 760), 1.0, false),
  ]) {
    testWidgets('GitHub token form fits $size scale $scale', (tester) async {
      await render(tester, size: size, scale: scale, dark: dark, login: true);
      try {
        expect(find.text('GitHub 登录'), findsOneWidget);
        final field = tester.widget<TextField>(
          find.byKey(const Key('github-token')),
        );
        expect(field.obscureText, isTrue);
        expect(field.enableSuggestions, isFalse);
        expect(find.text('验证并登录'), findsOneWidget);
        expect(tester.takeException(), isNull);
      } finally {
        await finish(tester);
      }
    });
  }

  testWidgets('repository root and public views hide write controls', (
    tester,
  ) async {
    await render(tester, root: _root.copy(kind: GitHubNodeKind.repository));
    try {
      expect(find.byKey(const Key('browser-add-menu')), findsNothing);
      expect(tester.takeException(), isNull);
    } finally {
      await finish(tester);
    }
    await render(tester, public: true);
    try {
      expect(find.byKey(const Key('browser-add-menu')), findsNothing);
      expect(find.textContaining('只读'), findsOneWidget);
    } finally {
      await finish(tester);
    }
  });

  testWidgets('code folder shows branch, upload and new-folder operations', (
    tester,
  ) async {
    await render(tester);
    try {
      expect(find.textContaining('分支：main'), findsOneWidget);
      await tester.tap(find.byKey(const Key('browser-add-menu')));
      await tester.pumpAndSettle();
      expect(find.text('上传文件'), findsOneWidget);
      expect(find.text('新建文件夹'), findsOneWidget);
      expect(tester.takeException(), isNull);
    } finally {
      await finish(tester);
    }
  });

  testWidgets(
    'release offers upload and asset rename/delete but not folder creation or move',
    (tester) async {
      final root = _root.copy(
        kind: GitHubNodeKind.release,
        releaseId: '12',
        ref: 'v1',
        branch: false,
      );
      final file = CloudFile(
        id: root.copy(kind: GitHubNodeKind.asset, assetId: '45').id,
        name: 'app.apk',
        size: 5,
        parentId: root.id,
      );
      await render(tester, root: root, items: [file]);
      try {
        await tester.tap(find.byKey(const Key('browser-add-menu')));
        await tester.pumpAndSettle();
        expect(find.text('上传文件'), findsOneWidget);
        expect(find.text('新建文件夹'), findsNothing);
        await tester.tapAt(const Offset(10, 350));
        await tester.pumpAndSettle();
        await tester.tap(find.byTooltip('app.apk操作'));
        await tester.pumpAndSettle();
        expect(find.text('重命名'), findsOneWidget);
        expect(find.text('删除'), findsOneWidget);
        expect(find.text('移动'), findsNothing);
        expect(tester.takeException(), isNull);
      } finally {
        await finish(tester);
      }
    },
  );

  testWidgets(
    'rename and delete refresh the GitHub file list after the commit succeeds',
    (tester) async {
      List<Json> tree = [
        {
          'path': 'old.txt',
          'sha': 'blob',
          'mode': '100644',
          'type': 'blob',
          'size': 5,
        },
      ];
      List<Json> changes = [];
      final http = FakeHttp((r) {
        if (r.uri.path == '/repos/sample/files') {
          return jsonResponse({
            'permissions': {'push': true},
          });
        }
        if (r.uri.path.contains('/commits/')) {
          return jsonResponse({
            'sha': 'commit',
            'commit': {
              'tree': {'sha': 'tree'},
            },
          });
        }
        if (r.uri.path == '/repos/sample/files/git/trees/tree') {
          return jsonResponse({'tree': tree});
        }
        if (r.uri.path.endsWith('/git/trees')) {
          changes = r.json.list('tree');
          return jsonResponse({'sha': 'new-tree'}, 201);
        }
        if (r.uri.path.endsWith('/git/commits')) {
          return jsonResponse({'sha': 'new-commit'}, 201);
        }
        if (r.method == 'PATCH') {
          tree = [
            for (final change in changes)
              if (change['sha'] != null) {...change, 'size': 5},
          ];
          return jsonResponse({
            'object': {'sha': 'new-commit'},
          });
        }
        throw StateError('Unexpected request ${r.method} ${r.uri.path}');
      });
      final file = CloudFile(
        id: _root.copy(kind: GitHubNodeKind.file, path: 'old.txt').id,
        name: 'old.txt',
        size: 5,
        parentId: _root.id,
        token: jsonEncode({'sha': 'blob', 'mode': '100644', 'type': 'blob'}),
      );
      await render(tester, items: [file], http: http);
      try {
        await tester.tap(find.byTooltip('old.txt操作'));
        await tester.pumpAndSettle();
        await tester.tap(find.text('重命名'));
        await tester.pumpAndSettle();
        await tester.enterText(
          find.descendant(
            of: find.byType(AlertDialog),
            matching: find.byType(TextField),
          ),
          'new.txt',
        );
        await tester.tap(find.text('确定'));
        await tester.pumpAndSettle();
        expect(find.text('new.txt'), findsOneWidget);
        expect(find.text('old.txt'), findsNothing);
        await tester.tap(find.byTooltip('new.txt操作'));
        await tester.pumpAndSettle();
        await tester.ensureVisible(find.text('删除'));
        await tester.pumpAndSettle();
        await tester.tap(find.text('删除'));
        await tester.pumpAndSettle();
        expect(find.text('删除 GitHub 文件'), findsOneWidget);
        await tester.tap(find.text('删除').last);
        await tester.pumpAndSettle();
        expect(find.text('new.txt'), findsNothing);
        expect(tree, isEmpty);
        expect(tester.takeException(), isNull);
      } finally {
        await finish(tester);
      }
    },
  );
}
