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
import com.jiexiy.app.data.download.DownloadPlatform
import com.jiexiy.app.data.network.FeijiApi
import com.jiexiy.app.data.parse.FeijiAccountStore
import java.io.IOException

/** 小飞机网盘上传（腾讯云 VOD + 分片），协议实现见 [FeijiApi.uploadFile]。 */
class FeijiUploader(private val context: Context) : CloudUploader {

    override val platform: String = DownloadPlatform.FEIJI
    override val label: String = "小飞机网盘"

    override suspend fun upload(
        source: UploadSource,
        folderId: String,
        onProgress: (Long, UploadPhase) -> Unit
    ): CloudUploadResult {
        val account = FeijiAccountStore.ensureAccount(context)
            ?: throw IOException("请先登录小飞机网盘")
        val result = FeijiApi.uploadFile(
            token = account.appToken,
            uuid = account.uuid,
            userId = account.userId,
            account = account.account,
            folderId = folderId,
            fileName = source.name,
            size = source.size,
            open = { start, end -> source.open(start, end) },
            onProgress = { sent -> onProgress(sent, UploadPhase.UPLOADING) }
        )
        return CloudUploadResult(result.id, result.name, result.size)
    }
}
