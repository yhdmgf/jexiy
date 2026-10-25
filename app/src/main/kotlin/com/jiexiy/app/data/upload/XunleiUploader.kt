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
import com.jiexiy.app.data.network.XunleiApi
import com.jiexiy.app.data.repository.XunleiAccountRepository
import java.io.IOException

/**
 * 迅雷云盘上传（块哈希聚合 gcid + `drive/v1/files` 创建 + S3 v4 可断点续传直传），
 * 协议实现见 [XunleiApi.uploadFile]。
 */
class XunleiUploader(private val context: Context) : CloudUploader {

    private val api = XunleiApi()

    override val platform: String = DownloadPlatform.XUNLEI
    override val label: String = "迅雷云盘"

    override suspend fun upload(
        source: UploadSource,
        folderId: String,
        onProgress: (Long, UploadPhase) -> Unit
    ): CloudUploadResult {
        val db = AppDatabase.get(context)
        val entity = XunleiAccountRepository(db.xunleiAccountDao(), api).getAccount()
            ?: throw IOException("请先登录迅雷云盘")
        val token = entity.accessToken
        if (token.isBlank()) throw IOException("请先登录迅雷云盘")
        if (entity.deviceId.isBlank()) throw IOException("迅雷云盘登录信息不完整，请重新登录")
        // 先缓存 JWT sub：captcha/init 的 meta 需要真实 user_id，空值会得到降级 token（POST 接口拒绝 → 「验证码无效」）
        api.cacheUserId(token)
        val id = api.uploadFile(
            parentId = folderId,
            fileName = source.name,
            size = source.size,
            accessToken = token,
            deviceId = entity.deviceId,
            captchaToken = entity.captchaToken,
            open = { start, end -> source.open(start, end) },
            onProgress = { sent -> onProgress(sent, UploadPhase.UPLOADING) }
        )
        return CloudUploadResult(id, source.name, source.size)
    }
}
