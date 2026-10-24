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
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONObject

/**
 * 账号接口的 Cookie 存储：`PHPSESSID` + `jiexiy_fp` + `jiexiy_user`。
 *
 * 为什么单独一套而不是复用 [com.jiexiy.app.data.network.HttpClients]：其它平台 API 都自带 token、
 * 用不上会话；给全局 client 挂 CookieJar 会把无关 Cookie 也带出去。而账号接口**必须**有会话——
 * 后端把图形验证码、邮箱验证码、60 秒发码冷却都写在 PHP Session 里，PHPSESSID 丢了就等于每次
 * 请求都是新会话，验证码永远对不上。
 *
 * 另外两个也不能丢：
 * - `jiexiy_fp`：30 天设备指纹，注册限流（单指纹 1 小时 2 次）靠它；
 * - `jiexiy_user`：7 天登录凭证，应用重启后靠它 `check` 出登录态。
 *
 * 持久化到 SharedPreferences，应用重启后未过期的 PHPSESSID 仍在，会话就还连着。
 * 并发：请求线程会跨线程读写，用 [lock] 串行化。
 */
class AccountCookieJar(context: Context) : CookieJar {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val lock = Any()

    /** 内存里的唯一真源；过期项在写入与读取时各清一遍 */
    private var cookies: MutableList<Cookie> = load()

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        synchronized(lock) {
            val alive = cookies.filter { isAlive(it) }
            if (alive.size != cookies.size) {
                cookies = ArrayList(alive)
                persist()
            }
            return alive.filter { it.matches(url) }
        }
    }

    override fun saveFromResponse(url: HttpUrl, newCookies: List<Cookie>) {
        synchronized(lock) {
            if (newCookies.isEmpty()) return
            // HTTP 语义：同一 host 下同名 Cookie 只保留响应里那一个，先删旧的同名项再写入
            val incoming = newCookies.map { it.name }.toSet()
            cookies = ArrayList(
                (cookies.filterNot { it.name in incoming && it.matches(url) } + newCookies)
                    .filter { isAlive(it) }
            )
            persist()
        }
    }

    /** 登出时清掉全部 Cookie（含 PHPSESSID / jiexiy_fp / jiexiy_user） */
    fun clear() {
        synchronized(lock) {
            cookies = ArrayList()
            prefs.edit().remove(KEY_COOKIES).apply()
        }
    }

    /** `expiresAt` 为负是会话 Cookie（无过期时间），只要还在就有效 */
    private fun isAlive(cookie: Cookie): Boolean =
        cookie.expiresAt < 0L || cookie.expiresAt >= System.currentTimeMillis()

    private fun persist() {
        val arr = JSONArray()
        for (c in cookies) {
            arr.put(
                JSONObject()
                    .put("name", c.name)
                    .put("value", c.value)
                    .put("domain", c.domain)
                    .put("path", c.path)
                    .put("expiresAt", c.expiresAt)
                    .put("secure", c.secure)
                    .put("httpOnly", c.httpOnly)
                    .put("hostOnly", c.hostOnly)
            )
        }
        prefs.edit().putString(KEY_COOKIES, arr.toString()).apply()
    }

    private fun load(): MutableList<Cookie> {
        val text = prefs.getString(KEY_COOKIES, null) ?: return ArrayList()
        val out = ArrayList<Cookie>()
        try {
            val arr = JSONArray(text)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                // 单条损坏（手改 / 写一半崩了）只丢这一条，不能因为一条脏数据丢掉整个会话
                runCatching {
                    val builder = Cookie.Builder()
                        .name(o.optString("name"))
                        .value(o.optString("value"))
                        .path(o.optString("path", "/"))
                        .expiresAt(o.optLong("expiresAt", -1L))
                    // secure / httpOnly 是无参方法，只在为 true 时调用
                    if (o.optBoolean("secure")) builder.secure()
                    if (o.optBoolean("httpOnly")) builder.httpOnly()
                    // 域与 hostOnly 二选一：hostOnly 走精确匹配，否则走 dot-prefix 匹配
                    val domain = o.optString("domain")
                    if (o.optBoolean("hostOnly")) builder.hostOnlyDomain(domain) else builder.domain(domain)
                    builder.build()
                }.getOrNull()?.let { if (isAlive(it)) out.add(it) }
            }
        } catch (e: Exception) {
            // 整体解析失败：当作没有 Cookie，不要让一个偏好项让应用起不来
        }
        return out
    }

    companion object {
        private const val PREFS_NAME = "account_cookie_prefs"
        private const val KEY_COOKIES = "cookies"
    }
}
