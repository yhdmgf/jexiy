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
import android.util.Log
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
 * （每个平台一条主账号）。
 *
 * ★ 合并**在客户端**做（sync.php 只有 pull / sync_save / sync_del / clear_all 四个 CRUD，
 *   本身不合并），规则是**本地优先**：
 *   - 本地有的账号全部推上去（`sync_save` 覆盖写）——本地独有=新增，两端都有=本地为准覆盖云端；
 *   - 云端有、本地没有的记录拉下来写本地（「把多余的数据弄过来」）；
 *   - 本地删除走 [deleteAndSync]，先 `sync_del` 再同步，避免被云端拉回来。
 *
 * ★ GitHub Token 不参与同步：它在备份里是顶层字段，这里显式剔除，
 *   避免把 Keystore 里的单个标量凭证传到云端。
 */
class AccountSyncManager(private val backupManager: AuthBackupManager) {

    private companion object {
        const val TAG = "AccountSync"
        const val APP_TAG = "jexiy_auth_backup"
    }

    data class Stats(val uploaded: Int, val downloaded: Int, val kept: Int, val applied: Int)

    /** 同步串行化：启动对齐、登录保存、删除账号可能同时触发，避免两轮合并交叉写入 */
    private val mutex = Mutex()

    suspend fun sync(context: Context): AccountApi.Result<Stats> =
        mutex.withLock { doSync(context) }

    /**
     * 删除账号后的同步：先把云端该平台那条删掉，再做一次双向合并
     * （本地若还有同平台的其他账号，会被重新推上去）。
     */
    suspend fun deleteAndSync(
        platform: String,
        context: Context,
    ): AccountApi.Result<Stats> {
        when (val deleted = AccountApi.syncDel(context, platform)) {
            is AccountApi.Result.Failure -> return deleted
            is AccountApi.Result.Success -> Unit
        }
        return sync(context)
    }

    private suspend fun doSync(context: Context): AccountApi.Result<Stats> =
        withContext(Dispatchers.IO) {
            // 1. 拉云端全部记录
            val cloud = when (val res = AccountApi.pull(context)) {
                is AccountApi.Result.Failure -> return@withContext res
                is AccountApi.Result.Success -> res.data
            }
            val cloudByPlatform = cloud.associateBy { it.platform }

            // 2. 导出本地已登录账号（剔除 GitHub Token，不参与同步）
            val localAccounts = runCatching {
                val root = JSONObject(backupManager.exportJson(onlyLoggedIn = true, context))
                root.remove("githubToken")
                root.optJSONArray("accounts") ?: JSONArray()
            }.getOrElse {
                Log.e(TAG, "读取本地认证失败：${it.message}", it)
                return@withContext AccountApi.Result.Failure("读取本地认证失败")
            }

            val localPlatforms = (0 until localAccounts.length())
                .mapNotNull { localAccounts.optJSONObject(it)?.optString("platform") }
                .filter { it.isNotBlank() }
                .toSet()

            // 3. 上传：本地所有已登录账号都推上去（本地优先：冲突时覆盖云端）
            var uploaded = 0   // 本地有、云端没有 → 新增到云端
            var kept = 0       // 两端都有 → 本地为准，把本地版本覆盖上去
            for (i in 0 until localAccounts.length()) {
                val acct = localAccounts.optJSONObject(i) ?: continue
                val platform = acct.optString("platform")
                if (platform.isBlank()) continue
                val payload = extractPayload(acct)
                when (AccountApi.syncSave(context, platform, payload, acct.optLong("expiresAt", 0L))) {
                    is AccountApi.Result.Failure -> return@withContext it
                    is AccountApi.Result.Success -> {
                        if (cloudByPlatform.containsKey(platform)) kept++ else uploaded++
                    }
                }
            }

            // 4. 下载：云端有、本地没有的记录拉下来写本地
            val toImport = JSONArray()
            var downloaded = 0
            for (record in cloud) {
                if (record.platform in localPlatforms) continue
                val acct = JSONObject(record.payload)
                acct.put("platform", record.platform)
                acct.put("expiresAt", record.expiresAt)
                toImport.put(acct)
                downloaded++
            }

            var applied = 0
            if (!toImport.isEmpty()) {
                val importRoot = JSONObject()
                    .put("app", APP_TAG)
                    .put("version", 1)
                    .put("accounts", toImport)
                applied = runCatching { backupManager.importJson(importRoot.toString(), context) }
                    .getOrElse {
                        Log.e(TAG, "写入本地认证失败：${it.message}", it)
                        return@withContext AccountApi.Result.Failure("写入本地认证失败")
                    }
            }

            Log.i(TAG, "双向同步完成：上传 $uploaded / 保留(本地优先) $kept / 下载 $downloaded / 实际写入 $applied")
            AccountApi.Result.Success(Stats(uploaded, downloaded, kept, applied))
        }

    /** 账号对象 → 凭据 payload：去掉 `platform` 与 `expiresAt`（后者由 sync.php 单独存 `expires_at`） */
    private fun extractPayload(acct: JSONObject): JSONObject {
        val payload = JSONObject()
        val keys = acct.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k == "platform" || k == "expiresAt") continue
            payload.put(k, acct.get(k))
        }
        return payload
    }
}
