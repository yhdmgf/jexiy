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

import com.jiexiy.app.data.network.FeijiApi
import com.jiexiy.app.data.network.ShareLinkParser
import com.jiexiy.app.data.network.model.DownloadLink
import com.jiexiy.app.data.network.model.ShareFile
import com.jiexiy.app.data.network.model.ShareSession
import com.jiexiy.app.data.parse.FeijiAccountStore
import java.io.IOException

/**
 * 小飞机分享仓库：完全走本地直连接口（对齐 wenxi-main 的 FeijipanConnector）。
 *
 * 分享根目录用 [FeijiApi.SHARE_ROOT]，子目录用接口返回的 folderId；
 * 列表是 POST，下载地址由 /ws/file/download 返回后 AES 解密。
 */
class FeijiResolveRepository(
    private val tokenProvider: () -> String = { FeijiAccountStore.token.value.orEmpty() },
    private val uuidProvider: () -> String = { FeijiAccountStore.account.value?.uuid.orEmpty() }
) : ShareResolveRepository {

    override suspend fun createSession(
        link: String,
        pwd: String?,
        cookie: String
    ): Result<ShareSession> = runCatching {
        val parsed = ShareLinkParser.parse(link)
            ?: throw IOException("无法识别小飞机分享链接")
        val uuid = uuidProvider().ifBlank { FeijiApi.getUuid() }
        val data = FeijiApi.shareList(
            uuid = uuid,
            token = tokenProvider(),
            shareId = parsed.shareId,
            code = pwd.orEmpty(),
            parent = FeijiApi.SHARE_ROOT
        )
        ShareSession(
            shareId = parsed.shareId,
            stoken = pwd.orEmpty(),
            title = data.title.ifBlank { "小飞机分享" }
        )
    }

    override suspend fun listFiles(
        session: ShareSession,
        dirFid: String,
        cookie: String
    ): Result<List<ShareFile>> = runCatching {
        val uuid = uuidProvider().ifBlank { FeijiApi.getUuid() }
        val files = FeijiApi.shareFiles(
            uuid = uuid,
            token = tokenProvider(),
            shareId = session.shareId,
            code = session.stoken,
            parent = dirFid.ifBlank { FeijiApi.SHARE_ROOT }
        )
        files.map { f ->
            ShareFile(
                fid = f.id,
                fname = f.name,
                fsize = f.size,
                isdir = f.isDir,
                pdirFid = dirFid.ifBlank { FeijiApi.SHARE_ROOT },
                fidToken = "",
                modifyTime = f.modifyTime
            )
        }
    }

    override suspend fun getShareDownloadLink(
        session: ShareSession,
        file: ShareFile,
        cookie: String
    ): Result<DownloadLink> = runCatching {
        val uuid = uuidProvider().ifBlank { FeijiApi.getUuid() }
        val userId = FeijiAccountStore.account.value?.userId.orEmpty()
        val spec = FeijiApi.downloadShare(
            token = tokenProvider(),
            uuid = uuid,
            userId = userId,
            shareId = session.shareId,
            fileId = file.fid,
            code = session.stoken
        )
        DownloadLink(
            fid = file.fid,
            filename = file.fname,
            downloadUrl = spec.url,
            size = file.fsize
        )
    }

    override suspend fun ensureTempDir(cookie: String): Result<String> =
        Result.failure(IOException("小飞机分享不支持转存"))

    override suspend fun transferFile(
        session: ShareSession,
        file: ShareFile,
        toDirFid: String,
        cookie: String
    ): Result<String> = runCatching {
        val account = FeijiAccountStore.account.value
            ?: throw IOException("请先登录小飞机网盘")
        val source = FeijiApi.File(
            id = file.fid,
            name = file.fname,
            isDir = file.isdir,
            size = file.fsize,
            parentId = file.pdirFid
        )
        FeijiApi.transfer(
            token = account.appToken,
            uuid = account.uuid,
            shareId = session.shareId,
            code = session.stoken,
            targetFolderId = toDirFid,
            files = listOf(source)
        )
        toDirFid
    }

    override suspend fun getDownloadLink(fid: String, cookie: String): Result<DownloadLink> =
        Result.failure(IOException("小飞机个人文件请从网盘页下载"))
}
