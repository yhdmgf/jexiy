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

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** 上传任务 DAO：与 DownloadTaskDao 对称，持久化上传历史。 */
@Dao
interface UploadTaskDao {

    @Query("SELECT * FROM upload_task ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<UploadTaskEntity>>

    @Query("SELECT * FROM upload_task ORDER BY createdAt DESC LIMIT :limit")
    suspend fun listRecent(limit: Int = 200): List<UploadTaskEntity>

    @Insert
    suspend fun insert(task: UploadTaskEntity): Long

    @Update
    suspend fun update(task: UploadTaskEntity)

    @Query("UPDATE upload_task SET status = :status, progress = :progress, error = :error WHERE id = :id")
    suspend fun updateProgress(id: Long, status: Int, progress: Long, error: String)

    @Query("DELETE FROM upload_task WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM upload_task WHERE status = 2 OR status = 3")
    suspend fun clearFinished()

    @Query("DELETE FROM upload_task")
    suspend fun deleteAll()
}
