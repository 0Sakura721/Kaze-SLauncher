package com.mcserver.launcher.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mcserver.launcher.data.ServerState
import com.mcserver.launcher.server.ServerManager
import com.mcserver.launcher.server.WorldManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorldsScreen() {
    val serverManager = ServerManager.instance
    val serverStatus by serverManager.serverStatus.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var worlds by remember { mutableStateOf<List<WorldManager.WorldInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    var showDeleteConfirm by remember { mutableStateOf<String?>(null) }
    var showSetDefaultConfirm by remember { mutableStateOf<String?>(null) }
    var exportingWorld by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        scope.launch {
            loading = true
            worlds = WorldManager.listWorlds()
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    // 自动清除消息
    LaunchedEffect(message) {
        if (message != null) {
            kotlinx.coroutines.delay(3000)
            message = null
        }
    }

    // 删除确认弹窗
    showDeleteConfirm?.let { worldName ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            icon = { Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("删除世界") },
            text = { Text("确定要删除世界「$worldName」吗？\n\n此操作不可撤销！所有该世界的建筑和进度将永久丢失。") },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            WorldManager.deleteWorld(worldName)
                                .onSuccess {
                                    message = "世界「$worldName」已删除"
                                    refresh()
                                }
                                .onFailure { message = "删除失败：${it.message}" }
                            showDeleteConfirm = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = null }) { Text("取消") } }
        )
    }

    // 设置默认世界确认
    showSetDefaultConfirm?.let { worldName ->
        AlertDialog(
            onDismissRequest = { showSetDefaultConfirm = null },
            title = { Text("切换默认世界") },
            text = { Text("将默认世界切换为「$worldName」？\n\n下次启动服务器时将使用此世界。当前服务器需重启后生效。") },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        WorldManager.setDefaultWorld(worldName)
                            .onSuccess {
                                message = "默认世界已切换为「$worldName」（重启后生效）"
                                refresh()
                            }
                            .onFailure { message = "切换失败：${it.message}" }
                        showSetDefaultConfirm = null
                    }
                }) { Text("确认切换") }
            },
            dismissButton = { TextButton(onClick = { showSetDefaultConfirm = null }) { Text("取消") } }
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 标题栏
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("世界管理", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            // 刷新按钮
            IconButton(onClick = { refresh() }) {
                if (loading) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Refresh, "刷新")
                }
            }
        }

        // 运行时警告
        if (serverStatus.state == ServerState.RUNNING) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Warning, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "服务器正在运行，请先停止服务器再进行世界操作",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
        }

        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (worlds.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Public, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    Text("没有找到世界", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("启动服务器后，世界将自动创建", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            // 世界列表
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(worlds) { world ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Filled.Public,
                                        null,
                                        Modifier.size(24.dp),
                                        tint = if (world.isDefault) MaterialTheme.colorScheme.primary
                                               else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                world.name,
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            if (world.isDefault) {
                                                Spacer(Modifier.width(8.dp))
                                                SuggestionChip(
                                                    onClick = {},
                                                    label = { Text("默认", style = MaterialTheme.typography.labelSmall) }
                                                )
                                            }
                                        }
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            "大小: ${WorldManager.formatWorldSize(world.sizeBytes)} · " +
                                            "${world.dimensionCount} 个子维度 · " +
                                            "最后修改: ${WorldManager.formatLastModified(world.lastModified)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.height(8.dp))
                            HorizontalDivider()
                            Spacer(Modifier.height(4.dp))

                            // 操作按钮
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val isBusy = serverStatus.state == ServerState.RUNNING
                                // 设为默认
                                if (!world.isDefault) {
                                    TextButton(
                                        onClick = { showSetDefaultConfirm = world.name },
                                        enabled = !isBusy
                                    ) {
                                        Icon(Icons.Filled.Home, null, Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("设为默认", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                // 导出
                                TextButton(
                                    onClick = {
                                        exportingWorld = world.name
                                        scope.launch {
                                            WorldManager.exportWorld(world.name)
                                                .onSuccess { path ->
                                                    Toast.makeText(context,
                                                        "世界已导出到 Downloads",
                                                        Toast.LENGTH_LONG).show()
                                                }
                                                .onFailure {
                                                    Toast.makeText(context,
                                                        "导出失败：${it.message}",
                                                        Toast.LENGTH_SHORT).show()
                                                }
                                            exportingWorld = null
                                        }
                                    },
                                    enabled = exportingWorld != world.name
                                ) {
                                    if (exportingWorld == world.name) {
                                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                    } else {
                                        Icon(Icons.Filled.FileDownload, null, Modifier.size(16.dp))
                                    }
                                    Spacer(Modifier.width(4.dp))
                                    Text("导出", style = MaterialTheme.typography.labelSmall)
                                }
                                // 删除
                                TextButton(
                                    onClick = { showDeleteConfirm = world.name },
                                    enabled = !isBusy && !world.isDefault
                                ) {
                                    Icon(Icons.Filled.Delete, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                                    Spacer(Modifier.width(4.dp))
                                    Text("删除", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }

        // 提示消息
        message?.let {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Info, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
