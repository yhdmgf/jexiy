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
import com.jiexiy.app.data.network.LanzouApi
import com.jiexiy.app.data.network.LanzouConstants
import com.jiexiy.app.data.repository.LanzouAccountRepository
import java.io.IOException

/**
 * 蓝奏云上传（`html5up.php` 单 multipart 请求，不分片），协议实现见 [LanzouApi.uploadFile]。
 *
 * 目录 id 取纯数字（根目录 `-1`）；界面侧可能带 [LanzouConstants.FOLDER_PREFIX] 前缀，这里统一剥离。
 */
class LanzouUploader(private val context: Context) : CloudUploader {

    private val api = LanzouApi()

    override val platform: String = DownloadPlatform.LANZOU
    override val label: String = "蓝奏云"

    override suspend fun upload(
        source: UploadSource,
        folderId: String,
        onProgress: (Long, UploadPhase) -> Unit
    ): CloudUploadResult {
        val db = AppDatabase.get(context)
        val cookie = LanzouAccountRepository(db.lanzouAccountDao(), api).getAccount()?.cookie
            ?.takeIf { it.isNotBlank() }
            ?: throw IOException("请先登录蓝奏云")
        val params = api.fetchLoginParams(cookie)
            ?: throw IOException("蓝奏登录态无效，请重新登录")
        val parentId = folderId.removePrefix(LanzouConstants.FOLDER_PREFIX)
            .ifBlank { LanzouConstants.ROOT_FOLDER_ID }
        val id = api.uploadFile(
            cookie = cookie,
            uid = params.uid,
            parentId = parentId,
            fileName = source.name,
            size = source.size,
            open = { start, end -> source.open(start, end) },
            onProgress = { sent -> onProgress(sent, UploadPhase.UPLOADING) }
        )
        return CloudUploadResult(LanzouConstants.FILE_PREFIX + id, source.name, source.size)
    }
}
