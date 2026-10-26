package com.jiexiy.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.jiexiy.app.data.download.DownloadPlatform
import com.jiexiy.app.data.upload.CloudUploadManager
import androidx.compose.ui.platform.LocalContext
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
    
    var multiSelectMode by remember { mutableStateOf(false) }
    var selectedFiles by remember { mutableStateOf(listOf<ShareFile>()) }
    var cloudMessage by remember { mutableStateOf<String?>(null) }
    var shareResult by remember { mutableStateOf<String?>(null) }
    var actionFile by remember { mutableStateOf<ShareFile?>(null) }
    
    var showDownloadDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showFolderDialog by remember { mutableStateOf(false) }
    
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    
    // 上传选择器
    val uploadPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        var added = 0
        uris.forEach { uri ->
            val info = CloudUploadManager.describe(context, uri) ?: return@forEach
            CloudUploadManager.enqueue(
                context = context,
                platform = DownloadPlatform.ALIYUN,
                uri = uri,
                fileName = info.name,
                size = info.size,
                mimeType = info.mimeType,
                folderId = (uiState as? AliyunCloudUiState.Loaded)?.dirId ?: ""
            )
            added++
        }
        if (added > 0) {
            cloudMessage = "已加入 ${added} 个上传任务"
            onUploadStarted()
        }
    }
    
    // 同步 ViewModel 的状态变化
    LaunchedEffect(uiState) {
        // 可以在这里同步一些状态
    }
    
    LaunchedEffect(cloudMessage) {
        cloudMessage?.let {
            snackbarHostState.showSnackbar(it)
            cloudMessage = null
        }
    }
    
    if (shareResult != null) {
        AlertDialog(
            onDismissRequest = { shareResult = null },
            title = { Text("分享创建成功") },
            text = { Text(shareResult ?: "") },
            confirmButton = {
                TextButton(onClick = {
                    clipboardManager.setText(AnnotatedString(shareResult ?: ""))
                    shareResult = null
                }) { Text("复制链接") }
            },
            dismissButton = { TextButton(onClick = { shareResult = null }) { Text("关闭") } }
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
                        IconButton(onClick = { viewModel.enterMultiSelect(); multiSelectMode = true }) {
                            Icon(imageVector = Icons.Outlined.MoreVert, contentDescription = "更多")
                        }
                    } else {
                        TextButton(onClick = { viewModel.exitMultiSelect(); multiSelectMode = false; selectedFiles = emptyList() }) { Text("取消") }
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
                                Card(modifier = Modifier.fillMaxWidth().clickable { uploadPicker.launch(arrayOf("*/*")) }) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Outlined.InsertDriveFile, contentDescription = null, modifier = Modifier.size(24.dp))
                                        Spacer(Modifier.width(16.dp))
                                        Text("上传文件", fontWeight = FontWeight.Medium)
                                    }
                                }
                            }
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
                            val isSelected = selectedFiles.contains(file)
                            AliyunFileRow(
                                file = file,
                                selected = isSelected,
                                multiSelectMode = multiSelectMode,
                                onClick = {
                                    if (multiSelectMode) {
                                        selectedFiles = if (isSelected) selectedFiles.filter { it != file } else selectedFiles + file
                                    } else if (file.isdir) {
                                        viewModel.openFolder(file)
                                    } else {
                                        actionFile = file
                                        showDownloadDialog = true
                                    }
                                },
                                onLongClick = {
                                    multiSelectMode = true
                                    selectedFiles = if (isSelected) emptyList() else listOf(file)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // 下载对话框
    if (showDownloadDialog && actionFile != null) {
        val file = actionFile!!
        AlertDialog(
            onDismissRequest = { showDownloadDialog = false },
            title = { Text("下载") },
            text = { Text("确定下载「${file.fname}」吗？") },
            confirmButton = {
                TextButton(onClick = {
                    showDownloadDialog = false
                    viewModel.startDownload(file)
                    onDownloadStarted()
                }) { Text("下载") }
            },
            dismissButton = { TextButton(onClick = { showDownloadDialog = false }) { Text("取消") } }
        )
    }

    // 重命名对话框
    if (showRenameDialog && actionFile != null) {
        val file = actionFile!!
        var renameText by remember { mutableStateOf(file.fname) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("重命名") },
            text = { OutlinedTextField(value = renameText, onValueChange = { renameText = it }, label = { Text("文件名") }) },
            confirmButton = {
                TextButton(onClick = {
                    showRenameDialog = false
                    viewModel.rename(file, renameText)
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showRenameDialog = false }) { Text("取消") } }
        )
    }

    // 删除对话框
    if (showDeleteDialog && actionFile != null) {
        val file = actionFile!!
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("删除") },
            text = { Text("确定删除「${file.fname}」吗？") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    viewModel.delete(file)
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("取消") } }
        )
    }

    // 多选操作栏
    if (multiSelectMode && selectedFiles.isNotEmpty()) {
        BottomAppBar {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = {
                viewModel.batchDelete(selectedFiles)
                multiSelectMode = false
                selectedFiles = emptyList()
            }) { Text("删除 (${selectedFiles.size})") }
            TextButton(onClick = {
                viewModel.batchMove(selectedFiles)
                multiSelectMode = false
                selectedFiles = emptyList()
            }) { Text("移动") }
            TextButton(onClick = {
                val shareUrl = viewModel.createShare(selectedFiles, "阿里云盘分享", 7, null)
                shareResult = shareUrl
                multiSelectMode = false
                selectedFiles = emptyList()
            }) { Text("分享") }
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
