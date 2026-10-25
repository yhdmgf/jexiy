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

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jiexiy.app.data.db.PikPakAccountEntity
import com.jiexiy.app.data.download.DownloadManager
import com.jiexiy.app.data.download.DownloadPlatform
import com.jiexiy.app.data.network.PikPakApi
import com.jiexiy.app.data.network.model.DownloadLink
import com.jiexiy.app.data.network.model.ShareFile
import com.jiexiy.app.data.repository.PikPakAccountRepository
import com.jiexiy.app.data.upload.CloudUploadManager
import com.jiexiy.app.ui.SnackbarController
import com.jiexiy.app.ui.components.ScrollToTopButton
import com.jiexiy.app.ui.components.jexiyLoading
import com.jiexiy.app.ui.items.CloudAddMenu
import com.jiexiy.app.ui.items.MultiSelectAction
import com.jiexiy.app.ui.items.MultiSelectBar
import com.jiexiy.app.ui.resolve.BackToParentItem
import com.jiexiy.app.ui.resolve.CrumbBar
import com.jiexiy.app.ui.resolve.DownloadLinkDialog
import com.jiexiy.app.ui.resolve.ShareFileRow
import com.jiexiy.app.ui.theme.ListGroupGap
import com.jiexiy.app.ui.theme.effectsDefault
import com.jiexiy.app.ui.theme.effectsFast
import com.jiexiy.app.ui.theme.listGroupShape
import com.jiexiy.app.ui.theme.spatialDefault
import com.jiexiy.app.ui.theme.spatialFast
import com.jiexiy.app.data.network.model.ShareInfo
import com.jiexiy.app.data.network.model.ShareExpire
import kotlinx.coroutines.launch

