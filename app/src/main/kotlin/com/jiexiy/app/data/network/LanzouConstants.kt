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

package com.jiexiy.app.data.network

/**
 * 蓝奏云常量（文档 §1）。
 */
object LanzouConstants {

    const val WEB_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    /** 个人盘登录入口（文档 §1.2） */
    const val MYDISK_URL = "https://pc.woozooo.com/mydisk.php"
    const val MYDISK_FILES_URL = "https://pc.woozooo.com/mydisk.php?item=files&action=index"
    const val DOUPLOAD_URL = "https://pc.woozooo.com/doupload.php"
    /** 网页端 WebUploader 上传入口（B3）：multipart 单请求，不带 uid/vei 查询参数 */
    const val HTML5UP_URL = "https://pc.woozooo.com/html5up.php"
    const val ORIGIN = "https://pc.woozooo.com"

    /**
     * 原生账号密码登录（官网登录页 accounts.woozooo.com）：
     * POST /accounts.php {task=uselogin, username, password, ref=pc.woozooo.com} → {zt, msgs}；
     * 无挑战 Cookie 时返回 JS 人机校验页（arg1 + acw_sc__v2），需计算后重试。
     */
    const val ACCOUNTS_URL = "https://accounts.woozooo.com/accounts.php"
    const val ACCOUNTS_ORIGIN = "https://accounts.woozooo.com"
    const val LOGIN_REF = "pc.woozooo.com"

    /**
     * 网页登录入口（官网登录页，用户在浏览器里手动登录）。
     * 原生账号密码接口遇到滑块 / 短信 / 扫码这些网页侧独有形态时绕不过去，只能走这条。
     */
    const val WEB_LOGIN_URL = "https://pc.woozooo.com/account.php?action=login"

    /**
     * 网页登录的 Cookie 采集地址：会话 Cookie（如 phpdisk_info）要访问过「我的文件」页才齐，
     * 所以除了登录页还要一并采集 mydisk.php / doupload.php 所在的域。
     */
    val WEB_COOKIE_URLS = listOf(
        "https://pc.woozooo.com/mydisk.php",
        "https://pc.woozooo.com/account.php?action=login",
        "https://up.woozooo.com/mydisk.php",
        "https://accounts.woozooo.com/accounts.php"
    )

    /** 官网域名（与 [HOST_REGEX] 那族分享链接域名不同，这里是登录 / 个人盘 / 上传节点） */
    private val WEB_TRUSTED_HOSTS = listOf("woozooo.com")

    /** 网页登录需要保留的 Cookie 字段（落库后供 mydisk.php / doupload.php 系列接口使用） */
    private val WEB_COOKIE_KEEP_KEYS =
        setOf("ylogin", "ylogins", "uag", "phpdisk_info", "lanzou_ifo", "PHPSESSID")

    /**
     * 从整串 Cookie 里只保留 [WEB_COOKIE_KEEP_KEYS] 中的字段（按首次出现顺序去重），
     * 拼成请求头可直接使用的整串 Cookie。
     */
    fun filterWebCookies(cookie: String): String {
        val out = linkedMapOf<String, String>()
        for (kv in cookie.split(";")) {
            val pair = kv.trim()
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val key = pair.substring(0, eq)
            val value = pair.substring(eq + 1)
            if (key in WEB_COOKIE_KEEP_KEYS && key !in out) out[key] = value
        }
        return out.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /**
     * 从 WebView 的 CookieManager 提取网页登录 Cookie：依次读取 [WEB_COOKIE_URLS]
     * （Android 的 CookieManager 能读到 HttpOnly 的会话 Cookie），合并后过滤。
     */
    fun extractWebCookies(getCookie: (String) -> String?): String =
        filterWebCookies(WEB_COOKIE_URLS.mapNotNull { getCookie(it) }.joinToString("; "))

    /**
     * 网页登录 Cookie 是否形似可用（登录页自动检测的「plausible」判据）。
     * 只要求 ylogin + phpdisk_info；真正是否有效由 mydisk.php 取 uid/vei 校验。
     */
    fun isValidWebCookie(cookie: String?): Boolean {
        if (cookie.isNullOrBlank()) return false
        fun has(name: String) = cookie.split(";").any {
            val pair = it.trim()
            pair.startsWith("$name=") && pair.length > name.length + 1
        }
        return has("ylogin") && has("phpdisk_info")
    }

    /**
     * WebView 是否允许停留在该 URL：仅 https 的官网域及其子域、443 端口（about:blank 放行）。
     * 登录过程可能跳 accounts.woozooo.com / up.woozooo.com，统一放行。
     */
    fun isTrustedWebUrl(url: String?): Boolean {
        val value = url?.trim().orEmpty()
        if (value.isEmpty() || value == "about:blank") return true
        val uri = runCatching { android.net.Uri.parse(value) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        if (uri.userInfo != null) return false
        // ⚠️ android.net.Uri 的 port 在 URL **没写端口**时返回 -1，**不会**补 scheme 默认端口
        // （和 java.net.URL.getDefaultPort() 不是一回事）。所以这里必须把 -1 当成 443 放行，
        // 否则最常见的地址会被判成不可信 ⇒ WebView 拦掉所有跳转，网页登录一步都走不动。
        val port = uri.port
        if (port != -1 && port != 443) return false
        val host = uri.host?.lowercase().orEmpty()
        return WEB_TRUSTED_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    /** 个人盘根目录 folder_id */
    const val ROOT_FOLDER_ID = "-1"

    /** 页大小相关（文档 §B3/§B4 分页上限） */
    const val MAX_FILE_PAGES = 1000
    const val MAX_FOLDER_PAGES = 500

    /** 下载节点注入的 Cookie */
    const val DOWN_IP_COOKIE = "down_ip=1"

    /** 文件夹 id 前缀 d: / 文件 id 前缀 f: */
    const val FOLDER_PREFIX = "d:"
    const val FILE_PREFIX = "f:"

    /** 域名族：lanzou*、lan[zs]o[ux]、lanzn，后缀 com/net/org/cn */
    private val HOST_REGEX =
        Regex("""^(?:[a-z0-9-]+\.)*(?:lanzou[a-z]?|lan[zs]o[ux]|lanzn)\.(?:com|net|org|cn)$""", RegexOption.IGNORE_CASE)

    fun isLanzouHost(host: String): Boolean = HOST_REGEX.matches(host)

    /** 将分享链接规范为 https、去掉 fragment、去掉多余 query（保留 pwd 类参数由调用方处理） */
    fun normalizeShareUrl(url: String): String {
        var u = url.trim()
        u = u.replaceFirst(Regex("^http://", RegexOption.IGNORE_CASE), "https://")
        u = u.substringBefore('#')
        return u.trimEnd('/')
    }
}
