/*
 * jexiy (jexiy) - A network drive share-link parser and high-speed downloader for Android.
 * Copyright (C) 2026 jexiy
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.jiexiy.app.data.prefs

/**
 * 深链接（网盘分享链接一键唤起）的注册项。
 *
 * ★ [alias] 必须与 `AndroidManifest.xml` 里 `com.jiexiy.app.link.*` 的 activity-alias **逐字一致**：
 *   清单里的 intent-filter 改不了，用户在设置里改「哪些链接由解析云接管」只能靠启停这些组件
 *   （见 [com.jiexiy.app.util.LinkOpener]），类名对不上就会静默失效。
 *
 * 默认全部启用；[prefKey] 里存的是用户意愿（true/false）。
 */
enum class LinkOpenTarget(
    /** activity-alias 的完整类名（清单里的注册项） */
    val alias: String,
    /** 设置页显示的名字 */
    val label: String,
    /** 设置页显示的链接样例 */
    val sample: String
) {
    QUARK("com.jiexiy.app.link.QuarkLink", "夸克网盘", "pan.quark.cn/s/…"),
    UC("com.jiexiy.app.link.UcLink", "UC 网盘", "drive.uc.cn/s/…"),
    XUNLEI("com.jiexiy.app.link.XunleiLink", "迅雷网盘", "pan.xunlei.com/s/…"),
    BAIDU("com.jiexiy.app.link.BaiduLink", "百度网盘", "pan.baidu.com/s/…"),
    C139("com.jiexiy.app.link.C139Link", "139（和彩云）", "yun.139.com/shareweb/…"),
    PAN123("com.jiexiy.app.link.Pan123Link", "123 云盘", "www.123pan.com/s/…"),
    PAN115("com.jiexiy.app.link.Pan115Link", "115 网盘", "115.com/s/…"),
    GUANGYA("com.jiexiy.app.link.GuangyaLink", "光鸭云盘", "www.guangyapan.com/s/…"),
    LANZOU("com.jiexiy.app.link.LanzouLink", "蓝奏云 / 优享版", "lanzou*.com/…"),
    MAGNET("com.jiexiy.app.link.MagnetLink", "磁力链接", "magnet:?xt=…"),
    JIEXIY("com.jiexiy.app.link.YunxLink", "jexiy:// 自有协议", "jexiy://resolve?url=…"),
    SHARE("com.jiexiy.app.link.ShareLink", "分享菜单", "其它 App「分享 → 解析云」"),
    PROCESS_TEXT("com.jiexiy.app.link.ProcessTextLink", "选中文本解析", "长按选中文字 →「用解析云解析」");

    /** SharedPreferences 键（默认开） */
    val prefKey: String
        get() = "link_open_${name.lowercase()}"
}
