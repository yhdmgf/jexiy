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

import com.jiexiy.app.data.network.LanzouApi
import com.jiexiy.app.data.network.LanzouNewResult
import com.jiexiy.app.data.network.LanzouSharePage
import com.jiexiy.app.data.network.model.DownloadLink
import com.jiexiy.app.data.network.model.ShareFile
import com.jiexiy.app.data.network.model.ShareSession
import java.net.URI

/**
 * 蓝奏云分享解析仓库（文档 §3）：**匿名**，无需登录。
 * 分享页 HTML → 文件夹分页（filemoreajax）/ 单文件 → 下载参数（ajaxm/ajaxfile）→ HEAD 探测直链。
 * 解析出的分享页缓存在内存中（按分享 URL 作 key），供后续列目录 / 取链复用。
 *
 * 域名分流：
 * - `lanzn.com` 族（新版蓝奏，ESA 校验）走 [LanzouApi.resolveNewShare] 全新流程，无页面缓存；
 * - 其他蓝奏域（lanzou*/lan[zs]o[ux]）走老流程（页面 + iframe + downprocess + HEAD 探测）。
 */
class LanzouResolveRepository(
    private val api: LanzouApi
) : ShareResolveRepository {

    private val pageCache = mutableMapOf<String, LanzouSharePage>()

    /** 新版域（lanzn…）解析结果：createSession 时一次取全（name + 直链），后续步骤复用。 */
    private val newResults = mutableMapOf<String, LanzouNewResult>()

    private fun isNewDomain(link: String): Boolean {
        val host = runCatching { URI(link).host?.lowercase() }.getOrNull() ?: return false
        return host == "lanzn.com" || host.endsWith(".lanzn.com")
    }

    override suspend fun createSession(link: String, pwd: String?, cookie: String): Result<ShareSession> =
        runCatching {
            if (isNewDomain(link)) {
                val result = api.resolveNewShare(link, pwd)
                newResults[link] = result
                ShareSession(shareId = link, stoken = pwd.orEmpty(), title = result.name)
            } else {
                val page = api.resolveShare(link, pwd)
                if (page.needsPwd && pwd.isNullOrBlank()) {
                    throw IllegalStateException("此蓝奏分享需要提取码，请填写后重新解析")
                }
                pageCache[page.shareUrl] = page
                ShareSession(shareId = page.shareUrl, stoken = pwd.orEmpty(), title = page.title)
            }
        }

    override suspend fun listFiles(session: ShareSession, dirFid: String, cookie: String): Result<List<ShareFile>> =
        runCatching {
            newResults[session.shareId]?.let { r ->
                return@runCatching listOf(
                    ShareFile(
                        fid = session.shareId,
                        fname = r.name,
                        fsize = parseSizeLabel(r.filesizeLabel),
                        isdir = false,
                        pdirFid = "",
                        fidToken = ""
                    )
                )
            }
            val page = pageCache[session.shareId]
                ?: throw IllegalStateException("蓝奏分享会话已失效，请重新解析")
            val pwd = session.stoken.takeIf { it.isNotBlank() }
            if (page.isFolder) {
                api.listShareFolder(page, pwd, dirFid)
            } else {
                listOfNotNull(page.singleFile)
            }
        }

    override suspend fun ensureTempDir(cookie: String): Result<String> =
        Result.failure(UnsupportedOperationException("蓝奏分享暂不支持直接转存，请下载后上传"))

    override suspend fun transferFile(
        session: ShareSession,
        file: ShareFile,
        toDirFid: String,
        cookie: String
    ): Result<String> = Result.failure(
        UnsupportedOperationException("蓝奏分享暂不支持直接转存，请下载后上传")
    )

    override suspend fun getDownloadLink(fid: String, cookie: String): Result<DownloadLink> =
        Result.failure(UnsupportedOperationException("蓝奏分享请使用 getShareDownloadLink"))

    override suspend fun getShareDownloadLink(
        session: ShareSession,
        file: ShareFile,
        cookie: String
    ): Result<DownloadLink> = runCatching {
        newResults[session.shareId]?.let { r ->
            return@runCatching DownloadLink(
                fid = file.fid,
                filename = r.name,
                downloadUrl = r.downUrl,
                size = file.fsize
            )
        }
        val page = pageCache[session.shareId]
            ?: throw IllegalStateException("蓝奏分享会话已失效，请重新解析")
        api.getShareDownloadLink(page, file, session.stoken.takeIf { it.isNotBlank() })
    }

    override suspend fun getGuestShareDownloadLink(
        session: ShareSession,
        file: ShareFile
    ): Result<DownloadLink> = runCatching {
        newResults[session.shareId]?.let { r ->
            return@runCatching DownloadLink(
                fid = file.fid,
                filename = r.name,
                downloadUrl = r.downUrl,
                size = file.fsize
            )
        }
        val page = pageCache[session.shareId]
            ?: throw IllegalStateException("蓝奏分享会话已失效，请重新解析")
        api.getShareDownloadLink(page, file, session.stoken.takeIf { it.isNotBlank() })
    }

    /** 解析“1.5 M”之类的大小文本为字节；解析失败返回 0。 */
    private fun parseSizeLabel(label: String): Long {
        val m = Regex("""([\d.]+)\s*([KMGT]?)\s*B?""", RegexOption.IGNORE_CASE).find(label.trim()) ?: return 0L
        val value = m.groupValues[1].toDoubleOrNull() ?: return 0L
        val unit = m.groupValues[2].uppercase()
        val factor = when (unit) {
            "K" -> 1024L
            "M" -> 1024L * 1024
            "G" -> 1024L * 1024 * 1024
            "T" -> 1024L * 1024 * 1024 * 1024
            else -> 1L
        }
        return (value * factor).toLong()
    }
}
