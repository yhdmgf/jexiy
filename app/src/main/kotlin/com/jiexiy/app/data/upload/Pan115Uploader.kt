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
import com.jiexiy.app.data.db.AppDatabase
import com.jiexiy.app.data.download.DownloadPlatform
import com.jiexiy.app.data.network.Pan115Api
import com.jiexiy.app.data.network.Pan115Constants
import com.jiexiy.app.data.repository.Pan115AccountRepository
import java.io.IOException

/** 115 网盘上传（阿里云 OSS 一次性凭据 + 单 multipart 请求），协议实现见 [Pan115Api.uploadFile]。 */
class Pan115Uploader(private val context: Context) : CloudUploader {

    private val api = Pan115Api()

    override val platform: String = DownloadPlatform.PAN115
    override val label: String = "115 网盘"

    override suspend fun upload(
        source: UploadSource,
        folderId: String,
        onProgress: (Long, UploadPhase) -> Unit
    ): CloudUploadResult {
        val db = AppDatabase.get(context)
        val cookie = Pan115AccountRepository(db.pan115AccountDao(), api).getAccount()?.cookie
            ?.takeIf { it.isNotBlank() }
            ?: throw IOException("请先登录115网盘")
        val parentId = folderId.ifBlank { Pan115Constants.ROOT_CID }
        val fid = api.uploadFile(
            cookie = cookie,
            parentId = parentId,
            fileName = source.name,
            size = source.size,
            open = { start, end -> source.open(start, end) },
            onProgress = { sent -> onProgress(sent, UploadPhase.UPLOADING) }
        )
        return CloudUploadResult(fid, source.name, source.size)
    }
}
