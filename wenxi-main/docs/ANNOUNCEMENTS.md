# 消息公告

解析页右上角的铃铛直接打开消息列表，角标显示未读数量。点击公告可查看全文，支持置顶、发布时间、外部链接、全部已读和手动刷新。原三点菜单中的打赏入口移到「我的」右上角菜单。

公告使用独立的 `announcements.json`，与原在线配置的更新检查、网盘开关和启动弹窗分别管理。原启动公告及其「7天不再显示」规则继续保留。

## 编辑公告

复制 [JSON 示例](../config/announcements.example.json) 为自己的 `announcements.json`，使用文本编辑器修改标题、正文和显示设置。

1. 每条公告填写标题与正文，正文可使用 Markdown。
2. 按需设置置顶、显示开关、日期和底部按钮。
3. 使用文末的校验命令检查文件，再上传到公告地址。

发布时间只影响显示和排序，不是定时发布。根目录的公告工作文件仅供本地维护，仓库保留通用 JSON 示例。

## 正文排版

`content` 支持 Markdown：一级至六级标题、加粗、斜体、删除线、有序/无序列表、引用、分隔线、链接、图片、代码块和表格。普通文字与换行继续兼容，列表摘要会去掉排版标记；宽表格、长代码可横向滚动。

例如：`## 使用提示`、`**重要内容**`、`[反馈问题](https://github.com/z7786/wenxi/issues)`、`![图片说明](https://example.com/image.png)`。

图片使用可公开访问的 HTTPS 直链，不支持本机路径或登录后才能读取的图片；加载失败会显示占位提示。链接仅在点击后打开。正文不会执行 HTML、脚本或内嵌网页。

## 发布文件

参考 [JSON 示例](../config/announcements.example.json) 编辑公告，发布到能够直接返回 UTF-8 JSON 的 HTTPS 地址。

```json
{
  "announcements": [
    {
      "id": "notice-001",
      "title": "软件公告",
      "content": "## 使用提示\n\n**软件完全免费**。\n\n- 查看最新公告\n- 遇到问题可前往仓库反馈",
      "publishedAt": "2026-10-10T08:00:00+08:00",
      "pinned": true,
      "enabled": true,
      "buttonText": "反馈问题",
      "buttonUrl": "https://github.com/z7786/wenxi/issues"
    }
  ]
}
```

- `title`、`content` 必填，分别最多 80 和 8192 字；其他字段可省略。
- `id` 方便维护，可以省略；不需要设置或递增 `revision`。
- `publishedAt` 支持 `2026-10-10` 或上例中带时区的完整时间。
- `pinned` 默认为 `false`；置顶优先，同组按发布时间从新到旧排列。未填时间的公告按文件中的顺序排在该组末尾。
- `enabled` 默认为 `true`。设置为 `false` 或删除该条即可撤下；`"announcements": []` 清空列表。顶层也可设 `"enabled": false` 暂停全部公告。
- `buttonText` 和 `buttonUrl` 同时填写或同时省略；链接使用 HTTPS，仅在用户点击时打开。

文件最多 100 条、256 KiB。部分条目格式有误时跳过该条；整个文件无效或获取失败时保留上次缓存。已读状态保存在本机，修改标题、正文或按钮后重新显示未读，调整日期、置顶或 ID 不影响已读。

## 公告地址

正式构建脚本默认使用原配置 JSON 同目录的 `announcements.json`。自定义地址时，在本机 `.local/build-config.json` 增加：

```json
"announcementsUrl": "https://example.com/announcements.json"
```

直接运行 Flutter 时可传 `--dart-define=ASTERLINK_ANNOUNCEMENTS_URL=https://example.com/announcements.json`。未配置在线服务的公开构建不会访问作者的公告地址。

应用进入前台时检查公告，正常情况下每 15 分钟刷新，失败后间隔 1 分钟重试；进入页面、下拉或点击刷新会立即获取。后台停止定时刷新。发布公告只需更新 JSON；更换构建中配置的地址才需要重新编译。

发布前可运行：

```powershell
dart run tool/validate_announcements.dart announcements.json
```
