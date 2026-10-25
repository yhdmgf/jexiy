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
 * with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.jiexiy.app.data.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.InputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 阿里云 OSS 分片上传（v1 签名），供网盘把 STS 临时凭据交给客户端直传的场景使用。
 *
 * 流程：`POST ?uploads=` 创建分片任务 → 每片 `PUT ?partNumber=&uploadId=` →
 * `POST ?uploadId=` 提交 `CompleteMultipartUpload` XML；任一步失败则 `DELETE ?uploadId=` 撤销任务，
 * 避免留下半截对象。
 *
 * 注意与夸克/UC 的区别：那里每片授权由网盘侧 `upload/auth` 接口下发（客户端不接触密钥），
 * 这里用 [secretKey] 本地做 HMAC-SHA1 签名，所以两者不能共用一个实现。
 */
object OssMultipartUploader {

    private const val MIN_PART_SIZE = 8L * 1024 * 1024
    private const val TARGET_PARTS = 9000L
    private const val OSS_USER_AGENT = "aliyun-sdk-js/6.6.1 Chrome 98.0.4758.80 on Windows 10 64-bit"

    /**
     * @param endpoint 网盘下发的上传域名（可含 scheme，缺省按 https 处理）
     * @param objectKey 对象路径（不含 bucket）
     * @param securityToken STS 临时令牌，可空
     * @param referer 可选防盗链 Referer
     * @param onProgress 已上传字节数
     */
    fun upload(
        endpoint: String,
        bucket: String,
        objectKey: String,
        accessKey: String,
        secretKey: String,
        securityToken: String = "",
        size: Long = 0L,
        contentType: String = "application/octet-stream",
        referer: String = "",
        open: (Long, Long) -> InputStream,
        onProgress: (Long) -> Unit = {}
    ): Unit {
        require(bucket.isNotBlank()) { "上传凭据缺少 bucket" }
        require(objectKey.isNotBlank()) { "上传凭据缺少对象路径" }
        require(accessKey.isNotBlank() && secretKey.isNotBlank()) { "上传凭据缺少密钥" }

        val rawEndpoint = if (endpoint.contains("://")) endpoint else "https://$endpoint"
        val parsed = UriParts(rawEndpoint)
        var host = parsed.host
        if (host.isNotBlank() && !host.startsWith("$bucket.")) host = "$bucket.$host"
        if (host.isBlank()) throw IllegalStateException("网盘返回的上传服务器无效")
        // ★ 用 objectKey 替换 endpoint 的 path（对齐 wenxi upload_io.dart 的
        //   `base.replace(pathSegments: object.split('/'))`）。光鸭返回的 endpoint 可能带前缀
        //   路径（如 /upload/），如果保留会让请求 URI 与 resource 不一致，签名直接失败。
        val objectPath = objectKey.removePrefix("/")
        val uri = "https://$host/$objectPath"

        fun request(
            method: String,
            parameters: Map<String, String>,
            body: RequestBody? = null,
            start: Long? = null,
            end: Long? = null,
            type: String = contentType
        ): ResponseWrapper {
            val date = rfc1123()
            val sorted = parameters.keys.toList().sorted()
            val canonical = sorted.joinToString("&") { key ->
                if (parameters[key].isNullOrEmpty()) key else "$key=${parameters[key]}"
            }
            val resource = "/$bucket/${objectKey.removePrefix("/")}" +
                if (canonical.isEmpty()) "" else "?$canonical"
            val signed = buildString {
                append(method).append('\n')
                appendLine("")
                appendLine(type)
                append(date)
                if (securityToken.isNotBlank()) appendLine("x-oss-security-token:$securityToken")
                append(resource)
            }
            val signature = Base64Encode(hmacSha1(secretKey, signed))
            val headers = LinkedHashMap<String, String>()
            headers["Authorization"] = "OSS $accessKey:$signature"
            headers["Date"] = date
            headers["Content-Type"] = type
            if (securityToken.isNotBlank()) headers["x-oss-security-token"] = securityToken
            if (referer.isNotBlank()) headers["Referer"] = referer
            // 不发送 x-oss-user-agent，避免未签名 header 导致 SignatureDoesNotMatch

            val address = if (parameters.isEmpty()) uri else "$uri?${parameters.entries.joinToString("&") { (k, v) -> "$k=$v" }}"
            if (start != null && end != null) {
                val resp = UploadIo.rangePut(address, start, end, open, type, headers, false, onProgress)
                return ResponseWrapper(resp.code, resp.isSuccessful, resp.header("ETag").orEmpty(), "")
            }
            val requestBuilder = Request.Builder().url(address).method(method, body ?: EMPTY_BODY)
            headers.forEach { (k, v) -> requestBuilder.header(k, v) }
            val response = UploadIo.execute(requestBuilder.build(), false)
            return response.use { resp ->
                ResponseWrapper(resp.code, resp.isSuccessful, resp.header("ETag").orEmpty(),
                    resp.body?.string().orEmpty())
            }
        }

        if (size <= 0) {
            val resp = request("PUT", emptyMap(), start = 0, end = 0, type = contentType)
            checkOss(resp.code, "OSS 空文件上传失败")
            return
        }

        val initiated = request("POST", linkedMapOf("uploads" to ""), body = EMPTY_BODY, type = "application/xml")
        if (!initiated.isSuccessful) throw IllegalStateException("OSS 创建分片任务失败（HTTP ${initiated.code}）")
        val uploadId = xmlValue(initiated.body, "UploadId")
        if (uploadId.isBlank()) throw IllegalStateException("OSS 未返回分片任务标识")

        var completed = false
        try {
            val partSize = maxOf(MIN_PART_SIZE, (size + TARGET_PARTS - 1) / TARGET_PARTS)
            val etags = ArrayList<String>()
            var offset = 0L
            while (offset < size) {
                val stop = minOf(size, offset + partSize)
                val resp = request(
                    "PUT",
                    linkedMapOf("partNumber" to (etags.size + 1).toString(), "uploadId" to uploadId),
                    start = offset,
                    end = stop
                )
                if (resp.etag.isBlank()) throw IllegalStateException("OSS 未确认上传分段（HTTP ${resp.code}）")
                etags.add(resp.etag)
                offset = stop
            }
            val xml = completeXml(etags)
            val done = request("POST", linkedMapOf("uploadId" to uploadId),
                body = xml.toByteArray().toRequestBody("application/xml".toMediaType()), type = "application/xml")
            if (!done.isSuccessful || done.body.contains("<Error>")) {
                throw IllegalStateException("OSS 未完成分片合并（HTTP ${done.code}）")
            }
            completed = true
        } finally {
            if (!completed) {
                runCatching { request("DELETE", linkedMapOf("uploadId" to uploadId), type = "application/xml") }
            }
        }
    }

