# jexiy

Android 网盘分享链接解析器与高速下载器。粘贴一条网盘分享链接，自动识别平台、提取文件目录，用内置 [Gopeed](https://github.com/Gopeed/gopeed) 下载内核多线程拉取；同时支持反向——把本地文件上传到你已登录的网盘。

- **许可证**：AGPL-3.0-or-later
- **技术栈**：Kotlin + Jetpack Compose（material3-expressive）、Room、OkHttp；minSdk 24 / targetSdk 34 / compileSdk 36
- **代码仓库**：[github.com/yhdmgf/jexiy](https://github.com/yhdmgf/jexiy)
- **下载内核**：[github.com/yhdmgf/jexiy_gopeed_build](https://github.com/yhdmgf/jexiy_gopeed_build)（首次运行按架构自动下载匹配的内核 so）

## 支持的网盘（12 个）

| 平台 | 分享解析 | 下载 | 上传 | 多账号 | 网页登录 |
|---|:-:|:-:|:-:|:-:|:-:|
| 夸克网盘 | ✅ | ✅ | ✅ | ✅ | |
| UC 网盘 | ✅ | ✅ | ✅ | ✅ | |
| 迅雷网盘 | ✅ | ✅ | ✅ | ✅ | |
| 百度网盘 | ✅ | ✅ | ✅ | ✅ | |
| 139 网盘 | ✅ | ✅ | ✅ | ✅ | ✅ |
| 123云盘 | ✅ | ✅ | ✅ | ✅ | |
| 115网盘 | ✅ | ✅ | ✅ | ✅ | |
| 光鸭云盘 | ✅ | ✅ | ✅ | ✅ | ✅ |
| 小飞机网盘 | ✅ | ✅ | ✅ | ✅ | |
| 阿里云盘 | ✅ | ✅ | ✅ | ✅ | |
| 蓝奏云优享版 | ✅ | ✅ | ✅ | ✅ | |
| 蓝奏云 | ✅ | ✅ | ✅ | ✅ | ✅ |

> 同一网盘可登录多个账号、加备注、设主账号；列目录 / 转存 / 上传等需要单一身份的操作走主账号，多账号下载轮询才用到非主账号。

## 支持的视频平台（7 个）

抖音、小红书、快手、微博、AcFun、哔哩哔哩、Pixiv——粘贴视频链接解析直链后下载。

## 核心功能

- **分享解析**：粘贴链接（含提取码、整段分享文案）自动识别平台与 share_id，拉取文件目录树。
- **高速下载**：内置 Gopeed 多线程内核，按架构自动 provision；任务管理、重试、取消、断点。
- **上传**：点「+」选文件 → 弹出已登录网盘选择器（图标 + 平台名 + 账号昵称）→ 上传到选中网盘当前目录；进度以波浪条展示。
- **账号系统**：独立后端 `auth.php`，邮箱注册登录 + 图形验证码 + 邮箱验证码；会话 Cookie 持久化，重启后保持登录。
- **网盘账号云同步**：`sync.php` 双向合并各平台账号，换机不丢账号；删除先清云端再合并，防回弹。
- **网页登录**：139 / 光鸭 / 蓝奏 等用 WebView 抓 Cookie 登录，绕开无开放 API 的限制。
- **公告系统**：后端下发公告，多图展示，未读角标。
- **自动更新**：查 GitHub Releases 比版本号，提示升级。

## 后端

账号与同步走两个独立 PHP 单文件（与公告同目录）：

- `auth.php`：图形验证码、邮箱验证码、注册、登录、登出、登录态检查（`action=info`）。
- `sync.php`：网盘账号双向同步、按平台删除。

> 邮箱验证码依赖服务器 `mail()`；若 VPS 未装 MTA 或发件域未配 SPF/DKIM，验证码发不出去但接口仍返回成功——需用真实 SMTP（PHPMailer + QQ/163 SMTP）替代。

## 构建运行

```bash
# 需要 Android SDK（android-36、build-tools 35）+ JDK 17
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew assembleDebug        # 出 debug APK
./gradlew assembleRelease      # 出 release APK（需配置签名）
```

> 大型项目，编译机内存建议 ≥ 8 GB；内存吃紧时 Kotlin 守护进程会 OOM，可在 `gradle.properties` 设 `kotlin.daemon.jvmargs=-Xmx2g`。
