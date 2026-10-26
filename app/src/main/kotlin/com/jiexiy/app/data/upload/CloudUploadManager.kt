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
import android.provider.OpenableColumns
import com.jiexiy.app.data.db.AppDatabase
import com.jiexiy.app.data.db.UploadTaskDao
import com.jiexiy.app.data.db.UploadTaskEntity
import com.jiexiy.app.data.download.DownloadPlatform
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
import java.util.concurrent.atomic.AtomicLong

/** 文件选择器返回的信息：显示名、字节数、MIME 类型。 */
data class SelectedFile(
    val name: String,
    val size: Long,
    val mimeType: String
)

/**
 * 平台无关的上传任务管理：选择文件 → 读取 ContentResolver → 按平台分发给 [CloudUploader]。
 *
 * 上传进度、失败重试、取消都收敛在这里，传输页只读 [tasks]；各平台的协议差异全部隔离在
 * 实现 [CloudUploader] 的类里。每个任务一条独立协程（与下载侧的分片并发模型一致）。
 */
object CloudUploadManager {

    private var appContext: Context? = null
    private var uploadDao: UploadTaskDao? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<Long, Job>()
    private val nextId = AtomicLong(1)

    private val _tasks = MutableStateFlow<List<UploadTask>>(emptyList())
    val tasks: StateFlow<List<UploadTask>> = _tasks.asStateFlow()

    private val factories = LinkedHashMap<String, () -> CloudUploader?>()

