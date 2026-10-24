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
        val text = raw.trim().ifBlank { return null }
        val decoded = runCatching { java.net.URLDecoder.decode(text, "UTF-8") }.getOrElse { text }
        val data = runCatching { JSONObject(decoded) }.getOrNull()
            ?: runCatching { JSONObject(decoded.removeSurrounding("\"").replace("\\\"", "\"")) }.getOrNull()
            ?: return null

        val access = pick(data, "access_token", "accessToken")
        val refresh = pick(data, "refresh_token", "refreshToken")
        if (refresh.isBlank()) return null
        return AliyunWebTokens(
            accessToken = access,
            refreshToken = refresh,
            userId = pick(data, "user_id", "userId", "sub"),
            nickname = pick(data, "nick_name", "nickName", "nickname", "name")
        )
    }

    private fun pick(data: JSONObject, vararg keys: String): String {
        for (key in keys) {
            val value = data.optString(key).trim()
            if (value.isNotBlank()) return value
        }
        return ""
    }
}
