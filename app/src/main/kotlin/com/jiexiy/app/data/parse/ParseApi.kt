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

package com.jiexiy.app.data.parse

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
 * 代解析客户端：每个网盘一个独立接口，按平台名分文件（data/parse/<平台>.php）。
 *
 * 接口要求登录态（与 auth.php 同一个 jiexiy_user Cookie），所以请求必须带 Cookie；
 * 返回统一响应壳，data 里是 `{directUrl, name}`。
 */
object ParseApi {

    /** 解析接口根路径；按平台拼 `<platform>.php` */
    private const val BASE = "https://qlyyz.xyz/jiexiy/parse"

    private const val TAG = "jexiy-Parse"
    private const val COOKIE_NAME = "jiexiy_user"
    private val JSON = "application/json; charset=utf-8".toMediaType()

    data class Parsed(val directUrl: String, val name: String)

    data class ShareFile(val fileId: String, val name: String, val isDir: Boolean, val size: Long)

    data class ShareList(val shareId: String, val files: List<ShareFile>)

    sealed interface Result<out T> {
        data class Success<T>(val data: T) : Result<T>
        data class Failure(val message: String) : Result<Nothing>
    }

    suspend fun feiji(
        url: String,
        password: String,
        credential: String,
        cookie: String?,
    ): Result<Parsed> = request(
        platform = "feiji",
        payload = JSONObject()
            .put("url", url)
            .put("password", password)
            .put("credential", credential),
        cookie = cookie
    )

    /** 分享文件列表（action=list） */
    suspend fun feijiList(
        url: String,
        password: String,
        credential: String,
        cookie: String?,
        folderId: String = "0"
    ): Result<ShareList> = requestData(
        platform = "feiji",
        payload = JSONObject()
            .put("url", url)
            .put("password", password)
            .put("credential", credential)
            .put("action", "list")
            .put("folderId", folderId),
        cookie = cookie
    ).let { r ->
        when (r) {
            is Result.Failure -> r
            is Result.Success -> {
                val data = r.data
                val array = data.optJSONArray("files") ?: JSONArray()
        val files = buildList {
            for (i in 0 until array.length()) {
                val row = array.optJSONObject(i) ?: continue
                val id = row.optString("fileId")
                if (id.isBlank()) continue
                add(
                    ShareFile(
                        fileId = id,
                        name = row.optString("name"),
                        isDir = row.optBoolean("isDir"),
                        size = row.optLong("size")
                    )
                )
            }
        }
                Result.Success(ShareList(data.optString("shareId"), files))
            }
        }
    }

    /** 分享单文件直链（action=download） */
    suspend fun feijiDownload(
        url: String,
        password: String,
        fileId: String,
        fileName: String,
        credential: String,
        cookie: String?
    ): Result<Parsed> = requestData(
        platform = "feiji",
        payload = JSONObject()
            .put("url", url)
            .put("password", password)
            .put("credential", credential)
            .put("action", "download")
            .put("fileId", fileId)
            .put("fileName", fileName),
        cookie = cookie
    ).let { r ->
        when (r) {
            is Result.Failure -> r
            is Result.Success -> {
                val data = r.data
                val direct = data.optString("directUrl")
                if (direct.isBlank()) Result.Failure("没有返回直链")
                else Result.Success(Parsed(direct, data.optString("name")))
            }
        }
    }

    private suspend fun request(
        platform: String,
        payload: JSONObject,
        cookie: String?,
    ): Result<Parsed> = when (val r = requestData(platform, payload, cookie)) {
        is Result.Failure -> r
        is Result.Success -> {
            val direct = r.data.optString("directUrl")
            if (direct.isBlank()) Result.Failure("没有返回直链")
            else Result.Success(Parsed(direct, r.data.optString("name")))
        }
    }

    private suspend fun requestData(
        platform: String,
        payload: JSONObject,
        cookie: String?,
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        if (cookie.isNullOrBlank()) {
            return@withContext Result.Failure("请先在「设置 → 账号」登录")
        }
        val url = "$BASE/$platform.php"
        val text = try {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "jexiy")
                .header("Cookie", "$COOKIE_NAME=$cookie")
                .post(payload.toString().toRequestBody(JSON))
                .build()
            HttpClients.apiClient().newCall(request).execute().use { resp ->
                resp.body?.string().orEmpty()
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析请求异常（$url）：${e.javaClass.simpleName}: ${e.message}", e)
            return@withContext Result.Failure("${e.javaClass.simpleName}: ${e.message ?: "网络异常"}")
        }
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            Log.e(TAG, "解析响应不是 JSON（前 200 字=${text.take(200)}）", e)
            return@withContext Result.Failure("响应格式异常")
        }
        if (!json.optBoolean("success")) {
            return@withContext Result.Failure(
                json.optString("message").ifBlank { "解析失败（code=${json.optInt("code")}）" }
            )
        }
        val data = json.optJSONObject("data") ?: return@withContext Result.Failure("解析结果为空")
        Result.Success(data)
    }
}
