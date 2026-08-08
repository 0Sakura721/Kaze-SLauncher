package com.mcserver.launcher.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcserver.launcher.core.env.EnvManager
import com.mcserver.launcher.core.server.ExportManager
import com.mcserver.launcher.core.server.InstanceStore
import com.mcserver.launcher.core.server.ServerManager
import com.mcserver.launcher.data.InstanceStatus
import com.mcserver.launcher.data.ServerInstance
import com.mcserver.launcher.ui.components.CompactGroup
import com.mcserver.launcher.ui.components.EmptyState
import com.mcserver.launcher.ui.components.LocalUiMessenger
import com.mcserver.launcher.ui.components.PageTransition
import com.mcserver.launcher.ui.components.RowItemDivider
import com.mcserver.launcher.ui.components.SectionHeader
import com.mcserver.launcher.ui.screens.home.DashboardHeader
import com.mcserver.launcher.ui.theme.badgeColor
import com.mcserver.launcher.ui.theme.badgeLetter
import com.mcserver.launcher.ui.theme.KazeCorners
import com.mcserver.launcher.ui.theme.KazeMotion
import com.mcserver.launcher.ui.theme.KazeSizes
import com.mcserver.launcher.ui.theme.KazeSpacing
import com.mcserver.launcher.ui.theme.KazeSuccess
import com.mcserver.launcher.ui.theme.KazeWarning
import com.mcserver.launcher.util.FileImporter
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    onGotoSettings: () -> Unit = {}
) {
    val context = LocalContext.current
    val messenger = LocalUiMessenger.current
    val scope = rememberCoroutineScope()
    val instances by InstanceStore.instances.collectAsState()
    val envReady = EnvManager.isEnvironmentReady()
    val serverStatus by ServerManager.status.collectAsState()
    val players by ServerManager.players.collectAsState()
    val uptime by ServerManager.uptimeSec.collectAsState()
    var showNew by remember { mutableStateOf(false) }
    var selectedInstance by remember { mutableStateOf<ServerInstance?>(null) }
    var menuInstance by remember { mutableStateOf<ServerInstance?>(null) }
    var importing by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            importing = true
            scope.launch {
                val tmp = File(context.filesDir, "import_instance")
                if (tmp.exists()) tmp.deleteRecursively()
                FileImporter.copyFile(context, uri, tmp)
                    .onSuccess { zipFile ->
                        val inst = ExportManager.importInstance(zipFile)
                        tmp.deleteRecursively()
                        importing = false
                        if (inst != null) messenger.toastSuccess("实例导入完成:${inst.name}")
                        else messenger.toastError("导入失败:无法解析实例包")
                    }
                    .onFailure { err ->
                        importing = false
                        messenger.toastError("导入失败:${err.message}")
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

    BackHandler(enabled = selectedInstance != null) { selectedInstance = null }
    BackHandler(enabled = showNew) { showNew = false }

    PageTransition(navTarget, modifier) { target ->
        when (target) {
            1 -> current?.let { detail ->
                InstanceDetailScreen(instance = detail, onBack = { selectedInstance = null })
            }
            2 -> NewInstanceScreen(onDone = { showNew = false })
            else -> HomeContent(
                instances = instances,
                envReady = envReady,
                serverStatus = serverStatus,
                playerCount = players.size,
                uptimeSec = uptime,
                importing = importing,
                onGotoSettings = onGotoSettings,
                onNew = { showNew = true },
                onImport = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
                onOpen = { selectedInstance = it },
                onMenu = { menuInstance = it },
                onStart = { inst ->
                    scope.launch {
                        val result = ServerManager.start(inst)
                        if (result.isFailure) messenger.toastError(result.exceptionOrNull()?.message ?: "启动失败")
                    }
                },
                onStop = { scope.launch { ServerManager.stop() } }
            )
        }
    }

    LongPressMenu(
        menuInstance = menuInstance,
        onDismiss = { menuInstance = null },
        onOpen = { menuInstance = null; selectedInstance = it },
        onExport = { target ->
            menuInstance = null
            scope.launch {
                val name = target.name.replace(Regex("[^\\w\\u4e00-\\u9fa5-]"), "_").take(30)
                val f = File(context.getExternalFilesDir(null), "exports")
                f.mkdirs()
                val dest = File(f, "$name-${System.currentTimeMillis().toString().takeLast(6)}.zip")
                val ok = ExportManager.exportInstance(target, dest)
                messenger.toast(
                    if (ok) "已导出:${dest.absolutePath}" else "导出失败",
                    long = true
                )
            }
        },
        onDelete = { target ->
            menuInstance = null
            scope.launch {
                if (ServerManager.isRunningFor(target.id)) {
                    messenger.toast("服务器运行中,请先停止")
                    return@launch
                }
                InstanceStore.delete(target.id)
                messenger.toastSuccess("已删除 ${target.name}")
            }
        }
    )
}

@Composable
private fun HomeContent(
    instances: List<ServerInstance>,
    envReady: Boolean,
    serverStatus: InstanceStatus,
    playerCount: Int,
    uptimeSec: Long,
    importing: Boolean,
    onGotoSettings: () -> Unit,
    onNew: () -> Unit,
    onImport: () -> Unit,
    onOpen: (ServerInstance) -> Unit,
    onMenu: (ServerInstance) -> Unit,
    onStart: (ServerInstance) -> Unit,
    onStop: (ServerInstance) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filteredInstances = remember(instances, query) {
        val q = query.trim()
        if (q.isEmpty()) instances
        else instances.filter {
            it.name.contains(q, ignoreCase = true) ||
                it.coreType.displayName.contains(q, ignoreCase = true) ||
                it.coreType.name.contains(q, ignoreCase = true) ||
                it.mcVersion.contains(q, ignoreCase = true)
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = KazeSpacing.sm,
                bottom = KazeSpacing.xxxl + KazeSizes.buttonHeight + KazeSpacing.lg
            )
        ) {
            item {
                CompactTopBar(
                    instanceCount = instances.size,
                    envReady = envReady,
                    status = serverStatus,
                    playerCount = playerCount,
                    uptimeSec = uptimeSec
                )
            }

            if (!envReady) {
                item {
                    EnvNotReadyBanner(onGotoSettings = onGotoSettings)
                }
                item { Spacer(Modifier.height(KazeSpacing.sm)) }
            }

            if (importing) {
                item {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = KazeSpacing.pageHorizontal, vertical = KazeSpacing.xs)
                    )
                }
            }

            item {
                SectionHeader(
                    title = "实例",
                    count = instances.size,
                    subtitle = "选择实例管理你的服务器",
                    trailing = {
                        if (instances.isNotEmpty()) {
                            FilledTonalButton(
                                onClick = onImport,
                                enabled = !importing,
                                contentPadding = PaddingValues(horizontal = KazeSpacing.md, vertical = 2.dp)
                            ) {
                                Icon(Icons.Filled.SystemUpdate, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(KazeSpacing.xxs))
                                Text("导入", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                )
            }

            if (instances.isEmpty()) {
                item {
                    EmptyState(
                        title = "暂无实例",
                        description = "点击右下角新建实例，选择核心类型与 MC 版本，快速创建服务器",
                        icon = Icons.Filled.Add,
                        action = {
                            ExtendedFloatingActionButton(
                                onClick = onNew,
                                icon = { Icon(Icons.Filled.Add, null) },
                                text = { Text("新建实例") }
                            )
                        }
                    )
                }
            } else {
                item {
                    CompactGroup {
                        InstanceSearchField(
                            query = query,
                            onQueryChange = { query = it },
                            resultCount = filteredInstances.size,
                            totalCount = instances.size
                        )
                        if (filteredInstances.isNotEmpty()) {
                            RowItemDivider(indent = KazeSpacing.xxxl)
                            filteredInstances.forEachIndexed { idx, instance ->
                                InstanceCardRow(
                                    instance = instance,
                                    onClick = { onOpen(instance) },
                                    onLongClick = { onMenu(instance) },
                                    onStart = { onStart(instance) },
                                    onStop = { onStop(instance) }
                                )
                                if (idx < filteredInstances.size - 1) {
                                    RowItemDivider(indent = KazeSpacing.xxxl)
                                }
                            }
                        }
                    }
                    if (filteredInstances.isEmpty()) {
                        EmptyState(
                            title = "无匹配实例",
                            description = "没有找到含「$query」的实例,换个关键词试试",
                            icon = Icons.Filled.Search
                        )
                    }
                }
                item { Spacer(Modifier.height(KazeSpacing.sm)) }
            }
        }

        ExtendedFloatingActionButton(
            text = { Text("新建实例") },
            icon = { Icon(Icons.Filled.Add, contentDescription = "新建实例") },
            onClick = onNew,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = KazeSpacing.pageHorizontal, bottom = KazeSpacing.lg)
        )
    }
}

@Composable
private fun CompactTopBar(
    instanceCount: Int,
    envReady: Boolean,
    status: InstanceStatus,
    playerCount: Int,
    uptimeSec: Long
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = KazeSpacing.pageHorizontal, vertical = KazeSpacing.sm)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Kaze 服务端",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.width(KazeSpacing.sm))
            val statusColor = when (status) {
                InstanceStatus.RUNNING -> KazeSuccess
                InstanceStatus.STARTING, InstanceStatus.STOPPING -> KazeWarning
                InstanceStatus.ERROR -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Box(
                Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(statusColor)
            )
            Spacer(Modifier.width(KazeSpacing.xs))
            Text(
                when (status) {
                    InstanceStatus.RUNNING -> "运行中"
                    InstanceStatus.STARTING -> "启动中"
                    InstanceStatus.STOPPING -> "停止中"
                    InstanceStatus.ERROR -> "错误"
                    else -> "待机"
                },
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                color = statusColor
            )
        }

        Spacer(Modifier.height(KazeSpacing.sm))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(KazeSpacing.xs)
        ) {
            StatChip(label = "实例", value = "$instanceCount")
            StatChip(label = "在线", value = if (status == InstanceStatus.RUNNING) "$playerCount" else "-")
            StatChip(
                label = "Java",
                value = if (envReady) "就绪" else "待配",
                color = if (envReady) KazeSuccess else KazeWarning
            )
            StatChip(
                label = "时长",
                value = formatUptime(uptimeSec)
            )
        }
    }
}

