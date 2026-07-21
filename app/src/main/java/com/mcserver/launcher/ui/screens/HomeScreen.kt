package com.mcserver.launcher.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mcserver.launcher.data.ServerConfig
import com.mcserver.launcher.data.ServerState
import com.mcserver.launcher.server.*
import com.mcserver.launcher.ui.components.ServerStatusCard
import com.mcserver.launcher.utils.NetworkUtils
import kotlinx.coroutines.launch

/**
 * 极简仪表盘 — 白底黑字，按状态驱动布局。
 *
 * 运行时：状态横幅 + 资源监控 + 快捷操作
 * 未配置：引导卡片 + 操作入口
 */
@Composable
fun HomeScreen(
    config: ServerConfig,
    onNavigateToConfig: () -> Unit,
    onNavigateToConsole: () -> Unit,
    onNavigateToManagement: () -> Unit = {},
    onNavigateToServerList: () -> Unit = {},
    onNavigateToTerminal: () -> Unit = {}
) {
    val serverManager = ServerManager.instance
    val serverStatus by serverManager.serverStatus.collectAsState()
    val jreInfo by serverManager.jreInfo.collectAsState()
    val perfMetrics by PerformanceMonitor.instance.metrics.collectAsState()
    val serverList by serverManager.serverList.collectAsState()
    val currentServerId by serverManager.currentServerId.collectAsState()
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    var networkState by remember { mutableStateOf(NetworkUtils.NetworkState.DISCONNECTED) }
    var localIp by remember { mutableStateOf<String?>(null) }
    var networkType by remember { mutableStateOf("未知") }

    var pluginCount by remember { mutableIntStateOf(0) }
    var worldCount by remember { mutableIntStateOf(0) }
    var backupCount by remember { mutableIntStateOf(0) }
    var showServerMenu by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        networkState = NetworkUtils.getNetworkState(context)
        localIp = NetworkUtils.getLocalIpAddress()
        networkType = NetworkUtils.getNetworkTypeDescription(context)
        scope.launch {
            pluginCount = PluginManager.scanPlugins().size
            worldCount = FileManager.listWorlds().size
            backupCount = serverManager.backups.value.size
        }
    }

    val isRunning = serverStatus.state == ServerState.RUNNING
    val hasJar = config.jarPath.isNotBlank()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ══ 顶部：名称（可切换） + 网络状态 ══
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                TextButton(onClick = { if (!isRunning) showServerMenu = true }) {
                    Text(
                        text = config.name,
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Icon(
                        Icons.Filled.ArrowDropDown,
                        null,
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                DropdownMenu(
                    expanded = showServerMenu,
                    onDismissRequest = { showServerMenu = false }
                ) {
                    serverList.forEach { server ->
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (server.id == currentServerId) {
                                        Icon(
                                            Icons.Filled.CheckCircle,
                                            null,
                                            Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Text(server.name)
                                }
                            },
                            onClick = {
                                if (server.id != currentServerId && !isRunning) {
                                    serverManager.switchServer(server.id)
                                }
                                showServerMenu = false
                            },
                            enabled = server.id != currentServerId && !isRunning
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("管理服务器...") },
                        leadingIcon = {
                            Icon(Icons.Filled.Dns, null, Modifier.size(18.dp))
                        },
                        onClick = {
                            showServerMenu = false
                            onNavigateToServerList()
                        }
                    )
                }
            }
            Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Wifi, null, Modifier.size(14.dp),
                        tint = when (networkState) {
                            NetworkUtils.NetworkState.CONNECTED -> MaterialTheme.colorScheme.primary
                            NetworkUtils.NetworkState.CONNECTING -> MaterialTheme.colorScheme.tertiary
                            NetworkUtils.NetworkState.DISCONNECTED -> MaterialTheme.colorScheme.error
                        }
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        when (networkState) {
                            NetworkUtils.NetworkState.CONNECTED -> networkType
                            NetworkUtils.NetworkState.CONNECTING -> "连接中"
                            NetworkUtils.NetworkState.DISCONNECTED -> "无网络"
                        },
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }

        // ══ 局域网 IP（运行时） ══
        if (isRunning && localIp != null) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            ) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Lan, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "$localIp:${config.serverPort}",
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        // ══ 状态 + 启停 ══
        ServerStatusCard(
            status = serverStatus,
            onStart = { if (hasJar) scope.launch { serverManager.startServer(config) } },
            onStop = { serverManager.stopServer() }
        )

        // ══ 运行时：资源监控 + 快捷 ══
        if (isRunning) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("资源监控", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    MetricRow("CPU", "${"%.1f".format(perfMetrics.cpuPercent)}%", perfMetrics.cpuPercent / 100f)
                    Spacer(Modifier.height(8.dp))
                    val memUsed = perfMetrics.memoryUsedMB; val memTotal = perfMetrics.memoryTotalMB
                    MetricRow("内存", "$memUsed / $memTotal MB", if (memTotal > 0) memUsed.toFloat() / memTotal else 0f)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("在线玩家", style = MaterialTheme.typography.bodySmall)
                        Text("${serverStatus.playerCount}", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            fontFamily = FontFamily.Monospace)
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onNavigateToConsole, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Terminal, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("控制台")
                }
                OutlinedButton(onClick = onNavigateToTerminal, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Computer, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Linux 终端")
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onNavigateToManagement, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.ManageAccounts, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("管理")
                }
                OutlinedButton(onClick = onNavigateToConfig, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Tune, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("配置")
                }
            }
        }

        // ══ 未配置引导 ══
        if (!hasJar) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CloudDownload, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        Spacer(Modifier.width(10.dp))
                        Text("尚未选择服务器核心", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("下载 Paper/Fabric/Forge 核心或选择已有的 JAR 文件开始使用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f))
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onNavigateToConfig) { Text("配置服务器") }
                        OutlinedButton(onClick = onNavigateToManagement) { Text("浏览文件") }
                    }
                }
            }
        }

        // ══ 概览统计 ══
        if (hasJar) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("服务器概览", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        MiniStat(label = "插件", value = "$pluginCount", icon = Icons.Filled.Extension)
                        MiniStat(label = "世界", value = "$worldCount", icon = Icons.Filled.Public)
                        MiniStat(label = "备份", value = "$backupCount", icon = Icons.Filled.Backup)
                        MiniStat(label = "Java", value = "${config.javaVersion}", icon = Icons.Filled.IntegrationInstructions)
                    }
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        InfoLine("核心", config.jarPath.substringAfterLast("/"))
                        InfoLine("内存", "${config.allocatedMemoryMB} MB")
                        InfoLine("端口", "${config.serverPort}")
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

// ═══════════════════════════════════
// 内部组件
// ═══════════════════════════════════

@Composable
private fun MetricRow(label: String, value: String, ratio: Float) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text(value, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(2.dp))
        LinearProgressIndicator(
            progress = { ratio.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }
}

@Composable
private fun MiniStat(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(4.dp))
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}