import 'package:flutter_test/flutter_test.dart';
import 'package:asterlink/data/providers/lanzou_protocol.dart';
import 'lanzou_support.dart';

const aliasFrame = r'''<script>
var wp_sign = 'opaque-sign';
var ajaxdata = 'fixture-key';
var domain1 = 'https://apifile.woozooo.com/ajaxfile.php?file=42';
var domain2 = 'https://apifile.lanzouw.com/ajaxfile.php?file=42';
var dom_ajaxs;
if (typeof(killdnsweb)=='undefined') {
  dom_ajaxs = domain2;
} else {
  dom_ajaxs = domain1;
}
$.ajax({url: dom_ajaxs,
data: {'action':'downprocess','sign':wp_sign,'websignkey':ajaxdata,
'signs':ajaxdata,'websign':'','kd':1,'ves':1}});
// dom_ajaxs = fakeEndpoint;
</script>''';

void main() {
  test(
    'File download resolves the new conditional endpoint alias',
    () async {
      final fixture = LanzouFixture()..frame = aliasFrame;
      final session = await fixture.connector.openShare(fixture.link(), null);
      final files = await fixture.connector.list(session, session.rootId, null);
      final spec = await fixture.connector.download(
        session,
        files.single,
        null,
      );
      expect(spec.url, lanzouTestFinal);
      final post = fixture.http.calls.singleWhere((r) => r.method == 'POST');
      expect(
        post.uri.toString(),
        'https://apifile.woozooo.com/ajaxfile.php?file=42',
      );
      expect(Uri.splitQueryString(post.body as String)['sign'], 'opaque-sign');
    },
  );

  test('Unresolved or cyclic aliases do not select unrelated endpoints', () {
    for (final assignment in ['a = b; b = a;', 'a = missing;']) {
      final page = LanzouPage('''<script>
var unrelated = '/ajaxm.php?file=99';
$assignment
\$.ajax({url:a});
</script>''');
      expect(page.ajaxUrl, isEmpty);
    }
  });

  test(
    'Alias endpoint still requires a supported path and numeric file id',
    () {
      for (final url in ['/other.php?file=42', '/ajaxfile.php?file=invalid']) {
        final page = LanzouPage('''<script>
var endpoint = '$url'; var alias = endpoint;
\$.ajax({url:alias});
</script>''');
        expect(page.ajaxUrl, isEmpty);
      }
    },
  );
}
