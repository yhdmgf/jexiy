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

package com.jiexiy.app.data.network

/**
 * PikPak（mypikpak.com）安卓客户端常量。
 *
 * 逐字对齐 Android App 客户端（package=com.pikcloud.pikpak / version=1.21.0）抓包结果：
 * 服务端只认这份 UA 与 Client-Id/Secret 组合，改成别的会被判定为非法客户端。
 */
object PikPakConstants {

    /** 云端业务 API（文件列表 / 详情 / 分享 / 上传）。 */
    const val API_BASE = "https://api-drive.mypikpak.com"

    /** 认证 API（登录 / 刷新 / 用户信息）。 */
    const val USER_BASE = "https://user.mypikpak.com"

    /** 安卓 App 客户端标识（`X-Client-Id` 与登录 body 都用它）。 */
    const val CLIENT_ID = "YNxT9w7GMdWvEOKa"

    /** 安卓 App 客户端密钥（登录 / 刷新用，别放进 UI 展示）。 */
    const val CLIENT_SECRET = "dbw2OtmVEeuUvIptb1Coyg"

    /** 安卓 App 客户端 User-Agent（服务端强校验）。 */
    const val USER_AGENT = "ANDROID-com.pikcloud.pikpak/1.21.0"

    /** 安卓 App 客户端应用包名（部分签名字段拼接用它）。 */
    const val APP_PACKAGE = "com.pikcloud.pikpak"

    /** 安卓 App 客户端版本号（部分签名字段拼接用它）。 */
    const val APP_VERSION = "1.21.0"

    // ---------- 端点 ----------
    /** 匿名 / 密码登录前获取 captcha_token。 */
    const val CAPTCHA_INIT = "$USER_BASE/v1/shield/captcha/init"
    /** 账号密码登录（OAuth password grant）。 */
    const val SIGNIN = "$USER_BASE/v1/auth/signin"
    /** 用 refresh_token 换新 access_token（OAuth refresh_token grant）。 */
    const val REFRESH_TOKEN = "$USER_BASE/v1/auth/token"
    /** 账号信息（sub / name）。 */
    const val USER_ME = "$USER_BASE/v1/user/me"
    /** 云端配额（quota.usage / quota.limit）。 */
    const val ABOUT = "$API_BASE/drive/v1/about"

    // 文件操作
    const val FILES = "$API_BASE/drive/v1/files"
    const val FILES_BATCH_TRASH = "$API_BASE/drive/v1/files:batchTrash"
    const val FILES_BATCH_MOVE = "$API_BASE/drive/v1/files:batchMove"

    // 分享
    const val SHARE = "$API_BASE/drive/v1/share"
    const val SHARE_DETAIL = "$API_BASE/drive/v1/share/detail"
    const val SHARE_RESTORE = "$API_BASE/drive/v1/share/restore"

    // 任务
    const val TASKS = "$API_BASE/drive/v1/tasks"

    // ---------- 上传 ----------
    /** 单片大小档位（字节），按本地文件大小选。 */
    const val PART_SIZE_256K = 0x40000L
    const val PART_SIZE_512K = 0x80000L
    const val PART_SIZE_1M = 0x100000L
    const val PART_SIZE_2M = 0x200000L

    /** 上传完成后轮询次数上限（1 秒间隔）。 */
    const val UPLOAD_POLL_MAX = 15

    /**
     * 安卓客户端 captcha_sign 的 10 层 MD5 盐（第一个为空串，逐字照抄 wenxi）。
     * 官方 API 本身不加签；该算法只是 App 内的设备级指纹，本实现照抄但**不发送**到服务端。
     */
    val CAPTCHA_SALT_S: List<String> = listOf(
        "",
        "E32cSkYXC2bciKJGxRsE8ZgwmH/YwkvpD6/O9guSOa2irCwciH4xPHaH",
        "QtqgfMgHP2TFl",
        "zOKgHT56L7nIzFzDpUGhpWFrgP53m3G6ML",
        "S",
        "THxpsktzfFXizUv7DK1y/N7NZ1WhayViluBEvAJJ8bA1Wr6",
        "y9PXH3xGUhG/zQI8CaapRw2LhldCaFM9CRlKpZXJvj+pifu",
        "+RaaG7T8FRTI4cP019N5y9ofLyHE9ySFUr",
        "6Pf1l8UTeuzYldGtb/d"
    )
}
