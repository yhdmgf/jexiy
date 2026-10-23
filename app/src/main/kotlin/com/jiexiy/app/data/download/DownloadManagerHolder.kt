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

/**
 * 进程内 DownloadManager 的持有者。
 *
 * 悬浮窗是 Service（可能跑在 Activity 之外），拿不到 MainScreen 里 remember 出来的实例；
 * 但两者在同一个进程，用这个持有者把实例暴露给 Service 即可（没有实例时按钮置灰）。
 */
object DownloadManagerHolder {

    @Volatile
    var manager: DownloadManager? = null
        private set

    fun attach(manager: DownloadManager) {
        this.manager = manager
    }
}
