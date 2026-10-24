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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jiexiy.app.data.network.FeijApi
import com.jiexiy.app.data.network.model.ShareFile
import com.jiexiy.app.data.parse.FeijAccountStore
import com.jiexiy.app.ui.SnackbarController
import com.jiexiy.app.ui.components.jexiyLoading
import com.jiexiy.app.ui.rememberGlobalSnackbarHostState
import com.jiexiy.app.ui.resolve.BackToParentItem
import com.jiexiy.app.ui.resolve.CrumbBar
import com.jiexiy.app.ui.resolve.ShareFileRow
import com.jiexiy.app.ui.theme.effectsDefault
import com.jiexiy.app.ui.theme.ListGroupGap
import com.jiexiy.app.ui.theme.effectsFast
import com.jiexiy.app.ui.theme.listGroupShape
import com.jiexiy.app.ui.viewmodel.ResolveViewModel

/** 小飞机个人网盘根目录的 folderId（网盘页与转存目录选择共用同一定义） */
private const val FEIJ_ROOT_FOLDER_ID = "0"

/**
 * 「转存到小飞机网盘」步骤内容：浏览小飞机个人网盘目录（只进文件夹），确认后转存到当前目录。
 *
 * 与网盘页同一条数据链路（[FeijApi.list]，本地直连不经服务器）；转存走 /app/file/transfer。
 * 之所以不复用网盘页的浏览状态，是因为转存时用户可能同时开着网盘页浏览别处，
 * 两条链路共用状态会互相踩目录栈。
 */
@Composable
internal fun FeijSaveContent(
    resolveViewModel: ResolveViewModel,
    onBack: () -> Unit
) {
    val account = FeijAccountStore.account.collectAsState().value
    val token = account?.appToken.orEmpty()
    val uuid = account?.uuid.orEmpty()

    // 目录栈：每项 (folderId, 名称)；空栈表示根目录
    var path by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var dirs by remember { mutableStateOf<List<ShareFile>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    // 加载失败后的重试触发器（同值写回状态不会触发重组，所以用递增计数器）
    var retryTick by remember { mutableStateOf(0) }

    val saving = resolveViewModel.isSaving
    val message = resolveViewModel.saveMessage
    val currentFolderId = path.lastOrNull()?.first ?: FEIJ_ROOT_FOLDER_ID

    // 目录栈、登录态或重试信号变化时重新拉取当前目录的文件夹列表
    LaunchedEffect(token, uuid, path, retryTick) {
        if (token.isBlank() || uuid.isBlank()) {
            loading = false
            loadError = "请先登录小飞机网盘"
            dirs = emptyList()
            return@LaunchedEffect
        }
        loading = true
        loadError = null
        runCatching { FeijApi.list(token, uuid, currentFolderId) }
            .onSuccess { files ->
                dirs = files.filter { it.isDir }.map { f ->
                    ShareFile(
                        fid = f.id,
                        fname = f.name,
                        fsize = f.size,
                        isdir = true,
                        pdirFid = currentFolderId,
                        fidToken = "",
                        modifyTime = f.modifyTime
                    )
                }
            }
            .onFailure { loadError = it.message ?: "小飞机文件列表加载失败" }
        loading = false
    }

    LaunchedEffect(message) {
        if (message != null) {
            SnackbarController.show(message)
            resolveViewModel.consumeSaveMessage()
        }
    }

    val snackbarHostState = rememberGlobalSnackbarHostState()

    SaveStepScaffold(
        title = "转存到小飞机网盘",
        subtitle = resolveViewModel.saveTarget?.fname ?: "",
        onBack = onBack
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 32.dp)
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            CrumbBar(
                rootTitle = "根目录",
                pathNames = path.map { it.second },
                onNavigate = { level -> path = path.take(level + 1) }
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (path.isNotEmpty()) {
                BackToParentItem(onClick = { path = path.dropLast(1) })
                Spacer(modifier = Modifier.height(4.dp))
            }

            when {
                loading -> Box(
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                    contentAlignment = Alignment.Center
                ) { jexiyLoading() }

                loadError != null -> Box(
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = loadError ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = { retryTick++ }) { Text("重试") }
                    }
                }

                dirs.isEmpty() -> Box(
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "当前目录没有子文件夹，可直接转存到此目录",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp),
                    verticalArrangement = Arrangement.spacedBy(ListGroupGap)
                ) {
                    itemsIndexed(dirs, key = { _, d -> d.fid }) { index, dir ->
                        ShareFileRow(
                            file = dir,
                            shape = listGroupShape(index, dirs.size),
                            onClick = { path = path + (dir.fid to dir.fname) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            val currentDirName = path.lastOrNull()?.second ?: "根目录"
            Button(
                onClick = { resolveViewModel.saveToCloud(currentFolderId) },
                enabled = !saving,
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                if (saving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(Icons.Outlined.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("转存到此目录（$currentDirName）")
                }
            }

            SnackbarHost(hostState = snackbarHostState)
        }
    }
}
