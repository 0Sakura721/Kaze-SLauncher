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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowRight
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.kaze.newage.core.server.ServerState
import com.kaze.newage.data.model.CoreCategory
import com.kaze.newage.data.model.ServerInstance
import com.kaze.newage.ui.AppViewModel
import com.kaze.newage.ui.components.InstanceIcon
import com.kaze.newage.ui.isBusy
import com.kaze.newage.ui.theme.cardBorderColor
import com.kaze.newage.ui.theme.itemColor
import com.kaze.newage.ui.theme.serverItemBorderColor
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
    val instances by viewModel.instances.collectAsStateWithLifecycle()
    val states by viewModel.serverStates.collectAsStateWithLifecycle()
    val currentInstanceId by viewModel.currentInstanceId.collectAsStateWithLifecycle()
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
        // 复制挪到 ViewModel 的 IO 协程里：主线程同步拷贝几十 MB 的 jar 会 ANR，
        // 而且原来的 `catch (_: Exception) { }` 会把失败全吞掉（用户点完毫无反应）。
        uri?.let {
            viewModel.importJar(
                uri = it,
                name = "导入-" + java.text.SimpleDateFormat("MMdd-HHmmss", java.util.Locale.US)
                    .format(java.util.Date()),
                memoryMb = 1024,
            )
        }
    }

    // 内容直接铺在背景上（无整页大卡框架）；列表项/工具条为小型玻璃元素
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp)
            // 底部空白承载常驻栏：实例列表滚动中透过底栏玻璃，滚到底最后一项不被遮挡
            .padding(bottom = 96.dp)
    ) {
        // ── 顶部工具条（横向滚动）──
        // 窄屏上 chip 会排不下（340dp 时最后一个会被直接裁掉，用户看不出还能滑）。
        // 右侧加一个可点的"更多"指示：比渐隐更稳——页面背景是图片，渐隐色对不上。
        val chipsScroll = rememberScrollState()
        val scope = rememberCoroutineScope()
        val canScrollRight by remember {
            derivedStateOf { chipsScroll.value < chipsScroll.maxValue }
        }
        Box(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(chipsScroll)
                    // 右侧留出指示按钮的位置，滑动到底时最后一个 chip 不会被按钮压住
                    .padding(vertical = 10.dp, horizontal = 0.dp)
                    .padding(end = if (canScrollRight) 34.dp else 0.dp),
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
            if (canScrollRight) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .size(28.dp),
                    onClick = { scope.launch { chipsScroll.animateScrollTo(chipsScroll.maxValue) } },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.KeyboardArrowRight,
                            contentDescription = "还有更多筛选项",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
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
    // 删除实例必须二次确认：会连目录内全部世界数据一起删掉，一次误触即丢档不可恢复
    var confirmDelete by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
            // 无障碍：整卡是一个单选项目。此前只有 Surface(onClick)（读作"按钮"），
            // TalkBack 读不出"已选中"，也说不清这是单选列表。
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onSelect,
            ),
        shape = MaterialTheme.shapes.large,
        color = itemColor(),
        contentColor = MaterialTheme.colorScheme.onSurface,
        // 每个服务器项带主题对应的可见边框（选中=主色粗框）
        border = androidx.compose.foundation.BorderStroke(
            if (selected) 2.dp else 1.dp,
            serverItemBorderColor(selected),
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 当前实例单选（Zalith：RadioButton）。
            // onClick = null：整卡的 selectable 已是唯一触控目标，圆圈只作指示，
            // 否则同一个动作会有两个可点区域与两套语义。
            RadioButton(selected = selected, onClick = null)

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
                // Zalith 信息行 + 状态胶囊：FlowRow 自动换行——
                // 元素多时（Java/内存/状态/EULA）宁可整体换行，不能把文字压成竖排。
                // 注意：状态与 EULA 不再跟随整行降透明度——它们是需要一眼看到的信息
                // （旧实现把状态点/状态字放在 alpha 0.7 的行里，真机上几乎看不清）。
                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val metaColor = MaterialTheme.colorScheme.onSurfaceVariant
                    Text(
                        "Java ${instance.javaMajor}",
                        style = MaterialTheme.typography.labelSmall,
                        color = metaColor,
                    )
                    Text(
                        "${instance.memoryMb} MB",
                        style = MaterialTheme.typography.labelSmall,
                        color = metaColor,
                    )
                    StatusBadge(statusText, dotColor)
                    if (!instance.eulaFile.exists() && !running) EulaWarningChip()
                }
            }

            // 右侧动作：启动/停止 + ⋮ 菜单（Zalith 动作列）
            // 不再写死 size(36/32)：那会把 M3 的 48dp 触控区一起缩小，容易误触
            if (running) {
                FilledIconButton(onClick = onStop) {
                    Icon(Icons.Filled.Stop, contentDescription = "停止", modifier = Modifier.size(18.dp))
                }
            } else {
                FilledIconButton(onClick = onStart, enabled = !busy) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "启动", modifier = Modifier.size(18.dp))
                }
            }
            IconButton(onClick = { menuExpanded = true }) {
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
                        confirmDelete = true
                    },
                )
            }
        }

        // 删除确认弹窗
        if (confirmDelete) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text("删除「${instance.name}」？") },
                text = {
                    Text("将永久删除实例目录及其全部备份（世界存档、配置、插件/模组）。此操作不可恢复。")
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(
                        onClick = {
                            confirmDelete = false
                            onDelete()
                        }
                    ) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { confirmDelete = false }) {
                        Text("取消")
                    }
                },
            )
        }
    }
}

/**
 * 状态胶囊：带底色的圆角标签（点 + 文案同色）。
 * 旧实现只是混在降透明度信息行里的一个小圆点，实际观感是"看不清的状态"。
 */
@Composable
private fun StatusBadge(text: String, color: Color) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Text(text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

/** EULA 未接受：这是一项需要用户动作的关键信息，用警示色 + 图标点出来，而不是普通灰字 */
@Composable
private fun EulaWarningChip() {
    val amber = Color(0xFFE0A02B)
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(amber.copy(alpha = 0.18f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            Icons.Filled.ErrorOutline,
            contentDescription = null,
            tint = amber,
            modifier = Modifier.size(12.dp),
        )
        Text("EULA 未接受", style = MaterialTheme.typography.labelSmall, color = amber)
    }
}
