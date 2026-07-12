package com.mcserver.launcher.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcserver.launcher.data.JreStatus
import com.mcserver.launcher.server.JreManager
import com.mcserver.launcher.server.MirrorLatency
import com.mcserver.launcher.server.ServerManager
import com.mcserver.launcher.ui.components.ThemeSelectorCard
import com.mcserver.launcher.ui.theme.ThemeMode
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    currentTheme: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit
) {
    val serverManager = ServerManager.instance
    val jreInfo by serverManager.jreInfo.collectAsState()
    val scope = rememberCoroutineScope()

    var showJreProgress by remember { mutableStateOf(false) }
    var jreProgress by remember { mutableFloatStateOf(0f) }

    var availableVersions by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedVersion by remember { mutableStateOf(serverManager.selectedJreVersion) }
    var selectedPackage by remember { mutableStateOf(serverManager.selectedJrePackage) }
    var selectedMirror by remember { mutableStateOf(serverManager.mirror) }
    var loadingVersions by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf(false) }

    var customUrl by remember { mutableStateOf(serverManager.customBaseUrl) }
    var showCustomUrl by remember { mutableStateOf(false) }

    // 延迟测试
    var mirrorLatencyTest by remember { mutableStateOf<List<MirrorLatency>>(emptyList()) }
    var testingLatency by remember { mutableStateOf(false) }

    // 删除确认弹窗
    var showDeleteDialog by remember { mutableStateOf<String?>(null) }

    fun loadVersions() {
        scope.launch {
            loadingVersions = true; loadError = false
            serverManager.fetchAvailableVersions().fold(
                onSuccess = { availableVersions = it; loadingVersions = false },
                onFailure = { availableVersions = listOf("21", "17", "11", "8"); loadError = true; loadingVersions = false }
            )
        }
    }

    LaunchedEffect(Unit) { loadVersions() }

    // 删除确认对话框
    showDeleteDialog?.let { version ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            icon = { Icon(Icons.Filled.DeleteForever, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("删除 Java $version") },
            text = { Text("确定要删除已安装的 Java $version 运行时吗？此操作不可撤销。") },
            confirmButton = {
                Button(
                    onClick = { serverManager.deleteInstalledVersion(version); showDeleteDialog = null },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = null }) { Text("取消") } }
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("设置", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        ThemeSelectorCard(currentTheme, onThemeChange)

        // Java 运行时管理
        Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Java 运行时管理", style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { loadVersions(); serverManager.refreshJreStatus() }) {
                        Icon(Icons.Filled.Refresh, "刷新")
                    }
                }
                Spacer(Modifier.height(12.dp))

                JreStatusRow(jreInfo.status, jreInfo.downloadProgress, selectedVersion)

                if (jreInfo.status == JreStatus.INSTALLED && jreInfo.installedVersions.size > 1) {
                    Text("已安装: ${jreInfo.installedVersions.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                // 下载进度
                if ((jreInfo.status == JreStatus.DOWNLOADING || jreInfo.status == JreStatus.PAUSED) && jreInfo.totalBytes > 0) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { jreInfo.downloadProgress }, modifier = Modifier.fillMaxWidth(),
                        color = if (jreInfo.status == JreStatus.PAUSED) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(formatBytes(jreInfo.downloadedBytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (jreInfo.status == JreStatus.PAUSED) {
                                Icon(Icons.Filled.PauseCircle, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.tertiary)
                                Spacer(Modifier.width(2.dp))
                            }
                            Text("${(jreInfo.downloadProgress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                        }
                        Text(formatBytes(jreInfo.totalBytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    // 速率 + ETA
                    if (jreInfo.status == JreStatus.DOWNLOADING) {
                        Spacer(Modifier.height(2.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                            if (jreInfo.downloadSpeedBytesPerSec > 0) {
                                Icon(Icons.Filled.Speed, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.tertiary)
                                Spacer(Modifier.width(2.dp))
                                Text(formatSpeed(jreInfo.downloadSpeedBytesPerSec), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                            }
                            if (jreInfo.remainingSeconds > 0) {
                                Spacer(Modifier.width(8.dp))
                                Icon(Icons.Filled.Schedule, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.tertiary)
                                Spacer(Modifier.width(2.dp))
                                Text("剩余 ${formatRemaining(jreInfo.remainingSeconds)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // 版本选择
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (loadingVersions) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(4.dp)); Text("获取中...", style = MaterialTheme.typography.bodySmall) }
                    else {
                        var expanded by remember { mutableStateOf(false) }
                        Box {
                            OutlinedButton(onClick = { expanded = true }) { Text(selectedVersion); Icon(Icons.Filled.ArrowDropDown, null) }
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                availableVersions.forEach { v ->
                                    DropdownMenuItem(
                                        text = { Row(verticalAlignment = Alignment.CenterVertically) { Text("Java $v"); if (jreInfo.installedVersions.contains(v)) { Spacer(Modifier.width(6.dp)); Icon(Icons.Filled.Done, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary) } } },
                                        onClick = { selectedVersion = v; serverManager.setJreVersion(v, selectedPackage); expanded = false },
                                        leadingIcon = { if (v == selectedVersion) Icon(Icons.Filled.Check, null, Modifier.size(18.dp)) }
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    FilterChip(selected = selectedPackage == "jdk", onClick = { selectedPackage = "jdk"; serverManager.setJreVersion(selectedVersion, "jdk") }, label = { Text("JDK", style = MaterialTheme.typography.labelSmall) })
                    FilterChip(selected = selectedPackage == "jre", onClick = { selectedPackage = "jre"; serverManager.setJreVersion(selectedVersion, "jre") }, label = { Text("JRE", style = MaterialTheme.typography.labelSmall) })
                }

                // 镜像源选择
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("下载源", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (testingLatency) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(4.dp))
                            Text("测速中...", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                        }
                        TextButton(onClick = {
                            scope.launch {
                                testingLatency = true
                                val results = serverManager.testMirrorLatency()
                                mirrorLatencyTest = results
                                // 自动选择延迟最低的
                                val best = results.firstOrNull()
                                if (best != null) {
                                    selectedMirror = best.key
                                    serverManager.setMirror(best.key)
                                }
                                testingLatency = false
                            }
                        }) {
                            Icon(if (mirrorLatencyTest.isEmpty()) Icons.Filled.NetworkCheck else Icons.Filled.Refresh, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(2.dp))
                            Text("测速", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    JreManager.MIRROR_OPTIONS.take(4).forEach { (key, label) ->
                        val latency = mirrorLatencyTest.firstOrNull { it.key == key }
                        FilterChip(
                            selected = selectedMirror == key,
                            onClick = { selectedMirror = key; serverManager.setMirror(key) },
                            label = {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(label.split("（")[0].split("(")[0], style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                    if (latency != null) {
                                        val color = when {
                                            latency.isBest -> MaterialTheme.colorScheme.primary
                                            latency.latencyMs == Long.MAX_VALUE -> MaterialTheme.colorScheme.error
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                        Text(
                                            if (latency.latencyMs == Long.MAX_VALUE) "超时" else "${latency.latencyMs}ms",
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                            color = color
                                        )
                                    }
                                }
                            }
                        )
                    }
                }

                // 自定义源
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { showCustomUrl = !showCustomUrl }) {
                        Icon(Icons.Filled.Link, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("自定义下载源")
                    }
                    if (customUrl.isNotBlank()) { Spacer(Modifier.width(4.dp)); Icon(Icons.Filled.Check, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary) }
                }
                if (showCustomUrl) {
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(value = customUrl, onValueChange = { customUrl = it }, label = { Text("下载源 URL") },
                        placeholder = { Text("留空使用默认/镜像源") }, supportingText = { Text("支持 {version} {arch} {package} 占位符") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        trailingIcon = { if (customUrl.isNotBlank()) IconButton(onClick = { customUrl = ""; serverManager.setCustomBaseUrl("") }) { Icon(Icons.Filled.Clear, "清除") } })
                    Spacer(Modifier.height(4.dp))
                    Button(onClick = { serverManager.setCustomBaseUrl(customUrl.trim()) }, modifier = Modifier.fillMaxWidth()) { Text("应用自定义源") }
                }

                if (loadError) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CloudOff, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(4.dp))
                        Text("无法连接网络，使用内置版本列表", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }

                Spacer(Modifier.height(12.dp))

                // 按钮区
                when (jreInfo.status) {
                    JreStatus.DOWNLOADING -> {
                        Button(onClick = { serverManager.pauseDownload() }, modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer)) {
                            Icon(Icons.Filled.PauseCircle, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("暂停下载")
                        }
                    }
                    JreStatus.PAUSED -> {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { scope.launch { serverManager.resumeDownload(); serverManager.installJre() } }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("继续下载")
                            }
                            OutlinedButton(onClick = { serverManager.cancelDownload() }, modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                                Icon(Icons.Filled.Close, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("取消")
                            }
                        }
                    }
                    else -> {
                        val needInstall = jreInfo.status != JreStatus.INSTALLED || !jreInfo.installedVersions.contains(selectedVersion)
                        Button(onClick = { scope.launch { showJreProgress = true; serverManager.installJre { p, _, _ -> jreProgress = p }; showJreProgress = false } },
                            enabled = !showJreProgress, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Filled.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                            Text(if (showJreProgress) "安装中..." else if (needInstall) "安装 Java $selectedVersion (${selectedPackage.uppercase()})" else "重新安装")
                        }
                    }
                }

                // 已安装版本管理
                if (jreInfo.installedVersions.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    Text("已安装的版本", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(4.dp))
                    jreInfo.installedVersions.forEach { v ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.CheckCircle, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(8.dp))
                                Text("Java $v", style = MaterialTheme.typography.bodyMedium)
                            }
                            IconButton(onClick = { showDeleteDialog = v }) {
                                Icon(Icons.Filled.DeleteOutline, "删除", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }

        // 关于
        Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("关于", style = MaterialTheme.typography.titleMedium); Spacer(Modifier.height(8.dp))
                AboutRow("版本", "0.1.0"); AboutRow("目标架构", "ARM64 (v7a / v8a)")
                AboutRow("平台", "Android 8.0+ (API 26+)"); AboutRow("Java 运行时", "Eclipse Temurin / 国内镜像")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JreStatusRow(status: JreStatus, progress: Float, version: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (status) {
            JreStatus.INSTALLED -> { Icon(Icons.Filled.CheckCircle, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(8.dp)); Text("Java $version 已就绪", fontWeight = FontWeight.Medium) }
            JreStatus.NOT_INSTALLED -> { Icon(Icons.Filled.ErrorOutline, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.width(8.dp)); Text("未安装", fontWeight = FontWeight.Medium) }
            JreStatus.DOWNLOADING -> { Icon(Icons.Filled.Downloading, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.tertiary); Spacer(Modifier.width(8.dp)); Text("下载中 ${(progress * 100).toInt()}%", fontWeight = FontWeight.Medium) }
            JreStatus.PAUSED -> { Icon(Icons.Filled.PauseCircle, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.tertiary); Spacer(Modifier.width(8.dp)); Text("已暂停", fontWeight = FontWeight.Medium) }
            JreStatus.EXTRACTING -> { Icon(Icons.Filled.Archive, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.tertiary); Spacer(Modifier.width(8.dp)); Text("解压中...", fontWeight = FontWeight.Medium) }
            JreStatus.ERROR -> { Icon(Icons.Filled.Cancel, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error); Spacer(Modifier.width(8.dp)); Text("安装失败", fontWeight = FontWeight.Medium) }
        }
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0; if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0; if (mb < 1024) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}

private fun formatSpeed(bytesPerSec: Long): String {
    if (bytesPerSec <= 0) return ""
    val mbps = bytesPerSec / (1024.0 * 1024.0); if (mbps >= 1.0) return "%.1f MB/s".format(mbps)
    return "%.0f KB/s".format(bytesPerSec / 1024.0)
}

private fun formatRemaining(seconds: Long): String {
    if (seconds < 60) return "${seconds}s"
    val m = seconds / 60; if (m < 60) return "${m}min"
    val h = m / 60; val rm = m % 60
    return if (rm > 0) "${h}h${rm}min" else "${h}h"
}
