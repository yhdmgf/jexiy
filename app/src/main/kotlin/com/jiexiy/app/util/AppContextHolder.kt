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

package com.jiexiy.app.util

import android.content.Context

/**
 * 应用级 [Context] 持有者。
 *
 * 一部分底层工具类（S3 上传、上传状态持久化等）不在 DI 链路里、构造函数拿不到 [Context]，
 * 而断点续传又必须知道往哪儿落盘。这里的装配只发生在 [com.jiexiy.app.JexiyApp.onCreate]，
 * 任何失败都不该影响应用启动——所以调用方都先用 [isReady] 判断、拿不到就降级为不续传。
 */
object AppContextHolder {

    private val value = java.util.concurrent.atomic.AtomicReference<Context>()

    val isReady: Boolean
        get() = value.get() != null

    /** 当前应用上下文；未装配返回 null（调用方需自行降级）。 */
    fun get(): Context? = value.get()

    fun install(context: Context) {
        value.set(context.applicationContext)
    }
}
