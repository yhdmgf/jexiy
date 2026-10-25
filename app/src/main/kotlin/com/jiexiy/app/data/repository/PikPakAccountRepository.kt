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
import com.jiexiy.app.data.db.PikPakAccountDao
import com.jiexiy.app.data.db.PikPakAccountEntity
import com.jiexiy.app.data.db.resolveAccountSlot
import com.jiexiy.app.data.download.DownloadPlatform
import com.jiexiy.app.data.network.PikPakApi
import com.jiexiy.app.data.network.PikPakWebCredential
import com.jiexiy.app.data.network.PikPakLoginExpiredException
import kotlinx.coroutines.flow.Flow

/**
 * PikPak 账号仓库：账号密码登录 → 拿 access_token / refresh_token 落库（加密）；
 * access_token 快过期时自动用 refresh_token 刷新；refresh 本身失效时抛出登录失效。
 *
 * 凭证不落密码：PikPak 走 OAuth refresh_token 续期，无需保存明文密码。
 */
class PikPakAccountRepository(
    private val dao: PikPakAccountDao,
    private val api: PikPakApi
) {

    fun observeAccount(): Flow<PikPakAccountEntity?> = dao.observeAccount()

    suspend fun getAccount(): PikPakAccountEntity? = dao.getAccount()

    /**
     * 账号密码登录并落库。
     * @param newAccount true = 「添加账号」模式，落成新的一行；false = 覆盖当前主账号那一行
     */
    suspend fun login(
        account: String,
        password: String,
        newAccount: Boolean = false
    ): Result<PikPakAccountEntity> = runCatching {
        val existing = dao.getAccount()
        val deviceId = existing?.deviceId?.takeIf { it.isNotBlank() } ?: api.newDeviceId()
        val tokens = api.login(account, password, deviceId)
        val info = runCatching { api.fetchMe(tokens.accessToken, deviceId) }.getOrDefault("" to "")
        val slot = resolveAccountSlot(DownloadPlatform.PIKPAK, existing, newAccount)
        val entity = PikPakAccountEntity(
            id = slot.id,
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken,
            deviceId = deviceId,
            account = account.trim(),
            userId = info.first,
            nickname = info.second.ifBlank { account.trim() },
            expiresAtMs = tokens.expiresAtMs,
            captchaToken = tokens.captchaToken,
            isPrimary = slot.isPrimary,
            remark = slot.remark,
            updatedAt = System.currentTimeMillis()
        )
        dao.upsert(entity)
        AccountSyncTrigger.notifySaved()
        entity
    }

    /**
     * 保存网页登录凭据到数据库。从 [PikPakWebCredential] 解析后的 tokens 直接落库。
     *
     * 网页登录得到的 access_token / refresh_token 与账号密码登录属于同一套 OAuth 体系
     * （网页版 client_id `YUMx5nI8ZU8Ap8pm` 与移动端 `YNxT9w7GMdWvEOKa` 的令牌在服务端互认），
     * 可直接走后续 API 请求。
     *
     * expiresAtMs 存 0：网页令牌默认不过期，refresh 机制会自己判定。后续 refresh 失败时
     * 用户重新登录即可。
     */
    suspend fun saveWebCredential(raw: String, newAccount: Boolean = false): Boolean {
        val tokens = PikPakWebCredential.parse(raw) ?: return false
        if (tokens.accessToken.isBlank()) return false
        return try {
            val existing = dao.getAccount()
            val deviceId = tokens.deviceId.ifBlank {
                existing?.deviceId?.takeIf { it.isNotBlank() } ?: api.newDeviceId()
            }
            val account = tokens.userId.ifBlank { tokens.nickname }.ifBlank { "pikpak" }
            val slot = resolveAccountSlot(DownloadPlatform.PIKPAK, existing, newAccount)
            val entity = PikPakAccountEntity(
                id = slot.id,
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken,
                deviceId = deviceId,
                account = account,
                userId = tokens.userId,
                nickname = tokens.nickname.ifBlank { account },
                expiresAtMs = 0L,
                captchaToken = tokens.captchaToken,
                isPrimary = slot.isPrimary,
                remark = slot.remark,
                updatedAt = System.currentTimeMillis()
            )
            dao.upsert(entity)
            AccountSyncTrigger.notifySaved()
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 取有效 access_token：临近过期（<60 秒）自动刷新；refresh 也失效时返回 null。
     * 调用方拿到 null 后应引导重新登录。
     */
    suspend fun ensureAccessToken(): String? {
        val acc = dao.getAccount() ?: return null
        if (acc.accessToken.isBlank()) return null
        if (!needsRefresh(acc.expiresAtMs)) return acc.accessToken
        return refreshAndPersist(acc)
    }

    /** refresh_token 换新 access_token 并写库。失败时返回 null（意味着需要重新登录）。 */
    private suspend fun refreshAndPersist(acc: PikPakAccountEntity): String? {
        if (acc.refreshToken.isBlank() || acc.deviceId.isBlank()) return null
        return try {
            val refreshed = api.refreshToken(acc.refreshToken, acc.deviceId)
            val updated = acc.copy(
                accessToken = refreshed.accessToken,
                refreshToken = refreshed.refreshToken,
                expiresAtMs = refreshed.expiresAtMs,
                updatedAt = System.currentTimeMillis()
            )
            dao.upsert(updated)
            refreshed.accessToken
        } catch (e: PikPakLoginExpiredException) {
            null
        }
    }

    /** access_token 是否已过期或将在 60 秒内过期。 */
    private fun needsRefresh(expiresAtMs: Long): Boolean =
        expiresAtMs > 0L && expiresAtMs - System.currentTimeMillis() <= REFRESH_AHEAD_MS
}

/** 提前刷新窗口（60 秒）。 */
private const val REFRESH_AHEAD_MS = 60_000L
