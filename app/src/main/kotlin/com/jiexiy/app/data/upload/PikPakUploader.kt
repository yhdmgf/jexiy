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
import com.jiexiy.app.data.db.AppDatabase
import com.jiexiy.app.data.download.DownloadPlatform
import com.jiexiy.app.data.network.PikPakApi
import com.jiexiy.app.data.repository.PikPakAccountRepository
import com.jiexiy.app.data.upload.CloudUploadManager
import java.io.IOException

/**
 * PikPak 上传（两步：创建上传槽 → 阿里云 OSS V1 分片直传）。
 *
 * hash 是「每块 SHA1 → 拼接 → 再 SHA1」；协议细节见 [PikPakApi.uploadFile]。
 */
class PikPakUploader(private val context: Context) : CloudUploader {

    private val api = PikPakApi()
    private val repository: PikPakAccountRepository by lazy {
        PikPakAccountRepository(AppDatabase.get(context).pikpakAccountDao(), api)
    }

    override val platform: String = DownloadPlatform.PIKPAK
    override val label: String = "PikPak网盘"

    override suspend fun upload(
        source: UploadSource,
        folderId: String,
        onProgress: (Long, UploadPhase) -> Unit
    ): CloudUploadResult {
        val entity = repository.getAccount()
            ?: throw IOException("请先登录 PikPak")
        if (entity.deviceId.isBlank()) throw IOException("PikPak 登录信息不完整，请重新登录")
        val accessToken = repository.ensureAccessToken()
            ?: throw IOException("PikPak 登录已过期，请重新登录")
        onProgress(0L, UploadPhase.PREPARING)
        val result = api.uploadFile(
            accessToken = accessToken,
            deviceId = entity.deviceId,
            parentId = folderId,
            name = source.name,
            size = source.size,
            open = { start, end -> source.open(start, end) },
            onProgress = { sent -> onProgress(sent, UploadPhase.UPLOADING) }
        )
        onProgress(result.size, UploadPhase.FINISHING)
        return CloudUploadResult(result.fileId, source.name, result.size)
    }
}