@Composable
private fun StatChip(
    label: String,
    value: String,
    color: Color = MaterialTheme.colorScheme.primary
) {
    Row(
        Modifier
            .height(KazeSizes.compactButtonHeight)
            .clip(KazeCorners.tiny)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
            .padding(horizontal = KazeSpacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.width(KazeSpacing.xxs))
        Text(
            value,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun InstanceSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    resultCount: Int,
    totalCount: Int
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(KazeSpacing.searchFieldH)
            .padding(horizontal = KazeSpacing.rowHorizPad),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Search, null,
            Modifier.size(KazeSizes.iconSmall),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(KazeSpacing.sm))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    "搜索实例名称 / 核心 / 版本",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
            androidx.compose.foundation.text.BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface
                )
            )
        }
        if (query.isNotEmpty()) {
            if (resultCount < totalCount) {
                Text(
                    "$resultCount/$totalCount",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(KazeSpacing.xs))
            }
            IconButton(
                onClick = { onQueryChange("") },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(Icons.Filled.Close, "清除",
                    Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun EnvNotReadyBanner(onGotoSettings: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = KazeSpacing.pageHorizontal, vertical = KazeSpacing.xs)
            .clip(KazeCorners.medium)
            .background(KazeWarning.copy(alpha = 0.1f))
            .border(
                KazeSizes.strokeThin,
                KazeWarning.copy(alpha = 0.3f),
                KazeCorners.medium
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onGotoSettings
            )
            .padding(horizontal = KazeSpacing.md, vertical = KazeSpacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.Info,
            null,
            Modifier.size(20.dp),
            tint = KazeWarning
        )
        Spacer(Modifier.width(KazeSpacing.sm))
        Column(Modifier.weight(1f)) {
            Text(
                "需要 Java 运行时",
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                "点击前往设置下载或导入 JDK",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = onGotoSettings) {
            Text("设置", style = MaterialTheme.typography.labelLarge, color = KazeWarning)
        }
    }
}

private fun formatUptime(sec: Long): String = when {
    sec <= 0 -> "-"
    sec < 3600 -> "${sec / 60}分"
    else -> "${sec / 3600}时${(sec % 3600) / 60}分"
}

@Composable
private fun LongPressMenu(
    menuInstance: ServerInstance?,
    onDismiss: () -> Unit,
    onOpen: (ServerInstance) -> Unit,
    onExport: (ServerInstance) -> Unit,
    onDelete: (ServerInstance) -> Unit
) {
    val target = menuInstance ?: return
    DropdownMenu(
        expanded = true,
        onDismissRequest = onDismiss,
        shape = KazeCorners.medium,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        DropdownMenuItem(
            text = { Text("打开", style = MaterialTheme.typography.bodyLarge) },
            leadingIcon = { Icon(Icons.Filled.FolderOpen, null, Modifier.size(20.dp)) },
            onClick = { onOpen(target) }
        )
        DropdownMenuItem(
            text = { Text("导出备份", style = MaterialTheme.typography.bodyLarge) },
            leadingIcon = { Icon(Icons.Filled.Share, null, Modifier.size(20.dp)) },
            onClick = { onExport(target) }
        )
        DropdownMenuItem(
            text = {
                Text(
                    "删除实例",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyLarge
                )
            },
            leadingIcon = {
                Icon(Icons.Filled.Delete, null, Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.error)
            },
            onClick = { onDelete(target) }
        )
    }
}

// ═══════════════════════════════════════════════════════════════
//  实例行卡片(紧凑列表版)——放在 CompactGroup 分组容器内使用
//  无独立背景/边框,仅由分组外框提供边界
// ═══════════════════════════════════════════════════════════════

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InstanceCardRow(
    instance: ServerInstance,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val status by ServerManager.status.collectAsState()
    val running = ServerManager.isRunningFor(instance.id)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.99f else 1f,
        spring(KazeMotion.springDamping, KazeMotion.springStiff),
        label = "rowScale"
    )

    Row(
        Modifier
            .fillMaxWidth()
            .height(KazeSpacing.rowItemH)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = KazeSpacing.rowHorizPad),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左侧:统一尺寸小徽标
        Box(
            Modifier
                .size(KazeSizes.badgeSmall)
                .clip(KazeCorners.tiny)
                .background(instance.coreType.badgeColor()),
            contentAlignment = Alignment.Center
        ) {
            Text(
                instance.coreType.badgeLetter(),
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White
            )
        }
        Spacer(Modifier.width(KazeSpacing.md))

        // 中间:名称 + 副信息
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    instance.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.width(KazeSpacing.xs))
                if (running && status == InstanceStatus.RUNNING) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(RoundedCornerShape(50))
                            .background(KazeSuccess)
                    )
                }
            }
            Spacer(Modifier.height(KazeSpacing.xxs))
            Text(
                "${instance.coreType.displayName} ${instance.mcVersion} · :${instance.config.serverPort}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(KazeSpacing.sm))

        // 右侧:启停方形按钮(40dp,紧凑)
        val actionSize = 40.dp
        if (running) {
            Box(
                Modifier
                    .size(actionSize)
                    .clip(KazeCorners.tiny)
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f))
                    .clickable(onClick = onStop),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Stop, "停止",
                    Modifier.size(KazeSizes.iconSmall),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        } else {
            Box(
                Modifier
                    .size(actionSize)
                    .clip(KazeCorners.tiny)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                    .clickable(onClick = onStart),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.PlayArrow, "启动",
                    Modifier.size(KazeSizes.iconSmall),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
