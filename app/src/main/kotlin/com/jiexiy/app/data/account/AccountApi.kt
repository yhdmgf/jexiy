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

package com.jiexiy.app.data.account

import android.content.Context
import android.util.Log
import com.jiexiy.app.data.network.HttpClients
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * 账号系统客户端（单文件 PHP 后端 auth.php）。
 *
 * 接口形态（统一响应壳，与公告接口一致）：
 * - `GET  auth.php?action=captcha`         → **PNG 二进制**（不走 JSON，见 [fetchCaptcha]）
 * - `POST auth.php?action=send_mail_code`  body `{captcha, email}` → 发邮箱验证码
 * - `POST auth.php?action=register`        body `{email, password, password2, mail_code, captcha}`
 * - `POST auth.php?action=login`           body `{email, password, captcha}`
 * - `POST auth.php?action=logout`
 * - `GET  auth.php?action=check`           → `data:{loggedIn, email}`
 *
 * 成功 `{"success":true,"message":...,"data":{}}`；失败 `{"success":false,"message":...}`。
 *
 * ★ 请求必须走 [client]（带 CookieJar 的独立实例），不能用全局 [HttpClients.apiClient]：
 *   后端把图形验证码、邮箱验证码、发码冷却都写在 PHP Session 里，靠 `PHPSESSID` 关联；
 *   指纹风控靠 `jiexiy_fp`，登录态靠 `jiexiy_user`。三个 Cookie 漏一个，验证码就永远对不上。
 *   会话由 [AccountCookieJar] 持久化，应用重启后仍连着。
 */
object AccountApi {

    /** 账号服务地址：与公告后端同目录的 auth.php */
    const val BASE_URL = "https://qlyyz.xyz/jiexiy/auth.php"

    /** 网盘账号同步服务：与 auth.php 同目录的 sync.php（独立文件，不能拼到 BASE_URL 上） */
    const val SYNC_URL = "https://qlyyz.xyz/jiexiy/sync.php"

    /** 服务端约定的图形验证码错误文案：命中时前端自动刷新验证码图（见 [isCaptchaRefresh]） */
    const val CAPTCHA_REFRESH_MSG = "图形验证码错误，请刷新图片重试"

    private const val TAG = "jexiy-Account"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val networkLock = Any()

    @Volatile
    private var accountClient: OkHttpClient? = null

    private val jarRef: AccountCookieJar?
        get() = accountClient?.cookieJar as? AccountCookieJar

    /** 一次登录态。Cookie 由 [AccountCookieJar] 统一管理，这里只留邮箱给界面显示 */
    data class Session(val email: String)

    /** 请求结果：失败带上可直接展示的原因（服务端 `message` / HTTP 码 / 异常信息） */
    sealed interface Result<out T> {
        data class Success<T>(val data: T) : Result<T>
        data class Failure(val message: String) : Result<Nothing>
    }

    /** 一次同步的结果：云端合并后的账号列表 + 本次各项计数 */
    data class SyncOutcome(
        val accounts: JSONArray,
        val uploaded: Int,
        val downloaded: Int,
        val kept: Int,
    )

    /** 消息是否为图形验证码错误（命中则界面应刷新验证码图并清空已输入内容） */
    fun isCaptchaRefresh(message: String): Boolean = message.contains(CAPTCHA_REFRESH_MSG)

    // ---------- 账号接口 ----------

    suspend fun login(context: Context, email: String, password: String, captcha: String): Result<Session> =
        request(
            context,
            "?action=login",
            "POST",
            JSONObject().put("email", email).put("password", password).put("captcha", captcha),
        ) { data, _ -> Session(data?.optString("email").orEmpty()) }

    suspend fun register(
        context: Context,
        email: String,
        password: String,
        password2: String,
        mailCode: String,
        captcha: String,
    ): Result<Session> =
        request(
            context,
            "?action=register",
            "POST",
            JSONObject()
                .put("email", email)
                .put("password", password)
                .put("password2", password2)
                .put("mail_code", mailCode)
                .put("captcha", captcha),
        ) { data, _ -> Session(data?.optString("email").orEmpty()) }

    /** 发送邮箱验证码。服务端进入校验即锁定 60 秒（成功失败都上锁），失败原因原样带回 */
    suspend fun sendMailCode(context: Context, captcha: String, email: String): Result<Unit> =
        request(
            context,
            "?action=send_mail_code",
            "POST",
            JSONObject().put("captcha", captcha).put("email", email),
        ) { _, _ -> Unit }

