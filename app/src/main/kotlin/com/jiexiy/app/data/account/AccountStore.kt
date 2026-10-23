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

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 账号登录态的本地存储。
 *
 * 后端把登录态放在 HttpOnly Cookie 里，客户端拿不到 token 本体，只能把整个 Cookie 值存下来，
 * 下次请求时原样塞回 `Cookie` 头（见 [AccountApi]）。
 *
 * [session] 是状态源：设置页那一行要随登录/登出即时变化，而账号页是同一 Activity 内的叠加页，
 * 关闭它不会触发 `ON_RESUME`，所以不能像「下载引擎」那样靠生命周期回调刷新。
 */
class AccountStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _session = MutableStateFlow(load())

    /** 当前登录态；null = 未登录 */
    val session: StateFlow<AccountApi.Session?> = _session.asStateFlow()

    fun save(session: AccountApi.Session) {
        prefs.edit()
            .putString(KEY_EMAIL, session.email)
            .putString(KEY_COOKIE, session.cookie)
            .apply()
        _session.value = session
    }

    fun clear() {
        prefs.edit()
            .remove(KEY_EMAIL)
            .remove(KEY_COOKIE)
            .apply()
        _session.value = null
    }

    private fun load(): AccountApi.Session? {
        val email = prefs.getString(KEY_EMAIL, null)?.takeIf { it.isNotBlank() } ?: return null
        return AccountApi.Session(email, prefs.getString(KEY_COOKIE, null))
    }

    companion object {
        private const val PREFS_NAME = "account_prefs"
        private const val KEY_EMAIL = "email"
        private const val KEY_COOKIE = "cookie"
    }
}
