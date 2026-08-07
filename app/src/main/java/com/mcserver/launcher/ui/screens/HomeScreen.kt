package com.mcserver.launcher.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
import com.mcserver.launcher.ui.components.ArcDashboard
import com.mcserver.launcher.ui.components.PageTransition
import com.mcserver.launcher.ui.components.PulseGlow
import com.mcserver.launcher.ui.components.ResourceRing
import com.mcserver.launcher.ui.components.pressScale
import com.mcserver.launcher.ui.components.pressSource
import com.mcserver.launcher.core.env.EnvManager
import com.mcserver.launcher.core.server.InstanceStore
import com.mcserver.launcher.data.InstanceStatus
import com.mcserver.launcher.data.ServerInstance
import com.mcserver.launcher.ui.theme.DashboardGradient
import com.mcserver.launcher.ui.theme.KazeCyan
import com.mcserver.launcher.ui.theme.KazeError
import com.mcserver.launcher.ui.theme.KazeSuccess
import com.mcserver.launcher.ui.theme.KazeWarning
import com.mcserver.launcher.ui.theme.badgeGradient
import com.mcserver.launcher.ui.theme.badgeLetter
import kotlinx.coroutines.launch

/** 首页:弧形仪表盘 + 资源环 + 实例列表(FCL 式:长按卡片弹出操作菜单) */
@androidx.compose.foundation.ExperimentalFoundationApi
@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val instances by InstanceStore.instances.collectAsState()
    val envReady = EnvManager.isEnvironmentReady()
    val serverStatus by com.mcserver.launcher.core.server.ServerManager.status.collectAsState()
    val players by com.mcserver.launcher.core.server.ServerManager.players.collectAsState()
    val uptime by com.mcserver.launcher.core.server.ServerManager.uptimeSec.collectAsState()
    var showNew by remember { mutableStateOf(false) }
    var selectedInstance by remember { mutableStateOf<ServerInstance?>(null) }
    var menuInstance by remember { mutableStateOf<ServerInstance?>(null) }
    var showImport by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }

    // SAF:导入实例包(zip)
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            importing = true
            scope.launch {
                val tmp = java.io.File(context.filesDir, "import_instance")
                if (tmp.exists()) tmp.deleteRecursively()
                com.mcserver.launcher.util.FileImporter.copyFile(context, uri, tmp)
                    .onSuccess { zipFile ->
                        val inst = com.mcserver.launcher.core.server.ExportManager.importInstance(zipFile)
                        tmp.deleteRecursively()
                        importing = false
                        if (inst != null) {
                            Toast.makeText(context, "实例导入完成:${inst.name}", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(context, "导入失败:无法解析实例包", Toast.LENGTH_LONG).show()
                        }
                    }
                    .onFailure { err ->
                        importing = false
                        Toast.makeText(context, "导入失败:${err.message}", Toast.LENGTH_LONG).show()
                    }
            }
        }
    }

    val current = selectedInstance
    val navTarget = when {
        current != null -> 1
        showNew -> 2
        else -> 0
    }

    // 系统返回键:先关详情页/新建页,而不是直接退出 App
    androidx.activity.compose.BackHandler(enabled = selectedInstance != null) { selectedInstance = null }
    androidx.activity.compose.BackHandler(enabled = showNew) { showNew = false }

    PageTransition(navTarget, modifier) { target ->
        when (target) {
            1 -> {
                val detail = remember { current }
                if (detail != null) {
                    InstanceDetailScreen(instance = detail, onBack = { selectedInstance = null })
                }
            }
            2 -> NewInstanceScreen(onDone = { showNew = false })
            else -> HomeContent(
                instances = instances,
                envReady = envReady,
                serverStatus = serverStatus,
                playerCount = players.size,
                uptimeSec = uptime,
                importing = importing,
                onNew = { showNew = true },
                onImport = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
                onOpen = { selectedInstance = it },
                onMenu = { menuInstance = it },
                onStart = { scope.launch {
                    val result = com.mcserver.launcher.core.server.ServerManager.start(it)
                    if (result.isFailure) {
                        Toast.makeText(context, result.exceptionOrNull()?.message ?: "启动失败", Toast.LENGTH_LONG).show()
                    }
                } },
                onStop = { scope.launch { com.mcserver.launcher.core.server.ServerManager.stop() } }
            )
        }
    }

    // ── 长按操作菜单(FCL 式) ──
    val menuTarget = menuInstance
    if (menuTarget != null) {
        DropdownMenu(
            expanded = true,
            onDismissRequest = { menuInstance = null }
        ) {
            DropdownMenuItem(
                text = { Text("打开") },
                leadingIcon = { Icon(Icons.Filled.FolderOpen, null, Modifier.size(20.dp)) },
                onClick = { menuInstance = null; selectedInstance = menuTarget }
            )
            DropdownMenuItem(
                text = { Text("导出为 zip(备份/迁移)") },
                leadingIcon = { Icon(Icons.Filled.Share, null, Modifier.size(20.dp)) },
                onClick = {
                    menuInstance = null
                    scope.launch {
                        val name = menuTarget.name.replace(Regex("[^\\w\\u4e00-\\u9fa5-]"), "_").take(30)
                        val f = java.io.File(context.getExternalFilesDir(null), "exports")
                        f.mkdirs()
                        val dest = java.io.File(f, "$name-${System.currentTimeMillis().toString().takeLast(6)}.zip")
                        val ok = com.mcserver.launcher.core.server.ExportManager.exportInstance(menuTarget, dest)
                        Toast.makeText(
                            context,
                            if (ok) "已导出:${dest.absolutePath}(可在文件管理器找到)" else "导出失败",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            )
            DropdownMenuItem(
                text = { Text("删除实例", color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Filled.Delete, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error) },
                onClick = {
                    menuInstance = null
                    scope.launch {
                        if (com.mcserver.launcher.core.server.ServerManager.isRunningFor(menuTarget.id)) {
                            Toast.makeText(context, "服务器运行中,请先停止", Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        InstanceStore.delete(menuTarget.id)
                        Toast.makeText(context, "已删除 ${menuTarget.name}", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }
    }
}

@androidx.compose.foundation.ExperimentalFoundationApi
@Composable
private fun HomeContent(
    instances: List<ServerInstance>,
    envReady: Boolean,
    serverStatus: InstanceStatus,
    playerCount: Int,
    uptimeSec: Long,
    importing: Boolean,
    onNew: () -> Unit,
    onImport: () -> Unit,
    onOpen: (ServerInstance) -> Unit,
    onMenu: (ServerInstance) -> Unit,
    onStart: (ServerInstance) -> Unit,
    onStop: (ServerInstance) -> Unit
) {
    Scaffold(
        floatingActionButton = {
            val (pressFab, srcFab) = pressSource()
            FloatingActionButton(onClick = onNew, interactionSource = srcFab, modifier = pressFab) {
                Icon(Icons.Filled.Add, "新建服务端")
            }
        }
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            // ── 弧形仪表盘头部 ──
            item {
                DashboardHeader(
                    status = serverStatus,
                    instanceCount = instances.size,
                    playerCount = playerCount,
                    uptimeSec = uptimeSec,
                    envReady = envReady
                )
            }

            // ── 环境警告 ──
            if (!envReady) {
                item {
                    Surface(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer
                    ) {
                        Text(
                            "Linux 环境未就绪,请先在设置页重新部署",
                            Modifier.padding(14.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            // ── 导入进度 ──
            if (importing) {
                item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }

            // ── 标题行 ──
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "我的服务端",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.weight(1f)
                    )
                    val (pressImp, srcImp) = pressSource()
                    IconButton(onClick = onImport, interactionSource = srcImp, modifier = pressImp, enabled = !importing) {
                        Icon(Icons.Filled.SystemUpdate, "导入实例包(zip)")
                    }
                }
            }

            // ── 实例列表 / 空状态 ──
            if (instances.isEmpty()) {
                item {
                    Box(
                        Modifier.fillMaxWidth().height(280.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("还没有服务端实例", style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(8.dp))
                            Text("点击右下角 + 新建:选择核心类型与 MC 版本,自动下载",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else {
                items(instances, key = { it.id }) { instance ->
                    InstanceCard(
                        instance,
                        onClick = { onOpen(instance) },
                        onLongClick = { onMenu(instance) },
                        onStart = { onStart(instance) },
                        onStop = { onStop(instance) }
                    )
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
//  弧形仪表盘头部:半圆弧 + 资源环 + 脉冲光圈
// ═══════════════════════════════════════════════════════════

@Composable
private fun DashboardHeader(
    status: InstanceStatus,
    instanceCount: Int,
    playerCount: Int,
    uptimeSec: Long,
    envReady: Boolean
) {
    val isRunning = status == InstanceStatus.RUNNING
    val isStarting = status == InstanceStatus.STARTING

    // 仪表盘进度:运行中=满,启动中=动画感,其他=空
    val arcProgress = when (status) {
        InstanceStatus.RUNNING -> 1f
        InstanceStatus.STARTING -> 0.6f
        InstanceStatus.STOPPING -> 0.3f
        else -> 0f
    }

    // 状态色
    val statusColor = when (status) {
        InstanceStatus.RUNNING -> KazeSuccess
        InstanceStatus.STARTING, InstanceStatus.STOPPING -> KazeWarning
        InstanceStatus.ERROR -> KazeError
        InstanceStatus.STOPPED -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(20.dp)) {
                // ── 顶部:弧形仪表盘 + 状态信息 ──
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(140.dp),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    // 脉冲光圈(运行中时)
                    if (isRunning) {
                        PulseGlow(
                            modifier = Modifier.size(160.dp).align(Alignment.Center),
                            color = statusColor,
                            active = true
                        )
                    }
                    // 弧形仪表盘
                    ArcDashboard(
                        progress = arcProgress,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        gradient = DashboardGradient,
                        backgroundColor = MaterialTheme.colorScheme.surfaceVariant,
                        strokeWidth = 14.dp
                    )
                    // 中央状态文字
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        Text(
                            when (status) {
                                InstanceStatus.RUNNING -> "运行中"
                                InstanceStatus.STARTING -> "启动中"
                                InstanceStatus.STOPPING -> "停止中"
                                InstanceStatus.ERROR -> "错误"
                                InstanceStatus.STOPPED -> "已停止"
                            },
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                            color = statusColor
                        )
                        if (isRunning) {
                            Text(
                                "${playerCount} 人在线 · ${uptimeSec / 60}m ${uptimeSec % 60}s",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // ── 资源环行:实例数 / 环境 / 状态 ──
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    // 实例数
                    ResourceRingItem(
                        icon = Icons.Filled.Memory,
                        label = "实例",
                        value = "$instanceCount",
                        progress = if (instanceCount == 0) 0f else (instanceCount.toFloat() / 10f).coerceIn(0f, 1f),
                        color = KazeCyan
                    )
                    // 环境状态
                    ResourceRingItem(
                        icon = Icons.Filled.Speed,
                        label = "环境",
                        value = if (envReady) "就绪" else "未部署",
                        progress = if (envReady) 1f else 0f,
                        color = if (envReady) KazeSuccess else KazeError
                    )
                    // 服务器状态
                    ResourceRingItem(
                        icon = Icons.Filled.PlayArrow,
                        label = "服务",
                        value = when (status) {
                            InstanceStatus.RUNNING -> "在线"
                            InstanceStatus.STARTING -> "启动"
                            InstanceStatus.STOPPING -> "停止"
                            InstanceStatus.ERROR -> "错误"
                            InstanceStatus.STOPPED -> "离线"
                        },
                        progress = arcProgress,
                        color = statusColor
                    )
                }
            }
        }
    }
}

/** 资源环项:图标 + 环 + 数值 + 标签 */
@Composable
private fun ResourceRingItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    progress: Float,
    color: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(64.dp)) {
            ResourceRing(
                progress = progress,
                modifier = Modifier.fillMaxSize(),
                color = color,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                strokeWidth = 5.dp
            )
            Icon(icon, null, Modifier.size(24.dp), tint = color)
        }
        Spacer(Modifier.height(4.dp))
        Text(value, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface)
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ═══════════════════════════════════════════════════════════
//  实例卡片:渐变徽标 + 状态指示 + 启停按钮
// ═══════════════════════════════════════════════════════════

@androidx.compose.foundation.ExperimentalFoundationApi
@Composable
private fun InstanceCard(instance: ServerInstance, onClick: () -> Unit, onLongClick: () -> Unit, onStart: () -> Unit, onStop: () -> Unit) {
    val status by com.mcserver.launcher.core.server.ServerManager.status.collectAsState()
    val running = com.mcserver.launcher.core.server.ServerManager.isRunningFor(instance.id)
    val interaction = remember { MutableInteractionSource() }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .pressScale(interaction)
            .combinedClickable(
                interactionSource = interaction,
                indication = ripple(),
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 核心类型渐变徽标(FCL 式)
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(instance.coreType.badgeGradient()),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        instance.coreType.badgeLetter(),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(instance.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${instance.coreType.displayName} ${instance.mcVersion}${if (instance.buildId.isNotBlank()) " (build ${instance.buildId})" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // 脉冲状态点(运行中)
                if (running && status == InstanceStatus.RUNNING) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(16.dp)) {
                        PulseGlow(
                            modifier = Modifier.size(16.dp),
                            color = KazeSuccess,
                            active = true
                        )
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(RoundedCornerShape(50))
                                .background(KazeSuccess)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                }
                StatusBadge(if (running) status else InstanceStatus.STOPPED)
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("端口 ${instance.config.serverPort} · 最多 ${instance.config.maxPlayers} 人",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                if (running) {
                    Button(onClick = onStop, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                        Icon(Icons.Filled.Stop, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("停止")
                    }
                } else {
                    Button(onClick = onStart) {
                        Icon(Icons.Filled.PlayArrow, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("启动")
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(status: InstanceStatus) {
    val (text, color) = when (status) {
        InstanceStatus.RUNNING -> "运行中" to KazeSuccess
        InstanceStatus.STARTING -> "启动中" to KazeWarning
        InstanceStatus.STOPPING -> "停止中" to KazeWarning
        InstanceStatus.ERROR -> "错误" to MaterialTheme.colorScheme.error
        InstanceStatus.STOPPED -> "已停止" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.15f)) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            color = color, style = MaterialTheme.typography.labelSmall)
    }
}
