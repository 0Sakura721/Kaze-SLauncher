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
import com.mcserver.launcher.server.ServerManager
import com.mcserver.launcher.ui.components.ServerStatusCard
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
    val scope = rememberCoroutineScope()

    var showJreProgress by remember { mutableStateOf(false) }

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
                                Text("安装")
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
                if (jreInfo.status == JreStatus.DOWNLOADING) {
                    LinearProgressIndicator(
                        progress = { jreInfo.downloadProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    )
                    if (jreInfo.totalBytes > 0) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .padding(bottom = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "${formatSize(jreInfo.downloadedBytes)} / ${formatSize(jreInfo.totalBytes)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "${(jreInfo.downloadProgress * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
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

        // 服务器状态卡片
        ServerStatusCard(
            status = serverStatus,
            onStart = {
                if (config.jarPath.isNotBlank()) {
                    scope.launch {
                        serverManager.startServer(config, scope)
                    }
                }
            },
            onStop = { serverManager.stopServer() }
        )

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
