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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jiexiy.app.data.db.FeijiAccountEntity
import com.jiexiy.app.data.download.DownloadManager
import com.jiexiy.app.data.download.DownloadPlatform
import com.jiexiy.app.data.network.FeijiApi
import com.jiexiy.app.data.network.model.DownloadLink
import com.jiexiy.app.data.network.model.ShareExpire
import com.jiexiy.app.data.network.model.ShareFile
import com.jiexiy.app.data.network.model.ShareInfo
import com.jiexiy.app.ui.items.MultiSelectAction
import com.jiexiy.app.ui.items.MultiSelectBar
import com.jiexiy.app.ui.components.ScrollToTopButton
import com.jiexiy.app.ui.components.jexiyLoading
import com.jiexiy.app.ui.resolve.BackToParentItem
import com.jiexiy.app.ui.resolve.CrumbBar
import com.jiexiy.app.ui.resolve.DownloadLinkDialog
import com.jiexiy.app.ui.resolve.ShareFileRow
import com.jiexiy.app.ui.theme.effectsDefault
import com.jiexiy.app.ui.theme.effectsFast
import com.jiexiy.app.ui.theme.ListGroupGap
import com.jiexiy.app.ui.theme.listGroupShape
import com.jiexiy.app.ui.theme.spatialDefault
import com.jiexiy.app.ui.theme.spatialFast
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.jiexiy.app.data.parse.FeijiAccountStore
import com.jiexiy.app.data.upload.CloudUploadManager
import com.jiexiy.app.ui.SnackbarController
import androidx.compose.ui.platform.LocalContext
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import kotlinx.coroutines.launch

