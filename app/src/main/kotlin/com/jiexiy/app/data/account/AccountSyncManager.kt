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

package com.jiexiy.app.data.account

import android.content.Context
import com.jiexiy.app.data.backup.AuthBackupManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 网盘账号双向同步：本地认证备份 ⇄ 云端（sync.php）。
 *
 * 复用 [AuthBackupManager] 的导出 / 导入格式，所以同步的范围与「网盘认证」备份完全一致
 * （每个平台一条主账号）。合并规则在服务端：
 * 本地独有 → 上传；云端独有 → 下载；两端都有 → 取 `expiresAt` 更晚的，
 * 到期时间都未知时取 `updatedAt` 更新的。
 *
 * ★ GitHub Token 不参与同步：它在备份里是顶层字段，这里显式剔除，
 *   避免把 Keystore 里的单个标量凭证传到云端。
 */
class AccountSyncManager(private val backupManager: AuthBackupManager) {

    data class Stats(val uploaded: Int, val downloaded: Int, val kept: Int, val applied: Int)

    /** 同步串行化：启动对齐、登录保存、删除账号可能同时触发，避免两轮合并交叉写入 */
    private val mutex = Mutex()

    suspend fun sync(cookie: String?, context: Context): AccountApi.Result<Stats> =
        mutex.withLock {
            doSync(cookie, context)
        }

    /**
     * 删除账号后的同步：先把云端该平台那条删掉，再做一次双向合并
     * （本地若还有同平台的其他账号，会被重新推上去）。
     */
    suspend fun deleteAndSync(
        platform: String,
        cookie: String?,
        context: Context,
    ): AccountApi.Result<Stats> {
        when (val deleted = AccountApi.deleteAccount(platform, cookie)) {
            is AccountApi.Result.Failure -> return deleted
            is AccountApi.Result.Success -> Unit
        }
        return sync(cookie, context)
    }

    private suspend fun doSync(cookie: String?, context: Context): AccountApi.Result<Stats> =
        withContext(Dispatchers.IO) {
            val local = runCatching {
                JSONObject(backupManager.exportJson(onlyLoggedIn = true, context))
            }.getOrElse {
                return@withContext AccountApi.Result.Failure("读取本地认证失败")
            }
            local.remove("githubToken")
            val payload = JSONObject()
                .put("app", "jexiy_auth_backup")
                .put("version", 1)
                .put("accounts", local.optJSONArray("accounts") ?: JSONArray())

            when (val result = AccountApi.sync(payload, cookie)) {
                is AccountApi.Result.Failure -> result
                is AccountApi.Result.Success -> {
                    val merged = JSONObject()
                        .put("app", "jexiy_auth_backup")
                        .put("version", 1)
                        .put("accounts", result.data.accounts)
                    val applied = runCatching {
                        backupManager.importJson(merged.toString(), context)
                    }.getOrElse {
                        return@withContext AccountApi.Result.Failure("写入本地认证失败")
                    }
                    AccountApi.Result.Success(
                        Stats(
                            uploaded = result.data.uploaded,
                            downloaded = result.data.downloaded,
                            kept = result.data.kept,
                            applied = applied
                        )
                    )
                }
            }
        }
}
