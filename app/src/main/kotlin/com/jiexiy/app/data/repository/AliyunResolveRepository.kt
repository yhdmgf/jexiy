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

import com.jiexiy.app.data.network.AliyunApi
import com.jiexiy.app.data.network.AliyunConstants
import com.jiexiy.app.data.network.AliyunCredential
import com.jiexiy.app.data.network.AliyunShareSession
import com.jiexiy.app.data.network.ShareLinkParser
import com.jiexiy.app.data.network.model.DownloadLink
import com.jiexiy.app.data.network.model.ShareFile
import com.jiexiy.app.data.network.model.ShareSession
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 阿里云盘分享仓库：分享令牌匿名鉴权，列目录 / 取直链都不需要登录账号。
 *
 * - createSession：`get_share_by_anonymous`（标题）+ `get_share_token`（令牌）
 * - listFiles：`list_by_share`（根目录 `parent_file_id = "root"`）
 * - getShareDownloadLink：`get_share_download_url`（匿名，靠 `X-Share-Token`）
 * - transferFile：`file/copy`（batch）转存到自己网盘
 */
class AliyunResolveRepository(
    private val api: AliyunApi = AliyunApi(),
    private val accountRepo: AliyunAccountRepository? = null
) : ShareResolveRepository {

    private val fileCache = ConcurrentHashMap<String, ShareFile>()

    private fun shareSession(s: ShareSession) =
        AliyunShareSession(s.shareId, s.stoken, s.title)

    private suspend fun credential(): AliyunCredential {
        val repo = accountRepo
            ?: throw IOException("阿里云盘账号仓库未初始化，无法转存")
        return repo.buildCredential()
            ?: throw IOException("请先登录阿里云盘")
    }

    override suspend fun createSession(
        link: String,
        pwd: String?,
        cookie: String
    ): Result<ShareSession> = runCatching {
        val parsed = ShareLinkParser.parse(link)
            ?: throw IOException("无法识别阿里云盘分享链接")
        val s = api.shareToken(parsed.shareId, pwd.orEmpty())
        ShareSession(shareId = s.shareId, stoken = s.stoken, title = s.title)
    }

    override suspend fun listFiles(
        session: ShareSession,
        dirFid: String,
        cookie: String
    ): Result<List<ShareFile>> = runCatching {
        api.listByShare(shareSession(session), dirFid.ifBlank { "root" })
    }

    override suspend fun getShareDownloadLink(
        session: ShareSession,
        file: ShareFile,
        cookie: String
    ): Result<DownloadLink> = runCatching {
        val url = api.shareDownloadUrl(shareSession(session), file.fid)
        DownloadLink(
            fid = file.fid,
            filename = file.fname,
            downloadUrl = url,
            size = file.fsize
        )
    }

    /** 阿里云盘分享匿名可下载：游客路径与登录路径一致。 */
    override suspend fun getGuestShareDownloadLink(
        session: ShareSession,
        file: ShareFile
    ): Result<DownloadLink> = getShareDownloadLink(session, file, "")

    override suspend fun ensureTempDir(cookie: String): Result<String> = runCatching {
        val c = credential()
        val name = AliyunConstants.TEMP_DIR_PREFIX + UUID.randomUUID().toString().take(8)
        val dir = api.mkdir(c, "root", name)
        dir.fid
    }

    override suspend fun transferFile(
        session: ShareSession,
        file: ShareFile,
        toDirFid: String,
        cookie: String
    ): Result<String> = runCatching {
        val c = credential()
        val driveId = c.driveId.ifBlank { throw IOException("阿里云盘空间标识缺失") }
        // 转存前刷新分享 token（短期有效）
        val fresh = api.shareToken(session.shareId, "")
        val newFid = api.saveShareFile(
            c, session.shareId, fresh.stoken, file.fid, driveId, toDirFid
        )
        // 缓存新 fid 对应的文件信息，供 getDownloadLink 使用
        fileCache[newFid] = file.copy(fid = newFid)
        newFid
    }

    override suspend fun getDownloadLink(fid: String, cookie: String): Result<DownloadLink> = runCatching {
        val c = credential()
        val cached = fileCache[fid]
        api.getDownloadUrl(c, fid, cached?.fname ?: "", cached?.fsize ?: 0L)
    }

    override suspend fun cleanupTempDir(dirFid: String, cookie: String) {
        runCatching {
            val c = credential()
            api.deleteTrash(c, dirFid)
        }
        // 清理缓存中属于该目录的文件
        fileCache.entries.removeIf { it.key == dirFid }
    }
}
