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

package com.jiexiy.app.data.account

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 网盘账号变更事件。
 *
 * 账号保存（扫码 / 密码 / 短信 / Cookie 粘贴，任何一种登录方式最终都会落到各平台的
 * `save*` 仓库方法）与删除账号都会发一条事件，由 MainScreen 统一转成一次云端同步：
 * - 保存 → 双向合并（本地独有的推上去，云端独有的拉下来）
 * - 删除 → 先删云端该平台那一条，再同步，避免下次同步把刚删的账号从云端加回来
 *
 * 用 SharedFlow 而不是直接回调：保存发生在仓库层（没有 Context，也不该知道 UI），
 * 由 MainScreen 在应用级统一收口，登录页 / 账号页都不需要各写一遍同步逻辑。
 */
object AccountSyncTrigger {

    sealed interface Event {
        /** 有账号被保存（新增或覆盖） */
        data object Saved : Event

        /** 有账号被删除；platform 取 [com.jiexiy.app.data.download.DownloadPlatform] 的常量 */
        data class Deleted(val platform: String) : Event
    }

    private val _events = MutableSharedFlow<Event>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val events: SharedFlow<Event> = _events.asSharedFlow()

    fun notifySaved() {
        _events.tryEmit(Event.Saved)
    }

    fun notifyDeleted(platform: String) {
        _events.tryEmit(Event.Deleted(platform))
    }
}