    /**
     * 拉取图形验证码 PNG。
     * 走独立通道：这个接口返回的是图片二进制而不是 JSON，不能进 [parseEnvelope]。
     */
    suspend fun fetchCaptcha(context: Context): Result<ByteArray> = withContext(Dispatchers.IO) {
        val url = BASE_URL + "?action=captcha"
        try {
            val bytes = client(context).newCall(
                Request.Builder()
                    .url(url)
                    .header("User-Agent", "jexiy")
                    .get()
                    .build()
            ).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.e(TAG, "验证码获取失败：HTTP ${resp.code}（$url）")
                    return@withContext Result.Failure("验证码获取失败（HTTP ${resp.code}）")
                }
                val data = resp.body?.bytes()
                if (data == null || data.isEmpty()) {
                    Log.e(TAG, "验证码响应为空（$url）")
                    return@withContext Result.Failure("验证码响应为空")
                } else {
                    data
                }
            }
            Result.Success(bytes)
        } catch (e: Exception) {
            Log.e(TAG, "验证码请求异常（$url）：${e.javaClass.simpleName}: ${e.message}", e)
            Result.Failure("验证码请求异常")
        }
    }

    /** 登出：服务端清 Cookie，本地会话也要一起清（见 [AccountCookieJar.clear]） */
    suspend fun logout(context: Context): Result<Unit> {
        val result = request(context, "?action=logout", "POST", null) { _, _ -> Unit }
        jarRef?.clear()
        return result
    }

    /**
     * 检查登录态。
     * 返回 `Success(null)` 表示「未登录」（Cookie 缺失或已过期），网络失败才走 `Failure` ——
     * 调用方据此区分「确实退出」与「只是没网」，后者不应清掉本地登录态。
     */
    suspend fun check(context: Context): Result<Session?> =
        request(context, "?action=check", "GET", null) { data, _ ->
            if (data?.optBoolean("loggedIn") == true) {
                Session(data.optString("email").orEmpty())
            } else {
                null
            }
        }

    // ---------- 账号同步接口（sync.php，同 host，共享同一份会话 Cookie）----------

    /**
     * 双向同步网盘账号。
     *
     * 请求体就是认证备份的根对象（`{app, version, accounts}`），`expiresAt` 由
     * [com.jiexiy.app.data.backup.AuthBackupManager] 计算；服务端合并后返回完整账号列表，
     * 客户端直接把它写回本地库（见 [AccountSyncManager]）。
     */
    suspend fun sync(context: Context, payload: JSONObject): Result<SyncOutcome> =
        request(context, "?action=sync", "POST", payload, SYNC_URL) { data, _ ->
            val accounts = data?.optJSONArray("accounts") ?: JSONArray()
            val stats = data?.optJSONObject("stats")
            SyncOutcome(
                accounts = accounts,
                uploaded = stats?.optInt("uploaded") ?: 0,
                downloaded = stats?.optInt("downloaded") ?: 0,
                kept = stats?.optInt("kept") ?: 0,
            )
        }

    /**
     * 删除云端该平台的账号。本地删除后先调它再同步，避免下次合并把刚删的账号从云端加回来。
     */
    suspend fun deleteAccount(context: Context, platform: String): Result<Unit> =
        request(
            context,
            "?action=delete",
            "POST",
            JSONObject().put("platform", platform),
            SYNC_URL
        ) { _, _ -> Unit }

    // ---------- 内部：请求与解析 ----------

    /**
     * 带 CookieJar 的独立客户端：只服务账号与同步接口。
     * 不复用 [HttpClients.apiClient] —— 全局实例没有 CookieJar，也不会被其它平台 API 污染。
     */
    private fun client(context: Context): OkHttpClient {
        accountClient?.let { return it }
        return synchronized(networkLock) {
            accountClient ?: OkHttpClient.Builder()
                .cookieJar(AccountCookieJar(context.applicationContext))
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
                .also { accountClient = it }
        }
    }

    /**
     * 发请求并解析统一响应壳。
     *
     * [fromData] 的第二个参数保留 `null` 占位（历史上用于回传新 Cookie），
     * 现在会话由 CookieJar 统一管理，这里不再手动解析 `Set-Cookie`。
     */
    private suspend fun <T> request(
        context: Context,
        path: String,
        method: String,
        body: JSONObject?,
        baseUrl: String = BASE_URL,
        fromData: (JSONObject?, Any) -> T,
    ): Result<T> = withContext(Dispatchers.IO) {
        val url = baseUrl + path
        val text = try {
            // OkHttp 不允许 POST 带空 body：logout 这类无参数接口显式发一个空对象
            val requestBody = body?.toString()?.toRequestBody(JSON)
                ?: if (method == "POST") "{}".toRequestBody(JSON) else null
            client(context).newCall(
                Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .header("User-Agent", "jexiy")
                    .method(method, requestBody)
                    .build()
            ).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) {
                    Log.e(TAG, "账号响应为空（HTTP ${resp.code}，$url）")
                    return@withContext Result.Failure("响应为空（HTTP ${resp.code}）")
                }
                // 非 2xx 也可能带合法 JSON（业务失败），先取 body 再交给 parseEnvelope 判 success
                body
            }
        } catch (e: Exception) {
            Log.e(TAG, "账号请求异常（$url）：${e.javaClass.simpleName}: ${e.message}", e)
            return@withContext Result.Failure("${e.javaClass.simpleName}: ${e.message ?: "网络异常"}")
        }
        parseEnvelope(text, url, fromData)
    }

    /** 解析统一响应结构：`success` 为 false 时把服务端 message 原样带回 */
    private fun <T> parseEnvelope(
        body: String,
        url: String,
        fromData: (JSONObject?, Any) -> T,
    ): Result<T> {
        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            Log.e(TAG, "账号响应不是合法 JSON（前 200 字=${body.take(200)}）", e)
            return Result.Failure("响应格式异常")
        }
        if (!json.optBoolean("success")) {
            val code = json.optInt("code")
            val message = json.optString("message").ifBlank { "请求失败" }
            Log.e(TAG, "账号接口业务失败：[$code] $message（$url）")
            return Result.Failure(message)
        }
        return try {
            Result.Success(fromData(json.optJSONObject("data"), Unit))
        } catch (e: Exception) {
            Log.e(TAG, "账号数据解析失败（$url）：${e.message}", e)
            Result.Failure("数据解析失败")
        }
    }
}
