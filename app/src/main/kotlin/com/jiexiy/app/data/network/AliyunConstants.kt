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
 * 阿里云盘常量（对照 wenxi `aliyun.dart` / `aliyun_web_login.dart`）。
 *
 * 阿里云盘的 Web 设备协议分三层：
 *  1. OAuth 令牌（`auth.aliyundrive.com/v2/account/token`）——刷新 access_token；
 *  2. 用户信息（`user.aliyundrive.com/v2/user/get`）——拿 userId 与各 drive_id；
 *  3. 设备会话（`api.aliyundrive.com/users/v1/users/device/create_session`）——
 *     带 secp256k1 签名头，有效期 25 分钟。
 * 除了用户信息接口，其余列表/下载/上传都必须带 `X-Signature`。
 */
object AliyunConstants {

    // ---------- 域名 ----------

    const val API = "https://api.aliyundrive.com"
    const val AUTH = "https://auth.aliyundrive.com"
    const val USER = "https://user.aliyundrive.com"
    const val WEBAPI = "https://api.alipan.com"

    /** 网页版站点（登录页 + Referer）。 */
    const val WEB = "https://www.alipan.com"

    // ---------- 设备协议头 ----------

    /** 网页版 canary；列表/账号接口用这个。 */
    const val CANARY_WEB = "client=web,app=adrive,version=v6.8.12"

    /** 取下载直链用 Android 客户端的 canary（wenxi `downloadCanary`）。 */
    const val CANARY_DOWNLOAD = "client=Android,app=adrive,version=v4.1.0"

    /** 取下载直链的 Referer（OSS 签名 URL 会校验这个值，wenxi `downloadReferer`）。 */
    const val DOWNLOAD_REFERER = "https://www.aliyundrive.com/"

    /** 桌面版 UA。 */
    const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    // ---------- 接口路径（相对 [API]，除非特别注明） ----------

    const val PATH_TOKEN_REFRESH = "/v2/account/token"            // 相对 [AUTH]
    const val PATH_USER_INFO = "/v2/user/get"                    // 相对 [USER]，无需签名
    const val PATH_PERSONAL_INFO = "/v2/databox/get_personal_info"
    const val PATH_DEVICE_SESSION = "/users/v1/users/device/create_session"
    const val PATH_ALBUMS = "/adrive/v1/user/albums_info"

    const val PATH_SHARE_INFO = "/adrive/v3/share_link/get_share_by_anonymous"
    const val PATH_SHARE_TOKEN = "/v2/share_link/get_share_token"
    const val PATH_LIST_BY_SHARE = "/adrive/v2/file/list_by_share"

    const val PATH_LIST = "/adrive/v3/file/list"
    const val PATH_FILE_GET = "/v2/file/get"
    const val PATH_DOWNLOAD_URL = "/v2/file/get_download_url"
    const val PATH_MKDIR = "/adrive/v2/file/createWithFolders"
    const val PATH_RENAME = "/file/update"
    const val PATH_MOVE = "/file/move"
    const val PATH_COPY = "/file/copy"
    const val PATH_BATCH = "/adrive/v4/batch"
    const val PATH_VIDEO_PREVIEW = "/v2/file/get_video_preview_play_info" // 相对 [WEBAPI]

    // ---------- 业务约定 ----------

    /** 生成 32 位十六进制设备 id（阿里云盘 Web 端自生成，无需向服务端申请）。 */
    fun newDeviceId(): String {
        val bytes = java.security.SecureRandom().generateSeed(16)
        val sb = StringBuilder(32)
        for (b in bytes) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    /** 根目录 file_id。 */
    const val ROOT_FILE_ID = "root"

    /** 列表单页大小（wenxi 用 200）。 */
    const val LIST_LIMIT = 200

    /** 下载直链有效期（秒）。 */
    const val URL_EXPIRE_SEC = 14400

    /** 设备会话有效期（秒）；wenxi 留 1 分钟余量提前刷新。 */
    const val DEVICE_SESSION_TTL_SEC = 25 * 60L

    /** 设备名 / 机型（wenxi 用 `AsterLink` / `Windows网页版`）。 */
    const val DEVICE_NAME = "AsterLink"
    const val DEVICE_MODEL = "Windows网页版"
}
