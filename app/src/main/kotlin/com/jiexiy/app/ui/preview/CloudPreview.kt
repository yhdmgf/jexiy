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

package com.jiexiy.app.ui.preview


/**
 * 网盘页「在线预览」的统一出口。
 *
 * 各网盘云盘页各自负责「取直链 + 组装该平台要求的请求头」（每个平台的 Cookie / UA / Referer 都不一样），
 * 拿到直链后统一交给这里转发给全局预览页 [PreviewLauncher]，避免每个 ViewModel 各写一遍
 * 「文本走文本页、媒体走媒体页」的分支。
 *
 * 不可预览的类型（压缩包/安装包/未知扩展名）直接不转发——与解析页保持一致的口径：
 * 与其给了一个点了必然失败的菜单项，不如根本不显示。
 */
object CloudPreview {

    /**
     * @param fileName 文件名（决定按图片/视频/音频/文本哪一种打开）
     * @param url      直链地址
     * @param headers  直链请求头（**必须与下载用同一套**，少了会被 CDN 拒绝）
     * @param isHls    m3u8 直链传 true
     * @param audioUrl 合并音轨地址（音视频分离的流式内容，如哔哩哔哩 DASH）
     * @return 是否真的打开了预览；false 表示该文件类型不支持预览（调用方可据此给用户提示）
     */
    fun open(
        fileName: String,
        url: String,
        headers: Map<String, String>,
        isHls: Boolean = false,
        audioUrl: String = ""
    ): Boolean {
        if (url.isBlank()) return false
        val kind = PreviewTypes.kindOf(fileName) ?: return false
        if (kind == PreviewKind.TEXT) {
            PreviewLauncher.openText(url = url, fileName = fileName, headers = headers, title = fileName)
        } else {
            PreviewLauncher.openMedia(
                items = listOf(
                    PreviewMedia(
                        url = url,
                        kind = kind,
                        title = fileName,
                        headers = headers,
                        isHls = isHls,
                        audioUrl = audioUrl
                    )
                ),
                title = fileName
            )
        }
        return true
    }
}
