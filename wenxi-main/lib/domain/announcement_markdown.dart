import 'package:markdown/markdown.dart' as md;

/// Readable list previews use the parsed text, never Markdown link targets.
String announcementExcerpt(String content) {
  final nodes = md.Document(
    extensionSet: md.ExtensionSet.gitHubFlavored,
    encodeHtml: false,
  ).parse(content);
  final text = StringBuffer();
  void visit(md.Node node) {
    if (node is md.Text) {
      text.write(node.text);
    } else if (node is md.Element) {
      if (node.tag == 'img') {
        text.write(node.attributes['alt'] ?? '图片');
      } else {
        for (final child in node.children ?? <md.Node>[]) {
          visit(child);
        }
      }
      if (const {
        'p',
        'br',
        'hr',
        'li',
        'ul',
        'ol',
        'blockquote',
        'pre',
        'h1',
        'h2',
        'h3',
        'h4',
        'h5',
        'h6',
        'th',
        'td',
        'tr',
        'table',
        'img',
      }.contains(node.tag)) {
        text.write(' ');
      }
    }
  }

  for (final node in nodes) {
    visit(node);
  }
  final result = text.toString().replaceAll(RegExp(r'\s+'), ' ').trim();
  return result.isEmpty ? '点击查看公告内容' : result;
}
