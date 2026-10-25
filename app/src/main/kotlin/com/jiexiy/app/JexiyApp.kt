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

package com.jiexiy.app

import android.app.Application
import android.content.Context
import com.jiexiy.app.crash.CrashHandler

// 防篡改检测已移除：本项目为开源项目，无需防止二次打包。
// 如需重新启用，可从 git 历史恢复 ArchiveProbe 和 entryMismatch 逻辑。

class JexiyApp : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // 移除早期探测：不再检查可疑类加载
    }

    override fun onCreate() {
        super.onCreate()
        // 应用级 Context：断点续传等底层工具类拿不到注入的 Context，只能从这里取
        com.jiexiy.app.util.AppContextHolder.install(this)
        // 移除调试日志
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(this))
        // 上传队列：注册各平台上传实现，让上传页在还没入队文件时也能展示平台名
        com.jiexiy.app.data.upload.CloudUploadManager.init(this)
        // 移除防篡改检测：不再检查 ArchiveProbe 或类名匹配
        
        // 防篡改检测已移除：不再执行深度扫描
        // scheduleDeepScan(this)
        com.jiexiy.app.data.network.XunleiDeviceFingerprint.init(this)
        // 123 云盘的设备标识（loginuuid）：账号密码登录与所有业务请求都要带，必须跨启动保持稳定，
        // 否则服务端会把每次启动都当成新设备（登录更容易被要求过验证）
        com.jiexiy.app.data.network.Pan123DeviceId.install(this)
        // 本机密钥失效自愈：必须在**任何**凭证读写之前装配。改锁屏密码/指纹会让 Android Keystore
        // 里的密钥被系统永久作废（KeyPermanentlyInvalidatedException / Key not found / Invalid
        // key blob 三种形态），此时要记下「登录态已失效」的一次性提示，而不是把 Keystore 异常
        // 抛到主线程把 App 崩掉（1.2.8 的三份崩溃报告）。
        com.jiexiy.app.data.security.CredentialStore.installRecovery(this)
        // 隐藏 WebView 解析通道：需要跟踪「当前前台 Activity」以便把隐藏 WebView 挂上窗口
        // （不挂窗口的话 requestAnimationFrame / visibilityState 会不对，页面初始化脚本可能卡住）
        com.jiexiy.app.data.network.video.HeadlessWebView.install(this)
        // 启动时恢复用户配置的 HTTP 代理（任何异常都不得影响应用启动）
        runCatching {
            val prefs = getSharedPreferences("jexiy_settings", Context.MODE_PRIVATE)
            if (prefs.getBoolean("proxy_enabled", false)) {
                val host = prefs.getString("proxy_host", "")?.takeIf { it.isNotBlank() }
                val port = prefs.getInt("proxy_port", 7890)
                if (host != null && port in 1..65535) {
                    com.jiexiy.app.data.network.HttpClients.setProxy(host, port)
                }
            }
        }
        purgeDownloadLeftovers(this)
        // 诊断日志：读开关（默认关）并定好落盘目录；开着才起写线程，关着这里几乎零开销
        com.jiexiy.app.util.DiagnosticLog.install(this)
        // 下载调试：读开关（默认关）。必须在任何下载开始之前装配，否则开关打开后先建的任务收不到事件
        com.jiexiy.app.data.download.DownloadDebugLog.install(this)
        // 链接唤起（深链接）：把用户设置刷到清单里那些 activity-alias 的启停状态。
        // 设置里改开关是即时同步的，这里兜安装后首次启动与「改过开关又重装/清数据」的对齐；
        // 任何异常都只记日志，绝不影响启动。
        runCatching { com.jiexiy.app.util.LinkOpener.syncAll(this) }
        // 选了 Gopeed 引擎且已导入内核：启动就把它加载起来（失败只记日志，绝不影响应用启动）
        autoStartGopeedIfSelected(this)
    }

    /**
     * 内存压力回调：系统回收前先释放「可再生」的内存 —— 空闲 HTTP 连接及其 socket/Conscrypt 缓冲。
     * 分片下载的数据全部流式落盘、不在堆上缓存，所以这里只丢弃空闲连接，不会影响进行中的下载。
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            com.jiexiy.app.data.network.HttpClients.evictIdleConnections()
            android.util.Log.w("jexiy", "onTrimMemory level=$level：已释放空闲连接")
        }
    }
}

/** 清理历史版本保存失败遗留的不可见下载半成品（详见 DownloadSaver.purgeOwnPendingFiles）。
 *  必须早于任何下载启动，故放在 Application.onCreate 的后台线程里做一次。 */
private fun purgeDownloadLeftovers(ctx: Context) {
    Thread {
        runCatching { com.jiexiy.app.data.download.DownloadSaver.purgeOwnPendingFiles(ctx) }
    }.start()
}

/**
 * 选了 Gopeed 引擎时，应用启动就把引擎加载起来（只有导入过内核才做，见 Agent.md §3.32）。
 *
 * 引擎是进程内单例，首次加载要 System.load 56 MB 的 .so 并初始化 Go runtime，提前加载能让第一个
 * 下载任务不必等它；**任何失败都只记日志** —— 引擎起不来不能影响应用启动，下载侧会按失败任务处理。
 *
 * 另外无论选的是哪个下载器，都在这里把引擎的内存状态与真实文件对齐一次（`syncInstalledState`）：
 * 选内置下载器时下面会直接 return、整条启动流程都不会碰引擎，不对齐的话引擎页会以为「未导入内核」。
 */
private fun autoStartGopeedIfSelected(ctx: Context) {
    val engine = com.jiexiy.app.data.gopeed.GopeedEngine
    val settingsRepo = com.jiexiy.app.data.prefs.SettingsRepository(ctx)
    val engineChoice = settingsRepo.downloadEngine
    val kernel = com.jiexiy.app.data.gopeed.GopeedEngine.Kernel.of(engineChoice)
    engine.syncInstalledState(ctx, kernel)
    if (engineChoice != com.jiexiy.app.data.prefs.SettingsRepository.ENGINE_GOPEED) return
    if (!engine.isInstalled(ctx, kernel)) {
        // 内核被删了（或从没导入过）但设置还停在引擎上：自愈回内置下载器，避免"设置说在用引擎、
        // 实际跑的是内置下载器"的错位（与下载引擎页删内核时的处理一致）。
        settingsRepo.downloadEngine = com.jiexiy.app.data.prefs.SettingsRepository.ENGINE_BUILTIN
        return
    }
    Thread {
        runCatching {
            engine.start(ctx, engine.resolveDownloadDir(ctx), kernel)
        }.onFailure {
            android.util.Log.e("jexiy", "启动时自动加载下载引擎失败：${it.message}", it)
        }
    }.start()
}

// 防篡改检测已移除：scheduleDeepScan 函数已删除
// 如需恢复，可从 git 历史找回 ArchiveProbe.deep 相关逻辑