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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * 小飞机网盘直连接口（对齐 wenxi-main 的 FeijipanConnector）。
 *
 * 关键点：
 * - devType=6、devCode=uuid、devModel=chrome、devVersion=131；
 * - appToken 放 query，不是请求头；
 * - AES-128-ECB/PKCS7，key=dingHao-disk-app，hex 小写；
 * - 个人文件列表 /app/record/file/list，下载 /ws/file/redirect；
 * - 分享列表是 POST（/ws/recommend/list、/ws/share/list），下载 /ws/file/download；
 * - 文件操作走 /app/file/...，分享创建走 /app/share/url。
 */
object FeijiApi {
    private const val BASE = "https://api.feijipan.com"
    private const val WEB = "https://www.feijipan.com"
    private const val AES_KEY = "dingHao-disk-app"
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .build()

    /** 云盘文件（对齐 ShareFile 口径，方便直接喂给 [com.jiexiy.app.ui.resolve.ShareFileRow]）。 */
    data class File(
        val id: String,
        val name: String,
        val isDir: Boolean,
        val size: Long,
        val parentId: String,
        val modifyTime: String = ""
    )

    /** 账号信息：用户名 + 已用/总容量（字节）+ 下载接口需要的 userId */
    data class Account(
        val name: String,
        val used: Long,
        val total: Long,
        val userId: String = "",
        val account: String = ""
    )

    /** 分享信息（对齐 [com.jiexiy.app.data.network.model.ShareInfo] 的核心字段） */
    data class ShareInfo(val shareUrl: String, val passcode: String, val title: String)

    /** 分享文件列表结果 */
    data class ShareListResult(
        val files: List<File>,
        val hasMore: Boolean,
        val title: String = ""
    )

    /** 下载直链 + 必须随请求带上的请求头 */
    data class DownloadSpec(val url: String, val headers: Map<String, String>)

    /** 上传完成后的文件信息 */
    data class UploadResult(val id: String, val name: String, val size: Long)

    val downloadHeaders: Map<String, String>
        get() = linkedMapOf(
            "User-Agent" to UA,
            "Origin" to WEB,
            "Referer" to "$WEB/",
            "Accept-Language" to "zh-CN"
        )

    suspend fun getUuid(): String = withContext(Dispatchers.IO) {
        val json = getJson(buildUrl("/ws/getUuid", "", ""))
        val uuid = json.optString("uuid")
        if (!Regex("^[A-Za-z0-9_-]{8,128}$").matches(uuid)) {
            throw IOException("小飞机设备标识获取失败")
        }
        uuid
    }

    /**
     * 账号昵称、容量与 userId（对齐 wenxi）：
     * account/map 取 usedSize/totalSize/userId，info/map 取 userName。
     * 两个接口的 userId 必须一致，否则视为登录态异常。
     */
    suspend fun account(token: String, uuid: String): Account = withContext(Dispatchers.IO) {
        val accountMap = getJson(buildUrl("/app/user/account/map", uuid, token))
            .optJSONObject("map") ?: throw IOException("小飞机账号信息为空")
        val infoMap = getJson(buildUrl("/app/user/info/map", uuid, token))
            .optJSONObject("map")
        val userId = accountMap.optString("userId")
        if (!Regex("^\\d+$").matches(userId)) {
            throw IOException("小飞机未返回账号身份，请重新登录")
        }
        if (infoMap != null && infoMap.optString("userId").isNotBlank() &&
            infoMap.optString("userId") != userId
        ) {
            throw IOException("小飞机账号身份不一致，请重新登录")
        }
        val name = infoMap?.optString("userName").orEmpty()
            .ifBlank { accountMap.optString("nickName") }
            .ifBlank { accountMap.optString("nickname") }
            .ifBlank { accountMap.optString("account") }
            .ifBlank { "小飞机用户" }
        Account(
            name = name,
            used = accountMap.optLong("usedSize") * 1024,
            total = (accountMap.optLong("totalSize") +
                accountMap.optLong("vipSize") +
                accountMap.optLong("contractSize") +
                accountMap.optLong("rewardSize")) * 1024,
            userId = userId,
            account = accountMap.optString("account")
        )
    }