    /** 惰性装配：首次入队时把应用上下文与各平台上传实现装好，并从 DB 加载历史任务。 */
    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        val db = AppDatabase.get(app)
        uploadDao = db.uploadTaskDao()
        // 从 DB 加载历史任务（已完成/失败/等待中的都保留，仅跳过已取消）
        scope.launch {
            val history = runCatching { db.uploadTaskDao().listRecent() }.getOrDefault(emptyList())
            // 更新 nextId 为 DB 中最大 id + 1，避免与新任务 id 冲突
            if (history.isNotEmpty()) {
                val maxId = history.maxOfOrNull { it.id } ?: 0L
                nextId.set(maxId + 1)
            }
            _tasks.value = history.map { it.toUploadTask() }
        }
        register(DownloadPlatform.FEIJI) { FeijiUploader(app) }
        register(DownloadPlatform.ALIYUN) { AliyunUploader(app) }
        register(DownloadPlatform.QUARK) { QuarkUploader(app) }
        register(DownloadPlatform.UC) { UCUploader(app) }
        register(DownloadPlatform.XUNLEI) { XunleiUploader(app) }
        register(DownloadPlatform.BAIDU) { BaiduUploader(app) }
        register(DownloadPlatform.C139) { C139Uploader(app) }
        register(DownloadPlatform.PAN123) { Pan123Uploader(app) }
        register(DownloadPlatform.PAN115) { Pan115Uploader(app) }
        register(DownloadPlatform.GUANGYA) { GuangYaUploader(app) }
        register(DownloadPlatform.LANZOU) { LanzouUploader(app) }
        register(DownloadPlatform.ILANZOU) { ILanzouUploader(app) }
        register(DownloadPlatform.PIKPAK) { PikPakUploader(app) }
    }

    /** 注册一个平台的上传实现；同名平台后注册覆盖先注册。 */
    fun register(platform: String, factory: () -> CloudUploader?) {
        factories[platform] = factory
    }

    /** 该平台是否已注册上传实现（界面据此决定「上传」入口是否显示）。 */
    fun supports(platform: String): Boolean = factories.containsKey(platform)

    /** 平台展示名；未注册或尚未 init 时返回 null。 */
    fun labelOf(platform: String): String? = factories[platform]?.invoke()?.label

    /** 读取系统文件选择器返回的显示名与大小；名称缺失时回退到 Uri 末段。 */
    fun describe(context: Context, uri: Uri): SelectedFile? {
        var name = ""
        var size = -1L
        var type = ""
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0) name = cursor.getString(nameIndex).orEmpty()
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
            type = context.contentResolver.getType(uri).orEmpty()
        }
        if (name.isBlank()) name = uri.lastPathSegment.orEmpty().substringAfterLast('/')
        if (name.isBlank()) return null
        return SelectedFile(name, size, type)
    }

    fun enqueue(
        context: Context,
        platform: String,
        uri: Uri,
        fileName: String,
        size: Long,
        mimeType: String,
        folderId: String
    ): Long {
        init(context)
        val id = nextId.getAndIncrement()
        val task = UploadTask(
            id = id,
            platform = platform,
            platformLabel = labelOf(platform).orEmpty(),
            uri = uri.toString(),
            fileName = fileName,
            size = size,
            mimeType = mimeType,
            folderId = folderId
        )
        _tasks.update { it + task }
        // 持久化到 DB，重启后仍可回看
        val dao = uploadDao
        if (dao != null) {
            scope.launch { dao.insert(task.toEntity()) }
        }
        start(id)
        return id
    }

    fun retry(id: Long) {
        if (appContext == null) return
        update(id) { it.copy(status = UploadStatus.PENDING, phase = UploadPhase.PREPARING, progress = 0L, error = "") }
        start(id)
    }

    fun cancel(id: Long) {
        jobs.remove(id)?.cancel()
        update(id) {
            if (it.status == UploadStatus.COMPLETED) it
            else it.copy(status = UploadStatus.CANCELLED, error = "")
        }
    }

    fun remove(id: Long) {
        jobs.remove(id)?.cancel()
        _tasks.update { list -> list.filterNot { it.id == id } }
        // 同步删除 DB 记录
        val dao = uploadDao
        if (dao != null) {
            scope.launch { dao.deleteById(id) }
        }
    }

    fun clearCompleted() {
        _tasks.update { list -> list.filter { it.status != UploadStatus.COMPLETED } }
        // 同步删除 DB 里已完成/失败的任务
        val dao = uploadDao
        if (dao != null) {
            scope.launch { dao.clearFinished() }
        }
    }

    private fun start(id: Long) {
        val context = appContext ?: return
        val task = _tasks.value.firstOrNull { it.id == id } ?: return
        jobs[id]?.cancel()
        jobs[id] = scope.launch {
            update(id) { it.copy(status = UploadStatus.RUNNING, phase = UploadPhase.PREPARING, error = "") }
            try {
                val uploader = factories[task.platform]?.invoke()
                    ?: throw IOException("该平台暂不支持上传")
                val uri = Uri.parse(task.uri)
                val size = if (task.size > 0) task.size else describe(context, uri)?.size ?: -1L
                if (size <= 0) throw IOException("无法读取文件大小")
                val source = ContentResolverUploadSource(context, uri, task.fileName, size, task.mimeType)
                val result = uploader.upload(
                    source = source,
                    folderId = task.folderId,
                    onProgress = { sent, phase ->
                        update(id) { it.copy(progress = sent.coerceAtMost(size), size = size, phase = phase) }
                    }
                )
                update(id) {
                    it.copy(
                        status = UploadStatus.COMPLETED,
                        progress = if (result.size > 0) result.size else size,
                        size = if (result.size > 0) result.size else size,
                        phase = UploadPhase.FINISHING,
                        error = ""
                    )
                }
            } catch (e: UploadException) {
                if (jobs[id]?.isCancelled == true) {
                    update(id) { it.copy(status = UploadStatus.CANCELLED) }
                } else {
                    // 完整原因：阶段 + 消息 + HTTP 码 + 服务端响应片段
                    update(id) { it.copy(status = UploadStatus.FAILED, error = e.detailText) }
                }
            } catch (e: Exception) {
                if (jobs[id]?.isCancelled == true) {
                    update(id) { it.copy(status = UploadStatus.CANCELLED) }
                } else {
                    // 平台自己抛的普通异常：带上异常类名，便于区分超时/网络/磁盘满/解析错
                    val msg = e.message?.takeIf { it.isNotBlank() } ?: "上传失败"
                    update(id) {
                        it.copy(status = UploadStatus.FAILED, error = "$msg（${e.javaClass.simpleName}）")
                    }
                }
            } finally {
                jobs.remove(id)
            }
        }
    }

    private fun update(id: Long, transform: (UploadTask) -> UploadTask) {
        _tasks.update { list ->
            val updated = list.map { if (it.id == id) transform(it) else it }
            // 仅在状态变更时写 DB（进度更新不写，避免频繁 I/O）
            val old = _tasks.value.firstOrNull { it.id == id }
            val new = updated.firstOrNull { it.id == id }
            if (old != null && new != null && old.status != new.status) {
                val dao = uploadDao
                if (dao != null) {
                    scope.launch { dao.update(new.toEntity()) }
                }
            }
            updated
        }
    }

    /** 内存任务 → DB Entity。 */
    private fun UploadTask.toEntity() = UploadTaskEntity(
        id = id,
        platform = platform,
        platformLabel = platformLabel,
        uri = uri,
        fileName = fileName,
        size = size,
        mimeType = mimeType,
        folderId = folderId,
        status = when (status) {
            UploadStatus.PENDING -> UploadTaskEntity.STATUS_PENDING
            UploadStatus.RUNNING -> UploadTaskEntity.STATUS_UPLOADING
            UploadStatus.COMPLETED -> UploadTaskEntity.STATUS_COMPLETED
            UploadStatus.FAILED -> UploadTaskEntity.STATUS_FAILED
            UploadStatus.CANCELLED -> UploadTaskEntity.STATUS_FAILED
        },
        progress = progress,
        error = error,
        phase = phase.ordinal,
        createdAt = createdAt
    )

    /** DB Entity → 内存任务。 */
    private fun UploadTaskEntity.toUploadTask() = UploadTask(
        id = id,
        platform = platform,
        platformLabel = platformLabel,
        uri = uri,
        fileName = fileName,
        size = size,
        mimeType = mimeType,
        folderId = folderId,
        status = when (status) {
            UploadTaskEntity.STATUS_PENDING -> UploadStatus.PENDING
            UploadTaskEntity.STATUS_UPLOADING -> UploadStatus.RUNNING
            UploadTaskEntity.STATUS_COMPLETED -> UploadStatus.COMPLETED
            UploadTaskEntity.STATUS_FAILED -> UploadStatus.FAILED
            else -> UploadStatus.PENDING
        },
        phase = if (phase in UploadPhase.entries.indices) UploadPhase.entries[phase] else UploadPhase.PREPARING,
        progress = progress,
        error = error,
        createdAt = createdAt
    )
}
