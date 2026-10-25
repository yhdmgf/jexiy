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

import com.jiexiy.app.data.upload.UploadException
import com.jiexiy.app.data.upload.UploadPhase
import com.jiexiy.app.util.AppContextHolder
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * S3 兼容协议（AWS Signature Version 4）分片上传，供网盘下发完整 AK/SK 的场景使用。
 *
 * 流程：单片直接 `PUT`；多片则 `POST ?uploads=` → 每片 `PUT ?uploadId=&partNumber=` →
 * `POST ?uploadId=` 提交 `CompleteMultipartUpload` XML。
 *
 * 两个增强点（相对最简实现）：
 *
 * 1. **断点续传**：多片会话的 `uploadId` 与已完成分片的 ETag 落在 [stateKey] 命名的状态文件里。
 *    网络断了、进程被杀、用户点了重试，只要同一个上传任务再来一次，就能复用未失效的服务端会话、
 *    只补传缺的分片，不会从头再来。已完成的分片**以服务端 ListParts 的返回为准**，本地记录只做
 *    兜底——服务端才是权威，本地记错了也不会把脏数据提交上去。
 *    失败时**故意不 abort** 会话：abort 了下次就没法续了，代价只是留下一个未完成的上传任务，
 *    由网盘侧自行回收。
 * 2. **并发**：默认 [DEFAULT_CONCURRENCY] 个分片同时上传，把上行带宽用满（单线程顺序传时
 *    手机端实测普遍只跑到下行的一半）。
 *
 * 与 [OssMultipartUploader] 的区别：这里用 `AWS4-HMAC-SHA256` + `x-amz-*` 头；OSS 直传用
 * `OSS` v1 签名 + `x-oss-*` 头，两者的签名串与头前缀都不同，不能共用实现。
 */
object S3v4Uploader {

    private const val MIN_PART_SIZE = 8L * 1024 * 1024
    private const val TARGET_PARTS = 9000L
    private const val DEFAULT_CONCURRENCY = 4
    private const val MAX_PARTS_PER_LIST = 1000

    /** 已完成的分片：`partNumber -> ETag`。 */
    private data class PartEntry(val etag: String, val size: Long)

    private data class PartResult(val partNumber: Int, val etag: String, val size: Long)

