/*
 * jexiy (jexiy) - A network drive share-link parser and high-speed downloader for Android.
 * Copyright (C) 2026 jexiy
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along
 * with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.jiexiy.app.data.network

import android.net.Uri
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.InputStream
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 阿里云 CCP 上传流程（夸克网盘 / UC 网盘共用同一套接口，仅域名、产品码与防盗链头不同）：
 *
 * 1. `upload/pre` 取一次性上传凭据（含 STS `auth_info`、`obj_key`、`upload_id`）；
 * 2. `update/hash` 提交 MD5 + SHA1，命中已有内容时 `finish=true` 直接秒传；
 * 3. 否则每片单独向 `upload/auth` 签发 OSS 授权头（客户端拿不到密钥，只拿签名结果）；
 * 4. `CompleteMultipartUpload` XML 合并；
 * 5. `upload/finish` 让网盘把对象正式入库。
 *
 * 与 [OssMultipartUploader] / [S3v4Uploader] 的区别：那两个由网盘下发密钥、客户端本地签名；
 * 这里由网盘代签，每片一次授权，所以流程必须逐片申请，不能复用同一个签名。
 */
object CcpUploader {

    /** 平台差异配置：接口地址（含产品码）+ 上传时的防盗链与 UA 头。 */
    data class Config(
        val label: String,
        val uploadPreUrl: String,
        val updateHashUrl: String,
        val uploadAuthUrl: String,
        val uploadFinishUrl: String,
        val ossReferer: String,
        val ossUserAgent: String,
        val defaultPartSize: Long = 8L * 1024 * 1024
    )

    /** `upload/pre` 返回的一次性上传凭据。 */
    private data class Credentials(
        val taskId: String,
        val bucket: String,
        val objectKey: String,
        val uploadId: String,
        val uploadUrl: String,
        val authInfo: String,
        val callbackJson: String,
        val partSize: Long
    )

    /**
     * 上传一个文件，返回新文件标识（CCP 的 `obj_key`）。
     *
     * @param post 平台自己的 JSON POST 实现，返回响应的 `data` 节点（供 pre/auth/hash/finish 复用）
     * @param open 打开文件的 [start, end) 输入流（算 hash 与分片都会重新调用）
     * @param onProgress 已传输字节数
     */
    fun upload(
        config: Config,
        post: (url: String, body: String) -> JSONObject,
        fileName: String,
        size: Long,
        parentFid: String,
        mimeType: String,
        open: (Long, Long) -> InputStream,
        onProgress: (Long) -> Unit = {}
    ): String {
        val label = config.label
        val md5 = UploadIo.digest(open, size, "MD5", onProgress)
        val sha1 = UploadIo.digest(open, size, "SHA-1", onProgress)
        val json = pre(config, post, parentFid, fileName, size, mimeType)

        val hashData = post(config.updateHashUrl, JSONObject()
            .put("md5", md5).put("sha1", sha1).put("task_id", json.taskId).toString())
        if (!hashData.optBoolean("finish", false)) {
            val endpoint = Uri.parse(json.uploadUrl)
            val endpointHost = endpoint.host.orEmpty()
            val host = if (endpointHost.startsWith("${json.bucket}.")) endpointHost
            else "${json.bucket}.$endpointHost"
            val path = json.objectKey.removePrefix("/")
            val address = "https://$host/${path.encodePath()}"
            val partSize = json.partSize
            val count = ((size + partSize - 1) / partSize).toInt().coerceAtLeast(1)
            val etags = ArrayList<String>(count)
            for (index in 0 until count) {
                val date = rfc1123()
                val partNumber = index + 1L
                val key = authorize(config, post, json.authInfo, json.taskId, "PUT", "", mimeType, date,
                    "/${json.bucket}/${json.objectKey}?partNumber=$partNumber&uploadId=${json.uploadId}")
                UploadIo.rangePut(
                    url = "$address?partNumber=$partNumber&uploadId=${json.uploadId}",
                    start = index * partSize,
                    end = minOf(size, (index + 1) * partSize),
                    open = open,
                    contentType = mimeType,
                    headers = linkedMapOf(
                        "Authorization" to key,
                        "Referer" to config.ossReferer,
                        "x-oss-date" to date,
                        "x-oss-user-agent" to config.ossUserAgent
                    ),
                    followRedirects = false,
                    onProgress = onProgress
                ).use { resp ->
                    val etag = resp.header("ETag").orEmpty()
                    if (resp.code == 403) throw IllegalStateException("$label 上传授权已失效，请重试")
                    if (etag.isBlank()) throw IllegalStateException("$label 未确认上传分段（HTTP ${resp.code}）")
                    etags.add(etag)
                }
            }

            val xml = completeXml(etags)
            val hash = Base64.encodeToString(
                MessageDigest.getInstance("MD5").digest(xml.toByteArray(Charsets.UTF_8)),
                Base64.NO_WRAP
            )
            val callback = Base64.encodeToString(
                json.callbackJson.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val date = rfc1123()
            val key = authorize(config, post, json.authInfo, json.taskId, "POST", hash, "application/xml", date,
                "/${json.bucket}/${json.objectKey}?uploadId=${json.uploadId}",
                mapOf("x-oss-callback" to callback))
            postXml(
                "$address?uploadId=${json.uploadId}",
                xml,
                linkedMapOf(
                    "Authorization" to key,
                    "Content-MD5" to hash,
                    "Referer" to config.ossReferer,
                    "x-oss-callback" to callback,
                    "x-oss-date" to date,
                    "x-oss-user-agent" to config.ossUserAgent
                )
            ).use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.code == 403) throw IllegalStateException("$label 合并上传授权已失效，请重试")
                if (!resp.isSuccessful || body.contains("<Error>")) {
                    throw IllegalStateException("$label 未合并上传分段（HTTP ${resp.code}）")
                }
            }
        }

