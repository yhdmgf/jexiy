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

package com.jiexiy.app.data.download

import android.content.Context
import android.os.SystemClock
import com.jiexiy.app.data.gopeed.GopeedEngine
import com.jiexiy.app.data.network.HttpClients
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * 下载引擎页顶部的测速：拿同一份发布物（`my_gopeed_<版本>.zip`，约 45MB）跑一遍，
 * 用当前选中的引擎下载，统计**平均速度**与**总耗时**。
 *
 * - 内置分片下载器：64 路 Range 请求并发读、数据直接丢弃（不落盘）；
 * - Gopeed / 自研内核：交给引擎建一个 64 连接的任务，落到缓存目录，测完删除。
 *
 * 测速文件不写入用户下载目录，也不进入下载列表。
 */
object SpeedTest {

    private const val API = "https://api.gitcode.com/api/v5/repos/qldwi/latest/releases"
    private val ASSET_NAME = Regex("""^my_gopeed_.+\.zip$""")

    /** 测速固定 64 线程（用户要求：默认 64，不跟设置里的分片并发走） */
    const val THREADS = 64

    data class Stats(val bytes: Long, val millis: Long) {
        /** 平均速度（字节/秒） */
        val avgBytesPerSec: Long get() = if (millis > 0) bytes * 1000L / millis else 0L
    }

    /** 从 Release 列表里取测速文件地址 */
    suspend fun resolveTestUrl(): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(API)
            .header("Accept", "application/json")
            .header("User-Agent", "jexiy")
            .build()
        HttpClients.apiClient().newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("获取测速文件失败（HTTP ${resp.code}）")
            val releases = JSONArray(resp.body?.string().orEmpty())
            val release = (0 until releases.length())
                .mapNotNull { releases.optJSONObject(it) }
                .firstOrNull()
                ?: throw IllegalStateException("没有可用的测速文件")
            val assets = release.optJSONArray("assets") ?: JSONArray()
            val asset = (0 until assets.length())
                .mapNotNull { assets.optJSONObject(it) }
                .firstOrNull { ASSET_NAME.matches(it.optString("name")) }
                ?: throw IllegalStateException("这个版本里没有 my_gopeed_*.zip")
            asset.optString("browser_download_url")
        }
    }

    /**
     * 用内置分片下载器测速：64 路 Range 并发读，读完即丢。
     * 服务器不支持 Range 时退回单流。
     */
    suspend fun runBuiltin(
        url: String,
        threads: Int = THREADS,
        onProgress: (Long) -> Unit = {},
    ): Stats = withContext(Dispatchers.IO) {
        val client = HttpClients.downloadClient()
        var total = -1L
        val probe = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-0")
            .header("User-Agent", "jexiy")
            .build()
        client.newCall(probe).execute().use { resp ->
            val range = resp.header("Content-Range")
            if (resp.code == 206 && range != null) {
                total = range.substringAfter('/', "").trim().toLongOrNull() ?: -1L
            }
        }

        val counter = AtomicLong(0L)
        val start = SystemClock.elapsedRealtime()
        if (total <= 0L) {
            // 不支持分片：单流读完丢弃，速度按实际读到的字节算
            val request = Request.Builder().url(url).header("User-Agent", "jexiy").build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("测速请求失败（HTTP ${resp.code}）")
                resp.body?.byteStream()?.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        counter.addAndGet(read.toLong())
                    }
                }
            }
            onProgress(counter.get())
            return@withContext Stats(counter.get(), SystemClock.elapsedRealtime() - start)
        }

        coroutineScope {
            val ticker = launch {
                while (isActive) {
                    onProgress(counter.get())
                    delay(200)
                }
            }
            try {
                val chunk = (total + threads - 1) / threads
                val jobs = (0 until threads).map { index ->
                    launch(Dispatchers.IO) {
                        val from = index * chunk
                        if (from >= total) return@launch
                        val to = minOf(from + chunk - 1, total - 1)
                        val request = Request.Builder()
                            .url(url)
                            .header("Range", "bytes=$from-$to")
                            .header("User-Agent", "jexiy")
                            .build()
                        client.newCall(request).execute().use { resp ->
                            if (resp.code != 206) throw IllegalStateException("服务器不支持分片测速")
                            resp.body?.byteStream()?.use { input ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    counter.addAndGet(read.toLong())
                                }
                            }
                        }
                    }
                }
                jobs.forEach { it.join() }
            } finally {
                ticker.cancel()
            }
        }
        val millis = SystemClock.elapsedRealtime() - start
        onProgress(counter.get())
        Stats(counter.get(), millis)
    }

    /**
     * 用 Gopeed / 自研内核测速：建一个 64 连接任务，轮询到完成，删任务与临时文件。
     */
    suspend fun runEngine(
        context: Context,
        url: String,
        threads: Int = THREADS,
        kernel: GopeedEngine.Kernel,
        onProgress: (Long) -> Unit = {},
    ): Stats = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "speedtest_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            if (GopeedEngine.state.value != GopeedEngine.State.RUNNING) {
                GopeedEngine.start(context, dir, kernel)
            }
            val name = "jexiy_speedtest.bin"
            val taskId = GopeedEngine.createTask(
                url = url,
                saveDir = dir,
                connections = threads,
                name = name
            )
            val start = SystemClock.elapsedRealtime()
            var result: Stats? = null
            try {
                while (result == null) {
                    val status = GopeedEngine.taskStatus(taskId)
                    onProgress(status.downloaded)
                    when (status.status) {
                        "done" -> {
                            val millis = SystemClock.elapsedRealtime() - start
                            val bytes = status.total.takeIf { it > 0 } ?: status.downloaded
                            result = Stats(bytes, millis)
                        }
                        "error" -> throw IllegalStateException("引擎测速失败")
                    }
                    if (result == null) {
                        if (SystemClock.elapsedRealtime() - start > 10 * 60 * 1000L) {
                            throw IllegalStateException("测速超时")
                        }
                        delay(300)
                    }
                }
            } finally {
                runCatching { GopeedEngine.deleteTask(taskId) }
            }
            result ?: throw IllegalStateException("测速未完成")
        } finally {
            dir.deleteRecursively()
        }
    }
}
