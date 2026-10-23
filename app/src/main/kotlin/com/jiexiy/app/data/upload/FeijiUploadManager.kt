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

package com.jiexiy.app.data.upload

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.jiexiy.app.data.network.FeijiApi
import com.jiexiy.app.data.parse.FeijiAccountStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicLong

/** 上传任务状态 */
enum class UploadStatus { PENDING, RUNNING, COMPLETED, FAILED, CANCELLED }

/** 一条上传任务（进程内保存；页面切走/回来仍可见） */
data class UploadTask(
    val id: Long,
    val uri: String,
    val fileName: String,
    val size: Long,
    val folderId: String,
    val status: UploadStatus = UploadStatus.PENDING,
    val progress: Long = 0L,
    val error: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 小飞机上传任务管理：选择文件 → 读取 ContentResolver → 调用 [FeijiApi.uploadFile]。
 * 上传进度、失败重试、取消都收敛在这里，传输页只读 [tasks]。
 */
object FeijiUploadManager {

    private var appContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<Long, Job>()
    private val nextId = AtomicLong(1)

    private val _tasks = MutableStateFlow<List<UploadTask>>(emptyList())
    val tasks: StateFlow<List<UploadTask>> = _tasks.asStateFlow()

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    /** 读取系统文件选择器返回的显示名与大小 */
    fun describe(context: Context, uri: Uri): Pair<String, Long>? {
        var name = ""
        var size = -1L
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0) name = cursor.getString(nameIndex).orEmpty()
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        }
        if (name.isBlank()) name = uri.lastPathSegment.orEmpty().substringAfterLast('/')
        if (name.isBlank()) return null
        return name to size
    }

    fun enqueue(context: Context, uri: Uri, fileName: String, size: Long, folderId: String): Long {
        init(context)
        val id = nextId.getAndIncrement()
        _tasks.update {
            it + UploadTask(
                id = id,
                uri = uri.toString(),
                fileName = fileName,
                size = size,
                folderId = folderId
            )
        }
        start(id)
        return id
    }

    fun retry(id: Long) {
        update(id) { it.copy(status = UploadStatus.PENDING, progress = 0L, error = "") }
        start(id)
    }

    fun cancel(id: Long) {
        jobs.remove(id)?.cancel()
        update(id) {
            if (it.status == UploadStatus.COMPLETED) it
            else it.copy(status = UploadStatus.CANCELLED)
        }
    }

    fun remove(id: Long) {
        jobs.remove(id)?.cancel()
        _tasks.update { list -> list.filterNot { it.id == id } }
    }

    fun clearCompleted() {
        _tasks.update { list -> list.filter { it.status != UploadStatus.COMPLETED } }
    }

    private fun start(id: Long) {
        val context = appContext ?: return
        val task = _tasks.value.firstOrNull { it.id == id } ?: return
        jobs[id]?.cancel()
        jobs[id] = scope.launch {
            update(id) { it.copy(status = UploadStatus.RUNNING, error = "") }
            try {
                val account = FeijiAccountStore.ensureAccount(context)
                    ?: throw IOException("请先登录小飞机网盘")
                val uri = Uri.parse(task.uri)
                val size = if (task.size > 0) task.size else describe(context, uri)?.second ?: -1L
                if (size <= 0) throw IOException("无法读取文件大小")
                val result = FeijiApi.uploadFile(
                    token = account.appToken,
                    uuid = account.uuid,
                    userId = account.userId,
                    account = account.account,
                    folderId = task.folderId,
                    fileName = task.fileName,
                    size = size,
                    open = { start, end ->
                        openRange(context, uri, start, end)
                    },
                    onProgress = { sent ->
                        update(id) { it.copy(progress = sent.coerceAtMost(size), size = size) }
                    }
                )
                update(id) {
                    it.copy(
                        status = UploadStatus.COMPLETED,
                        progress = result.size,
                        size = result.size,
                        error = ""
                    )
                }
            } catch (e: Exception) {
                if (jobs[id]?.isCancelled == true) {
                    update(id) { it.copy(status = UploadStatus.CANCELLED) }
                } else {
                    update(id) {
                        it.copy(
                            status = UploadStatus.FAILED,
                            error = e.message ?: "上传失败"
                        )
                    }
                }
            } finally {
                jobs.remove(id)
            }
        }
    }

    private fun update(id: Long, transform: (UploadTask) -> UploadTask) {
        _tasks.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    /** 打开 [start, end) 区间；ContentResolver 的流只能顺序读，先 skip 再限制长度 */
    private fun openRange(context: Context, uri: Uri, start: Long, end: Long): InputStream {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("无法读取所选文件")
        var remaining = start
        val buffer = ByteArray(64 * 1024)
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

    private class LimitedInputStream(
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

        override fun close() = input.close()
    }
}