    /**
     * @param endpoint 网盘下发的上传域名（可含 scheme，缺省按 https 处理）
     * @param region   签名区域（123 网盘固定 `123pan`）
     * @param pathStyle true 时 bucket 走路径前缀（`host/bucket/key`），否则走 CNAME（`bucket.host/key`）
     * @param referer  可选防盗链 Referer
     * @param concurrency 并发分片数，1 就是顺序上传
     * @param stateKey 断点续传用的**稳定任务标识**（如 `"$parentFileId/$name/$size"`）。为空则不续传。
     *   注意：不能用网盘每次下发的对象 key，那个每次都不同，续不上。
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
        onProgress: (Long) -> Unit = {},
        concurrency: Int = DEFAULT_CONCURRENCY,
        stateKey: String? = null
    ) {
        require(bucket.isNotBlank()) { "上传凭据缺少 bucket" }
        require(objectKey.isNotBlank()) { "上传凭据缺少对象路径" }
        require(accessKey.isNotBlank() && secretKey.isNotBlank()) { "上传凭据缺少密钥" }
        require(region.isNotBlank()) { "上传凭据缺少区域" }

        val parsed = UriParts(if (endpoint.contains("://")) endpoint else "https://$endpoint")
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
            body: String? = null,
            onPartProgress: ((Long) -> Unit)? = null
        ): ResponseWrapper {
            val stamp = rfc3339()
            val date = stamp.substring(0, 8)
            val content = body.orEmpty()
            val contentHash = if (start != null) {
                UploadIo.digest(open, end ?: size, "SHA-256", { }, start = start)
            } else {
                sha256Hex(content.toByteArray(StandardCharsets.UTF_8))
            }
            // Content-Type 必须参与签名：S3 v4 只豁免 host / Authorization / Expect，
            // 发了却没签会直接 SignatureDoesNotMatch（HTTP 403）
            val bodyType = if (start != null) contentType else "application/xml"
            val headers = LinkedHashMap<String, String>()
            headers["host"] = authority
            headers["content-type"] = bodyType
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
                start != null -> UploadIo.chunkBody(open, start, end ?: size,
                    onProgress = { abs -> onPartProgress?.invoke(abs) }, mediaType = bodyType.toMediaType())
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
            if (!resp.isSuccessful) {
                throw UploadException(errDetail(resp), UploadPhase.UPLOADING, resp.code, UploadException.snip(resp.body))
            }
            stateFile(stateKey)?.delete()
            onProgress(size)
            return
        }

        val partCount = maxOf(1, ((size + chunkSize - 1) / chunkSize).toInt())
        val sizeOfPart = { partNo: Int -> minOf(chunkSize, size - (partNo - 1L) * chunkSize) }

        // ---- 断点续传：读上次遗留的会话 ----
        var uploadId = ""
        val done = LinkedHashMap<Int, PartEntry>()
        val previous = stateFile(stateKey)?.takeIf { it.exists() }?.let { readState(it, size, chunkSize) }
        if (previous != null) {
            uploadId = previous.uploadId
            previous.done.forEach { (no, e) -> if (no in 1..partCount) done[no] = e }
        }
        if (uploadId.isBlank()) {
            val init = request("POST", linkedMapOf("uploads" to ""))
            if (!init.isSuccessful) {
                throw UploadException("创建上传任务失败", UploadPhase.PREPARING, init.code, UploadException.snip(init.body))
            }
            uploadId = xmlValue(init.body, "UploadId")
            if (uploadId.isBlank()) {
                throw UploadException("网盘未创建分段上传任务", UploadPhase.PREPARING, init.code, UploadException.snip(init.body))
            }
        } else {
            // 会话还在：问服务端已经有哪些分片，以服务端为准
            val listed = listParts(request, uploadId, partCount, sizeOfPart)
            if (listed != null) {
                done.clear()
                listed.forEach { (no, e) -> done[no] = e }
            } else {
                uploadId = ""
                done.clear()
                val init = request("POST", linkedMapOf("uploads" to ""))
                if (!init.isSuccessful) {
                    throw UploadException("创建上传任务失败", UploadPhase.PREPARING, init.code, UploadException.snip(init.body))
                }
                uploadId = xmlValue(init.body, "UploadId")
                if (uploadId.isBlank()) {
                    throw UploadException("网盘未创建分段上传任务", UploadPhase.PREPARING, init.code, UploadException.snip(init.body))
                }
            }
        }
        persistState(stateKey, uploadId, size, chunkSize, done)

        // ---- 进度：已完成分片的字节数 + 正在传的分片已写字节；单调递增 ----
        val completedBytes = AtomicLong(0L)
        done.forEach { (no, _) -> completedBytes.addAndGet(sizeOfPart(no)) }
        val reported = AtomicLong(completedBytes.get())
        fun report(total: Long) {
            if (total <= 0L) return
            while (true) {
                val cur = reported.get()
                if (total <= cur) return
                if (reported.compareAndSet(cur, total)) {
                    onProgress(total)
                    return
                }
            }
        }
        onProgress(completedBytes.get())

        val todo = (1..partCount).filter { it !in done }
        val pool = Executors.newFixedThreadPool(concurrency.coerceIn(1, 16))
        try {
            val futures = todo.map { partNo ->
                pool.submit(Callable {
                    val start = (partNo - 1L) * chunkSize
                    val stop = minOf(size, start + chunkSize)
                    val resp = request(
                        "PUT",
                        linkedMapOf("uploadId" to uploadId, "partNumber" to partNo.toString()),
                        start, stop,
                        onPartProgress = { abs ->
                            report(completedBytes.get() + (abs - start).coerceAtLeast(0L))
                        }
                    )
                    if (!resp.isSuccessful) {
                        throw UploadException(errDetail(resp), UploadPhase.UPLOADING, resp.code, UploadException.snip(resp.body))
                    }
                    if (resp.etag.isBlank()) {
                        throw UploadException("网盘未确认上传分段", UploadPhase.UPLOADING, resp.code, UploadException.snip(resp.body))
                    }
                    PartResult(partNo, resp.etag, stop - start)
                })
            }
            var firstError: Exception? = null
            for (f in futures) {
                if (firstError != null) {
                    f.cancel(true)
                    continue
                }
                try {
                    val r = f.get()
                    done[r.partNumber] = PartEntry(r.etag, r.size)
                    completedBytes.addAndGet(r.size)
                    report(completedBytes.get())
                    persistState(stateKey, uploadId, size, chunkSize, done)
                } catch (e: ExecutionException) {
                    if (firstError == null) firstError = e.cause ?: e
                } catch (e: CancellationException) {
                    if (firstError == null) firstError = e
                }
            }
            firstError?.let { e ->
                throw if (e is UploadException) e
                else UploadException("分片上传失败：${e.message}", UploadPhase.UPLOADING, 0, "")
            }

            val etags = (1..partCount).map { no ->
                done[no]?.etag ?: throw UploadException(
                    "缺少第 $no 片的确认值，无法提交上传任务", UploadPhase.FINISHING
                )
            }
            val resp = request("POST", linkedMapOf("uploadId" to uploadId), body = completeXml(etags))
            if (!resp.isSuccessful) {
                throw UploadException("提交上传任务失败", UploadPhase.FINISHING, resp.code, UploadException.snip(resp.body))
            }
            reported.set(size)
            onProgress(size)
            stateFile(stateKey)?.delete()
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * 向服务端问已上传的分片，直到把所有分片页取全。
     *
     * @return `partNumber -> 已确认信息`；返回 null 表示会话已经不存在/不可续（调用方需新建会话）。
     */
    private fun listParts(
        request: (String, Map<String, String>, Long?, Long?, String?, ((Long) -> Unit)?) -> ResponseWrapper,
        uploadId: String,
        partCount: Int,
        sizeOfPart: (Int) -> Long
    ): LinkedHashMap<Int, PartEntry>? {
        val found = LinkedHashMap<Int, PartEntry>()
        var marker = ""
        var pages = 0
        while (true) {
            if (pages++ >= 20) break // 防御：页数上限，避免异常响应导致死循环
            val params = linkedMapOf("uploadId" to uploadId, "maxParts" to MAX_PARTS_PER_LIST.toString())
            if (marker.isNotBlank()) params["partNumberMarker"] = marker
            val resp = try {
                request("GET", params)
            } catch (e: Exception) {
                return null
            }
            if (!resp.isSuccessful) return null
            xmlValue(resp.body, "IsTruncated").takeIf { it.isNotBlank() }?.let { marker = it }
            for (m in Regex("""<Part>([\s\S]*?)</Part>""").findAll(resp.body)) {
                val block = m.groupValues[1]
                val no = xmlValue(block, "PartNumber").toIntOrNull()
                val etag = xmlValue(block, "ETag")
                val sz = xmlValue(block, "Size").toLongOrNull()
                if (no != null && no in 1..partCount && etag.isNotBlank()) {
                    found[no] = PartEntry(etag, sz ?: sizeOfPart(no))
                }
            }
            if (found.size >= partCount || marker.isBlank()) break
        }
        return found
    }

