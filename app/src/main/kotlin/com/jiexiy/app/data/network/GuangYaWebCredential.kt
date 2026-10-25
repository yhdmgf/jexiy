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

import org.json.JSONObject
import org.json.JSONTokener

/**
 * 光鸭网页登录凭据（www.guangyapan.com 的 localStorage 对象）。
 *
 * @param accessToken 云盘接口用的 access_token
 * @param refreshToken 刷新令牌（过期续期用；可能为空，为空时只能重登）
 * @param deviceId 网页侧 device_id（可能为空，落库时会本地生成）
 * @param deviceSign 网页侧 device_sign（形如 wdi10.<deviceId><32位十六进制>；可能为空）
 * @param userId 用户 ID（JWT sub / user_id），用于后续换号检测
 * @param nickname 昵称（可能为空）
 */
data class GuangYaWebTokens(
    val accessToken: String,
    val refreshToken: String,
    val deviceId: String,
    val deviceSign: String,
    val userId: String,
    val nickname: String
)

/**
 * 光鸭网页登录：站点私有格式的解析（纯逻辑，便于单测）。
 *
 * 网页版把登录态写进 localStorage 的 `credentials_<clientId>`（子账号时是 `credentials_<clientId>@<sub>`），
 * 形如 `{"access_token":"…","refresh_token":"…","user_id":"…","nickname":"…"}`。另外两个键是设备侧
 * 的：`swangpan_web_device_id`（设备 id）与 `deviceid`（设备签名，网页里常被包成 JSON 字符串带引号）。
 *
 * 这里不依赖任何 Android API，因此「页面结构变了导致登录不上」这类问题可以直接用单测锁住解析行为。
 *
 * ⚠️ 这依赖光鸭网页版的私有实现（键名、字段名）。改版失效时，用户仍可走「手动粘贴」兜底
 * （[parse] 同时支持完整 JSON 与被引号包起来的 JSON 字符串两种粘贴形态）。
 *
 * 为什么值得单独做一条网页登录：光鸭的账号密码 / 短信接口在命中风控时会返回一个**必须由网页完成的
 * 人机验证**（/v1/shield/captcha/init 下发一个第三方验证页 URL），纯 API 客户端解不了；网页登录让
 * 浏览器跑完整个验证流程，再回读它写进 localStorage 的令牌，能绕开这条拦路。
 */
object GuangYaWebCredential {

    /** 网页版 OAuth client_id（与 [GuangYaConstants.CLIENT_ID] 同一套令牌体系） */
    const val CLIENT_ID = GuangYaConstants.CLIENT_ID

    /** 登录态在 localStorage 中的键名 */
    const val STORAGE_KEY = "credentials_$CLIENT_ID"

    /** 设备 id 在 localStorage 中的键名 */
    const val DEVICE_ID_KEY = "swangpan_web_device_id"

    /** 设备签名在 localStorage 中的键名（网页里常是带引号的 JSON 字符串） */
    const val DEVICE_SIGN_KEY = "deviceid"

    /** 当前子账号（多账号切换）在 localStorage 中的键名 */
    const val CURRENT_SUB_KEY = "current_sub"

    /**
     * 网页登录入口（未登录会自动进入登录流程；账号密码 / 短信 / 滑块都由官网页面自己处理）。
     *
     * 使用 `/pan` 而不是 `/#/oauth/login`：后者是内部专用登录路由，直接打开会卡白屏；
     * `/pan` 是官网根页，未登录时官网自己会重定向到登录页。
     * WebView 抓 localStorage 只需域名匹配（[TRUSTED_HOSTS]），不依赖具体的 hash 路由。
     */
    const val LOGIN_URL = "https://www.guangyapan.com/pan"

    /** 网页版桌面 UA（与 [GuangYaConstants.WEB_UA] 一致，Chrome 131 / Windows） */
    const val DESKTOP_UA = GuangYaConstants.WEB_UA

    /**
     * 允许 WebView 停留 / 跳转的域名。
     *
     * 光鸭的滑块验证页可能跨到 xunlei.com 子域（光鸭账号体系与迅雷同源），必须一并放行，
     * 否则滑块验证第一步就会被拦掉，网页登录走不通。
     */
    private val TRUSTED_HOSTS = listOf("guangyapan.com", "xunlei.com")

    /** 点击「保存」/自动检测时读取登录态的 JS：返回 encodeURIComponent 后的 JSON（空串=还没登录） */
    val READ_SCRIPT: String = """
        (function(){try{
          var prefix='$STORAGE_KEY';
          var sub=localStorage.getItem('$CURRENT_SUB_KEY');
          var raw=(sub&&localStorage.getItem(prefix+'@'+sub))||localStorage.getItem(prefix);
          if(!raw)return '';
          var data=JSON.parse(raw);
          if(!data||typeof data!=='object'||Array.isArray(data))return '';
          try{
            var did=localStorage.getItem('$DEVICE_ID_KEY');
            if(did)data.device_id=String(did).replace(/['"]/g,'');
          }catch(e){}
          try{
            var sign=localStorage.getItem('$DEVICE_SIGN_KEY');
            if(sign)data.device_sign=String(sign).replace(/['"]/g,'');
          }catch(e){}
          return encodeURIComponent(JSON.stringify(data));
        }catch(e){return ''}})()
    """.trimIndent()

