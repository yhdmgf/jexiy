package com.jiexiy.app.data.network

import org.json.JSONObject

/** 从网页 localStorage 提取到的阿里云盘令牌。 */
data class AliyunWebTokens(
    val accessToken: String,
    val refreshToken: String,
    val userId: String = "",
    val nickname: String = ""
)

/**
 * 阿里云盘网页登录凭据提取（对应 wenxi `domain/web_tokens.dart` 的 aliyun 分支）。
 *
 * 网页版把令牌存在 `localStorage['token']`（JSON 串，含 access_token / refresh_token）。
 * 阿里云盘只认 refresh_token 换 access_token，access_token 自身约 15 分钟过期，
 * 因此持久化必须以 refresh_token 为准。
 */
object AliyunWebCredential {

    const val STORAGE_KEY = "token"
    const val LOGIN_URL = "https://www.alipan.com/drive"
    const val DESKTOP_UA = AliyunConstants.UA

    private val TRUSTED_HOSTS = listOf("alipan.com", "aliyundrive.com")

    val READ_SCRIPT: String = """
        (function(){try{
          var raw=localStorage.getItem('$STORAGE_KEY');
          if(!raw)return '';
          var data=JSON.parse(raw);
          if(!data||typeof data!=='object'||Array.isArray(data))return '';
          return encodeURIComponent(JSON.stringify(data));
        }catch(e){return ''}})()
    """.trimIndent()

    fun isTrustedUrl(url: String?): Boolean {
        val value = url?.trim().orEmpty()
        if (value.isEmpty() || value == "about:blank") return true
        val uri = runCatching { android.net.Uri.parse(value) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        if (uri.userInfo != null) return false
        val host = uri.host?.lowercase().orEmpty()
        return TRUSTED_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    /** 解析网页返回的 JSON 文本；refreshToken 为空视为失败。 */
    fun parse(raw: String): AliyunWebTokens? {
        var text = raw.trim().ifBlank { return null }
        val decoded = runCatching { java.net.URLDecoder.decode(text, "UTF-8") }.getOrElse { text }
        text = decoded

        // 如果外层是 JSON 字符串（引号包裹），先解开
        if (text.startsWith("\"") && text.endsWith("\"")) {
            val inner = runCatching { JSONObject("\"$text\"").optString("\"") }.getOrNull()
                ?: runCatching { text.removeSurrounding("\"").replace("\\\"", "\"") }.getOrNull()
            if (inner != null && inner.isNotEmpty()) text = inner
        }

        val data = runCatching { JSONObject(text) }.getOrNull() ?: return null

        // 嵌套展开：credentials / token / data 最多两层
        val unwrapped = unwrapNested(data)

        // 控制字符拒绝
        val access = pick(unwrapped, "access_token", "accessToken")
        val refresh = pick(unwrapped, "refresh_token", "refreshToken")
        if (refresh.isBlank() || !validToken(refresh)) return null
        if (access.isNotEmpty() && !validToken(access)) return null

        // Bearer 前缀剥离 + 裸 token 兼容
        if (refresh.isEmpty() && access.isEmpty()) {
            val bare = text.replaceFirst(Regex("(?i)^Bearer\\s+"), "").trim()
            if (validToken(bare)) {
                return AliyunWebTokens(accessToken = "", refreshToken = bare)
            }
        }

        val userId = pick(unwrapped, "user_id", "userId", "sub")
        val nickname = pick(unwrapped, "nick_name", "nickName", "nickname", "name", "user_name")
        return AliyunWebTokens(
            accessToken = access,
            refreshToken = refresh,
            userId = userId,
            nickname = nickname
        )
    }

    private fun unwrapNested(data: JSONObject): JSONObject {
        var result = data
        repeat(2) {
            if (result.has("access_token") || result.has("accessToken") ||
                result.has("refresh_token") || result.has("refreshToken")) {
                return result
            }
            val nested = result.opt("credentials") ?: result.opt("token") ?: result.opt("data")
            if (nested is JSONObject) {
                result = nested
            } else {
                return result
            }
        }
        return result
    }

    /** Token 校验：长度 16-16384，只含 URL-safe 字符，拒绝 null/undefined。 */
    private fun validToken(value: String): Boolean {
        if (value.length < 16 || value.length > 16384) return false
        if (value == "null" || value == "undefined") return false
        if (value.any { it.code < 0x20 || it.code == 0x7f }) return false
        return Regex("^[A-Za-z0-9._~+/=-]+").matches(value)
    }

    private fun pick(data: JSONObject, vararg keys: String): String {
        for (key in keys) {
            val value = data.optString(key).trim()
            if (value.isNotBlank()) return value
        }
        return ""
    }
}
