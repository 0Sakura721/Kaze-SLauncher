package com.mcserver.launcher.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mcserver.launcher.core.download.DownloadCenter
import com.mcserver.launcher.data.DownloadStatus
import com.mcserver.launcher.data.DownloadTask

/** 下载中心:全局任务队列 */
@Composable
fun DownloadScreen(modifier: Modifier = Modifier) {
    val tasks by DownloadCenter.tasks.collectAsState()

    Column(modifier) {
        Text("下载中心", style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
        if (tasks.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无下载任务", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(tasks, key = { it.id }) { task ->
                    DownloadTaskCard(task)
                }
            }
        }
    }
}

@Composable
private fun DownloadTaskCard(task: DownloadTask) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (task.status) {
                    DownloadStatus.DOWNLOADING -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    DownloadStatus.COMPLETED -> Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                    DownloadStatus.FAILED -> Icon(Icons.Filled.Error, null, tint = MaterialTheme.colorScheme.error)
                    DownloadStatus.PAUSED -> Icon(Icons.Filled.Pause, null)
                    else -> Icon(Icons.Filled.PlayArrow, null)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(task.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text(
                        statusText(task) + if (task.totalBytes > 0)
                            " · 已下载 ${formatSize(task.downloadedBytes)} / 共 ${formatSize(task.totalBytes)}" +
                                " (${(task.progress * 100).toInt()}%)" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (task.isActive) {
                    IconButton(onClick = { DownloadCenter.pause(task.id) }) { Icon(Icons.Filled.Pause, "暂停") }
                    IconButton(onClick = { DownloadCenter.cancel(task.id) }) { Icon(Icons.Filled.Close, "取消") }
                } else if (task.status == DownloadStatus.PAUSED) {
                    IconButton(onClick = { DownloadCenter.resume(task.id) }) { Icon(Icons.Filled.PlayArrow, "继续") }
                    IconButton(onClick = { DownloadCenter.cancel(task.id) }) { Icon(Icons.Filled.Close, "取消") }
                } else if (task.status == DownloadStatus.FAILED) {
                    IconButton(onClick = {
                        DownloadCenter.remove(task.id)
                        DownloadCenter.enqueue(task.id, task.title, task.urls, task.destFile)
                    }) { Icon(Icons.Filled.PlayArrow, "重试") }
                    IconButton(onClick = { DownloadCenter.remove(task.id) }) { Icon(Icons.Filled.Close, "移除") }
                } else if (task.status == DownloadStatus.COMPLETED) {
                    IconButton(onClick = { DownloadCenter.remove(task.id) }) { Icon(Icons.Filled.Close, "移除") }
                }
            }
            if (task.status == DownloadStatus.DOWNLOADING) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { task.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(4.dp)
                )
                if (task.speedBytesPerSec > 0) {
                    Text("${formatSpeed(task.speedBytesPerSec)}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
            if (task.status == DownloadStatus.FAILED && task.error != null) {
                Text(task.error, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private fun statusText(task: DownloadTask): String = when (task.status) {
    DownloadStatus.PENDING -> "等待中"
    DownloadStatus.DOWNLOADING -> "下载中"
    DownloadStatus.PAUSED -> "已暂停"
    DownloadStatus.COMPLETED -> "已完成"
    DownloadStatus.FAILED -> "失败"
    DownloadStatus.CANCELED -> "已取消"
}

fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}

fun formatSpeed(bytesPerSec: Long): String = formatSize(bytesPerSec) + "/s"