    /** WebView 是否允许停留在该 URL：仅 https 的白名单域及其子域、443 端口（about:blank 放行） */
    fun isTrustedUrl(url: String?): Boolean {
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
        return TRUSTED_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    /**
     * 解析网页登录凭据。支持两种输入：
     * 1. 完整 JSON（localStorage 原文 / 抓包复制）；
     * 2. 被引号包起来的 JSON 字符串（某些工具复制出来是这种）。
     *
     * 解析失败（含字段校验不过）一律返回 null，由调用方继续轮询或提示用户。
     */
    fun parse(raw: String): GuangYaWebTokens? {
        var text = raw.trim()
        if (text.isEmpty()) return null
        // 形态 2：外层是 JSON 字符串 → 还原成里面的内容
        unwrapQuoted(text)?.let { text = it.trim() }
        if (!text.startsWith("{")) return null

        val root = runCatching { JSONTokener(text).nextValue() as? JSONObject }.getOrNull() ?: return null
        return fieldsFrom(toFieldMap(unwrapNested(root)))
    }

    /**
     * 从「键 → 值」映射里挑出凭据字段（**纯逻辑，可单测**）。
     *
     * 兼容点：snake_case 与 camelCase 两种键名、嵌套包装已由调用方拆掉；access token 缺失即整体判失败
     * ——网页登录过程中会把中间态写进 localStorage，不能当成登录成功。
     */
    internal fun fieldsFrom(fields: Map<String, String>): GuangYaWebTokens? {
        val access = pick(fields, "access_token", "accessToken")
        if (access.isEmpty() || !isValidToken(access)) return null
        val refresh = pick(fields, "refresh_token", "refreshToken")
        if (refresh.isNotEmpty() && !isValidToken(refresh)) return null
        val sign = pick(fields, "device_sign", "deviceSign")
        return GuangYaWebTokens(
            accessToken = access,
            refreshToken = if (isValidToken(refresh)) refresh else "",
            deviceId = pick(fields, "device_id", "deviceId"),
            deviceSign = sign,
            userId = pick(fields, "user_id", "userId", "sub"),
            nickname = pick(fields, "nick_name", "nickname", "name", "user_name", "username")
        )
    }

    /** 把 JSONObject 拍平成「键 → 字符串值」；非字符串/数字的值直接丢掉（令牌一定是字符串） */
    private fun toFieldMap(data: JSONObject): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val keys = data.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = data.opt(key)
            if (value is String || value is Number) map[key] = value.toString()
        }
        return map
    }

    /** 外层是 JSON 字符串字面量时还原内容；不是则返回 null */
    private fun unwrapQuoted(text: String): String? {
        if (!text.startsWith("\"")) return null
        return runCatching {
            val parsed = JSONTokener(text).nextValue()
            parsed as? String
        }.getOrNull()
    }

    /**
     * 兼容嵌套包装：有些版本把令牌包在 `credentials` / `token` / `data` 里。
     * 最多向下找两层，找到含令牌字段的那层为止。
     */
    private fun unwrapNested(root: JSONObject): JSONObject {
        var current = root
        repeat(2) {
            if (current.has("access_token") || current.has("accessToken") ||
                current.has("refresh_token") || current.has("refreshToken")
            ) {
                return current
            }
            val nested = current.optJSONObject("credentials")
                ?: current.optJSONObject("token")
                ?: current.optJSONObject("data")
                ?: return current
            current = nested
        }
        return current
    }

    /** 取第一个非空字符串字段（数字也接受，与网页侧 pick 行为一致） */
    private fun pick(fields: Map<String, String>, vararg keys: String): String {
        for (key in keys) {
            val text = fields[key]?.trim().orEmpty()
            if (text.isEmpty()) continue
            if (CONTROL_CHARS.containsMatchIn(text)) continue
            return text
        }
        return ""
    }

    /**
     * 令牌合法性：长度 16~16384、仅 JWT/Base64 允许的字符集、且不是 "null"/"undefined" 这类占位串。
     * 中间态写进 localStorage 的垃圾值不会当成登录成功。
     */
    fun isValidToken(value: String): Boolean =
        value.length in 16..16384 &&
            TOKEN_CHARS.matches(value) &&
            value != "null" &&
            value != "undefined"

    private val TOKEN_CHARS = Regex("^[A-Za-z0-9._~+/=-]+$")
    private val CONTROL_CHARS = Regex("[\\x00-\\x1f\\x7f]")
}
