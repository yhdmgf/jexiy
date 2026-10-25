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

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jiexiy.app.data.db.AppDatabase
import com.jiexiy.app.data.download.DownloadPlatform
import com.jiexiy.app.data.network.SharePlatform
import com.jiexiy.app.data.repository.AccountDirectory
import com.jiexiy.app.data.upload.CloudUploadManager
import com.jiexiy.app.data.upload.SelectedFile
import com.jiexiy.app.data.upload.UploadPhase
import com.jiexiy.app.data.upload.UploadStatus
import com.jiexiy.app.data.upload.UploadTask
import com.jiexiy.app.ui.SnackbarController
import com.jiexiy.app.ui.components.jexiyWavyProgress
import com.jiexiy.app.ui.items.drivePlatformLogoRes

/**
 * 上传页：右上角「+ 添加文件」→ 系统文件选择器 → 弹出网盘选择弹窗（只显示已登录的网盘）→ 入队。
 *
 * 目标网盘不在进页时写死：每个账号平台都可能没登录，写死会让用户点「添加文件」才发现传不上去。
 */
@Composable
fun UploadScreen(
    modifier: Modifier = Modifier,
    folderId: String = "0"
) {
    val context = LocalContext.current
    val tasks by CloudUploadManager.tasks.collectAsState()

    // 选完文件先暂存，等用户在弹窗里选完网盘再真正入队
    var pending by remember { mutableStateOf<List<UploadPick>>(emptyList()) }
    var showTargetPicker by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val picked = uris.mapNotNull { uri ->
            CloudUploadManager.describe(context, uri)?.let { info -> UploadPick(uri, info) }
        }
        if (picked.isEmpty()) {
            SnackbarController.show("无法读取所选文件")
            return@rememberLauncherForActivityResult
        }
        pending = picked
        showTargetPicker = true
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "上传任务",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "共 ${tasks.size} 项",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("添加文件")
            }
        }

        if (tasks.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "还没有上传任务",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "点击右上角「添加文件」开始上传",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(tasks, key = { it.id }) { task ->
                    UploadTaskRow(
                        task = task,
                        onCancel = { CloudUploadManager.cancel(task.id) },
                        onRetry = { CloudUploadManager.retry(task.id) },
                        onRemove = { CloudUploadManager.remove(task.id) }
                    )
                }
            }
        }
    }

    if (showTargetPicker && pending.isNotEmpty()) {
        UploadTargetSheet(
            files = pending,
            folderId = folderId,
            onDismiss = { showTargetPicker = false },
            onPicked = { target ->
                CloudUploadManager.init(context)
                var added = 0
                pending.forEach { pick ->
                    CloudUploadManager.enqueue(
                        context = context,
                        platform = target.platform,
                        uri = pick.uri,
                        fileName = pick.info.name,
                        size = pick.info.size,
                        mimeType = pick.info.mimeType,
                        folderId = folderId
                    )
                    added++
                }
                showTargetPicker = false
                pending = emptyList()
                SnackbarController.show("已加入 $added 个上传任务 · ${target.title}")
            }
        )
    }
}

/** 已描述过的一个待上传文件：Uri + 显示名/大小/MIME */
private data class UploadPick(val uri: Uri, val info: SelectedFile)

/** 弹窗里的一行网盘：平台标识 + 展示名 + 主账号昵称 + logo */
private data class UploadTarget(
    val platform: String,
    val title: String,
    val accountName: String,
    @DrawableRes val logoRes: Int?
)

/** 可上传的平台清单，与 [CloudUploadManager.init] 里的注册顺序一致 */
private val UPLOADABLE_PLATFORMS = listOf(
    DownloadPlatform.FEIJI,
    DownloadPlatform.ALIYUN,
    DownloadPlatform.QUARK,
    DownloadPlatform.UC,
    DownloadPlatform.XUNLEI,
    DownloadPlatform.BAIDU,
    DownloadPlatform.C139,
    DownloadPlatform.PAN123,
    DownloadPlatform.PAN115,
    DownloadPlatform.GUANGYA,
    DownloadPlatform.LANZOU,
    DownloadPlatform.ILANZOU,
    DownloadPlatform.PIKPAK
)

/**
 * 读「已登录」的网盘账号，一行一个平台（同平台多账号时取主账号）。
 *
 * 上传走主账号：凭证刷新、会话续期这些平台语义都在主账号那套 Repository 里维护，
 * 非主账号只做下载轮询，拿它们上传会把会话状态搞乱。
 */
