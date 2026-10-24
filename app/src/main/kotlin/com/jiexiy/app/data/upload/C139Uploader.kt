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

package com.jiexiy.app.data.upload

import android.content.Context
import com.jiexiy.app.data.db.AppDatabase
import com.jiexiy.app.data.download.DownloadPlatform
import com.jiexiy.app.data.network.C139Api
import com.jiexiy.app.data.repository.C139AccountRepository
import java.io.IOException

/**
 * 移动云盘上传（SHA-256 + `file/create` 预建 + `file/getUploadUrl` 批量取直传地址 + `file/complete`），
 * 协议实现见 [C139Api.uploadFile]。
 */
class C139Uploader(private val context: Context) : CloudUploader {

    private val api = C139Api()

    override val platform: String = DownloadPlatform.C139
    override val label: String = "移动云盘"

    override suspend fun upload(
        source: UploadSource,
        folderId: String,
        onProgress: (Long, UploadPhase) -> Unit
    ): CloudUploadResult {
        val db = AppDatabase.get(context)
        val entity = C139AccountRepository(db.c139AccountDao()).getAccount()
        if (entity == null) throw IOException("请先登录移动云盘")
        val cookie = entity.cookie
        if (cookie.isBlank()) throw IOException("请先登录移动云盘")
        if (entity.authorization.isBlank()) {
            throw IOException("移动云盘登录信息不完整，请重新登录")
        }
        val id = api.uploadFile(
            parentFileId = folderId.ifBlank { "/" },
            name = source.name,
            size = source.size,
            cookie = cookie,
            open = { start, end -> source.open(start, end) },
            onProgress = { sent -> onProgress(sent, UploadPhase.UPLOADING) }
        )
        return CloudUploadResult(id, source.name, source.size)
    }
}
