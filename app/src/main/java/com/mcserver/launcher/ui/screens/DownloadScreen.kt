package com.mcserver.launcher.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mcserver.launcher.core.download.DownloadCenter
import com.mcserver.launcher.core.server.InstanceStore
import com.mcserver.launcher.core.server.PluginManager
import com.mcserver.launcher.data.DownloadStatus
import com.mcserver.launcher.data.DownloadTask
import com.mcserver.launcher.ui.components.AddonManageScreen

/** 下载中心:全局任务队列(可删历史)+ 插件/模组管理(全屏页) */
@Composable
fun DownloadScreen(modifier: Modifier = Modifier) {
    val tasks by DownloadCenter.tasks.collectAsState()
    val instances by InstanceStore.instances.collectAsState()
    var manageInstance by remember { mutableStateOf<com.mcserver.launcher.data.ServerInstance?>(null) }

    manageInstance?.let { inst ->
        AddonManageScreen(instance = inst, onBack = { manageInstance = null }, modifier = modifier)
        return
    }

    Column(modifier) {
        Text("下载中心", style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))

        // ── 插件/模组管理入口(全局,进独立页面) ──
        if (instances.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Extension, null, Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("插件/模组管理", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("选择实例:本地导入(不耗流量)、在线搜索(Modrinth,可选 MC 版本和加载器)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    instances.forEach { inst ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .clickable { manageInstance = inst }
                        ) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(inst.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                    Text("${inst.coreType.name} ${inst.mcVersion} · ${PluginManager.dirLabel(inst)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                TextButton(onClick = { manageInstance = inst }) { Text("管理") }
                            }
                        }
                    }
                }
            }
        }

        // ── 下载历史 ──
        if (tasks.isNotEmpty()) {
            val doneCount = tasks.count { it.status in listOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED, DownloadStatus.CANCELED) }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("下载历史(${tasks.size})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                if (doneCount > 0) {
                    TextButton(onClick = { DownloadCenter.clearFinished() }) { Text("清空已完成($doneCount)") }
                }
            }
        }

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

/** 字节数格式化:KB/MB/GB */
fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format("%.1f GB", bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024)
    bytes >= 1024L -> String.format("%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
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
                    DownloadStatus.COMPLETED -> Icon(Icons.Filled.CheckCircle, null, Modifier.size(18.dp),
                        tint = Color(0xFF4CAF50))
                    DownloadStatus.FAILED -> Icon(Icons.Filled.Error, null, Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error)
                    DownloadStatus.PENDING -> Icon(Icons.Filled.HourglassEmpty, null, Modifier.size(18.dp))
                    else -> {}
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(task.title, style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text(task.status.label + if (task.totalBytes > 0)
                        " · ${task.downloadedBytes / 1024 / 1024} MB / ${task.totalBytes / 1024 / 1024} MB"
                        else "", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (task.status == DownloadStatus.DOWNLOADING) {
                    Text("${task.speedBytesPerSec / 1024} KB/s", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                    IconButton(onClick = { DownloadCenter.pause(task.id) }) {
                        Icon(Icons.Filled.Pause, "暂停", Modifier.size(18.dp))
                    }
                    IconButton(onClick = { DownloadCenter.cancel(task.id) }) {
                        Icon(Icons.Filled.Close, "取消", Modifier.size(18.dp))
                    }
                } else if (task.status == DownloadStatus.PAUSED) {
                    IconButton(onClick = { DownloadCenter.resume(task.id) }) {
                        Icon(Icons.Filled.PlayArrow, "继续", Modifier.size(18.dp))
                    }
                }
                // 已完成/失败/取消:可删除历史
                if (task.status in listOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED, DownloadStatus.CANCELED)) {
                    IconButton(onClick = { DownloadCenter.remove(task.id) }) {
                        Icon(Icons.Filled.Delete, "删除记录", Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private val DownloadStatus.label: String
    get() = when (this) {
        DownloadStatus.PENDING -> "等待中"
        DownloadStatus.DOWNLOADING -> "下载中"
        DownloadStatus.PAUSED -> "已暂停"
        DownloadStatus.COMPLETED -> "已完成"
        DownloadStatus.FAILED -> "失败"
        DownloadStatus.CANCELED -> "已取消"
    }
