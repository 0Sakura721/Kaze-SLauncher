package com.kaze.newage.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kaze.newage.core.server.ServerState
import com.kaze.newage.data.model.CoreCategory
import com.kaze.newage.data.model.ServerInstance
import com.kaze.newage.ui.AppViewModel
import com.kaze.newage.ui.components.InstanceIcon
import com.kaze.newage.ui.isBusy
import com.kaze.newage.ui.theme.cardBorderColor
import com.kaze.newage.ui.theme.itemColor
import com.kaze.newage.ui.theme.statusPalette
import com.kaze.newage.ui.toLabel

/**
 * 服务端管理：整页大卡 + 顶部分类条带 + 单选列表。
 * 版式 1:1 移植 ZalithLauncher2 VersionsManageScreen（GPL-3.0）：
 * - 全页 BackgroundCard 作为唯一画布；
 * - CardTitleLayout 位 = 横向滚动工具条（新建/导入 + 分类 chip 带数量）；
 * - 列表项 VersionItemLayout：RadioButton 单选当前实例 + 图标 + 名称/摘要跑马灯 +
 *   FlowRow 信息行(alpha 0.7) + 启停按钮 + ⋮ 菜单 + 入场缩放。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerScreen(
    viewModel: AppViewModel,
    onOpenInstance: (ServerInstance) -> Unit,
    onNewServer: () -> Unit,
) {
    val instances by viewModel.instances.collectAsState()
    val states by viewModel.serverStates.collectAsState()
    val currentInstanceId by viewModel.currentInstanceId.collectAsState()
    val appContext = LocalContext.current.applicationContext

    // 分类筛选（Zalith VersionCategory：全部/官方/性能优化/模组加载）
    var category by remember { mutableStateOf<CoreCategory?>(null) }
    val filtered = remember(instances, category) {
        if (category == null) instances
        else instances.filter { it.coreType.category == category }
    }
    fun countOf(cat: CoreCategory?): Int =
        if (cat == null) instances.size else instances.count { it.coreType.category == cat }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val name = "导入-${System.currentTimeMillis() % 10000}"
            val dir = viewModel.instanceStore.createInstanceDir(name)
            val target = java.io.File(dir, "server.jar")
            try {
                appContext.contentResolver.openInputStream(uri)?.use { ins ->
                    target.outputStream().use { outs -> ins.copyTo(outs) }
                }
                viewModel.importJar(target, name, javaMajor = 17, memoryMb = 1024)
            } catch (_: Exception) { }
        }
    }

    // 内容直接铺在背景上（无整页大卡框架）；列表项/工具条为小型玻璃元素
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        // ── 顶部工具条（横向滚动）──
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolChip(Icons.Filled.Add, "新建", onClick = onNewServer)
            ToolChip(Icons.Filled.FileOpen, "导入 jar", onClick = {
                importLauncher.launch(arrayOf("application/java-archive", "application/octet-stream", "*/*"))
            })
            CategoryChip("全部", countOf(null), selected = category == null) { category = null }
            CategoryChip("官方", countOf(CoreCategory.OFFICIAL), selected = category == CoreCategory.OFFICIAL) {
                category = CoreCategory.OFFICIAL
            }
            CategoryChip("性能", countOf(CoreCategory.OPTIMIZED), selected = category == CoreCategory.OPTIMIZED) {
                category = CoreCategory.OPTIMIZED
            }
            CategoryChip("模组", countOf(CoreCategory.MODDED), selected = category == CoreCategory.MODDED) {
                category = CoreCategory.MODDED
            }
        }

        if (filtered.isEmpty()) {
            // 空态：居中文字
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("没有服务端", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "点上方「新建」下载 Vanilla / Paper 服务端，\n或「导入 jar」添加已有的 server.jar",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(filtered, key = { it.id }) { instance ->
                    val state = states[instance.id] ?: ServerState.Idle
                        InstanceCard(
                            instance = instance,
                            selected = instance.id == currentInstanceId,
                            state = state,
                            onSelect = {
                                viewModel.selectInstance(instance)
                                onOpenInstance(instance)
                            },
                            onStart = { viewModel.startInstance(instance) },
                            onStop = { viewModel.stopInstance(instance) },
                            onDelete = { viewModel.removeInstance(instance) },
                        )
                    }
                }
            }
        }
}

