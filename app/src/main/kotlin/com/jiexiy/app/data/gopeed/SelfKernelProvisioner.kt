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

package com.jiexiy.app.data.gopeed

import android.content.Context
import android.util.Log
import com.jiexiy.app.data.network.HttpClients
import com.jiexiy.app.data.prefs.SettingsRepository
import com.jiexiy.app.data.update.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * 自研内核的获取与安装。
 *
 * 发布物不是 AAR，而是一个直接可用的 ELF `.so`（文件名 `my_gopeed_<版本>.zip`，
 * 但内容是 ARM64 共享库，和 Gopeed 内核导出同一套 `libgopeed.Libgopeed` JNI 符号）。
 * 所以这里只做「拿最新 Release → 下载 → 交给 [GopeedEngine.installFromFile] 落到
 * `libgojni_self.so`」三步，不做解压。
 */
object SelfKernelProvisioner {

    private const val TAG = "SelfKernel"

    /** Gitcode 的 Release 详情接口（单条对象，latest 通道：tag_name / body / 公告正文） */
    private const val API = "https://api.gitcode.com/api/v5/repos/qldwi/latest/releases/latest"

    /** 发布物文件名形如 my_gopeed_1.0.0.zip；其它 source 包（zip/tar/…）是附赠源码，不认 */
    private val ASSET_NAME = Regex("""^my_gopeed_.+\.zip$""")

    data class Release(
        val version: String,
        val url: String,
        val size: Long,
        /** Release 正文（公告内容）：界面在「有更新」时展示 */
        val body: String = "",
        /** 正文里命中的 [网盘下载](url)；没有该条目则为 null（仅直链下载） */
        val panUrl: String? = null
    )

    /** 取最新 Release：一次请求同时拿到版本与公告（body）；网络/解析失败都以 [Result.failure] 返回 */
    suspend fun loadRelease(): Result<Release> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(API)
                .header("Accept", "application/json")
                .header("User-Agent", "jexiy")
                .build()
            HttpClients.apiClient().newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException("获取内核版本失败（HTTP ${resp.code}）")
                }
                val text = resp.body?.string().orEmpty()
                val release = JSONObject(text)
                val assets = release.optJSONArray("assets") ?: JSONArray()
                val asset = (0 until assets.length())
                    .mapNotNull { assets.optJSONObject(it) }
                    .firstOrNull { ASSET_NAME.matches(it.optString("name")) }
                    ?: throw IllegalStateException("这个版本里没有 my_gopeed_*.zip")
                val body = release.optString("body").orEmpty()
                Release(
                    version = release.optString("tag_name"),
                    url = asset.optString("browser_download_url"),
                    size = asset.optLong("size", 0L),
                    body = body,
                    panUrl = UpdateChecker.netdiskDownloadUrl(body)
                )
            }
        }
    }

    /**
     * 下载并安装到自研内核槽位。
     *
     * @param onProgress (已下载字节, 总字节)；总字节未知时为 0
     * @return 写出的字节数
     */
    suspend fun downloadAndInstall(
        context: Context,
        release: Release,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Long = withContext(Dispatchers.IO) {
        val dir = GopeedEngine.kernelTempDir(context).apply { mkdirs() }
        val target = File(dir, "self-kernel-${release.version}.so")
        try {
            val request = Request.Builder()
                .url(release.url)
                .header("User-Agent", "jexiy")
                .build()
            HttpClients.apiClient().newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException("下载内核失败（HTTP ${resp.code}）")
                }
                val body = resp.body ?: throw IllegalStateException("下载内核失败：响应为空")
                val total = body.contentLength().takeIf { it > 0 } ?: release.size
                var done = 0L
                body.byteStream().use { input ->
                    FileOutputStream(target).use { out ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            done += read
                            onProgress(done, total)
                        }
                    }
                }
            }
            Log.d(TAG, "内核已下载：${target.absolutePath} ${target.length()} 字节，开始安装")
            GopeedEngine.installFromFile(context, target, GopeedEngine.Kernel.SELF)
            // 记录已导入的本地内核版本：下载引擎页用它跟云端对比，版本相同就不提示更新公告
            runCatching { SettingsRepository(context).selfKernelVersion = release.version }
        } finally {
            target.delete()
        }
    }
}
