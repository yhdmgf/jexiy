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
import com.jiexiy.app.data.network.ILanzouApi
import com.jiexiy.app.data.network.ILanzouConstants
import com.jiexiy.app.data.repository.ILanzouAccountRepository
import java.io.IOException

/**
 * 蓝奏优享上传（七牛分片 + 轮询确认），协议实现见 [ILanzouApi.uploadFile]。
 *
 * 七牛 object key 里的账号段优先取登录时落库的 account；网页登录可能没存，回退到账号信息接口。
 */
class ILanzouUploader(private val context: Context) : CloudUploader {

    private val api = ILanzouApi()

    override val platform: String = DownloadPlatform.ILANZOU
    override val label: String = "蓝奏优享"

    override suspend fun upload(
        source: UploadSource,
        folderId: String,
        onProgress: (Long, UploadPhase) -> Unit
    ): CloudUploadResult {
        val db = AppDatabase.get(context)
        val entity = ILanzouAccountRepository(db.ilanzouAccountDao(), api).getAccount()
            ?: throw IOException("请先登录蓝奏优享")
        val appToken = entity.appToken
        if (appToken.isBlank()) throw IOException("请先登录蓝奏优享")
        val uuid = entity.uuid
        if (uuid.isBlank()) throw IOException("蓝奏优享登录信息不完整，请重新登录")
        val account = entity.account.ifBlank { api.fetchAccountInfo(appToken, uuid)?.account.orEmpty() }
        if (account.isBlank()) throw IOException("蓝奏优享未返回上传账号信息，请重新登录")
        val parentId = folderId.removePrefix(ILanzouConstants.FOLDER_PREFIX)
            .ifBlank { ILanzouConstants.ROOT_FOLDER_ID }
        val id = api.uploadFile(
            appToken = appToken,
            uuid = uuid,
            account = account,
            parentId = parentId,
            fileName = source.name,
            size = source.size,
            open = { start, end -> source.open(start, end) },
            onProgress = { sent -> onProgress(sent, UploadPhase.UPLOADING) }
        )
        return CloudUploadResult(ILanzouConstants.FILE_PREFIX + id, source.name, source.size)
    }
}
