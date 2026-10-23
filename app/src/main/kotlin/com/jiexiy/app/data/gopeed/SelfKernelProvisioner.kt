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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
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

    /** Gitcode 的 Release 列表接口（返回数组，取最新一条） */
    private const val API = "https://api.gitcode.com/api/v5/repos/qldwi/latest/releases"

    /** 发布物文件名形如 my_gopeed_1.0.0.zip */
    private val ASSET_NAME = Regex("""^my_gopeed_.+\.zip$""")

    data class Release(val version: String, val url: String, val size: Long)

    /** 取最新 Release 里与本机匹配的内核包；网络/解析失败都以 [Result.failure] 返回 */
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
                val releases = JSONArray(text)
                val release = (0 until releases.length())
                    .mapNotNull { releases.optJSONObject(it) }
                    .firstOrNull()
                    ?: throw IllegalStateException("没有可用的内核版本")
                val assets = release.optJSONArray("assets") ?: JSONArray()
                val asset = (0 until assets.length())
                    .mapNotNull { assets.optJSONObject(it) }
                    .firstOrNull { ASSET_NAME.matches(it.optString("name")) }
                    ?: throw IllegalStateException("这个版本里没有 my_gopeed_*.zip")
                Release(
                    version = release.optString("tag_name"),
                    url = asset.optString("browser_download_url"),
                    size = asset.optLong("size", 0L)
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
        } finally {
            target.delete()
        }
    }
}