    private fun checkOss(code: Int, fallback: String) {
        if (code < 200 || code >= 300) throw IllegalStateException("$fallback（HTTP $code）")
    }

    private fun completeXml(etags: List<String>): String = buildString {
        append("<CompleteMultipartUpload>")
        etags.forEachIndexed { index, etag ->
            append("<Part><PartNumber>").append(index + 1)
                .append("</PartNumber><ETag>").append(xmlEscape(etag)).append("</ETag></Part>")
        }
        append("</CompleteMultipartUpload>")
    }

    private fun xmlEscape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun xmlValue(body: String, tag: String): String {
        val match = Regex("""<$tag>([\s\S]*?)</$tag>""").find(body) ?: return ""
        return match.groupValues[1]
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
    }

    private fun rfc1123(): String = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'",
        java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date())

    private fun hmacSha1(secret: String, message: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8))
    }

    private fun Base64Encode(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)

    /** 极简 URL 拆分（只需 host + path，不解析 query）。 */
    private class UriParts(url: String) {
        val host: String
        val path: String
        init {
            val afterScheme = url.substringAfter("://")
            val slashIndex = afterScheme.indexOf('/')
            host = if (slashIndex < 0) afterScheme else afterScheme.substring(0, slashIndex)
            path = if (slashIndex < 0) "" else afterScheme.substring(slashIndex + 1)
                .substringBefore('?').substringBefore('#')
                .split("/").filterTo(ArrayList(), { it.isNotBlank() }).joinToString("/")
        }
    }

    private data class ResponseWrapper(
        val code: Int,
        val isSuccessful: Boolean,
        val etag: String,
        val body: String
    )

    private val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody()
}
