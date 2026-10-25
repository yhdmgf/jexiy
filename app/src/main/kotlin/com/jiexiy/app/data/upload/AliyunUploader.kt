package com.jiexiy.app.data.upload

import android.content.Context
import com.jiexiy.app.data.db.AppDatabase
import com.jiexiy.app.data.download.DownloadPlatform
import com.jiexiy.app.data.network.AliyunApi
import com.jiexiy.app.data.repository.AliyunAccountRepository
import java.io.IOException

/** 阿里云盘上传：走 batch 三步上传（申请 → 取预签名地址 → 直传 OSS → 提交）。 */
class AliyunUploader(private val context: Context) : CloudUploader {
    private val api = AliyunApi()
    private val repository: AliyunAccountRepository by lazy {
        AliyunAccountRepository(AppDatabase.get(context).aliyunAccountDao(), api)
    }

    override val platform: String = DownloadPlatform.ALIYUN
    override val label: String = "阿里云盘"

    override suspend fun upload(
        source: UploadSource,
        folderId: String,
        onProgress: (Long, UploadPhase) -> Unit
    ): CloudUploadResult {
        val entity = repository.getAccount()
        if (entity == null || entity.refreshToken.isBlank()) {
            throw IOException("请先登录阿里云盘")
        }
        onProgress(0L, UploadPhase.PREPARING)
        val credential = repository.buildCredential()
            ?: throw IOException("请先登录阿里云盘")
        val file = api.upload(
            credential = credential,
            parentFileId = folderId,
            name = source.name,
            size = source.size,
            input = { source.open(0, source.size - 1) }
        )
        onProgress(file.fsize, UploadPhase.FINISHING)
        return CloudUploadResult(file.fid, file.fname, file.fsize)
    }
}
