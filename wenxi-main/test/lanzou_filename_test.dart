import 'package:asterlink/core/json.dart';
import 'package:flutter_test/flutter_test.dart';
import 'lanzou_support.dart';

void main() {
  const fileName = '百度网盘官方精简版_12.1.3.apk';
  final apkPage = lanzouTestPage.replaceAll('Example &amp; 1.zip', fileName);

  for (final info in [0, 1, false, null, '0', '1', '成功', 'OK', 'null']) {
    test(
      'API placeholder $info retains the APK name through download',
      () async {
        final fixture = LanzouFixture()
          ..page = apkPage
          ..result['inf'] = info;
        final session = await fixture.open();
        expect(session.title, fileName);
        final files = await fixture.connector.list(
          session,
          session.rootId,
          null,
        );
        expect(files.single.name, fileName);
        expect((await fixture.download(session)).fileName, fileName);
        // Refreshing an expired direct URL must retain the same complete name.
        fixture.now = fixture.now.add(const Duration(minutes: 16));
        expect((await fixture.download(session)).fileName, fileName);
      },
    );
  }

  test(
    'Desktop legacy page recovers APK name when the API sends numeric zero',
    () async {
      final fixture = LanzouFixture()
        ..page =
            '''<title>$fileName - 蓝奏云</title>
<div style="font-size: 30px;text-align: center;">$fileName</div>
<iframe src="/fn"></iframe>'''
        ..result['inf'] = 0;
      final session = await fixture.open();
      expect(session.title, fileName);
      expect((await fixture.download(session)).fileName, fileName);
    },
  );

  test(
    'Title and iframe fallback preserve actual extensions and extensionless names',
    () async {
      for (final name in ['示例 & 说明.pdf', '文档.zip', '无后缀文件', '0']) {
        final fixture = LanzouFixture()
          ..page = '<title>$name - 蓝奏云</title><iframe src="/fn"></iframe>'
          ..result['inf'] = 0;
        expect((await fixture.download(await fixture.open())).fileName, name);
        fixture.page = '<iframe src="/fn"></iframe>';
        fixture.frame = '<div class="n_box_3fn">$name</div>$lanzouTestFrame';
        expect((await fixture.download(await fixture.open())).fileName, name);
      }
    },
  );

  test(
    'An actual API filename remains usable when HTML has no filename',
    () async {
      final fixture = LanzouFixture()
        ..page = '<iframe src="/fn"></iframe>'
        ..result['inf'] = fileName;
      expect((await fixture.download(await fixture.open())).fileName, fileName);
    },
  );

  test(
    'Missing filenames fail instead of saving a placeholder or inventing an APK',
    () async {
      final fixture = LanzouFixture()
        ..page = '<iframe src="/fn"></iframe>'
        ..result['inf'] = 0;
      await expectLater(
        fixture.open(),
        throwsA(
          isA<AppException>().having(
            (error) => error.message,
            'message',
            contains('文件名称'),
          ),
        ),
      );
    },
  );
}
