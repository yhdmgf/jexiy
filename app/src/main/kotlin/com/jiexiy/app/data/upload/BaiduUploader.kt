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
import com.jiexiy.app.data.network.BaiduApi
import com.jiexiy.app.data.repository.BaiduAccountRepository
import java.io.IOException

/**
 * 百度网盘上传（`precreate` 试秒传 + `superfile2` 4 MB 分片 + `create` 提交），
 * 协议实现见 [BaiduApi.uploadFile]。
 */
class BaiduUploader(private val context: Context) : CloudUploader {

    private val api = BaiduApi()

    override val platform: String = DownloadPlatform.BAIDU
    override val label: String = "百度网盘"

    override suspend fun upload(
        source: UploadSource,
        folderId: String,
        onProgress: (Long, UploadPhase) -> Unit
    ): CloudUploadResult {
        val db = AppDatabase.get(context)
        val cookie = BaiduAccountRepository(db.baiduAccountDao(), api).getAccount()?.cookie
            ?.takeIf { it.isNotBlank() }
            ?: throw IOException("请先登录百度网盘")
        val id = api.uploadFile(
            cookie = cookie,
            parentDir = folderId.ifBlank { "/" },
            fileName = source.name,
            size = source.size,
            open = { start, end -> source.open(start, end) },
            onProgress = { sent -> onProgress(sent, UploadPhase.UPLOADING) }
        )
        return CloudUploadResult(id, source.name, source.size)
    }
}
