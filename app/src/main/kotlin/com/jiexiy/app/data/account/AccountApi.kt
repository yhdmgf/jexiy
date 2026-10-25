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
 * - `GET  auth.php?action=check`            → `data:{login, email}`；login=true 已登录，login=false 未登录
 *
 * 成功 `{"code":200,"msg":...,"data":{}}`；失败 `{"code":<非200>,"msg":...}`。
 *
 * ★ 请求必须走 [client]（带 CookieJar 的独立实例），不能用全局 [HttpClients.apiClient]：
 *   后端把图形验证码、邮箱验证码、发码冷却写在 PHP Session 里（`PHPSESSID`），
 *   设备指纹靠 `jiexiy_fp`、登录态靠 `jiexiy_user`（登录后由服务端 Set-Cookie 下发）。
 *   这三个 Cookie 漏一个，验证码就对不上、登录态也丢。
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

    /** 消息是否为图形验证码错误（命中则界面应刷新验证码图并清空已输入内容） */
    fun isCaptchaRefresh(message: String): Boolean = message.contains(CAPTCHA_REFRESH_MSG)

    // ---------- 账号接口 ----------

    suspend fun login(context: Context, email: String, password: String, captcha: String): Result<Session> =
        request(
            context,
            "?action=login",
            "POST",
            JSONObject().put("email", email).put("password", password).put("captcha", captcha),
            // 后端登录成功不回 data（只 Set-Cookie jiexiy_user），邮箱用用户输入的
        ) { _, _ -> Session(email) }

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
            // 后端注册成功不回 data，邮箱用用户输入的
        ) { _, _ -> Session(email) }

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
     * 检查登录态（后端 `action=check`）。
     *
     * 返回 `Success(null)` 表示「未登录」，`Success(Session)` 表示已登录；网络失败才走 `Failure` ——
     * 调用方据此区分「确实退出」与「只是没网」，后者不应清掉本地登录态。
     * ★ 后端「未登录」也回 `code:200` + `data.login=false`（不是失败码），所以能直接走 [request]。
     */
    suspend fun check(context: Context): Result<Session?> =
        request(context, "?action=check", "GET", null) { data, _ ->
            if (data?.optBoolean("login") == true) {
                Session(data.optString("email").orEmpty())
            } else {
                null
            }
        }

    // ---------- 账号同步接口（sync.php，同 host，共享同一份会话 Cookie）----------
    // ★ sync.php 只有 4 个 CRUD action：pull / sync_save / sync_del / clear_all（没有 action=sync）。
    //   合并（双向、本地优先）由客户端在 AccountSyncManager 里做，这里只负责存取。

    /** 云端一条同步记录：platform + 凭据对象 payload + 过期时间戳 */
    data class CloudRecord(
        val platform: String,
        val payload: JSONObject,
        val expiresAt: Long,
    )

    /**
     * 拉取当前用户全部同步记录（`action=pull`，GET）。
     *
     * pull 的 `data` 是**数组**（不是对象），所以不走 [request]/parseEnvelope（后者只解 data 对象），
     * 自己发请求 + 用 [checkSuccess] 校验后直接读数组。
     */
    suspend fun pull(context: Context): Result<List<CloudRecord>> = withContext(Dispatchers.IO) {
        try {
            val httpClient = client(context)
            val resp = httpClient.newCall(
                Request.Builder().url("$SYNC_URL?action=pull").get().build()
            ).execute()
            val respText = resp.body?.string().orEmpty()
            resp.close()
            if (!resp.isSuccessful) {
                Log.e(TAG, "拉取同步失败：HTTP ${resp.code}（$SYNC_URL）")
                return@withContext Result.Failure("拉取同步失败（HTTP ${resp.code}）")
            }
            val json = try { JSONObject(respText) } catch (e: Exception) {
                return@withContext Result.Failure("响应解析失败")
            }
            val check = checkSuccess(json, SYNC_URL)
            if (check is Result.Failure) return@withContext Result.Failure(check.message)
            val arr = json.optJSONArray("data") ?: JSONArray()
            val out = ArrayList<CloudRecord>(arr.length())
            for (i in 0 until arr.length()) {
                val r = arr.optJSONObject(i) ?: continue
                val platform = r.optString("platform")
                if (platform.isBlank()) continue
                out.add(CloudRecord(platform, r.optJSONObject("payload") ?: JSONObject(), r.optLong("expires_at", 0L)))
            }
            Result.Success(out)
        } catch (e: Exception) {
            Log.e(TAG, "拉取同步异常：${e.javaClass.simpleName}: ${e.message}", e)
            Result.Failure("网络请求异常")
        }
    }

    /** 保存/覆盖单条同步凭据（`action=sync_save`，POST） */
    suspend fun syncSave(context: Context, platform: String, payload: JSONObject, expiresAt: Long): Result<Unit> =
        request(
            context,
            "?action=sync_save",
            "POST",
            JSONObject().put("platform", platform).put("payload", payload).put("expires_at", expiresAt),
            SYNC_URL
        ) { _, _ -> Unit }

    /** 删除云端单条同步凭据（`action=sync_del`，POST） */
    suspend fun syncDel(context: Context, platform: String): Result<Unit> =
        request(
            context,
            "?action=sync_del",
            "POST",
            JSONObject().put("platform", platform),
            SYNC_URL
        ) { _, _ -> Unit }

    /** 清空当前用户全部同步凭据（`action=clear_all`，POST 空 body） */
    suspend fun clearAll(context: Context): Result<Unit> =
        request(context, "?action=clear_all", "POST", JSONObject(), SYNC_URL) { _, _ -> Unit }

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
    ): Result<T> = when (val raw = fetchJson(context, baseUrl + path, method, body)) {
        is Result.Failure -> raw
        is Result.Success -> parseEnvelope(raw.data, baseUrl + path, fromData)
    }

    /**
     * 发请求并返回 JSON 对象，不判 `success`。
     *
     * `Failure` 表示网络/解析层失败（超时、断网、响应为空或不是 JSON）——调用方应视同「未知」；
     * `Success(json)` 才交给 [parseEnvelope] 判 `code` 字段。
     */
    private suspend fun fetchJson(
        context: Context,
        url: String,
        method: String,
        body: JSONObject?,
    ): Result<JSONObject?> = withContext(Dispatchers.IO) {
        try {
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
                val text = resp.body?.string().orEmpty()
                if (text.isBlank()) {
                    Log.e(TAG, "账号响应为空（HTTP ${resp.code}，$url）")
                    return@withContext Result.Failure("响应为空（HTTP ${resp.code}）")
                }
                try {
                    Result.Success(JSONObject(text))
                } catch (e: Exception) {
                    Log.e(TAG, "账号响应不是合法 JSON（前 200 字=${text.take(200)}）", e)
                    Result.Failure("响应格式异常")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "账号请求异常（$url）：${e.javaClass.simpleName}: ${e.message}", e)
            Result.Failure("${e.javaClass.simpleName}: ${e.message ?: "网络异常"}")
        }
    }

    /**
     * 校验响应成功（兼容两套壳）：
     * ★ auth.php 用 `code`/`msg`（成功 code=200），sync.php 用 `success`/`code`/`message`/`data`
     *   （成功 success=true，code 可能省略）。成功 = `success==true` 或 `code==200`；
     *   失败原因取 `msg`，没有再取 `message`。
     */
    private fun checkSuccess(json: JSONObject?, url: String): Result<Unit> {
        if (json == null) return Result.Failure("响应格式异常")
        if (json.optBoolean("success") || json.optInt("code") == 200) return Result.Success(Unit)
        val message = json.optString("msg")
            .ifBlank { json.optString("message") }
            .ifBlank { "请求失败" }
        Log.e(TAG, "账号接口业务失败：[code=${json.optInt("code")}] $message（$url）")
        return Result.Failure(message)
    }

    private fun <T> parseEnvelope(
        json: JSONObject?,
        url: String,
        fromData: (JSONObject?, Any) -> T,
    ): Result<T> {
        val check = checkSuccess(json, url)
        if (check is Result.Failure) return Result.Failure(check.message)
        return try {
            Result.Success(fromData(json?.optJSONObject("data"), Unit))
        } catch (e: Exception) {
            Log.e(TAG, "账号数据解析失败（$url）：${e.message}", e)
            Result.Failure("数据解析失败")
        }
    }
}