/** 工具条按钮（Zalith IconTextButton 简化版） */
@Composable
private fun ToolChip(icon: ImageVector, label: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clip(RoundedCornerShape(50)),
        shape = RoundedCornerShape(50),
        color = itemColor(),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        border = androidx.compose.foundation.BorderStroke(1.dp, cardBorderColor()),
        onClick = onClick,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** 分类 chip 带数量（Zalith VersionCategoryItem：TextRailItem 风格「标签 (N)」） */
@Composable
private fun CategoryChip(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(50)
    Surface(
        modifier = Modifier.clip(shape),
        shape = shape,
        color = if (selected) MaterialTheme.colorScheme.primary else itemColor(),
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        border = if (selected) null else androidx.compose.foundation.BorderStroke(1.dp, cardBorderColor()),
        onClick = onClick,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text("($count)", style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** 实例列表项（Zalith VersionItemLayout 移植：单选 + 图标 + 信息 + 动作） */
@Composable
private fun InstanceCard(
    instance: ServerInstance,
    selected: Boolean,
    state: ServerState,
    onSelect: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onDelete: () -> Unit,
) {
    // 入场缩放动画（Zalith VersionItemLayout 模式）
    val scale = remember { Animatable(0.95f) }
    LaunchedEffect(Unit) {
        scale.animateTo(1f, animationSpec = tween(220))
    }

    val running = state == ServerState.Running
    val busy = state.isBusy()
    val palette = statusPalette()
    val dotColor = when {
        running -> palette.running
        busy -> palette.busy
        state == ServerState.Error -> palette.error
        else -> palette.idle
    }
    val statusText = when {
        running -> "运行中"
        busy -> state.toLabel()
        state == ServerState.Error -> "启动失败"
        else -> "已停止"
    }

    val context = LocalContext.current
    var menuExpanded by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            },
        shape = MaterialTheme.shapes.large,
        color = itemColor(),
        contentColor = MaterialTheme.colorScheme.onSurface,
        onClick = onSelect,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 当前实例单选（Zalith：RadioButton）
            RadioButton(selected = selected, onClick = onSelect)

            InstanceIcon(instance.coreType, Modifier.size(34.dp))
            Spacer(Modifier.width(8.dp))

            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = instance.name,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                )
                Text(
                    text = "MC ${instance.mcVersion.ifBlank { "自定义" }} · ${instance.coreType.displayName}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                )
                // Zalith FlowRow 信息行（alpha 0.7 + 状态点）
                Row(
                    Modifier.alpha(0.7f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Java ${instance.javaMajor}", style = MaterialTheme.typography.labelSmall)
                    Text("${instance.memoryMb} MB", style = MaterialTheme.typography.labelSmall)
                    Box(Modifier.size(6.dp).clip(CircleShape).background(dotColor))
                    Text(statusText, style = MaterialTheme.typography.labelSmall, color = dotColor)
                    if (!instance.eulaFile.exists() && !running) {
                        Text(
                            "EULA 未接受",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // 右侧动作：启动/停止 + ⋮ 菜单（Zalith 动作列）
            if (running) {
                FilledIconButton(onClick = onStop, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Stop, contentDescription = "停止", modifier = Modifier.size(18.dp))
                }
            } else {
                FilledIconButton(onClick = onStart, enabled = !busy, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "启动", modifier = Modifier.size(18.dp))
                }
            }
            IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = "更多",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                shape = RoundedCornerShape(16.dp),
            ) {
                DropdownMenuItem(
                    text = { Text("打开实例目录") },
                    leadingIcon = { Icon(Icons.Filled.FolderOpen, null, Modifier.size(20.dp)) },
                    onClick = {
                        menuExpanded = false
                        try {
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(Uri.fromFile(instance.dir), "resource/folder")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        } catch (_: Exception) { }
                    },
                )
                DropdownMenuItem(
                    text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = {
                        Icon(
                            Icons.Filled.Delete,
                            null,
                            Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onDelete()
                    },
                )
            }
        }
    }
}
