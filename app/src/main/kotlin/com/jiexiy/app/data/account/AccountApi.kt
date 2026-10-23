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

import android.util.Log
import com.jiexiy.app.data.network.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * 账号系统客户端（单文件 PHP 后端 auth.php）。
 *
 * 接口形态（统一响应壳，与公告接口一致）：
 * - `POST auth.php?action=register` body `{email, password, password2}`
 * - `POST auth.php?action=login`    body `{email, password}`
 * - `POST auth.php?action=logout`
 * - `GET  auth.php?action=check`    → `data:{loggedIn, email}`
 *
 * ★ 登录态是后端下发的 HttpOnly Cookie（`jiexiy_user`），服务端不回传 token 本体。
 *   全局的 [HttpClients] 没有配 CookieJar（其它平台 API 都是自带 token 的，用不上），
 *   所以这里手动收发：请求时把 [Session.cookie] 塞进 `Cookie` 头，响应里从 `Set-Cookie` 取新值。
 *   Cookie 由 [AccountStore] 持久化，应用重启后仍能 `check` 出登录态。
 */
object AccountApi {

    /** 账号服务地址：与公告后端同目录的 auth.php */
    const val BASE_URL = "https://qlyyz.xyz/jiexiy/auth.php"

    /** 网盘账号同步服务：与 auth.php 同目录的 sync.php（独立文件，不能拼到 BASE_URL 上） */
    const val SYNC_URL = "https://qlyyz.xyz/jiexiy/sync.php"

    private const val TAG = "jexiy-Account"
    private const val COOKIE_NAME = "jiexiy_user"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** 一次登录态：邮箱 + 后端下发的 Cookie 值（null 表示服务端没有下发 / 已清除） */
    data class Session(val email: String, val cookie: String?)

    /** 请求结果：失败带上可直接展示的原因（服务端 `message` / HTTP 码 / 异常信息） */
    sealed interface Result<out T> {
        data class Success<T>(val data: T) : Result<T>
        data class Failure(val message: String) : Result<Nothing>
    }

    suspend fun login(email: String, password: String, cookie: String?): Result<Session> =
        request(
            "?action=login",
            "POST",
            JSONObject().put("email", email).put("password", password),
            cookie
        ) { data, newCookie -> Session(data?.optString("email").orEmpty(), newCookie) }

    suspend fun register(
        email: String,
        password: String,
        password2: String,
        cookie: String?,
    ): Result<Session> =
        request(
            "?action=register",
            "POST",
            JSONObject().put("email", email).put("password", password).put("password2", password2),
            cookie
        ) { data, newCookie -> Session(data?.optString("email").orEmpty(), newCookie) }

    /** 登出：服务端清 Cookie，本地无论如何都要清（见 [AccountStore.clear]） */
    suspend fun logout(cookie: String?): Result<Unit> =
        request("?action=logout", "POST", null, cookie) { _, _ -> Unit }

    /**
     * 检查登录态。
     * 返回 `Success(null)` 表示「未登录」（Cookie 缺失或已过期），网络失败才走 `Failure` ——
     * 调用方据此区分「确实退出」与「只是没网」，后者不应清掉本地登录态。
     */
    suspend fun check(cookie: String?): Result<Session?> =
        request("?action=check", "GET", null, cookie) { data, newCookie ->
            if (data?.optBoolean("loggedIn") == true) {
                Session(data.optString("email").orEmpty(), newCookie)
            } else {
                null
            }
        }

    /** 一次同步的结果：云端合并后的账号列表 + 本次各项计数 */
    data class SyncOutcome(
        val accounts: JSONArray,
        val uploaded: Int,
        val downloaded: Int,
        val kept: Int,
    )

    /**
     * 双向同步网盘账号。
     *
     * 请求体就是认证备份的根对象（`{app, version, accounts}`），`expiresAt` 由
     * [com.jiexiy.app.data.backup.AuthBackupManager] 计算；服务端合并后返回完整账号列表，
     * 客户端直接把它写回本地库（见 [AccountSyncManager]）。
     */
    suspend fun sync(payload: JSONObject, cookie: String?): Result<SyncOutcome> =
        request("?action=sync", "POST", payload, cookie, SYNC_URL) { data, _ ->
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
    suspend fun deleteAccount(platform: String, cookie: String?): Result<Unit> =
        request(
            "?action=delete",
            "POST",
            JSONObject().put("platform", platform),
            cookie,
            SYNC_URL
        ) { _, _ -> Unit }

    /**
     * 发请求并解析统一响应壳。
     *
     * [fromData] 的第二个参数是本次响应带来的新 Cookie（没有 `Set-Cookie` 时沿用传入值），
     * 由这里统一注入，避免每个接口各写一遍取头逻辑。
     *
     * [baseUrl] 默认是账号接口（auth.php）；同步接口在另一个文件（sync.php），显式传入。
     */
    private suspend fun <T> request(
        path: String,
        method: String,
        body: JSONObject?,
        cookie: String?,
        baseUrl: String = BASE_URL,
        fromData: (JSONObject?, String?) -> T,
    ): Result<T> = withContext(Dispatchers.IO) {
        val url = baseUrl + path
        val response = try {
            val builder = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "jexiy")
            if (!cookie.isNullOrBlank()) builder.header("Cookie", "$COOKIE_NAME=$cookie")
            // OkHttp 不允许 POST 带空 body：logout 这类无参数接口显式发一个空对象
            val requestBody = body?.toString()?.toRequestBody(JSON)
                ?: if (method == "POST") "{}".toRequestBody(JSON) else null
            builder.method(method, requestBody)
            HttpClients.apiClient().newCall(builder.build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (text.isBlank()) {
                    Log.e(TAG, "账号响应为空（HTTP ${resp.code}，$url）")
                    return@withContext Result.Failure("响应为空（HTTP ${resp.code}）")
                }
                // 非 2xx 也可能带合法 JSON（业务失败），先取 body 再交给 parseEnvelope 判 success
                text to mergedCookie(resp.headers.values("Set-Cookie"), cookie)
            }
        } catch (e: Exception) {
            Log.e(TAG, "账号请求异常（$url）：${e.javaClass.simpleName}: ${e.message}", e)
            return@withContext Result.Failure("${e.javaClass.simpleName}: ${e.message ?: "网络异常"}")
        }
        parseEnvelope(response.first, url, response.second, fromData)
    }

    /**
     * 取本次响应里的 `jiexiy_user`：服务端登出时下发的是空值/过期 Cookie，
     * 此时必须返回 null（清掉本地），不能回退成旧值。
     */
    private fun mergedCookie(setCookies: List<String>, old: String?): String? {
        val raw = setCookies.firstOrNull { it.startsWith("$COOKIE_NAME=") } ?: return old
        val value = raw.substringAfter('=').substringBefore(';').trim()
        return value.takeUnless { it.isEmpty() || it == "deleted" }
    }

    /** 解析统一响应结构：`success` 为 false 时把服务端 message 原样带回 */
    private fun <T> parseEnvelope(
        body: String,
        url: String,
        cookie: String?,
        fromData: (JSONObject?, String?) -> T,
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
            Result.Success(fromData(json.optJSONObject("data"), cookie))
        } catch (e: Exception) {
            Log.e(TAG, "账号数据解析失败（$url）：${e.message}", e)
            Result.Failure("数据解析失败")
        }
    }
}
