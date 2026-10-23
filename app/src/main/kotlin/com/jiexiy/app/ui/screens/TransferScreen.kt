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

package com.jiexiy.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.jiexiy.app.ui.viewmodel.DownloadViewModel
import kotlinx.coroutines.launch

/**
 * 传输页：顶部「下载 / 上传」两个页签，左右滑动即可切换。
 * 下载沿用原 [DownloadScreen]，上传走小飞机上传队列（[UploadScreen]）。
 */
@Composable
fun TransferScreen(
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: DownloadViewModel,
    downloadDebug: Boolean = false,
    incomingAddUrl: String? = null,
    onIncomingAddUrlConsumed: () -> Unit = {},
    uploadFolderId: String = "0",
    modifier: Modifier = Modifier
) {
    val pagerState = rememberPagerState(pageCount = { 2 })
    val scope = rememberCoroutineScope()
    val tabs = listOf("下载", "上传")

    Column(modifier = modifier.fillMaxSize()) {
        TabRow(
            selectedTabIndex = pagerState.currentPage,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                    text = { Text(title) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f)
        ) { page ->
            when (page) {
                0 -> DownloadScreen(
                    scrollBehavior = scrollBehavior,
                    viewModel = viewModel,
                    downloadDebug = downloadDebug,
                    incomingAddUrl = incomingAddUrl,
                    onIncomingAddUrlConsumed = onIncomingAddUrlConsumed
                )
                else -> UploadScreen(folderId = uploadFolderId)
            }
        }
    }
}
