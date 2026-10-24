package com.jiexiy.app.data.repository

import com.jiexiy.app.data.account.AccountSyncTrigger
import com.jiexiy.app.data.db.AliyunAccountDao
import com.jiexiy.app.data.db.AliyunAccountEntity
import com.jiexiy.app.data.db.MultiAccountEntity
import com.jiexiy.app.data.db.newAccountId
import com.jiexiy.app.data.db.resolveAccountSlot
import com.jiexiy.app.data.download.DownloadPlatform
import com.jiexiy.app.data.network.AliyunApi
import com.jiexiy.app.data.network.AliyunConstants
import com.jiexiy.app.data.network.AliyunCredential
import com.jiexiy.app.data.network.AliyunWebTokens
import com.jiexiy.app.data.network.model.DownloadLink
import com.jiexiy.app.data.network.model.QuotaInfo
import com.jiexiy.app.data.network.model.ShareFile
import kotlinx.coroutines.flow.Flow

/** 阿里云盘账号仓库。令牌由网页登录从 localStorage 提取，refresh_token 为准。 */
interface IAliyunAccountRepository {
    fun observeAccount(): Flow<AliyunAccountEntity?>
    fun observeAccounts(): Flow<List<AliyunAccountEntity>>
    suspend fun getAccount(): AliyunAccountEntity?
    suspend fun getAccounts(): List<AliyunAccountEntity>
    suspend fun allCredentials(): List<AliyunCredential>
    suspend fun login(tokens: AliyunWebTokens, newAccount: Boolean = false): AliyunAccountEntity
    suspend fun saveWebToken(tokens: AliyunWebTokens, newAccount: Boolean = false): Boolean
    suspend fun validate(accountId: String): Boolean
    suspend fun deleteById(id: String)
    suspend fun clear()
    suspend fun setPrimary(id: String)
    suspend fun updateRemark(id: String, remark: String)
    suspend fun list(parentFileId: String, accountId: String? = null): List<ShareFile>
    suspend fun listShare(session: com.jiexiy.app.data.network.AliyunShareSession, parentFileId: String): List<ShareFile>
    suspend fun downloadLink(file: ShareFile, accountId: String? = null): DownloadLink
    suspend fun quota(accountId: String? = null): QuotaInfo
    suspend fun mkdir(parentFileId: String, name: String, accountId: String? = null): ShareFile
    suspend fun rename(fileId: String, newName: String, accountId: String? = null)
    suspend fun move(fileIds: List<String>, toParentFileId: String, accountId: String? = null)
    suspend fun delete(fileIds: List<String>, accountId: String? = null)
    suspend fun shareInfo(shareId: String): String
    suspend fun shareSession(shareId: String, passcode: String): com.jiexiy.app.data.network.AliyunShareSession
    suspend fun upload(
        accountId: String?,
        parentFileId: String,
        name: String,
        size: Long,
        input: () -> java.io.InputStream
    ): ShareFile
}

