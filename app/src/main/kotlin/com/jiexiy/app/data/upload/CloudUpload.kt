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

package com.jiexiy.app.data.upload

import android.content.Context
import android.net.Uri
import java.io.IOException
import java.io.InputStream

/** 上传任务状态。 */
enum class UploadStatus { PENDING, RUNNING, COMPLETED, FAILED, CANCELLED }

/** 上传阶段：准备（算 hash / 拿凭证）→ 上传（传输字节）→ 收尾（服务端处理）。 */
enum class UploadPhase { PREPARING, UPLOADING, FINISHING }

/** 一条上传任务（进程内保存；页面切走再回来仍可见）。 */
data class UploadTask(
    val id: Long,
    val platform: String,
    val platformLabel: String,
    val uri: String,
    val fileName: String,
    val size: Long,
    val mimeType: String,
    val folderId: String,
    val status: UploadStatus = UploadStatus.PENDING,
    val phase: UploadPhase = UploadPhase.PREPARING,
    val progress: Long = 0L,
    val error: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

/** 上传结果：平台侧文件标识 + 名称 + 大小。 */
data class CloudUploadResult(
    val fileId: String,
    val fileName: String,
    val size: Long
)

/**
 * 上传失败异常：除用户可读描述外，还携带三件定位失败位置的信息——
 * [phase]（准备/传输/收尾哪一步）、[httpCode]（服务端状态码）、[detail]（响应体片段）。
 *
 * 之前各平台直接抛 `IOException("上传失败")`，用户只看到一句话，根本不知道是登录过期、
 * 签名不匹配、内容校验失败还是磁盘满；现在 [CloudUploadManager] 会把四者拼成完整原因展示。
 */
class UploadException(
    message: String,
    val phase: UploadPhase = UploadPhase.PREPARING,
    val httpCode: Int = 0,
    val detail: String = "",
    cause: Throwable? = null
) : IOException(message, cause) {

    /** 展示给用户的完整失败原因。 */
    val detailText: String
        get() = buildString {
            append(when (phase) {
                UploadPhase.PREPARING -> "准备阶段"
                UploadPhase.UPLOADING -> "上传阶段"
                UploadPhase.FINISHING -> "收尾阶段"
            })
            append("：").append(message)
            if (httpCode > 0) append("（HTTP ").append(httpCode).append("）")
            if (detail.isNotBlank()) append("｜服务端返回：").append(detail)
        }

    companion object {
        /** 把服务端响应体压成一行片段，供失败原因展示（避免整段 XML/JSON 淹没任务卡片）。 */
        fun snip(body: String?, max: Int = 220): String = body.orEmpty()
            .replace(Regex("\\s+"), " ").trim().take(max)
    }
}

/**
 * 待上传文件源：文件已被用户选中，调用方按 [start, end) 区间重开流读取。
 *
 * ContentResolver 的流只能顺序读，所以分片上传不能缓存已读部分，而是每次重新打开再跳过前缀。
 */
interface UploadSource {
    val name: String
    val size: Long
    val mimeType: String
    fun open(start: Long, end: Long): InputStream
}

/**
 * 一个网盘平台的上传实现。
 *
 * 实现方自己解析账号凭证（登录态失效、多账号切换都在 [upload] 内处理），
 * [CloudUploadManager] 只负责排队、进度与生命周期。
 */
interface CloudUploader {
    /** 平台标识，取 [com.jiexiy.app.data.download.DownloadPlatform] 常量。 */
    val platform: String

    /** 界面展示名（「上传任务」列表与空态文案用）。 */
    val label: String

    suspend fun upload(
        source: UploadSource,
        folderId: String,
        onProgress: (Long, UploadPhase) -> Unit
    ): CloudUploadResult
}

/**
 * 基于 [Context] + [Uri] 的上传源实现：`OpenMultipleDocuments` 选中的文件。
 *
 * [open] 重新打开流后跳过 [start] 字节，再限制最多读 [end] - [start] 字节，
 * 因此可以在任意分片上重入，不依赖流支持随机访问。
 */
class ContentResolverUploadSource(
    private val context: Context,
    private val uri: Uri,
    override val name: String,
    override val size: Long,
    override val mimeType: String
) : UploadSource {

    override fun open(start: Long, end: Long): InputStream {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("无法读取所选文件")
        return skipTo(input, start, end)
    }

    private fun skipTo(input: InputStream, start: Long, end: Long): InputStream {
        if (start <= 0) return LimitedInputStream(input, end - start)
        val buffer = ByteArray(64 * 1024)
        var remaining = start
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
                continue
            }
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (n < 0) throw IOException("文件已变化，请重新选择")
            remaining -= n
        }
        return LimitedInputStream(input, end - start)
    }
}

/** 上限为 [limit] 字节的输入流；读到上限即返回 -1。 */
class LimitedInputStream(
    private val input: InputStream,
    private var remaining: Long
) : InputStream() {

    override fun read(): Int {
        if (remaining <= 0) return -1
        val value = input.read()
        if (value >= 0) remaining--
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0) return -1
        val n = input.read(b, off, minOf(len.toLong(), remaining).toInt())
        if (n > 0) remaining -= n
        return n
    }

    override fun available(): Int =
        if (remaining <= 0) 0 else minOf(input.available().toLong(), remaining).toInt()

    override fun close() = input.close()
}
