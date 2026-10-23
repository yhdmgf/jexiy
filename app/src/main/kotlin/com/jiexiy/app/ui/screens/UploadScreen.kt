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

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jiexiy.app.data.download.DownloadPlatform
import com.jiexiy.app.data.upload.CloudUploadManager
import com.jiexiy.app.data.upload.UploadPhase
import com.jiexiy.app.data.upload.UploadStatus
import com.jiexiy.app.data.upload.UploadTask
import com.jiexiy.app.ui.SnackbarController

/**
 * 上传页：选择文件加入 [platform] 平台的上传队列，展示进度 / 失败原因，可取消、重试、删除记录。
 */
@Composable
fun UploadScreen(
    modifier: Modifier = Modifier,
    platform: String = DownloadPlatform.FEIJI,
    folderId: String = "0"
) {
    val context = LocalContext.current
    val tasks by CloudUploadManager.tasks.collectAsState()
    val label = CloudUploadManager.labelOf(platform)
        ?.ifBlank { "网盘" }
        .orEmpty().ifEmpty { "网盘" }
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        var added = 0
        uris.forEach { uri ->
            val info = CloudUploadManager.describe(context, uri) ?: return@forEach
            CloudUploadManager.enqueue(
                context = context,
                platform = platform,
                uri = uri,
                fileName = info.name,
                size = info.size,
                mimeType = info.mimeType,
                folderId = folderId
            )
            added++
        }
        if (added > 0) SnackbarController.show("已加入 $added 个上传任务")
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
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                        Text("选择文件上传到$label")
                    }
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
                LinearProgressIndicator(
                    progress = {
                        if (task.size > 0) (task.progress.toFloat() / task.size).coerceIn(0f, 1f)
                        else 0f
                    },
                    modifier = Modifier.fillMaxWidth()
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
