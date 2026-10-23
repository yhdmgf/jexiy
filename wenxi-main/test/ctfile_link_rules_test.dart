import 'package:flutter_test/flutter_test.dart';
import 'package:asterlink/domain/links.dart';
import 'package:asterlink/domain/models.dart';
import 'package:asterlink/data/providers/ctfile.dart';
import 'support.dart';

void main() {
  const id = '66485969-17569901563698-90650b';
  const base = 'https://url69.ctfile.com/f/$id';
  for (final host in [
    'ctfile.com',
    'url69.ctfile.com',
    'url123.ctfile.com',
    'www.ctfile.cn',
    'user.400gb.com',
    '545c.com',
    'pipipan.com',
  ]) {
    test('Recognizes scheme-less share on $host', () {
      final link = LinkParser.parse('$host/f/$id 密码：1664').single;
      expect(link.platform, CloudPlatform.ctfile);
      expect(link.kind, LinkKind.cloudShare);
      expect(link.passcode, '1664');
    });
  }
  for (final route in ['f', 'file', 'd', 'dir', 's']) {
    for (final prefix in ['', '#']) {
      test('Recognizes $prefix/$route route', () {
        final link = LinkParser.parse(
          'https://url.ctfile.com/$prefix/$route/$id/?P=ab1234'.replaceFirst(
            'com//',
            'com/',
          ),
        ).single;
        expect(link.shareId, '$route/$id');
        expect(link.passcode, 'ab1234');
      });
    }
  }
  for (final suffix in [
    '?p=1664',
    '?P=1664',
    '?pwd=1664',
    '?PASSWORD=1664',
    '#p=1664',
    '#1664',
    '?p=%31%36%36%34',
  ]) {
    test('Extracts password from $suffix', () {
      expect(LinkParser.parse('$base$suffix').single.passcode, '1664');
    });
  }
  for (final label in [
    '(访问密码: 1664)',
    '（访问密码：1664）',
    '密码1664',
    ' 提取码：1664',
    '\n访问码：1664',
  ]) {
    test('Separates adjacent text $label', () {
      final link = LinkParser.parse('$base$label').single;
      expect(link.url, base);
      expect(link.passcode, '1664');
    });
  }
  test('Markdown, punctuation and multiple links keep their own codes', () {
    final links = LinkParser.parse(
      '[$base?p=1664]($base?p=1664)\n'
      'https://url.ctfile.com/d/12-34-abcd 密码:abcdef',
    );
    expect(links, hasLength(2));
    expect(links.first.url, '$base?p=1664');
    expect(links.first.passcode, '1664');
    expect(links.last.passcode, 'abcdef');
  });
  test('Embedded code wins and longer code is not silently truncated', () {
    expect(
      LinkParser.parse('$base?p=abcdef 密码:1664').single.passcode,
      'abcdef',
    );
    expect(LinkParser.parse('$base?p=abcdefghijklmn').single.passcode, isNull);
    expect(LinkParser.parse('$base?p=https').single.passcode, isNull);
  });
  for (final url in [
    'https://ctfile.com/',
    'https://ctfile.com/login',
    'https://ctfile.com/help/f/$id',
    'https://ctfile.com/f/$id/extra',
    'https://ctfile.com/redirect?url=$base',
    'https://ctfile.com/f/',
    'https://ctfile.com.evil.test/f/$id',
    'https://notctfile.com/f/$id',
  ]) {
    test('Does not mistake $url for a share', () {
      expect(LinkParser.parse(url).single.kind, isNot(LinkKind.cloudShare));
    });
  }
  test('Legacy file route reaches file API with original path type', () async {
    final http = FakeHttp((r) {
      expect(r.uri.path, '/getfile.php');
      expect(r.uri.queryParameters['path'], 'file');
      expect(r.uri.queryParameters['f'], id);
      expect(r.uri.queryParameters['passcode'], '1664');
      return jsonResponse({
        'code': 200,
        'file': {
          'file_id': '17569901563698',
          'file_name': '1.zip',
          'file_size': 100,
        },
      });
    });
    final connector = CtfileConnector(http);
    final session = await connector.openShare(
      LinkParser.parse('https://url.ctfile.com/#/file/$id?p=1664').single,
      null,
    );
    expect(
      (await connector.list(session, session.rootId, null)).single.name,
      '1.zip',
    );
  });
}