private suspend fun loadUploadTargets(context: android.content.Context): List<UploadTarget> {
    CloudUploadManager.init(context)
    val directory = AccountDirectory(AppDatabase.get(context))
    val result = mutableListOf<UploadTarget>()
    UPLOADABLE_PLATFORMS.forEach { platform ->
        if (!CloudUploadManager.supports(platform)) return@forEach
        val accounts = directory.list(platform)
        if (accounts.isEmpty()) return@forEach
        val primary = accounts.firstOrNull { it.isPrimary } ?: accounts.first()
        result += UploadTarget(
            platform = platform,
            title = platformTitle(platform),
            accountName = primary.remark.ifBlank { primary.nickname },
            logoRes = drivePlatformLogoRes(platform)
        )
    }
    return result
}

private fun platformTitle(platform: String): String =
    SharePlatform.entries.first { it.name.equals(platform, ignoreCase = true) }.displayName

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UploadTargetSheet(
    files: List<UploadPick>,
    folderId: String,
    onDismiss: () -> Unit,
    onPicked: (UploadTarget) -> Unit
) {
    val context = LocalContext.current
    var targets by remember { mutableStateOf<List<UploadTarget>?>(null) }
    val sheetState = rememberModalBottomSheetState()

    LaunchedEffect(Unit) {
        targets = loadUploadTargets(context)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            Text(
                text = "上传到哪个网盘",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp)
            )
            Text(
                text = "已选 ${files.size} 个文件",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
            )

            val list = targets
            when {
                list == null -> Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }

                list.isEmpty() -> Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "还没有登录任何网盘",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "请先在「网盘」页登录一个网盘，再回来上传",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }

                else -> LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    items(list, key = { it.platform }) { target ->
                        UploadTargetRow(target, onClick = { onPicked(target) })
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun UploadTargetRow(target: UploadTarget, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val res = target.logoRes
            if (res != null) {
                Image(
                    painter = painterResource(res),
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    contentScale = ContentScale.Fit
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = target.title.take(1),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = target.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (target.accountName.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = target.accountName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun UploadTaskRow(
    task: UploadTask,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.fileName,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = statusText(task),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (task.status == UploadStatus.FAILED) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                        if (task.platformLabel.isNotBlank()) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "· ${task.platformLabel}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = formatUploadBytes(task.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (task.status == UploadStatus.FAILED) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                when (task.status) {
                    UploadStatus.RUNNING, UploadStatus.PENDING -> IconButton(onClick = onCancel) {
                        Icon(Icons.Outlined.Cancel, contentDescription = "取消")
                    }
                    UploadStatus.FAILED, UploadStatus.CANCELLED -> {
                        IconButton(onClick = onRetry) {
                            Icon(Icons.Outlined.Refresh, contentDescription = "重试")
                        }
                        IconButton(onClick = onRemove) {
                            Icon(Icons.Outlined.Delete, contentDescription = "删除记录")
                        }
                    }
                    UploadStatus.COMPLETED -> IconButton(onClick = onRemove) {
                        Icon(Icons.Outlined.Delete, contentDescription = "删除记录")
                    }
                }
            }
            if (task.status == UploadStatus.RUNNING || task.status == UploadStatus.PENDING) {
                Spacer(modifier = Modifier.height(8.dp))
                jexiyWavyProgress(
                    progress = {
                        if (task.size > 0) (task.progress.toFloat() / task.size).coerceIn(0f, 1f)
                        else 0f
                    },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    waving = task.status == UploadStatus.RUNNING
                )
            }
            if (task.status == UploadStatus.FAILED && task.error.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = task.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

private fun statusText(task: UploadTask): String = when (task.status) {
    UploadStatus.PENDING -> "等待上传"
    UploadStatus.RUNNING -> when (task.phase) {
        UploadPhase.PREPARING -> "准备中（算校验值 / 取上传凭证）"
        UploadPhase.UPLOADING -> "上传中 ${formatUploadBytes(task.progress)} / ${formatUploadBytes(task.size)}"
        UploadPhase.FINISHING -> "上传完成，等待网盘确认"
    }
    UploadStatus.COMPLETED -> "已完成"
    UploadStatus.FAILED -> "上传失败"
    UploadStatus.CANCELLED -> "已取消"
}

private fun formatUploadBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return String.format("%.1f %s", value, units[index])
}
