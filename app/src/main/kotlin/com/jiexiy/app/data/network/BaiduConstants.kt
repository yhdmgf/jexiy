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
 * 百度网盘登录与 API 相关常量（依据抓包 + 文档）。
 */
object BaiduConstants {

    /** WebView 登录页（提取 BDUSS/STOKEN） */
    const val LOGIN_URL = "https://pan.baidu.com/"

    /** 提取 Cookie 的域名 */
    const val COOKIE_DOMAIN = "https://pan.baidu.com"

    /** PC 网页 UA（share/verify、xpan/share、gettemplatevariable 使用） */
    const val UA_WEB =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /** 百度客户端 UA（yun/api/list、filemanager、locatedownload 使用） */
    const val UA_NETDISK =
        "netdisk;12.24.6;piano;android-android;16;JSbridge4.4.0;jointBridge;1.1.0"

    /** 百度网盘统一 app_id */
    const val APP_ID = "250528"

    // ---------- 上传（对齐 wenxi 的 baidu.dart） ----------

    /** 上传预创建：提交 block_list，重复文件直接返回 fs_id（return_type=2） */
    const val PRECREATE_URL = "https://pan.baidu.com/api/precreate"

    /** 上传提交：分段全部上传成功后把文件正式入库 */
    const val CREATE_URL = "https://pan.baidu.com/api/create"

    /** 上传节点发现（返回 servers / bak_servers / host） */
    const val LOCATE_UPLOAD_URL = "https://d.pcs.baidu.com/rest/2.0/pcs/file"

    /** 单分段上传路径（拼在节点主机后） */
    const val SUPERFILE_PATH = "/rest/2.0/pcs/superfile2"

    /** 上传分段大小：服务端硬校验 4 MB，块 MD5 必须按这个长度切 */
    const val UPLOAD_CHUNK_SIZE = 4L * 1024 * 1024

    /** 文件前 256 KB 的 MD5（slice-md5），服务端用它判断整文件是否已存在 */
    const val UPLOAD_PREFIX_SIZE = 256L * 1024

    /** 临时转存目录名（对齐抓包） */
    const val TEMP_DIR_NAME = "解析云临时转存"

    /** 关键 Cookie 字段，缺失 BDUSS 则视为未登录 */
    fun isValidCookie(cookie: String?): Boolean =
        cookie != null && cookie.contains("BDUSS=")
}