/**
 * PikPak 云盘浏览页（对齐小飞机云盘交互）：
 * - 目录浏览 + 下拉刷新 + 面包屑回退
 * - 搜索过滤（本地过滤当前目录）
 * - 长按多选（批量下载 / 分享 / 移动 / 删除）
 * - 文件操作菜单（下载 / 重命名 / 移动 / 分享 / 删除）
 * - 右上角「+」菜单（创建文件夹 / 上传文件）
 *
 * 认证走 [PikPakAccountRepository]（access_token 过期自动刷新）；数据走 [PikPakApi]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PikPakCloudScreen(
    account: PikPakAccountEntity?,
    repository: PikPakAccountRepository,
    api: PikPakApi,
    downloadManager: DownloadManager,
    scrollBehavior: TopAppBarScrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(),
    onExit: () -> Unit,
    onDownloadStarted: () -> Unit = {},
    onUploadStarted: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val deviceId = account?.deviceId.orEmpty()
    val path = remember {
        mutableStateOf(mutableListOf("ROOT" to "PikPak网盘"))
    }
    var files by remember { mutableStateOf<List<ShareFile>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    var multiSelectMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<ShareFile>>(emptySet()) }
    var isOperating by remember { mutableStateOf(false) }
    var showActionSheet by remember { mutableStateOf(false) }
    var actionFile by remember { mutableStateOf<ShareFile?>(null) }
    var showBatchActions by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showCreateFolder by remember { mutableStateOf(false) }
    var shareResult by remember { mutableStateOf<ShareInfo?>(null) }
    var downloadLink by remember { mutableStateOf<DownloadLink?>(null) }

    val listState = rememberLazyListState()
    val scrollPositions = remember { mutableStateMapOf<String, Int>() }
    val currentDirKey = remember(path) { path.joinToString("/") { it.first } }
    val displayFiles = remember(files, searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) files else files.filter { it.fname.contains(q, ignoreCase = true) }
    }

    fun currentFolderId(): String = path.lastOrNull()?.first.ifEmpty { "" }

    val uploadPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        var added = 0
        uris.forEach { uri ->
            val info = CloudUploadManager.describe(context, uri) ?: return@forEach
            CloudUploadManager.enqueue(
                context = context,
                platform = DownloadPlatform.PIKPAK,
                uri = uri,
                fileName = info.name,
                size = info.size,
                mimeType = info.mimeType,
                folderId = currentFolderId()
            )
            added++
        }
        if (added > 0) {
            SnackbarController.show("已加入 $added 个上传任务")
            onUploadStarted()
        }
    }

    suspend fun load(folderId: String) {
        loading = true
        error = null
        if (deviceId.isBlank()) {
            error = "请先登录 PikPak"
            loading = false
            return
        }
        val accessToken = repository.ensureAccessToken()
        if (accessToken == null) {
            error = "PikPak 登录已过期，请重新登录"
            loading = false
            return
        }
        runCatching { api.listFiles(accessToken, deviceId, folderId) }
            .onSuccess { files = it }
            .onFailure { error = it.message ?: "PikPak 文件列表加载失败" }
        loading = false
    }

    // 进入页面立即加载根目录（account 就绪后触发一次）
    LaunchedEffect(account?.id, deviceId) {
        if (deviceId.isBlank()) {
            loading = false
            error = "请先登录 PikPak"
        } else {
            load(currentFolderId())
        }
    }

    /** 显示下载直链弹窗（单文件下载用）。 */
    fun showDownloadDialog(file: ShareFile) {
        scope.launch {
            isOperating = true
            val accessToken = repository.ensureAccessToken()
            if (accessToken == null) {
                SnackbarController.show("PikPak 登录已过期，请重新登录")
                isOperating = false
                return@launch
            }
            runCatching {
                val link = api.getDownloadLink(accessToken, deviceId, file.fid, file.fname)
                downloadLink = link
            }.onFailure { SnackbarController.show(it.message ?: "获取下载链接失败") }
            isOperating = false
        }
    }

    /** 收集文件夹下的全部文件（带相对路径）。 */
    suspend fun collectFolder(
        accessToken: String,
        folderId: String,
        prefix: String,
        out: MutableList<Pair<ShareFile, String>>,
        depth: Int = 0
    ) {
        if (depth > 32) return
        val children = runCatching { api.listFiles(accessToken, deviceId, folderId) }.getOrElse { emptyList() }
        for (child in children) {
            if (child.isdir) {
                collectFolder(accessToken, child.fid, "$prefix${child.fname}/", out, depth + 1)
            } else {
                out.add(child to "$prefix${child.fname}")
            }
        }
    }

    /** 下载单个文件或整个文件夹。 */
    suspend fun downloadTargets(targets: List<ShareFile>, label: String): Int {
        val accessToken = repository.ensureAccessToken() ?: return 0
        val tasks = mutableListOf<Pair<ShareFile, String>>()
        for (target in targets) {
            if (target.isdir) collectFolder(accessToken, target.fid, "${target.fname}/", tasks)
            else tasks.add(target to target.fname)
        }
        if (tasks.isEmpty()) {
            SnackbarController.show("所选文件夹为空")
            return 0
        }
        var ok = 0
        tasks.forEachIndexed { index, (file, name) ->
            if (tasks.size > 1) SnackbarController.show("正在加入下载 ${index + 1}/${tasks.size}")
            runCatching {
                val link = api.getDownloadLink(accessToken, deviceId, file.fid, name)
                downloadManager.enqueue(
                    url = link.downloadUrl,
                    fileName = name,
                    size = link.size,
                    platform = DownloadPlatform.PIKPAK
                )
            }.onSuccess { ok++ }
        }
        SnackbarController.show("已加入 $ok 个下载任务（$label）")
        return ok
    }

    fun goBack() {
        if (multiSelectMode) {
            multiSelectMode = false
            selected = emptySet()
        } else if (path.size > 1) {
            val p = path
            val parentFolderId = if (p.size <= 2) "" else p[p.size - 2].first
            path = p.dropLast(1)
            scope.launch { load(parentFolderId) }
        } else {
            onExit()
        }
    }
    BackHandler { goBack() }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars),
        color = MaterialTheme.colorScheme.surface
    ) {
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                refreshing = true
                scope.launch {
                    val accessToken = repository.ensureAccessToken()
                    if (accessToken != null && deviceId.isNotBlank()) {
                        runCatching { files = api.listFiles(accessToken, deviceId, currentFolderId()) }
                    }
                    refreshing = false
                }
            },
            modifier = Modifier.fillMaxSize()
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp, top = 16.dp,
                    bottom = if (multiSelectMode) 96.dp else 16.dp
                ),
                verticalArrangement = Arrangement.spacedBy(ListGroupGap)
            ) {
                item {
                    Column(modifier = Modifier.padding(bottom = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (multiSelectMode) {
                                IconButton(onClick = {
                                    multiSelectMode = false
                                    selected = emptySet()
                                }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "取消选择")
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "已选 ${selected.size} 项",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = if (selected.size == displayFiles.size) "已全选" else "点击选择更多文件",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                TextButton(onClick = {
                                    selected = if (selected.size == displayFiles.size) emptySet() else files.toSet()
                                }) { Text(if (selected.size == displayFiles.size) "取消全选" else "全选") }
                            } else {
                                IconButton(onClick = { goBack() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "PikPak网盘",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = if (searchQuery.isBlank()) "共 ${files.size} 项"
                                        else "匹配 ${displayFiles.size} / ${files.size} 项",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(onClick = { showSearch = !showSearch }) {
                                    Icon(
                                        imageVector = Icons.Outlined.Search,
                                        contentDescription = if (showSearch) "关闭搜索" else "搜索文件",
                                        tint = if (showSearch) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                CloudAddMenu(
                                    onCreateFolder = { showCreateFolder = true },
                                    onUploadFile = { uploadPicker.launch(arrayOf("*/*")) }
                                )
                            }
                        }
                        if (!multiSelectMode) {
                            CrumbBar(
                                rootTitle = "PikPak网盘",
                                pathNames = path.drop(1).map { it.second },
                                onNavigate = { level ->
                                    val p = path
                                    val targetId = if (level == 0) "" else p[level].first
                                    scrollPositions[currentDirKey] = listState.firstVisibleItemIndex
                                    path = p.take(level + 1)
                                    scope.launch { load(targetId) }
                                }
                            )
                        }
                        AnimatedVisibility(
                            visible = showSearch && !multiSelectMode,
                            enter = expandVertically(spatialDefault()) + fadeIn(effectsDefault()),
                            exit = shrinkVertically(spatialFast()) + fadeOut(effectsFast())
                        ) {
                            Column {
                                Spacer(modifier = Modifier.height(10.dp))
                                OutlinedTextField(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    placeholder = { Text("搜索当前目录文件") },
                                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                                    trailingIcon = {
                                        if (searchQuery.isNotEmpty()) {
                                            IconButton(onClick = { searchQuery = "" }) {
                                                Icon(Icons.Filled.Close, contentDescription = "清空搜索")
                                            }
                                        }
                                    },
                                    singleLine = true,
                                    shape = MaterialTheme.shapes.large
                                )
                            }
                        }
                    }
                }

                if (path.size > 1) {
                    item {
                        BackToParentItem(onClick = {
                            val p = path
                            val parentFolderId = if (p.size <= 2) "" else p[p.size - 2].first
                            path = p.dropLast(1)
                            scrollPositions[currentDirKey] = listState.firstVisibleItemIndex
                            scope.launch { load(parentFolderId) }
                        })
                    }
                }

                if (loading) {
                    item {
                        Box(Modifier.fillMaxWidth().heightIn(min = 180.dp), contentAlignment = Alignment.Center) {
                            jexiyLoading()
                        }
                    }
                }

                error?.let { msg ->
                    item {
                        Box(Modifier.fillMaxWidth().heightIn(min = 140.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = msg,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 32.dp)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                OutlinedButton(onClick = {
                                    scope.launch { load(currentFolderId()) }
                                }) { Text("重试") }
                            }
                        }
                    }
                }

                if (!loading && error == null && displayFiles.isEmpty()) {
                    item {
                        Text(
                            text = if (files.isEmpty()) "此目录为空"
                            else "未找到匹配「${searchQuery.trim()}」的文件",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                }

                itemsIndexed(displayFiles, key = { _, f -> f.fid }) { index, file ->
                    ShareFileRow(
                        file = file,
                        shape = listGroupShape(index, displayFiles.size),
                        onClick = {
                            if (multiSelectMode) {
                                selected = if (file in selected) selected - file else selected + file
                            } else if (file.isdir) {
                                path = path + (file.fid to file.fname)
                                scrollPositions[currentDirKey] = listState.firstVisibleItemIndex
                                scope.launch { load(file.fid) }
                            } else {
                                actionFile = file
                                showActionSheet = true
                            }
                        },
                        onMore = if (!multiSelectMode && file.isdir) {
                            {
                                actionFile = file
                                showActionSheet = true
                            }
                        } else null,
                        onLongClick = if (!multiSelectMode) {
                            {
                                multiSelectMode = true
                                selected = setOf(file)
                            }
                        } else null,
                        selected = file in selected,
                        showCheckbox = multiSelectMode
                    )
                }
            }
        }

        ScrollToTopButton(
            listState = listState,
            modifier = Modifier
                .padding(end = 16.dp, bottom = if (multiSelectMode) 104.dp else 16.dp)
        )

        // 多选底部操作栏
        AnimatedVisibility(
            visible = multiSelectMode,
            enter = slideInVertically(spatialDefault()) { it } + fadeIn(effectsDefault()),
            exit = slideOutVertically(spatialFast()) { it } + fadeOut(effectsFast()),
            modifier = Modifier.padding(top = 16.dp, bottom = if (multiSelectMode) 104.dp else 16.dp)
        ) {
            MultiSelectBar(
                busy = isOperating,
                count = selected.size,
                actions = listOf(
                    MultiSelectAction("下载", Icons.Outlined.Download, MaterialTheme.colorScheme.primary) {
                        val targets = selected.toList()
                        scope.launch {
                            isOperating = true
                            runCatching { downloadTargets(targets, "已选 ${targets.size} 项") }
                            isOperating = false
                            multiSelectMode = false
                            selected = emptySet()
                            onDownloadStarted()
                        }
                    },
                    MultiSelectAction("分享", Icons.Outlined.Share, MaterialTheme.colorScheme.primary) {
                        showBatchActions = true
                    },
                    MultiSelectAction("移动", Icons.Outlined.DriveFileMove, MaterialTheme.colorScheme.primary) {
                        showBatchActions = true
                    },
                    MultiSelectAction("删除", Icons.Outlined.Delete, MaterialTheme.colorScheme.error) {
                        showDeleteConfirm = true
                    }
                )
            )
        }
    }

    // 单文件操作菜单
    if (showActionSheet && actionFile != null) {
        FileActionSheet(
            file = actionFile!!,
            operating = isOperating,
            onDownload = {
                val f = actionFile ?: return@FileActionSheet
                showDownloadDialog(f)
            },
            onDownloadFolder = {
                val f = actionFile ?: return@FileActionSheet
                scope.launch {
                    isOperating = true
                    runCatching { downloadTargets(listOf(f), f.fname) }
                    isOperating = false
                    onDownloadStarted()
                }
            },
            onShare = { withPassword, pwd, expiredType ->
                val f = actionFile ?: return@FileActionSheet
                scope.launch {
                    isOperating = true
                    val accessToken = repository.ensureAccessToken()
                    if (accessToken != null) {
                        runCatching {
                            val days = shareExpireToDays(expiredType)
                            val (shareUrl, passcode) = api.createShare(
                                accessToken, deviceId, listOf(f.fid), days,
                                passcode = if (withPassword) pwd else ""
                            )
                            shareResult = ShareInfo(
                                shareUrl = shareUrl,
                                passcode = passcode,
                                pwdId = "",
                                title = f.fname,
                                expiredType = expiredType
                            )
                        }
                    } else {
                        SnackbarController.show("PikPak 登录已过期，请重新登录")
                    }
                    isOperating = false
                }
            },
            onRename = { newName ->
                val f = actionFile ?: return@FileActionSheet
                scope.launch {
                    isOperating = true
                    val accessToken = repository.ensureAccessToken()
                    if (accessToken != null) {
                        runCatching { api.rename(accessToken, deviceId, f.fid, newName) }
                            .onFailure { SnackbarController.show(it.message ?: "重命名失败") }
                    } else {
                        SnackbarController.show("PikPak 登录已过期，请重新登录")
                    }
                    isOperating = false
                    load(currentFolderId())
                }
            },
            onConfirmDelete = {
                showActionSheet = false
                showDeleteConfirm = true
            },
            onDismiss = {
                showActionSheet = false
                actionFile = null
            },
            moveStep = { onBack, onDone ->
                PikPakMoveStep(
                    subtitle = actionFile?.fname ?: "",
                    deviceId = deviceId,
                    repository = repository,
                    onBack = { onBack() },
                    onDone = { targetFolderId ->
                        val f = actionFile
                        scope.launch {
                            isOperating = true
                            val accessToken = repository.ensureAccessToken()
                            if (f != null && accessToken != null) {
                                runCatching { api.batchMove(accessToken, deviceId, listOf(f.fid), targetFolderId) }
                                    .onFailure { SnackbarController.show(it.message ?: "移动失败") }
                            }
                            isOperating = false
                            onDone()
                            load(currentFolderId())
                        }
                    }
                )
            }
        )
    }

    // 批量操作弹窗
    if (showBatchActions) {
        BatchActionSheet(
            count = selected.size,
            operating = isOperating,
            onDownload = {
                val targets = selected.toList()
                scope.launch {
                    isOperating = true
                    runCatching { downloadTargets(targets, "已选 ${targets.size} 项") }
                    isOperating = false
                    multiSelectMode = false
                    selected = emptySet()
                    showBatchActions = false
                    onDownloadStarted()
                }
            },
            onShare = { withPassword, pwd, expiredType ->
                scope.launch {
                    isOperating = true
                    val accessToken = repository.ensureAccessToken()
                    if (accessToken != null) {
                        runCatching {
                            val days = shareExpireToDays(expiredType)
                            val (shareUrl, passcode) = api.createShare(
                                accessToken, deviceId, selected.map { it.fid }, days,
                                passcode = if (withPassword) pwd else ""
                            )
                            shareResult = ShareInfo(
                                shareUrl = shareUrl,
                                passcode = passcode,
                                pwdId = "",
                                title = "已选 ${selected.size} 项",
                                expiredType = expiredType
                            )
                        }
                    } else {
                        SnackbarController.show("PikPak 登录已过期，请重新登录")
                    }
                    isOperating = false
                    multiSelectMode = false
                    selected = emptySet()
                    showBatchActions = false
                }
            },
            onDelete = {
                showBatchActions = false
                showDeleteConfirm = true
            },
            onDismiss = { showBatchActions = false },
            moveStep = { onBack, onDone ->
                PikPakMoveStep(
                    subtitle = "已选 ${selected.size} 项",
                    deviceId = deviceId,
                    repository = repository,
                    onBack = { onBack() },
                    onDone = { targetFolderId ->
                        scope.launch {
                            isOperating = true
                            val accessToken = repository.ensureAccessToken()
                            if (accessToken != null) {
                                runCatching { api.batchMove(accessToken, deviceId, selected.map { it.fid }, targetFolderId) }
                                    .onFailure { SnackbarController.show(it.message ?: "移动失败") }
                            }
                            isOperating = false
                            multiSelectMode = false
                            selected = emptySet()
                            onDone()
                            load(currentFolderId())
                        }
                    }
                )
            }
        )
    }

    // 删除确认（单文件 or 批量）
    val deleteTarget = when {
        !showDeleteConfirm -> null
        multiSelectMode -> "选中的 ${selected.size} 项"
        else -> actionFile?.let { "「${it.fname}」" }
    }
    if (deleteTarget != null) {
        ConfirmDeleteSheet(
            target = deleteTarget,
            operating = isOperating,
            onDismiss = {
                showDeleteConfirm = false
                if (!multiSelectMode) { actionFile = null; showActionSheet = false }
            },
            onConfirm = {
                scope.launch {
                    isOperating = true
                    val ids = if (multiSelectMode) selected.map { it.fid }
                    else (actionFile?.let { listOf(it.fid) } ?: emptyList())
                    val accessToken = repository.ensureAccessToken()
                    if (accessToken != null && ids.isNotEmpty()) {
                        runCatching { api.batchTrash(accessToken, deviceId, ids) }
                            .onFailure { SnackbarController.show(it.message ?: "删除失败") }
                    }
                    isOperating = false
                    showDeleteConfirm = false
                    actionFile = null
                    multiSelectMode = false
                    selected = emptySet()
                    load(currentFolderId())
                }
            }
        )
    }

    // 创建文件夹
    if (showCreateFolder) {
        CreateFolderDialog(
            onDismiss = { showCreateFolder = false },
            onConfirm = { name ->
                showCreateFolder = false
                scope.launch {
                    isOperating = true
                    val accessToken = repository.ensureAccessToken()
                    if (accessToken != null) {
                        runCatching { api.createFolder(accessToken, deviceId, currentFolderId(), name) }
                            .onFailure { SnackbarController.show(it.message ?: "创建文件夹失败") }
                    } else {
                        SnackbarController.show("PikPak 登录已过期，请重新登录")
                    }
                    isOperating = false
                    load(currentFolderId())
                }
            }
        )
    }

    // 分享结果
    shareResult?.let { info ->
        ShareResultDialog(
            info = info,
            onDismiss = { shareResult = null }
        )
    }

    // 下载直链弹窗
    downloadLink?.let { link ->
        DownloadLinkDialog(
            link = link,
            onDownload = {
                scope.launch {
                    downloadManager.enqueue(
                        url = link.downloadUrl,
                        fileName = link.filename,
                        size = link.size,
                        platform = DownloadPlatform.PIKPAK
                    )
                }
                downloadLink = null
                SnackbarController.show("已加入下载")
                onDownloadStarted()
            },
            onDismiss = { downloadLink = null }
        )
    }
}

// ---------- 工具 ----------

/**
 * 将通用分享有效期档位（[ShareExpire]）折算为 PikPak 需要的天数。
 * 「永久有效」PikPak 不支持，回退为 30 天（UI 默认档）。
 */
private fun shareExpireToDays(expiredType: Int): Int = when (expiredType) {
    ShareExpire.FOREVER -> 30
    ShareExpire.ONE_DAY -> 1
    ShareExpire.SEVEN_DAYS -> 7
    ShareExpire.THIRTY_DAYS -> 30
    else -> 7
}

// ---------- 移动目录选择 ----------

sealed interface PikPakMoveState {
    object Loading : PikPakMoveState
    data class Loaded(val files: List<ShareFile>, val path: List<Pair<String, String>>) : PikPakMoveState
    data class Error(val message: String) : PikPakMoveState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PikPakMoveStep(
    subtitle: String,
    deviceId: String,
    repository: PikPakAccountRepository,
    onBack: () -> Unit,
    onDone: (targetFolderId: String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val api = PikPakApi()
    var state by remember { mutableStateOf<PikPakMoveState>(PikPakMoveState.Loading) }
    var movePath by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    suspend fun loadMove(folderId: String) {
        state = PikPakMoveState.Loading
        val accessToken = repository.ensureAccessToken()
        if (accessToken == null) {
            state = PikPakMoveState.Error("PikPak 登录已过期，请重新登录")
            return
        }
        runCatching { api.listFiles(accessToken, deviceId, folderId) }
            .onSuccess { state = PikPakMoveState.Loaded(files = it.filter { f -> f.isdir }, path = movePath) }
            .onFailure { state = PikPakMoveState.Error(it.message ?: "移动目录加载失败") }
    }

    LaunchedEffect(Unit) { loadMove("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 32.dp)
    ) {
        StepHeader(title = "移动到", subtitle = subtitle, onBack = onBack)
        Spacer(modifier = Modifier.height(8.dp))
        CrumbBar(
            rootTitle = "根目录",
            pathNames = (state as? PikPakMoveState.Loaded)?.path?.map { it.second } ?: emptyList(),
            onNavigate = { level ->
                val targetId = if (level == 0) "" else movePath[level - 1].first
                movePath = movePath.take(level)
                scope.launch { loadMove(targetId) }
            }
        )
        Spacer(modifier = Modifier.height(8.dp))
        if ((state as? PikPakMoveState.Loaded)?.path?.isNotEmpty() == true) {
            BackToParentItem(onClick = {
                val p = movePath
                val targetId = if (p.size <= 1) "" else p[p.size - 2].first
                movePath = p.dropLast(1)
                scope.launch { loadMove(targetId) }
            })
        }
        AnimatedContent(
            targetState = state,
            transitionSpec = { fadeIn(effectsDefault()) togetherWith fadeOut(effectsFast()) },
            label = "pikpakMoveState"
        ) { s ->
            when (s) {
                is PikPakMoveState.Loading -> Box(
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                    contentAlignment = Alignment.Center
                ) { jexiyLoading() }

                is PikPakMoveState.Error -> Box(
                    modifier = Modifier.fillMaxWidth().height(140.dp),
                    contentAlignment = Alignment.Center
                ) { Text(s.message, color = MaterialTheme.colorScheme.onSurfaceVariant) }

                is PikPakMoveState.Loaded -> {
                    val dirs = s.files
                    if (dirs.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(90.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "当前目录没有子文件夹，可直接移动到此处",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
                            verticalArrangement = Arrangement.spacedBy(ListGroupGap)
                        ) {
                            itemsIndexed(dirs, key = { _, d -> d.fid }) { index, dir ->
                                ShareFileRow(
                                    file = dir,
                                    onClick = {
                                        movePath = movePath + (dir.fid to dir.fname)
                                        scope.launch { loadMove(dir.fid) }
                                    },
                                    shape = listGroupShape(index, dirs.size)
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        val dirName = (state as? PikPakMoveState.Loaded)?.path?.lastOrNull()?.second ?: "根目录"
        val targetFolderId = (state as? PikPakMoveState.Loaded)?.path?.lastOrNull()?.first ?: ""
        TextButton(
            onClick = { onDone(targetFolderId) },
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) {
            Icon(Icons.Outlined.DriveFileMove, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("移动到此处（$dirName）")
        }
    }
}
