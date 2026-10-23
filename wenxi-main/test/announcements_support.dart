import 'package:asterlink/core/json.dart';

const announcementsEndpoint = 'https://notices.example.test/announcements.json';
final announcementsNow = DateTime.utc(2026, 10, 10, 6);
const deviceAnnouncementContent =
    '在解析页点击「互联」，即可向同一网络的其他设备发送文件、分享下载内容或发送网盘链接。\n\n连接前，请确认两台设备已经接入同一局域网。';

Json announcementRow(
  String id, {
  String? title,
  String? content,
  bool pinned = false,
  String? date,
}) => {
  'id': id,
  'title': title ?? '公告 $id',
  'content': content ?? '$id 的公告正文。\n可以查看完整内容。',
  'pinned': pinned,
  'publishedAt': ?date,
};

Json announcementsFixture() => {
  'announcements': <Object?>[
    announcementRow(
      'welcome',
      title: '欢迎使用文析助手',
      content: '软件完全免费。版本动态、使用提示和维护通知会在这里持续更新，感谢你的使用与支持。',
      pinned: true,
      date: '2026-10-08T08:00:00+08:00',
    ),
    announcementRow(
      'new',
      title: '设备互联使用提示',
      content: deviceAnnouncementContent,
      date: '2026-10-10T08:00:00+08:00',
    ),
    {
      ...announcementRow(
        'feedback',
        title: '问题反馈与维护通知',
        content: '遇到问题时，可以前往项目仓库反馈。请附上设备信息、问题描述和脱敏日志，方便定位处理。',
        date: '2026-10-09T08:00:00+08:00',
      ),
      'buttonText': '反馈问题',
      'buttonUrl': 'https://github.com/z7786/wenxi/issues',
    },
  ],
};
