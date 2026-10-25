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

package com.jiexiy.app.data.repository

import com.jiexiy.app.data.network.PikPakApi
import com.jiexiy.app.data.network.ShareLinkParser
import com.jiexiy.app.data.network.model.DownloadLink
import com.jiexiy.app.data.network.model.ShareFile
import com.jiexiy.app.data.network.model.ShareSession
import java.io.IOException

/**
 * PikPak 分享链接解析：打开分享 → 列目录 → 转存后取直链。
 *
 * ★ PikPak 的分享文件**不能直接下载**：服务端没有给分享文件返回直链，必须先 `share/restore`
 * 转存到自己的网盘，再用转存后的新文件 id 走个人文件取链（[PikPakApi.getDownloadLink]）。
 * 所以这里的 `getShareDownloadLink` 会真正执行转存 + 轮询任务，比夸克那套更重——
 * 对应到 UI 上，解析页点下载会先转存再取链，整个流程对用户透明。
 */
class PikPakResolveRepository(
    private val api: PikPakApi,
    private val accountRepository: PikPakAccountRepository
) : ShareResolveRepository {

    override suspend fun createSession(link: String, pwd: String?, cookie: String): Result<ShareSession> =
        runCatching {
            val parsed = ShareLinkParser.parse(link)
                ?: throw IOException("无法识别 PikPak 分享链接")
            val deviceId = accountRepository.getAccount()?.deviceId
                ?: api.newDeviceId()
            val (session, _) = api.openShare(deviceId, parsed.shareId, pwd.orEmpty())
            ShareSession(
                shareId = session.shareId,
                // 复用 stoken 字段存放 passCodeToken（后续 listShare / restoreShareFile 都要它）
                stoken = session.passCodeToken,
                title = session.title.ifBlank { "PikPak 分享" }
            )
        }

    override suspend fun listFiles(
        session: ShareSession,
        dirFid: String,
        cookie: String
    ): Result<List<ShareFile>> = runCatching {
        val deviceId = accountRepository.getAccount()?.deviceId
            ?: api.newDeviceId()
        if (dirFid.isBlank()) {
            // 根目录：openShare 已经能拿到第一页，但为了避免再请求一次，这里直接走 listShare 根目录
            val (files, _) = api.listShare(deviceId, session.shareId, session.stoken)
            files.map { it.copy(pdirFid = "") }
        } else {
            val (files, next) = api.listShare(deviceId, session.shareId, session.stoken, parentId = dirFid)
            val all = files.toMutableList()
            var page = next
            // 自动翻页（PikPak 一页 100 条，大分享会分多页）
            while (page.isNotEmpty()) {
                val (more, p) = api.listShare(
                    deviceId, session.shareId, session.stoken,
                    parentId = dirFid, pageToken = page
                )
                all += more
                page = p
            }
            all
        }
    }

    override suspend fun ensureTempDir(cookie: String): Result<String> =
        Result.failure(IOException("PikPak 分享转存到根目录，无需临时目录"))

    override suspend fun transferFile(
        session: ShareSession,
        file: ShareFile,
        toDirFid: String,
        cookie: String
    ): Result<String> = runCatching {
        val acc = accountRepository.getAccount()
            ?: throw IOException("请先登录 PikPak 网盘")
        val token = accountRepository.ensureAccessToken() ?: acc.accessToken
        val ancestorIds = file.pdirFid.takeIf { it.isNotBlank() }?.let { listOf(it) } ?: emptyList()
        api.restoreShareFile(
            accessToken = token,
            deviceId = acc.deviceId,
            shareId = session.shareId,
            passCodeToken = session.stoken,
            fileId = file.fid,
            ancestorIds = ancestorIds,
            parentId = toDirFid
        )
    }

    override suspend fun getShareDownloadLink(
        session: ShareSession,
        file: ShareFile,
        cookie: String
    ): Result<DownloadLink> = runCatching {
        val acc = accountRepository.getAccount()
            ?: throw IOException("请先登录 PikPak 网盘")
        val token = accountRepository.ensureAccessToken() ?: acc.accessToken
        // 转存到根目录（parentId 空 = 我的网盘根目录），拿到新文件 id 后走个人文件取链
        val newFid = api.restoreShareFile(
            accessToken = token,
            deviceId = acc.deviceId,
            shareId = session.shareId,
            passCodeToken = session.stoken,
            fileId = file.fid,
            ancestorIds = file.pdirFid.takeIf { it.isNotBlank() }?.let { listOf(it) } ?: emptyList(),
            parentId = ""
        )
        api.getDownloadLink(token, acc.deviceId, newFid, file.fname)
    }

    override suspend fun getDownloadLink(fid: String, cookie: String): Result<DownloadLink> =
        Result.failure(IOException("PikPak 个人文件请从网盘页下载"))
}
