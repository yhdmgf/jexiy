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

package com.jiexiy.app.util

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * 设置页「权限状态」里列出的权限。
 *
 * 分成两类：
 * - **运行时权限**（存储 10- / 通知 13+）：由调用方用 ActivityResultLauncher 申请；
 * - **系统设置项**（所有文件访问 / 电池优化 / 安装应用 / 悬浮窗）：只能跳系统页面，
 *   回来后再查一次状态。
 *
 * 网络与前台服务是安装时自动授予的普通权限，列出来只是为了把「还缺几项」的口径说全，
 * 点它们不会有任何系统弹窗。
 */
enum class AppPermission(val title: String, val detail: String) {

    STORAGE("存储权限", "下载文件写入本机存储"),
    NOTIFICATION("通知权限", "显示下载进度与完成提醒"),
    NETWORK("网络权限", "解析链接与下载都需要联网"),
    FOREGROUND_SERVICE("前台服务", "退到后台后继续下载"),
    BATTERY_OPTIMIZATION("忽略电池优化", "避免后台下载被系统休眠"),
    INSTALL_APP("安装应用", "下载 APK 后直接安装更新"),
    OVERLAY("悬浮窗", "在其他应用上显示下载进度");

    fun isGranted(context: Context): Boolean = when (this) {
        STORAGE -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.os.Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }

        NOTIFICATION -> NotificationManagerCompat.from(context).areNotificationsEnabled()

        // 安装时授予的普通权限：只要应用装着就是已授权
        NETWORK, FOREGROUND_SERVICE -> true

        BATTERY_OPTIMIZATION -> {
            val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            power.isIgnoringBatteryOptimizations(context.packageName)
        }

        INSTALL_APP -> Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

        OVERLAY -> Settings.canDrawOverlays(context)
    }

    /** 需要走 ActivityResultLauncher 的运行时权限；null 表示只能跳系统设置或无需申请 */
    val runtimePermission: String?
        get() = when (this) {
            STORAGE -> if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            } else {
                null
            }
            NOTIFICATION -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.POST_NOTIFICATIONS
            } else {
                null
            }
            else -> null
        }

    /**
     * 发起申请：运行时权限交给 [launchRuntime]；其余打开对应系统页面。
     * 页面打不开（厂商裁剪）时静默忽略，返回后状态不变。
     */
    fun request(context: Context, launchRuntime: (String) -> Unit) {
        val runtime = runtimePermission
        if (runtime != null) {
            launchRuntime(runtime)
            return
        }
        val packageUri = Uri.parse("package:${context.packageName}")
        val intent = when (this) {
            STORAGE -> Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri)
            NOTIFICATION -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            BATTERY_OPTIMIZATION ->
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri)
            INSTALL_APP -> Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, packageUri)
            OVERLAY -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri)
            // 普通权限无需申请
            NETWORK, FOREGROUND_SERVICE -> return
        }
        runCatching { context.startActivity(intent) }
    }

    companion object {
        /** 当前未授权的项（顺序即设置页里的展示顺序） */
        fun missing(context: Context): List<AppPermission> = entries.filter { !it.isGranted(context) }
    }
}
