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

package com.jiexiy.app.data.repository

import com.jiexiy.app.data.account.AccountSyncTrigger
import com.jiexiy.app.data.db.AliyunAccountDao
import com.jiexiy.app.data.db.AliyunAccountEntity
import com.jiexiy.app.data.db.resolveAccountSlot
import com.jiexiy.app.data.download.DownloadPlatform
import com.jiexiy.app.data.network.AliyunApi
import com.jiexiy.app.data.network.AliyunConstants
import com.jiexiy.app.data.network.AliyunCredential
import com.jiexiy.app.data.network.AliyunSignature
import com.jiexiy.app.data.network.AliyunWebTokens
import kotlinx.coroutines.flow.Flow

/**
 * 阿里云盘账号仓库：网页登录拿 access_token / refresh_token 落库（加密）；
 * access_token 约 15 分钟过期，用 refresh_token 自动续期；refresh 失效时抛出登录失效。
 *
 * 阿里云盘只认 refresh_token 换 access_token，access_token 自身短寿命，
 * 因此持久化必须以 refresh_token 为准。设备会话（X-Signature）25 分钟过期，
 * 由 [AliyunApi.ensureDeviceSession] 自动重建。
 *
 * 对照 wenxi `lib/data/providers/aliyun.dart` 的 `_session` / `_renew` / `_device`。
 */
class AliyunAccountRepository(
    private val dao: AliyunAccountDao,
    private val api: AliyunApi
) {

    fun observeAccount(): Flow<AliyunAccountEntity?> = dao.observeAccount()

    suspend fun getAccount(): AliyunAccountEntity? = dao.getAccount()

    /**
     * 保存网页登录凭据到数据库。
     *
     * @param newAccount true = 「添加账号」模式，落成新的一行；false = 覆盖当前主账号那一行
     */
    suspend fun saveWebCredential(tokens: AliyunWebTokens, newAccount: Boolean = false): Result<AliyunAccountEntity> =
        runCatching {
            val existing = dao.getAccount()
            val deviceId = existing?.deviceId?.takeIf { it.isNotBlank() } ?: AliyunConstants.newDeviceId()
            val devicePrivateKey = existing?.devicePrivateKey?.takeIf { it.isNotBlank() }
                ?: AliyunSignature.privateKey()
            val slot = resolveAccountSlot(DownloadPlatform.ALIYUN, existing, newAccount)

            // 用 refreshToken 换一个全新的 access_token 校验凭据有效性
            val refreshResult = api.refreshToken(tokens.refreshToken, deviceId)

            // 拿用户信息（userId、driveId、昵称）
            val info = api.userInfo(refreshResult.accessToken, deviceId)

            val entity = AliyunAccountEntity(
                id = slot.id,
                accessToken = refreshResult.accessToken,
                refreshToken = tokens.refreshToken,
                deviceId = deviceId,
                devicePrivateKey = devicePrivateKey,
                userId = info.userId.ifBlank { tokens.userId },
                driveId = info.driveId,
                expiresAtMs = System.currentTimeMillis() + refreshResult.expiresIn * 1000L,
                nickname = info.nickname.ifBlank { tokens.nickname }.ifBlank { "阿里云盘用户" },
                isPrimary = slot.isPrimary,
                remark = slot.remark,
                updatedAt = System.currentTimeMillis()
            )
            dao.upsert(entity)
            AccountSyncTrigger.notifySaved()
            entity
        }

    /** 取有效 access_token：临近过期（<60 秒）自动用 refresh_token 换新。失败时返回 null。 */
    suspend fun ensureAccessToken(): String? {
        val acc = dao.getAccount() ?: return null
        if (acc.refreshToken.isBlank() || acc.deviceId.isBlank()) return null
        // 未到刷新窗口就复用现有 token
        if (acc.expiresAtMs > 0 && System.currentTimeMillis() < acc.expiresAtMs - 60_000L) {
            return acc.accessToken.ifBlank { null }
        }
        return try {
            val result = api.refreshToken(acc.refreshToken, acc.deviceId)
            val expiresAt = System.currentTimeMillis() + result.expiresIn * 1000L
            dao.updateAccessToken(acc.id, result.accessToken, expiresAt, System.currentTimeMillis())
            result.accessToken
        } catch (_: Exception) {
            null
        }
    }

    /** 构建 API 凭证对象（从数据库实体 + 按需续期的 access_token）。 */
    suspend fun buildCredential(): AliyunCredential? {
        val acc = dao.getAccount() ?: return null
        if (acc.refreshToken.isBlank() || acc.deviceId.isBlank()) return null
        val accessToken = try {
            if (acc.expiresAtMs > 0 && System.currentTimeMillis() < acc.expiresAtMs - 60_000L) {
                acc.accessToken
            } else {
                val result = api.refreshToken(acc.refreshToken, acc.deviceId)
                val expiresAt = System.currentTimeMillis() + result.expiresIn * 1000L
                dao.updateAccessToken(acc.id, result.accessToken, expiresAt, System.currentTimeMillis())
                result.accessToken
            }
        } catch (_: Exception) {
            acc.accessToken
        }
        return AliyunCredential(
            id = acc.id,
            accessToken = accessToken,
            refreshToken = acc.refreshToken,
            deviceId = acc.deviceId,
            devicePrivateKey = acc.devicePrivateKey,
            userId = acc.userId,
            driveId = acc.driveId,
            nickname = acc.nickname
        )
    }

    /** 删除账号 */
    suspend fun delete(id: String) = dao.deleteById(id)

    /** 清空所有账号 */
    suspend fun clear() = dao.clear()

    /** 设置主账号 */
    suspend fun setPrimary(id: String) = dao.setPrimary(id)

    /** 更新备注 */
    suspend fun updateRemark(id: String, remark: String) = dao.updateRemark(id, remark)

    companion object {
        /** 提前刷新窗口（60 秒） */
        private const val REFRESH_AHEAD_MS = 60_000L
    }
}
