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

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 应用对外的联系方式与仓库地址（引导页 / 设置页 / 关于页共用）。
 *
 * ★ 为什么要集中放：同一个 QQ 群号、同一个仓库地址会在多个界面出现，
 *   就地写死的话改一处漏一处（换群号时最容易漏），所以对外入口一律从这里取常量。
 * ★ 新增对外入口（比如 Telegram 群、邮件反馈）时，先往这里加常量，再到界面里引用。
 */
object AppLinks {
    /** GitHub 仓库完整地址（点击用系统浏览器打开） */
    const val GITHUB_REPO = "https://github.com/yhdmgf/jexiy"

    /** GitHub 仓库展示用短地址（去掉协议头，界面文字用；与 [GITHUB_REPO] 必须指向同一仓库） */
    const val GITHUB_REPO_DISPLAY = "github.com/yhdmgf/jexiy"

    /**
     * 尝试拉起 QQ 群资料页；返回 false 表示这台设备上没有应用能处理该 scheme（基本都是没装 QQ）。
     *
     * ★ 刻意不用 resolveActivity() 预检：Android 11+ 的软件包可见性会让它在未声明 <queries> 时
     *   直接返回 null，把「装了 QQ」误判成「没装」；而隐式 Intent 直接 startActivity 不受该限制。
     *   所以这里靠捕获 ActivityNotFoundException 判断，返回 false 时调用方退回复制群号。
     *   
     * ★ QQ 群入口已移除：不再提供 QQ 群加入功能。
     */
    fun openQQGroup(context: Context): Boolean {
        // QQ 群入口已移除
        return false
    }
}