class AliyunAccountRepository(
    private val dao: AliyunAccountDao,
    private val api: AliyunApi = AliyunApi()
) : IAliyunAccountRepository {

    override fun observeAccount(): Flow<AliyunAccountEntity?> = dao.observeAccount()

    override fun observeAccounts(): Flow<List<AliyunAccountEntity>> = dao.observeAccounts()

    override suspend fun getAccount(): AliyunAccountEntity? = dao.getAccount()

    override suspend fun getAccounts(): List<AliyunAccountEntity> = dao.getAccounts()

    override suspend fun allCredentials(): List<AliyunCredential> =
        dao.getAccounts().mapNotNull { it.toCredential() }

    override suspend fun login(tokens: AliyunWebTokens, newAccount: Boolean): AliyunAccountEntity =
        run {
            val refreshToken = tokens.refreshToken.trim()
            if (refreshToken.isBlank()) throw IllegalStateException("阿里云盘登录凭据缺失，请重试")
            val existing = dao.getAccount()
            val credential = AliyunCredential(
                accessToken = tokens.accessToken.trim(),
                refreshToken = refreshToken,
                deviceId = existing?.deviceId.orEmpty().ifBlank { AliyunConstants.newDeviceId() },
                devicePrivateKey = existing?.devicePrivateKey.orEmpty().ifBlank { com.jiexiy.app.data.network.AliyunSignature.privateKey() },
                nickname = tokens.nickname
            )
            val access = api.refreshToken(credential.refreshToken, credential.deviceId)
            val info = api.userInfo(access, credential.deviceId)
            if (info.userId.isBlank()) throw IllegalStateException("阿里云盘账号信息缺失")
            val slot = resolveAccountSlot(DownloadPlatform.ALIYUN, existing, newAccount)
            val entity = AliyunAccountEntity(
                id = slot.id,
                accessToken = access,
                refreshToken = refreshToken,
                deviceId = credential.deviceId,
                devicePrivateKey = credential.devicePrivateKey,
                userId = info.userId,
                driveId = info.driveId,
                nickname = info.nickname.ifBlank { tokens.nickname.ifBlank { "阿里云盘用户" } },
                isPrimary = slot.isPrimary,
                remark = slot.remark
            )
            dao.upsert(entity)
            AccountSyncTrigger.notifySaved()
            entity
        }

    override suspend fun saveWebToken(tokens: AliyunWebTokens, newAccount: Boolean): Boolean =
        runCatching { login(tokens, newAccount) }.isSuccess

    /** 校验令牌是否仍有效：刷新一次即为有效；失效则删除该账号（其他账号保留）。 */
    override suspend fun validate(accountId: String): Boolean = run {
        val acc = dao.getAccounts().firstOrNull { it.id == accountId } ?: return false
        val c = acc.toCredential() ?: return false
        val ok = runCatching { api.refreshToken(c.refreshToken, c.deviceId) }.isSuccess
        if (!ok) dao.deleteById(acc.id)
        ok
    }

    override suspend fun deleteById(id: String) {
        dao.deleteById(id)
        AccountSyncTrigger.notifySaved()
    }

    override suspend fun clear() = dao.clear()

    override suspend fun setPrimary(id: String) = dao.setPrimary(id)

    override suspend fun updateRemark(id: String, remark: String) = dao.updateRemark(id, remark)

    private suspend fun credentialOf(accountId: String?): AliyunCredential = run {
        val acc = if (accountId.isNullOrBlank()) dao.getAccount()
        else dao.getAccounts().firstOrNull { it.id == accountId }
        acc?.toCredential() ?: throw IllegalStateException("阿里云盘未登录，请先登录")
    }

    /** 令牌可能过期，统一在此刷新后再执行业务操作。 */
    private suspend fun refreshed(c: AliyunCredential): AliyunCredential {
        val access = api.refreshToken(c.refreshToken, c.deviceId)
        dao.updateAccessToken(c.id, access, System.currentTimeMillis())
        return c.copy(accessToken = access)
    }

    override suspend fun list(parentFileId: String, accountId: String?): List<ShareFile> =
        api.listPersonal(refreshed(credentialOf(accountId)), parentFileId)

    override suspend fun listShare(
        session: com.jiexiy.app.data.network.AliyunShareSession,
        parentFileId: String
    ): List<ShareFile> = api.listByShare(session, parentFileId)

    override suspend fun downloadLink(file: ShareFile, accountId: String?): DownloadLink =
        api.getDownloadUrl(refreshed(credentialOf(accountId)), file.fid, file.fname, file.fsize)

    override suspend fun quota(accountId: String?): QuotaInfo =
        api.quota(refreshed(credentialOf(accountId)))

    override suspend fun mkdir(parentFileId: String, name: String, accountId: String?): ShareFile =
        api.mkdir(refreshed(credentialOf(accountId)), parentFileId, name)

    override suspend fun rename(fileId: String, newName: String, accountId: String?) {
        api.rename(refreshed(credentialOf(accountId)), fileId, newName)
    }

    override suspend fun move(fileIds: List<String>, toParentFileId: String, accountId: String?) {
        api.move(refreshed(credentialOf(accountId)), fileIds, toParentFileId)
    }

    override suspend fun delete(fileIds: List<String>, accountId: String?) {
        api.delete(refreshed(credentialOf(accountId)), fileIds)
    }

    override suspend fun shareInfo(shareId: String): String = api.shareInfo(shareId)

    override suspend fun shareSession(
        shareId: String,
        passcode: String
    ): com.jiexiy.app.data.network.AliyunShareSession = api.shareToken(shareId, passcode)

    override suspend fun upload(
        accountId: String?,
        parentFileId: String,
        name: String,
        size: Long,
        input: () -> java.io.InputStream
    ): ShareFile = api.upload(refreshed(credentialOf(accountId)), parentFileId, name, size, input)

    private fun AliyunAccountEntity.toCredential(): AliyunCredential? {
        if (refreshToken.isBlank() || deviceId.isBlank()) return null
        return AliyunCredential(
            id = id,
            accessToken = accessToken,
            refreshToken = refreshToken,
            deviceId = deviceId,
            devicePrivateKey = devicePrivateKey.ifBlank { com.jiexiy.app.data.network.AliyunSignature.privateKey() },
            userId = userId,
            driveId = driveId,
            nickname = nickname
        )
    }
}
