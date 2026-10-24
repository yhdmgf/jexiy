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
import com.jiexiy.app.data.network.GuangYaApi
import com.jiexiy.app.data.network.GuangYaConstants
import com.jiexiy.app.data.repository.GuangYaAccountRepository
import java.io.IOException

/**
 * 光鸭云盘上传（`get_res_center_token` 换发 OSS 临时凭据 → OSS v1 分片直传 →
 * 轮询 `get_info_by_task_id` 确认入库），协议实现见 [GuangYaApi.uploadFile]。
 */
class GuangYaUploader(private val context: Context) : CloudUploader {

    private val api = GuangYaApi()

    override val platform: String = DownloadPlatform.GUANGYA
    override val label: String = "光鸭云盘"

    override suspend fun upload(
        source: UploadSource,
        folderId: String,
        onProgress: (Long, UploadPhase) -> Unit
    ): CloudUploadResult {
        val db = AppDatabase.get(context)
        val entity = GuangYaAccountRepository(db.guangyaAccountDao(), api).getAccount()
        val token = entity?.accessToken.orEmpty()
        if (token.isBlank()) throw IOException("请先登录光鸭云盘")
        val device = if (entity != null && entity.deviceId.isNotBlank()) entity.deviceId else null
        api.deviceIdProvider = { device }
        val id = api.uploadFile(
            accessToken = token,
            parentId = folderId.ifBlank { GuangYaConstants.ROOT_PARENT_ID },
            fileName = source.name,
            size = source.size,
            open = { start, end -> source.open(start, end) },
            onProgress = { sent -> onProgress(sent, UploadPhase.UPLOADING) }
        )
        return CloudUploadResult(id, source.name, source.size)
    }
}
