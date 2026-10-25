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

package com.jiexiy.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 上传任务（Room 持久化）。与 [DownloadTaskEntity] 对称，让上传历史像下载历史一样
 * 在应用重启后仍可回看。
 */
@Entity(tableName = "upload_task")
data class UploadTaskEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val platform: String,
    val platformLabel: String,
    val uri: String,
    val fileName: String,
    val size: Long,
    val mimeType: String,
    val folderId: String,
    val status: Int = STATUS_PENDING,
    @ColumnInfo(defaultValue = "0")
    val progress: Long = 0L,
    @ColumnInfo(defaultValue = "''")
    val error: String = "",
    @ColumnInfo(defaultValue = "0")
    val phase: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val STATUS_PENDING = 0
        const val STATUS_UPLOADING = 1
        const val STATUS_COMPLETED = 2
        const val STATUS_FAILED = 3

        fun statusText(status: Int): String = when (status) {
            STATUS_PENDING -> "等待中"
            STATUS_UPLOADING -> "上传中"
            STATUS_COMPLETED -> "已完成"
            STATUS_FAILED -> "失败"
            else -> "未知"
        }
    }
}