    suspend fun userId(token: String, uuid: String): String = account(token, uuid).userId

    /** 个人文件列表（对齐 wenxi 的分页、去重与字段解析） */
    suspend fun list(token: String, uuid: String, folderId: String): List<File> =
        withContext(Dispatchers.IO) {
            val result = mutableListOf<File>()
            val seen = mutableSetOf<String>()
            var page = 1
            while (page <= 1000) {
                val json = getJson(
                    buildUrl(
                        "/app/record/file/list",
                        uuid,
                        token,
                        linkedMapOf(
                            "offset" to page.toString(),
                            "limit" to "60",
                            "folderId" to id(folderId),
                            "type" to "0"
                        )
                    )
                )
                val array = json.optJSONArray("list") ?: JSONArray()
                var added = 0
                for (i in 0 until array.length()) {
                    val row = array.optJSONObject(i) ?: continue
                    val file = parseFile(row, folderId) ?: continue
                    if (seen.add(file.id)) {
                        result.add(file)
                        added++
                    }
                }
                val totalPage = json.optInt("totalPage", 0)
                if (totalPage > 0) {
                    if (page >= totalPage) break
                } else if (array.length() < 60) {
                    break
                }
                if (added == 0) break
                page++
            }
            result
        }

    /** 取个��文件的直链；302 时读 Location，否则读 JSON 的 url */
    suspend fun download(
        token: String,
        uuid: String,
        userId: String,
        fileId: String
    ): DownloadSpec = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val url = buildUrl(
            "/ws/file/redirect",
            uuid,
            token,
            linkedMapOf(
                "enable" to "1",
                "downloadId" to aesHex("${id(fileId)}|$userId"),
                "auth" to aesHex("${id(fileId)}|$now")
            ),
            includeExtra = false,
            timestamp = now
        )
        val request = Request.Builder()
            .url(url)
            .get()
            .apply { downloadHeaders.forEach { (k, v) -> header(k, v) } }
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) {
                throw IOException("小飞机登录已过期，请重新登录")
            }
            if (response.code in 300..399) {
                val location = response.header("Location").orEmpty()
                if (location.isNotBlank()) {
                    return@withContext DownloadSpec(resolveUrl(location), downloadHeaders)
                }
            }
            val body = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(body) }.getOrNull()
                ?: throw IOException("小飞机未返回下载地址")
            if (json.optInt("code") == -2) {
                throw IOException("小飞机登录已过期，请重新登录")
            }
            if (json.optInt("code") != 200) {
                throw IOException(json.optString("msg", "小飞机未返回下载地址"))
            }
            val direct = json.optString("url").ifBlank {
                json.optJSONObject("data")?.optString("url").orEmpty()
            }
            if (direct.isBlank()) throw IOException("小飞机未返回下载地址")
            DownloadSpec(resolveUrl(direct), downloadHeaders)
        }
    }

    /** 取分享文件的直链：shareId 是分享短码，fileId 来自列表 */
    suspend fun downloadShare(
        token: String,
        uuid: String,
        userId: String,
        shareId: String,
        fileId: String,
        code: String
    ): DownloadSpec = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val url = buildUrl(
            "/ws/file/download",
            uuid,
            token,
            linkedMapOf(
                "shareId" to shareId,
                "code" to code,
                "enable" to "1",
                "downloadId" to aesHex("${id(fileId)}|$userId"),
                "auth" to aesHex("${id(fileId)}|$now")
            ),
            timestamp = now
        )
        val json = getJson(url)
        val direct = aesDecryptHex(json.optString("downloadUrl"))
        if (direct.isBlank()) throw IOException("小飞机下载地址解码失败")
        DownloadSpec(resolveUrl(direct), downloadHeaders)
    }

    // ---------- 个人盘操作（对齐 wenxi PersonalCloudConnector） ----------

    /** 创建文件夹（POST /app/file/folder/save） */
    suspend fun createFolder(
        token: String, uuid: String, folderId: String, name: String
    ): File = withContext(Dispatchers.IO) {
        val json = postJson(
            buildUrl("/app/file/folder/save", uuid, token),
            JSONObject()
                .put("folderDesc", "")
                .put("folderId", id(folderId))
                .put("folderName", name)
        )
        val row = json.optJSONArray("list")?.optJSONObject(0)
            ?: throw IOException("小飞机创建文件夹返回格式异常")
        val newId = row.optString("id").ifBlank { row.optString("folderId") }
        if (newId.isBlank()) throw IOException("小飞机创建文件夹返回格式异常")
        File(
            id = id(newId),
            name = row.optString("folderName").ifBlank { name },
            isDir = true,
            size = 0,
            parentId = folderId
        )
    }

    /** 重命名：文件夹 /app/file/folder/edit，文件 /app/file/edit */
    suspend fun rename(
        token: String, uuid: String, file: File, newName: String
    ): Boolean = withContext(Dispatchers.IO) {
        val type = if (file.isDir) "folder" else "file"
        val json = postJson(
            buildUrl(if (file.isDir) "/app/file/folder/edit" else "/app/file/edit", uuid, token),
            JSONObject()
                .put("${type}Desc", "")
                .put("${type}Id", id(file.id))
                .put("${type}Name", newName)
        )
        json.optInt("code", 0) == 200
    }

    /** 删除（POST /app/file/delete，文件夹与文件分字段逗号拼接） */
    suspend fun delete(
        token: String, uuid: String, files: List<File>
    ): Boolean = withContext(Dispatchers.IO) {
        if (files.isEmpty()) return@withContext true
        val json = postJson(
            buildUrl("/app/file/delete", uuid, token),
            JSONObject()
                .put("folderIds", files.filter { it.isDir }.joinToString(",") { id(it.id) })
                .put("fileIds", files.filter { !it.isDir }.joinToString(",") { id(it.id) })
                .put("status", 0)
        )
        json.optInt("code", 0) == 200
    }

    /** 移动（POST /app/file/folder/move）；targetFolderId = "0" 表示根目录 */
    suspend fun move(
        token: String, uuid: String, files: List<File>, targetFolderId: String
    ): Boolean = withContext(Dispatchers.IO) {
        if (files.isEmpty()) return@withContext true
        val json = postJson(
            buildUrl("/app/file/folder/move", uuid, token),
            JSONObject()
                .put("folderIds", files.filter { it.isDir }.joinToString(",") { id(it.id) })
                .put("fileIds", files.filter { !it.isDir }.joinToString(",") { id(it.id) })
                .put("targetId", id(targetFolderId))
        )
        json.optInt("code", 0) == 200
    }

    /**
     * 创建分享（POST /app/share/url）。
     * @param codes 提取码（可空，4 位字母/数字）
     * @param validDays 有效期天数，0 = 永久
     */
    suspend fun share(
        token: String, uuid: String, files: List<File>,
        codes: String = "", validDays: Int = 0
    ): ShareInfo = withContext(Dispatchers.IO) {
        if (files.isEmpty()) throw IOException("请选择要分享的文件")
        if (files.size > 5) throw IOException("小飞机一次最多分享 5 个文件或文件夹")
        val code = codes.trim()
        if (code.isNotEmpty() && !Regex("^[A-Za-z0-9]{4}$").matches(code)) {
            throw IOException("小飞机提取码应为 4 位字母或数字")
        }
        val json = postJson(
            buildUrl("/app/share/url", uuid, token),
            JSONObject()
                .put("folderIds", files.filter { it.isDir }.joinToString(",") { id(it.id) })
                .put("fileIds", files.filter { !it.isDir }.joinToString(",") { id(it.id) })
                .put("code", code)
                .put("term", validDays)
                .put("amt", "")
                .put("freeDownload", 1)
                .put("showRecommend", 0)
                .put("showUpTime", 1)
                .put("showDownloads", 0)
                .put("showComments", 0)
                .put("showStars", 0)
                .put("showLikes", 0)
        )
        val shareUrl = json.optString("shareUrl")
        if (shareUrl.isBlank()) throw IOException("小飞机未返回分享地址")
        ShareInfo(
            shareUrl = shareUrl,
            passcode = json.optString("code").ifBlank { code },
            title = files.firstOrNull()?.name ?: "小飞机分享"
        )
    }

    /** 转存分享文件到个人网盘（POST /app/file/transfer） */
    suspend fun transfer(
        token: String, uuid: String, shareId: String, code: String,
        targetFolderId: String, files: List<File>
    ): Boolean = withContext(Dispatchers.IO) {
        if (files.isEmpty()) return@withContext true
        val json = postJson(
            buildUrl("/app/file/transfer", uuid, token),
            JSONObject()
                .put("shareId", shareId)
                .put("code", code)
                .put("folderId", id(targetFolderId))
                .put("targetFileId", files.filter { !it.isDir }.joinToString(",") { id(it.id) })
                .put("targetFolderId", files.filter { it.isDir }.joinToString(",") { id(it.id) })
        )
        json.optInt("code", 0) == 200
    }

    // ---------- 上传（对齐 wenxi 的 feijipan_upload.dart：腾讯云 VOD + 分片） ----------

    /**
     * 上传文件到个人网盘。
     * @param open 打开文件的 [start, end) 输入流（分段上传会多次调用）
     * @param onProgress 已上传字节数
     */
    suspend fun uploadFile(
        token: String,
        uuid: String,
        userId: String,
        account: String,
        folderId: String,
        fileName: String,
        size: Long,
        open: (Long, Long) -> InputStream,
        onProgress: (Long) -> Unit = {}
    ): UploadResult = withContext(Dispatchers.IO) {
        if (size <= 0) throw IOException("小飞机不支持上传空文件")
        if (!Regex("^[A-Za-z0-9_-]+$").matches(account)) {
            throw IOException("小飞机上传账号标识无效，请重新登录")
        }
        if (userId.isBlank()) throw IOException("小飞机账号信息不完整，请重新登录")
        val existing = list(token, uuid, folderId)
        if (existing.any { it.name == fileName }) {
            throw IOException("目录中已有同名文件，请重命名后上传")
        }
        val md5 = digest(open, size, "MD5", onProgress)
        val now = System.currentTimeMillis()
        val day = SimpleDateFormat("yyyy/MM/dd", Locale.US).format(Date(now))
        val path = "storage/files/$day/${account.last()}/$account/" +
            "$now${UUID.randomUUID().toString().replace("-", "")}.gz"
        val kib = max(1L, (size / 1024.0).roundToLong())
        val body = JSONObject()
            .put("fileName", fileName)
            .put("fileType", 1)
            .put("userId", userId)
            .put("md5", md5)
            .put("pathKey", path)
            .put("fileSize", kib)
            .put("folderId", id(folderId))
            .put("fileId", "")
        val pre = postJson(buildUrl("/app/vod/getUpToken", uuid, token), body)
        if (pre.optString("upToken") == "-1") {
            val fid = pre.optJSONObject("map")?.optString("fileId").orEmpty()
            if (fid.isBlank()) throw IOException("小飞机未确认秒传文件")
            onProgress(size)
            return@withContext UploadResult(fid, fileName, size)
        }
        val credentials = pre.optJSONObject("upToken")
            ?: throw IOException("小飞机未返回上传授权")
        val accessKeyId = credentials.optString("accessKeyId")
        val secretAccessKey = credentials.optString("secretAccessKey")
        val sessionToken = credentials.optString("sessionToken")
        if (accessKeyId.isBlank() || secretAccessKey.isBlank() || sessionToken.isBlank()) {
            throw IOException("小飞机未返回完整上传授权")
        }
        val callbackBody = JSONObject(body.toString()).put("fileSize", kib * 1024)
        val callback = android.util.Base64.encodeToString(
            JSONObject()
                .put("callbackMode", "HTTP_ASYNC")
                .put("callbackBody", callbackBody.toString())
                .toString()
                .toByteArray(),
            android.util.Base64.NO_WRAP
        )
        vodUpload(
            open = open,
            size = size,
            path = path,
            accessKeyId = accessKeyId,
            secretAccessKey = secretAccessKey,
            sessionToken = sessionToken,
            callback = callback,
            onProgress = onProgress
        )
        onProgress(size)
        for (attempt in 0 until 60) {
            val result = getJson(
                buildUrl("/ws/vod/results", uuid, token, linkedMapOf("token" to path))
            ).optJSONObject("map")
            if (result?.optInt("status") == 1) {
                val fid = result.optString("fileId")
                if (fid.isBlank()) throw IOException("小飞机上传结果缺少文件标识")
                return@withContext UploadResult(fid, fileName, size)
            }
            delay(1_000)
        }
        throw IOException("小飞机正在处理上传，请稍后刷新目录确认")
    }

    private fun digest(
        open: (Long, Long) -> InputStream,
        size: Long,
        algorithm: String,
        onProgress: (Long) -> Unit
    ): String {
        val md = MessageDigest.getInstance(algorithm)
        open(0, size).use { input ->
            val buffer = ByteArray(256 * 1024)
            var readTotal = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                md.update(buffer, 0, n)
                readTotal += n
                onProgress(readTotal)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun vodUpload(
        open: (Long, Long) -> InputStream,
        size: Long,
        path: String,
        accessKeyId: String,
        secretAccessKey: String,
        sessionToken: String,
        callback: String,
        onProgress: (Long) -> Unit
    ) {
        val host = "1500033322.vodpro-upload.com"
        val uri = java.net.URI("https://$host/9b5hmmeuqkg00qf/$path")
        val partSize = max(4L * 1024 * 1024, ceil(size / 9000.0).toLong())

        fun sign(method: String, params: Map<String, String>, headers: Map<String, String>): String {
            val nowSec = System.currentTimeMillis() / 1000
            val keyTime = "${nowSec - 1};${nowSec + 600}"
            val headerNames = headers.keys.map { it.lowercase() }.sorted()
            val paramNames = params.keys.map { it.lowercase() }.sorted()
            val canonicalHeaders = headerNames.joinToString("&") { key ->
                val value = headers.entries.first { it.key.equals(key, ignoreCase = true) }.value
                "${percentEncode(key)}=${percentEncode(value)}"
            }
            val canonicalParams = paramNames.joinToString("&") { key ->
                val value = params.entries.first { it.key.equals(key, ignoreCase = true) }.value
                "${percentEncode(key)}=${percentEncode(value)}"
            }
            val canonical = "${method.lowercase()}\n${uri.path}\n$canonicalParams\n$canonicalHeaders\n"
            val signing = hmacSha1Hex(secretAccessKey, keyTime)
            val text = "sha1\n$keyTime\n${sha1Hex(canonical)}\n"
            val signature = hmacSha1Hex(signing, text)
            return "q-sign-algorithm=sha1&q-ak=$accessKeyId&q-sign-time=$keyTime" +
                "&q-key-time=$keyTime&q-header-list=${headerNames.joinToString(";")}" +
                "&q-url-param-list=${paramNames.joinToString(";")}&q-signature=$signature"
        }

        fun request(
            method: String,
            params: Map<String, String>,
            body: RequestBody? = null,
            meta: Boolean = false
        ): okhttp3.Response {
            val headers = linkedMapOf(
                "host" to host,
                "x-cos-security-token" to sessionToken
            )
            if (meta) headers["x-amz-meta-callback"] = callback
            headers["Authorization"] = sign(method, params, headers)
            val urlBuilder = uri.toString().toHttpUrl().newBuilder()
            params.forEach { (k, v) -> urlBuilder.addQueryParameter(k, v) }
            val builder = Request.Builder().url(urlBuilder.build())
            headers.forEach { (k, v) -> builder.header(k, v) }
            when (method) {
                "PUT", "POST" -> builder.method(method, body ?: EMPTY_BODY)
                "DELETE" -> builder.delete()
                else -> builder.get()
            }
            return client.newCall(builder.build()).execute()
        }

        if (size <= partSize) {
            val streamBody = object : RequestBody() {
                override fun contentType() = "application/xml".toMediaTypeOrNull()
                override fun contentLength() = size
                override fun writeTo(sink: BufferedSink) {
                    open(0, size).use { input ->
                        val buffer = ByteArray(256 * 1024)
                        var sent = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            sink.write(buffer, 0, n)
                            sent += n
                            onProgress(sent)
                        }
                    }
                }
            }
            request("PUT", emptyMap(), streamBody, meta = true).use { response ->
                if (!response.isSuccessful) throw IOException("文件上传失败（HTTP ${response.code}）")
            }
            return
        }

        val uploadId = request("POST", mapOf("uploads" to ""), meta = true).use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("小飞机上传服务返回异常（HTTP ${response.code}）")
            Regex("<UploadId>(.*?)</UploadId>").find(body)?.groupValues?.getOrNull(1).orEmpty()
        }
        if (uploadId.isBlank()) throw IOException("小飞机未创建分段上传")
        var completed = false
        try {
            val etags = mutableListOf<String>()
            var start = 0L
            while (start < size) {
                val end = minOf(size, start + partSize)
                val partBody = object : RequestBody() {
                    override fun contentType() = "application/octet-stream".toMediaTypeOrNull()
                    override fun contentLength() = end - start
                    override fun writeTo(sink: BufferedSink) {
                        open(start, end).use { input ->
                            val buffer = ByteArray(256 * 1024)
                            var sent = 0L
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                sink.write(buffer, 0, n)
                                sent += n
                                onProgress(start + sent)
                            }
                        }
                    }
                }
                val etag = request(
                    "PUT",
                    mapOf(
                        "partNumber" to "${etags.size + 1}",
                        "uploadId" to uploadId
                    ),
                    partBody
                ).use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("小飞机上传分段失败（HTTP ${response.code}）")
                    }
                    response.header("ETag").orEmpty().trim('"')
                }
                if (etag.isBlank()) throw IOException("小飞机未确认上传分段")
                etags.add(etag)
                start = end
            }
            val completeXml = buildString {
                append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
                append("<CompleteMultipartUpload>")
                etags.forEachIndexed { index, etag ->
                    append("<Part><PartNumber>${index + 1}</PartNumber><ETag>") 
                    append(etag)
                    append("</ETag></Part>")
                }
                append("</CompleteMultipartUpload>")
            }
            request(
                "POST",
                mapOf("uploadId" to uploadId),
                completeXml.toRequestBody("application/xml".toMediaTypeOrNull())
            ).use { response ->
                if (!response.isSuccessful) {
                    throw IOException("小飞机合并上传分段失败（HTTP ${response.code}）")
                }
            }
            completed = true
        } finally {
            if (!completed) {
                runCatching { request("DELETE", mapOf("uploadId" to uploadId)).close() }
            }
        }
    }

    private fun percentEncode(value: String): String = buildString {
        for (byte in value.toByteArray()) {
            val c = byte.toInt() and 0xff
            val unreserved = (c in 'A'.code..'Z'.code) || (c in 'a'.code..'z'.code) ||
                (c in '0'.code..'9'.code) || c == '-'.code || c == '_'.code ||
                c == '.'.code || c == '~'.code
            if (unreserved) append(c.toChar()) else append("%%%02X".format(c))
        }
    }

    private fun sha1Hex(value: String): String =
        MessageDigest.getInstance("SHA1").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun hmacSha1Hex(key: String, value: String): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key.toByteArray(), "HmacSHA1"))
        return mac.doFinal(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private val EMPTY_BODY: RequestBody =
        ByteArray(0).toRequestBody("application/xml".toMediaTypeOrNull())

    /**
     * 分享文件列表（POST /ws/recommend/list 根目录，/ws/share/list 子目录）。
     *
     * 响应里 `list` 可能是「扁平」（每行一个文件）或「嵌套」（每个顶层条目有 `fileList` 数组），
     * 对齐 wenxi 的 `_shareFiles`：两种格式都要展开。
     *
     * [parent] = "share-root" 表示根目录，其他值直接作为 folderId。
     */
    suspend fun shareList(
        uuid: String,
        token: String,
        shareId: String,
        code: String,
        parent: String = SHARE_ROOT,
        page: Int = 1
    ): ShareListResult = withContext(Dispatchers.IO) {
        val isRoot = parent == SHARE_ROOT
        val extra = linkedMapOf(
            "shareId" to shareId,
            "code" to code,
            "offset" to page.toString(),
            "limit" to "60"
        )
        if (isRoot) extra["type"] = "0" else extra["folderId"] = id(parent)
        val json = postJson(
            buildUrl(
                if (isRoot) "/ws/recommend/list" else "/ws/share/list",
                uuid,
                token,
                extra
            ),
            null
        )
        val status = json.optInt("status", 0)
        if (status == 1 || status == 2) {
            throw IOException("小飞机分享需要正确的提取码，请填写后重试")
        }
        if (status == -1) throw IOException("小飞机分享已失效或暂不可用")
        val topArray = json.optJSONArray("list") ?: JSONArray()
        val files = mutableListOf<File>()
        for (i in 0 until topArray.length()) {
            val row = topArray.optJSONObject(i) ?: continue
            val fileList = row.optJSONArray("fileList")
            if (fileList != null) {
                for (j in 0 until fileList.length()) {
                    val sub = fileList.optJSONObject(j) ?: continue
                    parseFile(sub, parent)?.let { files.add(it) }
                }
            } else {
                parseFile(row, parent)?.let { files.add(it) }
            }
        }
        val totalPage = json.optInt("totalPage", 0)
        val hasMore = if (totalPage > 0) page < totalPage else topArray.length() >= 60
        ShareListResult(files = files, hasMore = hasMore, title = json.optString("title"))
    }

    /** 分享目录全部文件（自动翻页，对齐 wenxi 的 _shareFiles） */
    suspend fun shareFiles(
        uuid: String,
        token: String,
        shareId: String,
        code: String,
        parent: String = SHARE_ROOT
    ): List<File> = withContext(Dispatchers.IO) {
        val result = mutableListOf<File>()
        val seen = mutableSetOf<String>()
        var page = 1
        while (page <= 1000) {
            val data = shareList(uuid, token, shareId, code, parent, page)
            for (file in data.files) {
                if (seen.add(file.id)) result.add(file)
            }
            if (!data.hasMore) break
            page++
        }
        result
    }

    private fun parseFile(row: JSONObject, parentId: String): File? {
        val type = row.optInt("fileType")
        if (type != 1 && type != 2) return null
        val isDir = type == 2
        val rawId = row.optString(if (isDir) "folderId" else "fileId")
        val name = row.optString(if (isDir) "folderName" else "fileName")
        if (rawId.isBlank() || name.isBlank()) return null
        val size = if (isDir) 0L else {
            ((row.optString("fileSize").toDoubleOrNull() ?: 0.0) * 1024).toLong()
        }
        // 对齐 wenxi：updTime 优先，其次 addTime
        val modifyTime = row.optString("updTime").ifBlank { row.optString("addTime") }
        return File(
            id = id(rawId),
            name = name,
            isDir = isDir,
            size = size,
            parentId = parentId,
            modifyTime = modifyTime
        )
    }

    private fun getJson(url: String): JSONObject {
        val request = Request.Builder()
            .url(url)
            .get()
            .apply { downloadHeaders.forEach { (k, v) -> header(k, v) } }
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(body) }.getOrNull()
                ?: throw IOException("小飞机返回格式异常")
            if (response.code == 401 || json.optInt("code") == -2) {
                throw IOException("小飞机登录已过期，请重新登录")
            }
            if (json.optInt("code") != 200) {
                throw IOException(json.optString("msg", "小飞机请求失败"))
            }
            return json
        }
    }

    /** POST JSON 请求；body 为 null 时发送空请求体（分享列表接口） */
    private fun postJson(url: String, body: JSONObject?): JSONObject {
        val payload = body?.toString().orEmpty()
        val request = Request.Builder()
            .url(url)
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
            .apply { downloadHeaders.forEach { (k, v) -> header(k, v) } }
            .build()
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(raw) }.getOrNull()
                ?: throw IOException("小飞机请求失败（HTTP ${response.code}）")
            if (response.code == 401 || json.optInt("code") == -2) {
                throw IOException("小飞机登录已过期，请重新登录")
            }
            if (json.optInt("code") != 200) {
                throw IOException(json.optString("msg", "小飞机操作失败（code=${json.optInt("code")}）"))
            }
            return json
        }
    }

    private fun buildUrl(
        path: String,
        uuid: String,
        token: String,
        extra: Map<String, String> = emptyMap(),
        includeExtra: Boolean = true,
        timestamp: Long = System.currentTimeMillis()
    ): String {
        val params = linkedMapOf(
            "uuid" to uuid,
            "devType" to "6",
            "devCode" to uuid,
            "devModel" to "chrome",
            "devVersion" to "131",
            "appVersion" to "",
            "timestamp" to aesHex(timestamp.toString())
        )
        if (token.isNotBlank()) params["appToken"] = token
        if (includeExtra) params["extra"] = "2"
        params.putAll(extra)
        val query = params.entries.joinToString("&") { (key, value) ->
            val encoded = URLEncoder.encode(value, "UTF-8")
            // 与 wenxi 的 tokenQueryValue 一致：只把 %3A 还原成冒号
            val tokenValue = if (key == "appToken") encoded.replace("%3A", ":") else encoded
            "${URLEncoder.encode(key, "UTF-8")}=$tokenValue"
        }
        return "$BASE$path?$query"
    }

    private fun resolveUrl(value: String): String {
        if (value.startsWith("http://") || value.startsWith("https://")) return value
        return runCatching { java.net.URI(BASE).resolve(value).toString() }.getOrDefault(value)
    }

    /** 文件 / 文件夹 ID：去掉 d:/f: 前缀，必须是纯数字 */
    private fun id(value: String): String {
        val raw = value.trim().replaceFirst(Regex("^[df]:"), "")
        if (!Regex("^\\d+$").matches(raw)) throw IOException("小飞机文件标识无效，请刷新列表")
        return raw
    }

    private fun aesHex(text: String): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(AES_KEY.toByteArray(), "AES"))
        val bytes = cipher.doFinal(text.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun aesDecryptHex(hex: String): String {
        if (hex.isBlank() || hex.length % 2 != 0) return ""
        val raw = ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return runCatching {
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(AES_KEY.toByteArray(), "AES"))
            String(cipher.doFinal(raw))
        }.getOrDefault("")
    }

    const val SHARE_ROOT = "share-root"
}
