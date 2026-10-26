package com.jiexiy.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jiexiy.app.data.network.model.ShareFile
import com.jiexiy.app.ui.viewmodel.AliyunCloudUiState
import com.jiexiy.app.ui.viewmodel.AliyunCloudViewModel

@Composable
fun AliyunCloudScreen(
    viewModel: AliyunCloudViewModel,
    scrollBehavior: TopAppBarScrollBehavior,
    onExit: () -> Unit,
    onDownloadStarted: () -> Unit,
    onUploadStarted: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val multiSelectMode by viewModel.multiSelectMode.collectAsState()
    val selected by remember { mutableStateOf(viewModel.selected) }
    val refreshing by viewModel.refreshing.collectAsState()
    val cloudMessage by viewModel.cloudMessage.collectAsState()
    val shareResult by viewModel.shareResult.collectAsState()
    val actionFile by viewModel.actionFile.collectAsState()

    var showDownloadDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showShareDialog by remember { mutableStateOf(false) }
    var showUploadDialog by remember { mutableStateOf(false) }
    var showFolderDialog by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(cloudMessage) {
        cloudMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.cloudMessage.value = null
        }
    }

    val clipboardManager = LocalClipboardManager.current
    if (shareResult != null) {
        AlertDialog(
            onDismissRequest = { viewModel.shareResult.value = null },
            title = { Text("分享创建成功") },
            text = { Text(shareResult ?: "") },
            confirmButton = {
                TextButton(onClick = {
                    clipboardManager.setText(AnnotatedString(shareResult ?: ""))
                    viewModel.shareResult.value = null
                }) { Text("复制链接") }
            },
            dismissButton = { TextButton(onClick = { viewModel.shareResult.value = null }) { Text("关闭") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("阿里云盘") },
                navigationIcon = {
                    IconButton(onClick = onExit) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (!multiSelectMode) {
                        IconButton(onClick = { showUploadDialog = true }) {
                            Icon(imageVector = Icons.Outlined.InsertDriveFile, contentDescription = "上传")
                        }
                        IconButton(onClick = { viewModel.enterMultiSelect() }) {
                            Icon(imageVector = Icons.Outlined.MoreVert, contentDescription = "更多")
                        }
                    } else {
                        TextButton(onClick = { viewModel.exitMultiSelect() }) { Text("取消") }
                        TextButton(onClick = { viewModel.selectAll() }) { Text("全选") }
                    }
                },
                scrollBehavior = scrollBehavior
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        when (val state = uiState) {
            is AliyunCloudUiState.Loading -> {
                Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            is AliyunCloudUiState.Error -> {
                Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text(state.message)
                }
            }
            is AliyunCloudUiState.Loaded -> {
                Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                    if (state.pathNames.isNotEmpty()) {
                        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text("我的云盘", style = MaterialTheme.typography.bodyMedium)
                            state.pathNames.forEach { name ->
                                Text(" / ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(name, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (!multiSelectMode) {
                            item {
                                Card(modifier = Modifier.fillMaxWidth().clickable { showFolderDialog = true }) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Outlined.Folder, contentDescription = null, modifier = Modifier.size(24.dp))
                                        Spacer(Modifier.width(16.dp))
                                        Text("新建文件夹", fontWeight = FontWeight.Medium)
                                    }
                                }
                            }
                        }

                        items(state.files, key = { it.fid }) { file ->
                            AliyunFileRow(
                                file = file,
                                selected = selected.contains(file),
                                multiSelectMode = multiSelectMode,
                                onClick = {
                                    if (multiSelectMode) viewModel.toggleSelect(file)
                                    else if (file.isdir) viewModel.openFolder(file)
                                    else showDownloadDialog = true
                                },
                                onLongClick = { viewModel.enterMultiSelect() }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showDownloadDialog && actionFile != null) {
        AlertDialog(
            onDismissRequest = { showDownloadDialog = false },
            title = { Text("下载") },
            text = { Text("确定下载「${actionFile?.fname}」吗？") },
            confirmButton = {
                TextButton(onClick = {
                    showDownloadDialog = false
                    actionFile?.let { viewModel.startDownload(it) }
                    onDownloadStarted()
                }) { Text("下载") }
            },
            dismissButton = { TextButton(onClick = { showDownloadDialog = false }) { Text("取消") } }
        )
    }

    if (showRenameDialog && actionFile != null) {
        var renameText by remember { mutableStateOf(actionFile?.fname ?: "") }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("重命名") },
            text = { OutlinedTextField(value = renameText, onValueChange = { renameText = it }, label = { Text("文件名") }) },
            confirmButton = {
                TextButton(onClick = {
                    showRenameDialog = false
                    actionFile?.let { viewModel.rename(it, renameText) }
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showRenameDialog = false }) { Text("取消") } }
        )
    }

    if (showDeleteDialog && actionFile != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除") },
            text = { Text("确定删除「${actionFile?.fname}」吗？") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    actionFile?.let { viewModel.delete(it) }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("取消") } }
        )
    }

    if (multiSelectMode && selected.isNotEmpty()) {
        BottomAppBar {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { viewModel.batchDelete(selected) }) { Text("删除 (${selected.size})") }
            TextButton(onClick = { viewModel.batchMove(selected) }) { Text("移动") }
            TextButton(onClick = { viewModel.createShare(selected, "阿里云盘分享", 7, null) }) { Text("分享") }
        }
    }
}

@Composable
private fun AliyunFileRow(
    file: ShareFile,
    selected: Boolean,
    multiSelectMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (multiSelectMode) {
                Checkbox(checked = selected, onCheckedChange = null)
                Spacer(Modifier.width(8.dp))
            }
            Icon(
                imageVector = if (file.isdir) Icons.Outlined.Folder else Icons.Outlined.InsertDriveFile,
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(file.fname, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!file.isdir && file.fsize > 0) {
                    Text(text = formatFileSize(file.fsize), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun formatFileSize(size: Long): String {
    return when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> "${size / 1024} KB"
        size < 1024 * 1024 * 1024 -> "${size / (1024 * 1024)} MB"
        else -> "${size / (1024 * 1024 * 1024)} GB"
    }
}