/**
 * 小飞机云盘浏览页（与其他网盘页完全对齐）：
 * - 目录浏览 + 下拉刷新 + 面包屑回退
 * - 搜索过滤（本地过滤当前目录）
 * - 长按多选（批量下载/分享/移动/删除）
 * - 文件/文件夹操作菜单（下载/重命名/移动/分享/删除）
 * - 右上角「+」菜单（创建文件夹 / 上传文件占位）
 *
 * 认证走 appToken；数据走 [FeijiApi]（纯本地直连，不经服务器）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeijiCloudScreen(
    account: FeijiAccountEntity?,
    downloadManager: DownloadManager,
    scrollBehavior: TopAppBarScrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(),
    onExit: () -> Unit,
    onDownloadStarted: () -> Unit = {},
    onUploadStarted: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val token = account?.appToken.orEmpty()
    val uuid = account?.uuid.orEmpty()
    val userId = account?.userId.orEmpty()
    val path = remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var files by remember { mutableStateOf<List<FeijiApi.File>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var multiSelectMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<FeijiApi.File>>(emptySet()) }
    var isOperating by remember { mutableStateOf(false) }
    var showActionSheet by remember { mutableStateOf(false) }
    var actionFile by remember { mutableStateOf<FeijiApi.File?>(null) }
    var showBatchActions by remember { mutableStateOf(false) }
    var batchInitial by remember { mutableStateOf(BatchStep.MENU) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showCreateFolder by remember { mutableStateOf(false) }
    var showUploadDestination by remember { mutableStateOf(false) }
    var uploadTargetFolderId by remember { mutableStateOf("0") }
    var shareResult by remember { mutableStateOf<ShareInfo?>(null) }
    var downloadLink by remember { mutableStateOf<DownloadLink?>(null) }

    // 旧账号可能缺 uuid / userId：进页时补齐（会回写账号表）
    LaunchedEffect(account?.id, account?.uuid, account?.userId) {
        if (account != null && (account.uuid.isBlank() || account.userId.isBlank())) {
            runCatching { FeijiAccountStore.ensureAccount(context) }
        }
    }

    val listState = rememberLazyListState()
    val scrollPositions = remember { mutableStateMapOf<String, Int>() }
    val currentDirKey = remember(path.value) { path.value.joinToString("/") { it.first } }
    val displayFiles = remember(files, searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) files else files.filter { it.name.contains(q, ignoreCase = true) }
    }

    fun currentFolderId(): String = path.value.lastOrNull()?.first ?: "0"

    val uploadPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        var added = 0
        uris.forEach { uri ->
            val info = CloudUploadManager.describe(context, uri) ?: return@forEach
            CloudUploadManager.enqueue(
                context = context,
                platform = DownloadPlatform.FEIJI,
                uri = uri,
                fileName = info.name,
                size = info.size,
                mimeType = info.mimeType,
                folderId = uploadTargetFolderId
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
        if (token.isBlank()) {
            error = "请先登录小飞机网盘"
            loading = false
            return
        }
        runCatching { FeijiApi.list(token, uuid, folderId) }
            .onSuccess { files = it }
            .onFailure { error = it.message ?: "小飞机文件列表加载失败" }
        loading = false
    }

    // 进入页面立即加载根目录（account 就绪后触发一次）
    LaunchedEffect(account?.id, account?.uuid, account?.userId, token) {
        if (token.isBlank()) {
            loading = false
            error = "请先登录小飞机网盘"
        } else {
            load(currentFolderId())
        }
    }

    suspend fun enqueueFile(file: FeijiApi.File, fileName: String) {
        val spec = FeijiApi.download(token, uuid, userId, file.id)
        downloadManager.enqueue(
            url = spec.url,
            fileName = fileName,
            size = file.size,
            platform = DownloadPlatform.FEIJI,
            headers = spec.headers
        )
    }

    /** 显示下载直链弹窗（单文件下载用） */
    fun showDownloadDialog(file: FeijiApi.File) {
        scope.launch {
            isOperating = true
            runCatching {
                val spec = FeijiApi.download(token, uuid, userId, file.id)
                downloadLink = DownloadLink(
                    fid = file.id,
                    filename = file.name,
                    downloadUrl = spec.url,
                    size = file.size
                )
            }
            isOperating = false
        }
    }

    /** 收集文件夹下的全部文件（带相对路径），对齐其它网盘的文件夹下载 */
    suspend fun collectFolder(
        folderId: String,
        prefix: String,
        out: MutableList<Pair<FeijiApi.File, String>>,
        depth: Int = 0
    ) {
        if (depth > 32) return
        val children = FeijiApi.list(token, uuid, folderId)
        for (child in children) {
            if (child.isDir) {
                collectFolder(child.id, "$prefix${child.name}/", out, depth + 1)
            } else {
                out.add(child to "$prefix${child.name}")
            }
        }
    }

    /** 下载单个文件或整个文件夹 */
    suspend fun downloadTargets(targets: List<FeijiApi.File>, label: String): Int {
        val tasks = mutableListOf<Pair<FeijiApi.File, String>>()
        for (target in targets) {
            if (target.isDir) {
                collectFolder(target.id, "${target.name}/", tasks)
            } else {
                tasks.add(target to target.name)
            }
        }
        if (tasks.isEmpty()) {
            SnackbarController.show("所选文件夹为空")
            return 0
        }
        var ok = 0
        tasks.forEachIndexed { index, (file, name) ->
            if (tasks.size > 1) {
                SnackbarController.show("正在加入下载 ${index + 1}/${tasks.size}")
            }
            runCatching { enqueueFile(file, name) }.onSuccess { ok++ }
        }
        SnackbarController.show("已加入 $ok 个下载任务（$label）")
        return ok
    }

    fun goBack() {
        if (multiSelectMode) {
            multiSelectMode = false
            selected = emptySet()
        } else if (path.value.isNotEmpty()) {
            val p = path.value
            val parentFolderId = if (p.size <= 1) "0" else p[p.size - 2].first
            path.value = p.dropLast(1)
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
                    runCatching { files = FeijiApi.list(token, uuid, currentFolderId()) }
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
                                }) {
                                    Text(if (selected.size == displayFiles.size) "取消全选" else "全选")
                                }
                            } else {
                                IconButton(onClick = { goBack() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "小飞机云盘",
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
                                    onUploadFile = { showUploadDestination = true }
                                )
                            }
                        }
                        if (!multiSelectMode) {
                            CrumbBar(
                                rootTitle = "小飞机云盘",
                                pathNames = path.value.map { it.second },
                                onNavigate = { level ->
                                    val p = path.value
                                    val targetId = if (level == 0) "0" else p[level - 1].first
                                    scrollPositions[currentDirKey] = listState.firstVisibleItemIndex
                                    path.value = p.take(level)
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

                if (path.value.isNotEmpty()) {
                    item {
                        BackToParentItem(onClick = {
                            val p = path.value
                            val parentFolderId = if (p.size <= 1) "0" else p[p.size - 2].first
                            path.value = p.dropLast(1)
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

                itemsIndexed(displayFiles, key = { _, f -> f.id }) { index, file ->
                    ShareFileRow(
                        file = file.toShareFile(),
                        shape = listGroupShape(index, displayFiles.size),
                        onClick = {
                            if (multiSelectMode) {
                                selected = if (file in selected) selected - file else selected + file
                            } else if (file.isDir) {
                                path.value = path.value + (file.id to file.name)
                                scrollPositions[currentDirKey] = listState.firstVisibleItemIndex
                                scope.launch { load(file.id) }
                            } else {
                                actionFile = file
                                showActionSheet = true
                            }
                        },
                        onMore = if (!multiSelectMode && file.isDir) {
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
                        batchInitial = BatchStep.SHARE
                        showBatchActions = true
                    },
                    MultiSelectAction("移动", Icons.Outlined.DriveFileMove, MaterialTheme.colorScheme.primary) {
                        scope.launch {
                            isOperating = true
                            runCatching { FeijiApi.move(token, uuid, selected.toList(), "0") }
                            isOperating = false
                            multiSelectMode = false
                            selected = emptySet()
                            load(currentFolderId())
                        }
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
            file = actionFile!!.toShareFile(),
            operating = isOperating,
            onDownload = {
                val f = actionFile ?: return@FileActionSheet
                showDownloadDialog(f)
            },
            onDownloadFolder = {
                val f = actionFile ?: return@FileActionSheet
                scope.launch {
                    isOperating = true
                    runCatching { downloadTargets(listOf(f), f.name) }
                    isOperating = false
                    onDownloadStarted()
                }
            },
            onShare = { _, pwd, expiredType ->
                val f = actionFile ?: return@FileActionSheet
                scope.launch {
                    isOperating = true
                    runCatching {
                        val days = ShareExpire.daysOrNull(expiredType) ?: 0
                        shareResult = FeijiApi.share(
                            token, uuid, listOf(f), codes = pwd, validDays = days
                        ).toModel(expiredType)
                    }
                    isOperating = false
                }
            },
            onRename = { newName ->
                val f = actionFile ?: return@FileActionSheet
                scope.launch {
                    isOperating = true
                    runCatching { FeijiApi.rename(token, uuid, f, newName) }
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
                FeijiMoveStep(
                    subtitle = actionFile?.name ?: "",
                    uuid = uuid,
                    token = token,
                    onBack = { onBack() },
                    onDone = { targetFolderId ->
                        val f = actionFile
                        scope.launch {
                            isOperating = true
                            if (f != null) runCatching { FeijiApi.move(token, uuid, listOf(f), targetFolderId) }
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
            onShare = { _, pwd, expiredType ->
                scope.launch {
                    isOperating = true
                    runCatching {
                        val days = ShareExpire.daysOrNull(expiredType) ?: 0
                        shareResult = FeijiApi.share(
                            token, uuid, selected.toList(), codes = pwd, validDays = days
                        ).toModel(expiredType)
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
            initialStep = batchInitial,
            moveStep = { onBack, onDone ->
                FeijiMoveStep(
                    subtitle = "已选 ${selected.size} 项",
                    uuid = uuid,
                    token = token,
                    onBack = { onBack() },
                    onDone = { targetFolderId ->
                        scope.launch {
                            isOperating = true
                            runCatching { FeijiApi.move(token, uuid, selected.toList(), targetFolderId) }
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
        else -> actionFile?.let { "「${it.name}」" }
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
                    val targets = if (multiSelectMode) selected.toList()
                    else (actionFile?.let { listOf(it) } ?: emptyList())
                    runCatching { FeijiApi.delete(token, uuid, targets) }
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
                    runCatching { FeijiApi.createFolder(token, uuid, currentFolderId(), name) }
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

    // 上传目标选择器：先选目录再选文件
    if (showUploadDestination) {
        FeijiMoveStep(
            subtitle = "上传文件到",
            uuid = uuid,
            token = token,
            onBack = { showUploadDestination = false },
            onDone = { folderId ->
                uploadTargetFolderId = folderId
                showUploadDestination = false
                uploadPicker.launch(arrayOf("*/*"))
            }
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
                        platform = DownloadPlatform.FEIJI
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

// ---------- 移动目录选择 ----------

sealed interface FeijiMoveState {
    object Loading : FeijiMoveState
    data class Loaded(val files: List<FeijiApi.File>, val path: List<Pair<String, String>>) : FeijiMoveState
    data class Error(val message: String) : FeijiMoveState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeijiMoveStep(
    subtitle: String,
    uuid: String,
    token: String,
    onBack: () -> Unit,
    onDone: (targetFolderId: String) -> Unit
) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<FeijiMoveState>(FeijiMoveState.Loading) }
    var movePath by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    suspend fun loadMove(folderId: String) {
        state = FeijiMoveState.Loading
        runCatching { FeijiApi.list(token, uuid, folderId) }
            .onSuccess {
                state = FeijiMoveState.Loaded(files = it.filter { f -> f.isDir }, path = movePath)
            }
            .onFailure {
                state = FeijiMoveState.Error(it.message ?: "移动目录加载失败")
            }
    }

    LaunchedEffect(Unit) { loadMove("0") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 32.dp)
    ) {
        StepHeader(title = "移动到", subtitle = subtitle, onBack = onBack)
        Spacer(modifier = Modifier.height(8.dp))
        CrumbBar(
            rootTitle = "根目录",
            pathNames = (state as? FeijiMoveState.Loaded)?.path?.map { it.second } ?: emptyList(),
            onNavigate = { level ->
                val targetId = if (level == 0) "0" else movePath[level - 1].first
                movePath = movePath.take(level)
                scope.launch { loadMove(targetId) }
            }
        )
        Spacer(modifier = Modifier.height(8.dp))
        if ((state as? FeijiMoveState.Loaded)?.path?.isNotEmpty() == true) {
            BackToParentItem(onClick = {
                val p = movePath
                val targetId = if (p.size <= 1) "0" else p[p.size - 2].first
                movePath = p.dropLast(1)
                scope.launch { loadMove(targetId) }
            })
        }
        AnimatedContent(
            targetState = state,
            transitionSpec = { fadeIn(effectsDefault()) togetherWith fadeOut(effectsFast()) },
            label = "feijiMoveState"
        ) { s ->
            when (s) {
                is FeijiMoveState.Loading -> Box(
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                    contentAlignment = Alignment.Center
                ) { jexiyLoading() }

                is FeijiMoveState.Error -> Box(
                    modifier = Modifier.fillMaxWidth().height(140.dp),
                    contentAlignment = Alignment.Center
                ) { Text(s.message, color = MaterialTheme.colorScheme.onSurfaceVariant) }

                is FeijiMoveState.Loaded -> {
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
                            itemsIndexed(dirs, key = { _, d -> d.id }) { index, dir ->
                                ShareFileRow(
                                    file = dir.toShareFile(),
                                    onClick = {
                                        movePath = movePath + (dir.id to dir.name)
                                        scope.launch { loadMove(dir.id) }
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
        val dirName = (state as? FeijiMoveState.Loaded)?.path?.lastOrNull()?.second ?: "根目录"
        val targetFolderId = (state as? FeijiMoveState.Loaded)?.path?.lastOrNull()?.first ?: "0"
        Button(
            onClick = { onDone(targetFolderId) },
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) {
            Icon(Icons.Outlined.DriveFileMove, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("移动到此处（$dirName）")
        }
    }
}

// ---------- 辅助 ----------


/** 接口分享信息 → 通用分享弹窗模型 */
private fun FeijiApi.ShareInfo.toModel(expiredType: Int): ShareInfo = ShareInfo(
    shareUrl = shareUrl,
    passcode = passcode,
    pwdId = "",
    title = title,
    expiredType = expiredType
)

/** 将 [FeijiApi.File] 转换为 [ShareFile]，供 [ShareFileRow] 使用 */
private fun FeijiApi.File.toShareFile(): ShareFile = ShareFile(
    fid = id,
    fname = name,
    fsize = size,
    isdir = isDir,
    pdirFid = parentId,
    fidToken = "",
    modifyTime = modifyTime
)

@Composable
private fun FeijiFileRow(
    file: FeijiApi.File,
    downloading: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = if (file.isDir) Icons.Outlined.Folder else Icons.Outlined.InsertDriveFile,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2
            )
            if (!file.isDir) {
                Text(
                    text = formatFeijiSize(file.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (downloading) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
}

private fun formatFeijiSize(bytes: Long): String {
    if (bytes <= 0) return ""
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return String.format("%.2f %s", value, units[index])
}
