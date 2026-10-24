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
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * S3 兼容协议（AWS Signature Version 4）分片上传，供网盘下发完整 AK/SK 的场景使用。
 *
 * 流程：单片直接 `PUT`；多片则 `POST ?uploads=` → 每片 `PUT ?uploadId=&partNumber=` →
 * `POST ?uploadId=` 提交 `CompleteMultipartUpload` XML；任一步失败 `POST ?uploadId=&abortMultipartUpload=`
 * 撤销任务，避免留下半截对象。
 *
 * 与 [OssMultipartUploader] 的区别：这里用 `AWS4-HMAC-SHA256` + `x-amz-*` 头；OSS 直传用
 * `OSS` v1 签名 + `x-oss-*` 头，两者的签名串与头前缀都不同，不能共用实现。
 */
object S3v4Uploader {

    private const val MIN_PART_SIZE = 8L * 1024 * 1024
    private const val TARGET_PARTS = 9000L

    /**
     * @param endpoint 网盘下发的上传域名（可含 scheme，缺省按 https 处理）
     * @param region   签名区域（123 网盘固定 `123pan`）
     * @param pathStyle true 时 bucket 走路径前缀（`host/bucket/key`），否则走 CNAME（`bucket.host/key`）
     * @param referer  可选防盗链 Referer
     */
    fun upload(
        endpoint: String,
        bucket: String,
        objectKey: String,
        accessKey: String,
        secretKey: String,
        region: String,
        token: String = "",
        pathStyle: Boolean = false,
        size: Long = 0L,
        contentType: String = "application/octet-stream",
        referer: String = "",
        open: (Long, Long) -> InputStream,
        onProgress: (Long) -> Unit = {}
    ) {
        require(bucket.isNotBlank(), "上传凭据缺少 bucket")
        require(objectKey.isNotBlank(), "上传凭据缺少对象路径")
        require(accessKey.isNotBlank() && secretKey.isNotBlank(), "上传凭据缺少密钥")
        require(region.isNotBlank(), "上传凭据缺少区域")

        val parsed = UriParse(if (endpoint.contains("://")) endpoint else "https://$endpoint")
        val authority = if (pathStyle || parsed.host.startsWith("$bucket.")) parsed.host
        else "$bucket.${parsed.host}"
        if (authority.isBlank()) throw IllegalStateException("网盘返回的上传服务器无效")
        val basePath = "/${(if (pathStyle) listOf(bucket) + objectKey.split("/").filter { it.isNotBlank() }
            else objectKey.split("/").filter { it.isNotBlank() }).joinToString("/") { s3Escape(it) }}"

        fun request(
            method: String,
            parameters: Map<String, String>,
            start: Long? = null,
            end: Long? = null,
            body: String? = null
        ): ResponseWrapper {
            val stamp = rfc3339()
            val date = stamp.substring(0, 8)
            val content = body.orEmpty()
            val contentHash = if (start != null) {
                UploadIo.digest(open, end ?: size, "SHA-256", { }, start = start)
            } else {
                sha256Hex(content.toByteArray(StandardCharsets.UTF_8))
            }
            val headers = LinkedHashMap<String, String>()
            headers["host"] = authority
            headers["x-amz-content-sha256"] = contentHash
            headers["x-amz-date"] = stamp
            if (token.isNotBlank()) headers["x-amz-security-token"] = token
            if (referer.isNotBlank()) headers["referer"] = referer
            val signedHeaders = headers.keys.toList().sorted().joinToString(";")
            val canonicalQuery = parameters.keys.toList().sorted()
                .joinToString("&") { "$it=${s3Escape(parameters[it].orEmpty())}" }
            val canonicalHeaders = headers.keys.toList().sorted()
                .joinToString("") { "$it:${headers[it]}\n" }
            val canonical = "$method\n$basePath\n$canonicalQuery\n$canonicalHeaders$signedHeaders\n$contentHash"
            val scope = "$date/$region/s3/aws4_request"
            val signingKey = hmacSha256(
                hmacSha256(hmacSha256(hmacSha256("AWS4$secretKey".toByteArray(StandardCharsets.UTF_8), date), region), "s3"),
                "aws4_request"
            )
            val signature = hmacSha256(signingKey, "AWS4-HMAC-SHA256\n$stamp\n$scope\n${sha256Hex(canonical.toByteArray(StandardCharsets.UTF_8))}")
                .joinToString("") { "%02x".format(it) }
            headers["Authorization"] =
                "AWS4-HMAC-SHA256 Credential=$accessKey/$scope, SignedHeaders=$signedHeaders, Signature=$signature"

            val query = if (canonicalQuery.isEmpty()) "" else "?$canonicalQuery"
            val url = "https://$authority$basePath$query"
            val requestBody = when {
                start != null -> UploadIo.chunkBody(open, start, end ?: size) { onProgress(it) }
                body != null -> content.toByteArray(StandardCharsets.UTF_8)
                    .toRequestBody("application/xml".toMediaType())
                else -> EMPTY_BODY
            }
            val builder = Request.Builder().url(url).method(method, requestBody)
            headers.filterKeys { it != "host" }.forEach { (k, v) -> builder.header(k, v) }
            return UploadIo.execute(builder.build(), false).use { resp ->
                ResponseWrapper(resp.code, resp.isSuccessful, resp.header("ETag").orEmpty(),
                    resp.body?.string().orEmpty())
            }
        }

        val chunkSize = maxOf(MIN_PART_SIZE, (size + TARGET_PARTS - 1) / TARGET_PARTS)
        if (size <= 0 || size <= chunkSize) {
            val resp = request("PUT", emptyMap(), 0L, size)
            if (!resp.isSuccessful) throw IllegalStateException("网盘上传服务返回错误（HTTP ${resp.code}）")
            return
        }

        val init = request("POST", linkedMapOf("uploads" to ""))
        if (!init.isSuccessful) throw IllegalStateException("网盘上传服务返回错误（HTTP ${init.code}）")
        val uploadId = xmlValue(init.body, "UploadId")
        if (uploadId.isBlank()) throw IllegalStateException("网盘未创建分段上传任务")

        var completed = false
        try {
            val etags = ArrayList<String>()
            var offset = 0L
            while (offset < size) {
                val stop = minOf(size, offset + chunkSize)
                val resp = request("PUT", linkedMapOf("uploadId" to uploadId, "partNumber" to (etags.size + 1).toString()), offset, stop)
                if (!resp.isSuccessful) throw IllegalStateException("网盘上传服务返回错误（HTTP ${resp.code}）")
                if (resp.etag.isBlank()) throw IllegalStateException("网盘未确认上传分段")
                etags.add(resp.etag)
                offset = stop
            }
            val resp = request("POST", linkedMapOf("uploadId" to uploadId),
                body = completeXml(etags))
            if (!resp.isSuccessful) throw IllegalStateException("网盘上传服务返回错误（HTTP ${resp.code}）")
            completed = true
        } finally {
            if (!completed) {
                runCatching { request("POST", linkedMapOf("uploadId" to uploadId, "abortMultipartUpload" to "")) }
            }
        }
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

    /** S3 规范保留字符，其它一律百分号大写编码（签名串与 URL 必须一致）。 */
    private fun s3Escape(value: String): String = value.toByteArray(StandardCharsets.UTF_8).joinToString("") { b ->
        val c = b.toInt() and 0xFF
        if (c in 0x41..0x5A || c in 0x61..0x7A || c in 0x30..0x39 || c == '-'.code ||
            c == '.'.code || c == '_'.code || c == '~'.code
        ) {
            c.toChar().toString()
        } else {
            "%${"%02X".format(c)}"
        }
    }

    private fun rfc3339(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        .replace("-", "").replace(":", "")

    private fun hmacSha256(key: ByteArray, value: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(value.toByteArray(StandardCharsets.UTF_8))
    }

    private fun sha256Hex(bytes: ByteArray): String = buildString {
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .forEach { append("%02x".format(it)) }
    }

    /** 极简 URL 拆分（只需 host + path）。 */
    private class UriParse(url: String) {
        val host: String
        val path: String
        init {
            val afterScheme = url.substringAfter("://")
            val slashIndex = afterScheme.indexOf('/')
            host = if (slashIndex < 0) afterScheme else afterScheme.substring(0, slashIndex)
            path = if (slashIndex < 0) "" else afterScheme.substring(slashIndex + 1)
                .substringBefore('?').substringBefore('#')
        }
    }

    private data class ResponseWrapper(
        val code: Int,
        val isSuccessful: Boolean,
        val etag: String,
        val body: String
    )

    private val EMPTY_BODY: RequestBody = "".toRequestBody("application/xml".toMediaType())
}
