package com.mcserver.launcher.ui.screens

import androidx.compose.foundation.clickable
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
import com.mcserver.launcher.data.ServerConfig
import com.mcserver.launcher.data.ServerState
import com.mcserver.launcher.server.FileManager
import com.mcserver.launcher.server.PluginManager
import com.mcserver.launcher.server.PlayerManager
import com.mcserver.launcher.server.ServerManager
import com.mcserver.launcher.ui.navigation.Screen
import com.mcserver.launcher.ui.navigation.managementSubScreens
import kotlinx.coroutines.launch

@Composable
fun ManagementScreen(
    config: ServerConfig,
    onNavigate: (Screen) -> Unit
) {
    val serverManager = ServerManager.instance
    val serverStatus by serverManager.serverStatus.collectAsState()
    val scope = rememberCoroutineScope()

    var pluginCount by remember { mutableIntStateOf(0) }
    var worldCount by remember { mutableIntStateOf(0) }
    var diskUsage by remember { mutableStateOf<FileManager.DiskUsage?>(null) }
    var opCount by remember { mutableIntStateOf(0) }
    var backupCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        scope.launch {
            pluginCount = PluginManager.scanPlugins().size
            worldCount = FileManager.listWorlds().size
            diskUsage = FileManager.getDiskUsage()
            opCount = PlayerManager.getOps().size
            backupCount = ServerManager.instance.backups.value.size
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("服务器管理", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "配置服务器、管理插件与玩家、查看文件与备份。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // 概览卡片
        Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("概览", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    StatItem("插件", "$pluginCount", Icons.Filled.Extension)
                    StatItem("世界", "$worldCount", Icons.Filled.Public)
                    StatItem("OP", "$opCount", Icons.Filled.AdminPanelSettings)
                    StatItem("备份", "${serverManager.backups.value.size}", Icons.Filled.Backup)
                }
                diskUsage?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "磁盘: ${it.totalFiles} 个文件, ${FileManager.formatFileSize(it.totalSize)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 管理子页入口
        managementSubScreens.forEach { screen ->
            ManagementCard(
                title = screen.label,
                icon = screen.icon,
                description = getScreenDescription(screen),
                onClick = { onNavigate(screen) }
            )
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ManagementCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.Filled.ChevronRight,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StatItem(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(4.dp))
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun getScreenDescription(screen: Screen): String = when (screen) {
    Screen.ServerConfig -> "服务器名称、JAR、内存、端口、游戏规则等核心配置"
    Screen.CoreDownload -> "从 Paper/Purpur/Fabric/Vanilla 下载服务器 JAR 核心"
    Screen.Plugins -> "管理 plugins/ 目录中的插件，启用/禁用/删除"
    Screen.Players -> "在线玩家列表、OP 管理、白名单、封禁列表"
    Screen.Files -> "浏览服务器文件、查看崩溃报告、编辑配置文件"
    Screen.Backups -> "完整备份/恢复服务器数据，自动备份策略"
    else -> ""
}
