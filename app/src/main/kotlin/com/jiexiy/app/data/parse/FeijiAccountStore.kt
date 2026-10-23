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

package com.jiexiy.app.data.parse

import android.content.Context
import com.jiexiy.app.data.db.AppDatabase
import com.jiexiy.app.data.db.FeijiAccountDao
import com.jiexiy.app.data.db.FeijiAccountEntity
import com.jiexiy.app.data.db.resolveAccountSlot
import com.jiexiy.app.data.network.FeijiApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 小飞机网盘账号（多账号，落 Room，appToken 加密）。
 *
 * 与其它网盘一致：主账号用于列目录 / 取链，账号弹窗可以设主账号、改备注、退出、添加账号。
 * 登录页只负责拿到 appToken + uuid，真实身份（昵称 / userId）由 [FeijiApi.account] 校验后写入。
 */
object FeijiAccountStore {

    private var dao: FeijiAccountDao? = null
    private var prefs: android.content.SharedPreferences? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _accounts = MutableStateFlow<List<FeijiAccountEntity>>(emptyList())
    val accounts: StateFlow<List<FeijiAccountEntity>> = _accounts.asStateFlow()

    private val _account = MutableStateFlow<FeijiAccountEntity?>(null)
    val account: StateFlow<FeijiAccountEntity?> = _account.asStateFlow()

    private val _token = MutableStateFlow<String?>(null)
    val token: StateFlow<String?> = _token.asStateFlow()

    val isLoggedIn: Boolean get() = !_token.value.isNullOrBlank()

    fun init(context: Context) {
        if (dao != null) return
        synchronized(this) {
            if (dao != null) return
            val appContext = context.applicationContext
            val store = AppDatabase.get(appContext).feijiAccountDao()
            dao = store
            prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            scope.launch {
                store.observeAccounts().collect { list ->
                    _accounts.value = list
                    val primary = list.firstOrNull()
                    _account.value = primary
                    _token.value = primary?.appToken?.takeIf { it.isNotBlank() }
                }
            }
            migrateLegacyToken(store)
        }
    }

    /** 旧版本只存了 SharedPreferences 里的 appToken，迁到账号表后删除旧值 */
    private fun migrateLegacyToken(store: FeijiAccountDao) {
        scope.launch {
            if (store.getAccounts().isNotEmpty()) return@launch
            val old = prefs?.getString(KEY_TOKEN, null)?.trim().orEmpty()
            val token = old.substringAfter("appToken=", old).trim()
            if (token.isBlank()) return@launch
            store.upsert(
                FeijiAccountEntity(
                    id = PLATFORM,
                    appToken = token,
                    nickname = "小飞机用户",
                    isPrimary = true
                )
            )
            prefs?.edit()?.remove(KEY_TOKEN)?.apply()
        }
    }

    /**
     * 登录成功后落库。
     * @param newAccount true = 账号弹窗「添加账号」进来的，落成新的一行
     */
    suspend fun save(
        context: Context,
        token: String,
        uuid: String,
        info: FeijiApi.Account,
        newAccount: Boolean
    ): FeijiAccountEntity {
        init(context)
        val store = dao ?: throw IllegalStateException("小飞机账号存储未初始化")
        val existing = store.getAccount()
        val slot = resolveAccountSlot(PLATFORM, existing, newAccount)
        val entity = FeijiAccountEntity(
            id = slot.id,
            appToken = token,
            uuid = uuid,
            account = info.account,
            userId = info.userId,
            nickname = info.name,
            updatedAt = System.currentTimeMillis(),
            isPrimary = slot.isPrimary,
            remark = slot.remark
        )
        store.upsert(entity)
        return entity
    }

    /** 补齐缺失的 uuid / userId（旧数据或网页登录未带设备号时） */
    suspend fun ensureAccount(context: Context): FeijiAccountEntity? {
        init(context)
        val store = dao ?: return null
        val acc = store.getAccount() ?: return null
        var uuid = acc.uuid
        if (!Regex("^[A-Za-z0-9_-]{8,128}$").matches(uuid)) uuid = FeijiApi.getUuid()
        if (uuid != acc.uuid || acc.userId.isBlank()) {
            val info = FeijiApi.account(acc.appToken, uuid)
            val updated = acc.copy(
                uuid = uuid,
                userId = info.userId,
                account = info.account.ifBlank { acc.account },
                nickname = info.name.ifBlank { acc.nickname },
                updatedAt = System.currentTimeMillis()
            )
            store.upsert(updated)
            return updated
        }
        return acc
    }

    suspend fun setPrimary(context: Context, id: String) {
        init(context)
        dao?.setPrimary(id)
    }

    suspend fun rename(context: Context, id: String, remark: String) {
        init(context)
        dao?.updateRemark(id, remark.trim())
    }

    /** 退出单个账号；删掉主账号后把剩余账号里最近登录的补为主账号 */
    suspend fun remove(context: Context, id: String) {
        init(context)
        val store = dao ?: return
        store.deleteById(id)
        val rest = store.getAccounts()
        if (rest.isNotEmpty() && rest.none { it.isPrimary }) {
            store.setPrimary(rest.first().id)
        }
    }

    suspend fun clearAll(context: Context) {
        init(context)
        dao?.clear()
    }

    private const val PLATFORM = "feiji"
    private const val PREFS_NAME = "feiji_account"
    private const val KEY_TOKEN = "app_token"
}
