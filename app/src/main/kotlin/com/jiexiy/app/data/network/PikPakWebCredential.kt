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
 * PikPak 网页登录凭据（mypikpak.com 的 localStorage 对象）。
 *
 * @param accessToken 云盘接口用的 access_token
 * @param refreshToken 刷新令牌（过期续期用；可能为空）
 * @param deviceId 网页侧 device_id（可能为空）
 * @param userId 用户 ID（JWT sub / user_id），用于后续换号检测
 * @param nickname 昵称（可能为空）
 * @param captchaToken 网页侧 captcha_token（用于需要人机验证的接口；可能为空）
 */
data class PikPakWebTokens(
    val accessToken: String,
    val refreshToken: String,
    val deviceId: String,
    val userId: String,
    val nickname: String,
    val captchaToken: String = ""
)

/**
 * PikPak 网页登录：站点私有格式的解析（纯逻辑，便于单测）。
 *
 * 网页版把登录态写进 localStorage 的 `credentials_YUMx5nI8ZU8Ap8pm`（子账号时是 `credentials_YUMx5nI8ZU8Ap8pm@<sub>`），
 * 形如 `{"access_token":"…","refresh_token":"…","expires_at":"…","name":"…","device_id":"…"}`。
 * 网页版 client_id `YUMx5nI8ZU8Ap8pm` 与移动端 `YNxT9w7GMdWvEOKa` 是两套独立体系，令牌不可混用。
 *
 * 这里不依赖任何 Android API，因此「页面结构变了导致登录不上」这类问题可以直接用单测锁住解析行为。
 *
 * ⚠️ 这依赖 PikPak 网页版的私有实现（键名、字段名）。改版失效时，用户仍可走「手动粘贴」兜底。
 *
 * 为什么值得单独做一条网页登录：PikPak 的账号密码 / 短信接口在命中风控时会返回一个**必须由网页完成的
 * 人机验证**，纯 API 客户端解不了；网页登录让浏览器跑完整个验证流程，再回读它写进 localStorage 的令牌，
 * 能绕开这条拦路。参考实现 wenxi-main/lib/domain/pikpak_web_login.dart。
 */
object PikPakWebCredential {

    /** 网页版 OAuth client_id（与 [PikPakConstants.CLIENT_ID] 是两套独立体系，令牌不通用） */
    const val WEB_CLIENT_ID = "YUMx5nI8ZU8Ap8pm"

    /** 登录态在 localStorage 中的键名 */
    const val STORAGE_KEY = "credentials_$WEB_CLIENT_ID"

    /** 当前子账号（多账号切换）在 localStorage 中的键名 */
    const val CURRENT_SUB_KEY = "current_sub"

    /** captcha_token 在 localStorage 中的键名 */
    const val CAPTCHA_KEY = "captcha_$WEB_CLIENT_ID"

    /**
     * 网页登录入口：未登录时官网自己会跳到登录页。
     * 参考 wenxi auth.dart 里 PikPak 的 WebLoginTarget URL。
     */
    const val LOGIN_URL = "https://mypikpak.com/drive/all"

    /** 网页版桌面 UA（Chrome 131 / Windows） */
    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/131.0.0.0 Safari/537.36"

    /**
     * 允许 WebView 停留 / 跳转的域名。
     * 参考 wenxi auth.dart PikPak WebLoginTarget 的 originAllowList。
     */
    private val TRUSTED_HOSTS = listOf("mypikpak.com")

    /**
     * 点击「保存」/自动检测时读取登录态的 JS：返回 encodeURIComponent 后的 JSON（空串=还没登录）。
     * 逻辑来自 wenxi-main/lib/domain/pikpak_web_login.dart 的 readScript，压缩成 IIFE 便于单点调用。
     *
     * 处理点：
     * - 优先 `credentials_<clientId>@<sub>`，其次 `credentials_<clientId>`，再退回 `credentials` / `token`
     * - 兼容嵌套包装（credentials / token / data）
     * - 兼容被 JSON.stringify 多层包裹的值（最多 3 层）
     * - 校验 client_id 匹配、sub 匹配，避免读到别人账号的令牌
     * - 附带 captcha_token（有效期内且属于当前账号时）
     */
    val READ_SCRIPT: String = """
        (function(){try{
          var client='$WEB_CLIENT_ID';
          var prefix='credentials_'+client;
          var sub=localStorage.getItem('$CURRENT_SUB_KEY')||'';
          var raw=(sub&&localStorage.getItem(prefix+'@'+sub))||localStorage.getItem(prefix);
          if(!raw)return '';
          var data=JSON.parse(raw);
          if(!data||typeof data!=='object'||Array.isArray(data))return '';
          if(data.client_id&&data.client_id!==client)return '';
          var owner=data.sub||data.user_id||data.userId||'';
          if(sub&&owner&&String(owner)!==sub)return '';
          var did=localStorage.getItem('deviceid')||localStorage.getItem('_deviceid')||'';
          var result={
            access_token: data.access_token||data.accessToken||'',
            refresh_token: data.refresh_token||data.refreshToken||'',
            sub: String(owner||''),
            name: data.name||data.nickname||'',
            device_id: data.device_id||data.deviceId||''
          };
          if(!result.device_id&&did){result.device_id=String(did).replace(/[\'"]/g,'').split('.').pop().slice(0,32);}
          try{
            var captchaRaw=localStorage.getItem('captcha_'+client);
            if(captchaRaw){
              var captcha=JSON.parse(captchaRaw);
              if(captcha&&typeof captcha==='object'&&!Array.isArray(captcha)){
                var capExp=captcha.expires_at||captcha.expiresAt||'';
                if(Date.parse(capExp)>Date.now()){
                  result.captcha_token=captcha.captcha_token||captcha.token||'';
                }
              }
            }
          }catch(e){}
          return encodeURIComponent(JSON.stringify(result));
        }catch(e){return ''}})()
    """.trimIndent()

    /** WebView 是否允许停留在该 URL：仅 https 的白名单域及其子域、443 端口（about:blank 放行） */
    fun isTrustedUrl(url: String?): Boolean {
        val value = url?.trim().orEmpty()
        if (value.isEmpty() || value == "about:blank") return true
        val uri = runCatching { android.net.Uri.parse(value) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        if (uri.userInfo != null) return false
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
    fun parse(raw: String): PikPakWebTokens? {
        var text = raw.trim()
        if (text.isEmpty()) return null
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
    internal fun fieldsFrom(fields: Map<String, String>): PikPakWebTokens? {
        val access = pick(fields, "access_token", "accessToken")
        if (access.isEmpty() || !isValidToken(access)) return null
        val refresh = pick(fields, "refresh_token", "refreshToken")
        return PikPakWebTokens(
            accessToken = access,
            refreshToken = if (isValidToken(refresh)) refresh else "",
            deviceId = pick(fields, "device_id", "deviceId"),
            userId = pick(fields, "sub", "user_id", "userId"),
            nickname = pick(fields, "name", "nickname")
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
