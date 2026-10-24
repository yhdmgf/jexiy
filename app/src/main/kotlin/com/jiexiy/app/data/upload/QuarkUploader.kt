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
import com.jiexiy.app.data.network.QuarkApi
import com.jiexiy.app.data.network.QuarkConstants
import com.jiexiy.app.data.repository.QuarkAccountRepository
import java.io.IOException

/** 夸克网盘上传（阿里云 CCP：MD5/SHA1 秒传 + 分片逐片签发授权），协议实现见 [QuarkApi.uploadFile]。 */
class QuarkUploader(private val context: Context) : CloudUploader {

    private val api = QuarkApi()

    override val platform: String = DownloadPlatform.QUARK
    override val label: String = "夸克网盘"

    override suspend fun upload(
        source: UploadSource,
        folderId: String,
        onProgress: (Long, UploadPhase) -> Unit
    ): CloudUploadResult {
        val db = AppDatabase.get(context)
        val cookie = QuarkAccountRepository(db.quarkAccountDao(), api).getAccount()?.cookie
            ?.takeIf { it.isNotBlank() }
            ?: throw IOException("请先登录夸克网盘")
        val fid = api.uploadFile(
            cookie = cookie,
            pdirFid = folderId.ifBlank { QuarkConstants.DEFAULT_PDIR_FID },
            fileName = source.name,
            size = source.size,
            mimeType = source.mimeType.ifBlank { "application/octet-stream" },
            open = { start, end -> source.open(start, end) },
            onProgress = { sent -> onProgress(sent, UploadPhase.UPLOADING) }
        )
        return CloudUploadResult(fid, source.name, source.size)
    }
}
