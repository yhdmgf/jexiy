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
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.InputStream
import java.security.MessageDigest

/**
 * 上传底层流式 IO（网盘上传协议共用的传输层，不含任何平台语义）。
 *
 * 文件按 [start, end) 区间**流式**读取，任意大的文件都不会整块进内存 —— 分片上传会多次调用
 * [open]，每次都重新打开流并跳过前缀。所有方法都是阻塞调用，调用方需已在 IO 线程上。
 */
object UploadIo {

    private const val BUFFER_SIZE = 256 * 1024

    private val client get() = HttpClients.apiClient()

    private val noRedirectClient get() = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    /** 流式计算文件 hash（MD5 / SHA-1 / SHA-256），读进度以已读字节数回调。 */
    fun digest(
        open: (Long, Long) -> InputStream,
        size: Long,
        algorithm: String,
        onProgress: (Long) -> Unit = {}
    ): String {
        val md = MessageDigest.getInstance(algorithm)
        open(0, size).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            var read = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                md.update(buffer, 0, n)
                read += n
                onProgress(read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * multipart/form-data POST：若干文本字段 + 单个文件字段，文件按区间流式上传。
     *
     * @param headers 附加请求头（Cookie / User-Agent / Referer 等）
     * @param onProgress 参数为 (已上传字节数, 文件总字节数)
     * @return 响应；调用方负责关闭
     */
    fun multipartPost(
        url: String,
        fields: Map<String, String>,
        fieldName: String,
        fileName: String,
        contentType: String,
        open: (Long, Long) -> InputStream,
        size: Long,
        headers: Map<String, String> = emptyMap(),
        followRedirects: Boolean = true,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): Response {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .apply {
                fields.forEach { (key, value) -> addFormDataPart(key, value) }
                addFormDataPart(
                    fieldName,
                    fileName,
                    rangeBody(open, 0, size, contentType) { sent -> onProgress(sent, size) }
                )
            }
            .build()
        val builder = Request.Builder().url(url).post(body)
        headers.forEach { (key, value) -> builder.header(key, value) }
        val callClient = if (followRedirects) client else noRedirectClient
        return callClient.newCall(builder.build()).execute()
    }

    /**
     * 按 [start, end) 区间 PUT 原始字节流（分片上传的单片请求）。
     *
     * @param onProgress 参数为绝对字节位置（start + 已上传）
     * @return 响应；调用方负责关闭
     */
    fun rangePut(
        url: String,
        start: Long,
        end: Long,
        open: (Long, Long) -> InputStream,
        contentType: String,
        headers: Map<String, String> = emptyMap(),
        followRedirects: Boolean = true,
        onProgress: (Long) -> Unit = {}
    ): Response {
        val body = rangeBody(open, start, end, contentType, onProgress)
        val builder = Request.Builder().url(url).method("PUT", body)
        headers.forEach { (key, value) -> builder.header(key, value) }
        val callClient = if (followRedirects) client else noRedirectClient
        return callClient.newCall(builder.build()).execute()
    }

    /** [start, end) 区间流式请求体；回调给出绝对字节位置。 */
    private fun rangeBody(
        open: (Long, Long) -> InputStream,
        start: Long,
        end: Long,
        contentType: String,
        onProgress: (Long) -> Unit
    ): RequestBody = object : RequestBody() {
        override fun contentType() = contentType.toMediaType()
        override fun contentLength() = end - start

        override fun writeTo(sink: BufferedSink) {
            open(start, end).use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
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
}
