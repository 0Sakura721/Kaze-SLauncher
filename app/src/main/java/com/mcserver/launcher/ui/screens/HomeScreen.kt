package com.mcserver.launcher.ui.screens

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
import com.mcserver.launcher.data.JreStatus
import com.mcserver.launcher.data.ServerConfig
import com.mcserver.launcher.data.ServerState
import com.mcserver.launcher.server.HealthChecker
import com.mcserver.launcher.server.PerformanceMonitor
import com.mcserver.launcher.server.ServerManager
import com.mcserver.launcher.server.TermuxManager
import com.mcserver.launcher.server.TermuxState
import com.mcserver.launcher.ui.components.ServerStatusCard
import com.mcserver.launcher.ui.components.formatUptime
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    config: ServerConfig,
    onNavigateToConfig: () -> Unit,
    onNavigateToConsole: () -> Unit
) {
    val serverManager = ServerManager.instance
    val serverStatus by serverManager.serverStatus.collectAsState()
    val jreInfo by serverManager.jreInfo.collectAsState()
    val perfMetrics by PerformanceMonitor.instance.metrics.collectAsState()
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    var showJreProgress by remember { mutableStateOf(false) }
    var termuxState by remember { mutableStateOf(TermuxState.NOT_INSTALLED) }
    var showHealthCheck by remember { mutableStateOf(false) }
    var healthResult by remember { mutableStateOf<HealthChecker.HealthResult?>(null) }

    LaunchedEffect(Unit) { termuxState = serverManager.termuxState }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 顶部标题
        Text(
            text = "Minecraft 服务器",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        // JRE 状态卡片
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.IntegrationInstructions,
                        contentDescription = null,
                        tint = when (jreInfo.status) {
                            JreStatus.INSTALLED -> MaterialTheme.colorScheme.primary
                            JreStatus.DOWNLOADING, JreStatus.EXTRACTING -> MaterialTheme.colorScheme.tertiary
                            JreStatus.PAUSED -> MaterialTheme.colorScheme.tertiary
                            JreStatus.ERROR -> MaterialTheme.colorScheme.error
                            JreStatus.NOT_INSTALLED -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Java 运行时",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = when (jreInfo.status) {
                                JreStatus.NOT_INSTALLED -> "未安装"
                                JreStatus.DOWNLOADING -> {
                                    if (jreInfo.totalBytes > 0)
                                        "下载中：${formatSize(jreInfo.downloadedBytes)} / ${formatSize(jreInfo.totalBytes)}"
                                    else "下载中 ${(jreInfo.downloadProgress * 100).toInt()}%"
                                }
                                JreStatus.PAUSED -> "已暂停 — ${(jreInfo.downloadProgress * 100).toInt()}%"
                                JreStatus.EXTRACTING -> "解压中..."
                                JreStatus.INSTALLED -> "已就绪"
                                JreStatus.ERROR -> "安装失败"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    when (jreInfo.status) {
                        JreStatus.NOT_INSTALLED, JreStatus.ERROR -> {
                            Button(
                                onClick = {
                                    scope.launch {
                                        showJreProgress = true
                                        serverManager.installJre()
                                        showJreProgress = false
                                    }
                                },
                                enabled = !showJreProgress && jreInfo.status != JreStatus.DOWNLOADING
                            ) {
                                Icon(Icons.Filled.Download, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("安装")
                            }
                        }
                        JreStatus.DOWNLOADING -> {
                            IconButton(onClick = { serverManager.pauseDownload() }) {
                                Icon(Icons.Filled.PauseCircle, "暂停", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                            }
                        }
                        JreStatus.PAUSED -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                FilledTonalButton(onClick = {
                                    scope.launch {
                                        serverManager.resumeDownload()
                                        serverManager.installJre()
                                    }
                                }, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                                    Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("继续")
                                }
                                IconButton(onClick = { serverManager.cancelDownload() }) {
                                    Icon(Icons.Filled.Close, "取消", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                        JreStatus.INSTALLED -> {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        else -> {}
                    }
                }
                // 下载进度条
                if (jreInfo.status == JreStatus.DOWNLOADING || jreInfo.status == JreStatus.PAUSED) {
                    LinearProgressIndicator(
                        progress = { jreInfo.downloadProgress },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        color = if (jreInfo.status == JreStatus.PAUSED)
                            MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.primary
                    )
                    if (jreInfo.totalBytes > 0) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "${formatSize(jreInfo.downloadedBytes)} / ${formatSize(jreInfo.totalBytes)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (jreInfo.downloadSpeedBytesPerSec > 0) {
                                    Text(
                                        formatSpeed(jreInfo.downloadSpeedBytesPerSec),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.tertiary
                                    )
                                }
                                if (jreInfo.remainingSeconds > 0) {
                                    Text(
                                        formatRemaining(jreInfo.remainingSeconds),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.tertiary
                                    )
                                }
                                Text(
                                    "${(jreInfo.downloadProgress * 100).toInt()}%",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        Spacer(modifier = Modifier.padding(bottom = 8.dp).height(2.dp))
                    } else {
                        Spacer(
                            modifier = Modifier
                                .padding(bottom = 8.dp)
                                .height(2.dp)
                        )
                    }
                }
            }
        }

        // Termux 环境状态
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.Terminal, null,
                    tint = when (termuxState) {
                        TermuxState.READY -> MaterialTheme.colorScheme.primary
                        TermuxState.JAVA_MISSING -> MaterialTheme.colorScheme.tertiary
                        TermuxState.NOT_INSTALLED -> MaterialTheme.colorScheme.error
                        TermuxState.INSTALLED -> MaterialTheme.colorScheme.tertiary
                    },
                    modifier = Modifier.size(32.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Termux 环境", style = MaterialTheme.typography.titleSmall)
                    Text(
                        when (termuxState) {
                            TermuxState.NOT_INSTALLED -> "未安装 — 需要 Termux 提供 Linux 环境"
                            TermuxState.INSTALLED -> "已安装"
                            TermuxState.JAVA_MISSING -> "Java 未安装"
                            TermuxState.READY -> "已就绪"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                when (termuxState) {
                    TermuxState.NOT_INSTALLED -> {
                        Button(onClick = { serverManager.openTermuxDownload() }) {
                            Text("安装 Termux")
                        }
                    }
                    TermuxState.JAVA_MISSING -> {
                        Button(onClick = { serverManager.installJavaInTermux() }) {
                            Text("安装 Java")
                        }
                    }
                    TermuxState.READY -> {
                        Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                    }
                    else -> {}
                }
            }
        }

        // 服务器状态卡片
        ServerStatusCard(
            status = serverStatus,
            onStart = {
                if (config.jarPath.isNotBlank()) {
                    scope.launch {
                        val result = HealthChecker.runAllChecks(config)
                        healthResult = result
                        if (result.passed) {
                            serverManager.startServer(config)
                        } else {
                            showHealthCheck = true
                        }
                    }
                }
            },
            onStop = { serverManager.stopServer() }
        )

        // 性能监控（仅运行时显示）
        if (serverStatus.state == ServerState.RUNNING) {
            PerformanceCard(metrics = perfMetrics)
        }

        // 健康检查结果弹窗
        if (showHealthCheck && healthResult != null) {
            HealthCheckDialog(
                result = healthResult!!,
                onDismiss = { showHealthCheck = false },
                onForceStart = {
                    showHealthCheck = false
                    scope.launch { serverManager.startServer(config) }
                }
            )
        }

        // 快速信息
        if (config.jarPath.isNotBlank()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "当前配置",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    InfoRow("名称", config.name)
                    InfoRow("JAR", config.jarPath.substringAfterLast("/"))
                    InfoRow("内存", "${config.allocatedMemoryMB} MB")
                    InfoRow("端口", "${config.serverPort}")
                }
            }
        } else {
            // 未选择 JAR
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "未选择服务器文件",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            "请先选择一个 Minecraft 服务器 JAR 文件",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    TextButton(onClick = onNavigateToConfig) {
                        Text("配置")
                    }
                }
            }
        }

        // 快捷操作
        if (serverStatus.state == ServerState.RUNNING) {
            TextButton(
                onClick = onNavigateToConsole,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Icon(Icons.Filled.Terminal, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("打开控制台")
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    val gb = mb / 1024.0
    return "%.2f GB".format(gb)
}

private fun formatSpeed(bytesPerSec: Long): String {
    if (bytesPerSec <= 0) return ""
    val mbps = bytesPerSec / (1024.0 * 1024.0)
    if (mbps >= 1.0) return "%.1f MB/s".format(mbps)
    val kbps = bytesPerSec / 1024.0
    return "%.0f KB/s".format(kbps)
}

private fun formatRemaining(seconds: Long): String {
    if (seconds < 60) return "${seconds}s"
    val m = seconds / 60; if (m < 60) return "${m}min"
    val h = m / 60; val rm = m % 60
    return if (rm > 0) "${h}h${rm}min" else "${h}h"
}

@Composable
private fun HealthCheckDialog(
    result: HealthChecker.HealthResult,
    onDismiss: () -> Unit,
    onForceStart: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                if (result.passed) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                null,
                tint = if (result.passed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
        },
        title = {
            Text(
                when {
                    result.passed && result.warnings.isEmpty() -> "诊断通过"
                    result.passed -> "诊断通过（有注意事项）"
                    else -> "启动前诊断发现问题"
                }
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // 检查项列表
                result.checks.forEach { check ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (check.passed) Icons.Filled.CheckCircle else {
                                if (check.severity == HealthChecker.Severity.ERROR) Icons.Filled.Cancel
                                else Icons.Filled.Warning
                            },
                            null,
                            Modifier.size(16.dp),
                            tint = when {
                                !check.passed && check.severity == HealthChecker.Severity.ERROR -> MaterialTheme.colorScheme.error
                                !check.passed -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.primary
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(check.name, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
                            Text(check.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (check.detail.isNotBlank()) {
                                Text(check.detail, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                            }
                        }
                    }
                }

                // 推荐建议
                if (result.recommendations.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    Text("推荐优化", style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary)
                    result.recommendations.forEach { rec ->
                        Row(
                            modifier = Modifier.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(Icons.Filled.Lightbulb, null,
                                Modifier.size(14.dp).padding(top = 2.dp),
                                tint = MaterialTheme.colorScheme.tertiary)
                            Spacer(Modifier.width(6.dp))
                            Text(rec, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (!result.passed) {
                Button(
                    onClick = onForceStart,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("仍然启动")
                }
            } else {
                Button(onClick = onDismiss) { Text("确定") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun PerformanceCard(metrics: PerformanceMonitor.Metrics) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("性能监控", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))

            // CPU
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("CPU", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(
                        progress = { metrics.cpuPercent / 100f },
                        modifier = Modifier.width(80.dp).height(8.dp),
                        color = when {
                            metrics.cpuPercent > 80 -> MaterialTheme.colorScheme.error
                            metrics.cpuPercent > 50 -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.primary
                        },
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "%.1f%%".format(metrics.cpuPercent),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // 内存
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("内存", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val memRatio = if (metrics.memoryTotalMB > 0) metrics.memoryUsedMB.toFloat() / metrics.memoryTotalMB else 0f
                    LinearProgressIndicator(
                        progress = { memRatio.coerceIn(0f, 1f) },
                        modifier = Modifier.width(80.dp).height(8.dp),
                        color = when {
                            memRatio > 0.9f -> MaterialTheme.colorScheme.error
                            memRatio > 0.7f -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.primary
                        },
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${metrics.memoryUsedMB}MB",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // TPS / MSPT
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("TPS", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        "%.1f".format(metrics.tps),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = when {
                            metrics.tps >= 19.5f -> MaterialTheme.colorScheme.primary
                            metrics.tps >= 15f -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.error
                        }
                    )
                    Text(
                        "MSPT %.1f".format(metrics.mspt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 玩家
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("在线玩家", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "${metrics.playerCount}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // 运行时间
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("运行时间", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    formatUptime(metrics.uptimeSeconds),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