        post(config.uploadFinishUrl, JSONObject()
            .put("obj_key", json.objectKey).put("task_id", json.taskId).toString())
        json.objectKey
    }

    private fun pre(
        config: Config,
        post: (String, String) -> JSONObject,
        parentFid: String,
        fileName: String,
        size: Long,
        mimeType: String
    ): Credentials {
        val now = System.currentTimeMillis()
        val data = post(
            config.uploadPreUrl,
            JSONObject()
                .put("ccp_hash_update", true)
                .put("dir_name", "")
                .put("file_name", fileName)
                .put("format_type", mimeType)
                .put("pdir_fid", parentFid)
                .put("size", size)
                .put("l_created_at", now)
                .put("l_updated_at", now)
                .put("same_path_reuse", false)
                .toString()
        )
        val bucket = data.optString("bucket")
        val objectKey = data.optString("obj_key")
        val uploadId = data.optString("upload_id")
        val uploadUrl = data.optString("upload_url")
        if (data.optString("task_id").isBlank() || bucket.isBlank() ||
            objectKey.isBlank() || uploadId.isBlank() || uploadUrl.isBlank()
        ) {
            throw IllegalStateException("${config.label} 未返回完整上传凭据")
        }
        return Credentials(
            taskId = data.optString("task_id"),
            bucket = bucket,
            objectKey = objectKey,
            uploadId = uploadId,
            uploadUrl = uploadUrl,
            authInfo = data.optString("auth_info"),
            callbackJson = data.optJSONObject("callback")?.toString()
                ?: data.optJSONArray("callback")?.toString()
                ?: JSONObject().toString(),
            partSize = partSize(data, config.defaultPartSize)
        )
    }

    private fun partSize(data: JSONObject, fallback: Long): Long {
        val meta = data.optJSONObject("metadata")
            ?: runCatching { JSONObject(data.toString()) }.getOrNull()
                ?.optJSONObject("metadata").orNull()
        val raw = meta?.optString("part_size").orEmpty()
        raw.toLongOrNull()?.takeIf { it > 0 }?.let { return it }
        val number = meta?.optLong("part_size")
        return if (number != null && number > 0) number else fallback
    }

    /**
     * 为单片 PUT / 合并 POST 签发一次性 OSS 授权头。
     * `auth_meta` 里的换行是签名结构的一部分，不能合并或改写顺序。
     */
    private fun authorize(
        config: Config,
        post: (String, String) -> JSONObject,
        authInfo: String,
        taskId: String,
        method: String,
        contentMd5: String,
        contentType: String,
        date: String,
        resource: String,
        extra: Map<String, String> = emptyMap()
    ): String {
        val meta = extra.keys.toList().sorted().joinToString("\n") { "$it:${extra[it]}" }
        val body = buildString {
            appendLine(method)
            appendLine(contentMd5)
            appendLine(contentType)
            append(date)
            if (meta.isNotEmpty()) append("\n")
            append(meta)
            append("\n")
            append(resource)
        }
        return post(
            config.uploadAuthUrl,
            JSONObject()
                .put("auth_info", authInfo)
                .put("auth_meta", body)
                .put("task_id", taskId)
                .toString()
        ).optString("auth_key").takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("${config.label} 未签发上传授权")
    }

    private fun completeXml(etags: List<String>): String {
        val parts = StringBuilder()
        etags.forEachIndexed { index, etag ->
            parts.append("<Part><PartNumber>").append(index + 1)
                .append("</PartNumber><ETag>").append(xmlEscape(etag))
                .append("</ETag></Part>")
        }
        return "<CompleteMultipartUpload>" + parts + "</CompleteMultipartUpload>"
    }

    /**
     * CompleteMultipartUpload 是 POST，分片 PUT 走 [UploadIo.rangePut]，
     * 这里单独给合并请求一个不跟随重定向的出口。
     */
    private fun postXml(url: String, xml: String, headers: Map<String, String>): Response {
        val body = xml.toByteArray(Charsets.UTF_8).toRequestBody("application/xml".toMediaType())
        val builder = Request.Builder().url(url).post(body)
        headers.forEach { (key, value) -> builder.header(key, value) }
        return HttpClients.apiClient()
            .newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .newCall(builder.build())
            .execute()
    }

    private fun xmlEscape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    /** OSS v1 签名要求的 RFC 1123 UTC 时间。 */
    private fun rfc1123(): String =
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())

    private fun String.encodePath(): String =
        split("/").joinToString("/") { segment ->
            URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
        }
}