    // ---------- 状态持久化（断点续传） ----------

    private fun stateFile(stateKey: String?): File? {
        val ctx = stateKey?.takeIf { it.isNotBlank() }?.let { _ -> AppContextHolder.get() } ?: return null
        val dir = File(ctx.cacheDir, "upload-s3v4")
        if (!dir.exists()) dir.mkdirs()
        val hash = Integer.toHexString(stateKey.hashCode())
        return File(dir, "$hash.json")
    }

    private fun readState(file: File, size: Long, chunkSize: Long): ResumeState? = runCatching {
        val obj = JSONObject(file.readText(StandardCharsets.UTF_8))
        if (obj.optLong("size", -1L) != size) return null   // 文件变了，旧的续不上
        if (obj.optLong("chunkSize", -1L) != chunkSize) return null
        ResumeState(
            uploadId = obj.optString("uploadId"),
            done = obj.optJSONObject("done")?.let { j ->
                val m = LinkedHashMap<Int, PartEntry>()
                val keys = j.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val e = j.optJSONObject(k) ?: continue
                    val no = e.optInt("partNumber", 0)
                    val etag = e.optString("etag")
                    if (no > 0 && etag.isNotBlank()) {
                        m[no] = PartEntry(etag, e.optLong("size", 0L))
                    }
                }
                m
            } ?: emptyMap()
        )
    }.getOrNull()

    private fun persistState(stateKey: String?, uploadId: String, size: Long, chunkSize: Long, done: Map<Int, PartEntry>) {
        val file = stateFile(stateKey) ?: return
        val out = File.createTempFile("s3v4-", ".json", file.parentFile)
        runCatching {
            val j = JSONObject()
            j.put("size", size).put("chunkSize", chunkSize).put("uploadId", uploadId)
            val dj = JSONObject()
            done.forEach { (no, e) ->
                dj.put(no.toString(), JSONObject().put("partNumber", no).put("etag", e.etag).put("size", e.size))
            }
            j.put("done", dj)
            out.writeText(j.toString(), StandardCharsets.UTF_8)
            // 写临时文件后原子重命名，避免中途断电留下半截状态文件
            if (!out.renameTo(file)) runCatching { out.copyTo(file, overwrite = true) }
            out.delete()
        }.onFailure { out.delete() }
    }

    private data class ResumeState(val uploadId: String, val done: Map<Int, PartEntry>)

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

    /** 上传失败信息：HTTP 码 + 响应体片段。S3 会回 SignatureDoesNotMatch 之类的 XML，只给码看不出原因。 */
    private fun errDetail(resp: ResponseWrapper): String = buildString {
        append("网盘上传服务返回错误（HTTP ").append(resp.code).append("）")
        val detail = resp.body.replace(Regex("\\s+"), " ").trim().take(200)
        if (detail.isNotBlank()) append("：").append(detail)
    }

    /** 极简 URL 拆分（只需 host + path）。 */
    private class UriParts(url: String) {
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
