package com.kaze.newage.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaze.newage.core.env.ProotEnvironment
import com.kaze.newage.core.server.ServerState
import com.kaze.newage.ui.AppViewModel
import com.kaze.newage.ui.Dest
import com.kaze.newage.ui.components.ExpressiveLoadingGlyph
import com.kaze.newage.ui.components.ExpressiveLoadingIndicator
import com.kaze.newage.ui.components.InstanceIcon
import com.kaze.newage.ui.components.M3ECard
import com.kaze.newage.ui.components.M3ECardVariant
import com.kaze.newage.ui.components.M3EListItem
import com.kaze.newage.ui.components.M3EStatusChip
import com.kaze.newage.ui.components.StatusTone
import com.kaze.newage.ui.components.WavyLinearProgress
import com.kaze.newage.ui.formatUptime
import com.kaze.newage.ui.isBusy
import com.kaze.newage.ui.theme.M3Shape
import com.kaze.newage.ui.theme.M3Spacing
import com.kaze.newage.ui.theme.statusPalette
import com.kaze.newage.ui.toLabel
import com.kaze.newage.ui.toTone

/**
 * 主页 —— 落地屏。
 *
 * 版式来自 m3e-canvas 生成的 `docs/m3e/prompt-主页.md`：
 *   状态指示 + 状态胶囊 → 当前实例卡（卡片本身即选择器）→ 启动/停止相连按钮组
 *   → 运行时长卡 → 环境状态行（部署中变成波浪进度）→ 一排次要动作图标按钮
 *
 * 与旧版的差别：
 *  - 状态球换成 M3 Expressive 的形状变化加载指示器，它同时承担「生命体征」：
 *    运行中常速变形、启动中加速、停止时定格成单个形状
 *  - 实例选择从「下拉菜单」改成「点卡片展开列表」——卡片本身就是控件
 *  - 「运行概况」卡不再单独存在，在线人数/内存并进实例卡的正文
 *  - 主行动按钮用 connected button group（外角全圆、内角 8dp）
 *  - 部署进度条换成官方波浪形进度条
 */
@Composable
fun HomeScreen(
    viewModel: AppViewModel,
    onNavigate: (String) -> Unit,
    onNewServer: () -> Unit,
) {
    val envState by viewModel.envState.collectAsStateWithLifecycle()
    val javaVersions by viewModel.envJavaVersions.collectAsStateWithLifecycle()
    // 部署进度走独立状态：与核心下载分开后，部署中进新建向导不会再串进度、下载中点部署也有反馈
    val download by viewModel.envTask.collectAsStateWithLifecycle()
    val serverState by viewModel.serverState.collectAsStateWithLifecycle()
    val instances by viewModel.instances.collectAsStateWithLifecycle()
    val currentInstanceId by viewModel.currentInstanceId.collectAsStateWithLifecycle()
    val uptime by viewModel.uptimeSec.collectAsStateWithLifecycle()
    val onlinePlayers by viewModel.onlinePlayers.collectAsStateWithLifecycle()

    val current = instances.firstOrNull { it.id == currentInstanceId } ?: instances.firstOrNull()
    val tone = serverState.toTone()
    val palette = statusPalette()
    val stateColor = when (tone) {
        StatusTone.Running -> palette.running
        StatusTone.Busy -> palette.busy
        StatusTone.Idle -> palette.idle
        StatusTone.Error -> palette.error
    }
    val busy = serverState.isBusy()
    val running = serverState == ServerState.Running
    // LocalContext 必须在组合期取好：点击回调不是 @Composable，里面不能读 CompositionLocal
    val context = LocalContext.current

    var listOpen by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = M3Spacing.screenMargin)
            .padding(top = 12.dp, bottom = M3Spacing.bottomBarSpace),
        verticalArrangement = Arrangement.spacedBy(M3Spacing.betweenParts),
    ) {
        // ── 第一行：状态指示 + 应用名 + 状态胶囊 ──
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 运行中常速变形，启动中加速，停止/出错时定格 —— 与状态同源，不额外维护一份
            when (tone) {
                StatusTone.Busy -> ExpressiveLoadingIndicator(size = 40.dp, color = stateColor, speed = 1.8f)
                StatusTone.Running -> ExpressiveLoadingIndicator(size = 40.dp, color = stateColor)
                // 出错用 Sunny（索引 4）：多角形读起来就是「不对劲」；
                // 空闲用 Oval（索引 6）——一个安静的整圆，不是多角星
                StatusTone.Error -> ExpressiveLoadingGlyph(size = 40.dp, color = stateColor, shapeIndex = 4)
                StatusTone.Idle -> ExpressiveLoadingGlyph(size = 40.dp, color = stateColor, shapeIndex = 6)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    "Kaze SLauncher",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Text(
                    serverState.toLabel(),
                    style = MaterialTheme.typography.bodySmall,
                    color = stateColor,
                    maxLines = 1,
                )
            }
            if (running) {
                M3EStatusChip(
                    text = formatUptime(uptime),
                    color = palette.running,
                    icon = Icons.Filled.CheckCircle,
                )
            }
        }

        // ── 当前实例卡：点一下展开实例列表 ──
        Box {
            if (current != null) {
                M3ECard(
                    variant = M3ECardVariant.Elevated,
                    title = current.name,
                    titleIcon = null,
                    supporting = buildString {
                        append("MC ")
                        append(current.mcVersion.ifBlank { "自定义" })
                        append(" · ")
                        append(current.coreType.displayName)
                        append(" · Java ")
                        append(current.javaMajor)
                        append(" · ")
                        append(current.memoryMb)
                        append(" MB")
                        if (running) append(" · 在线 ${onlinePlayers.size} 人")
                    },
                    onClick = { listOpen = true },
                    trailing = {
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            contentDescription = "切换实例",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    // 实例卡只有标题与正文，正文区留空由尾随 lambda 承担（这里显式给空）
                    content = { },
                )
            } else {
                M3ECard(
                    variant = M3ECardVariant.Outlined,
                    title = "还没有服务端",
                    supporting = "下载 Vanilla / Paper 服务端，或在「服务端」页导入已有的 server.jar",
                    onClick = onNewServer,
                    content = { },
                )
            }

            DropdownMenu(
                expanded = listOpen,
                onDismissRequest = { listOpen = false },
                modifier = Modifier.width(320.dp),
                shape = M3Shape.medium,
            ) {
                DropdownMenuItem(
                    text = { Text("切换实例", style = MaterialTheme.typography.titleSmall) },
                    enabled = false,
                    onClick = { },
                )
                instances.forEach { inst ->
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                InstanceIcon(inst.coreType, Modifier.size(28.dp))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = inst.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                    )
                                    Text(
                                        text = "MC ${inst.mcVersion.ifBlank { "自定义" }} · ${inst.coreType.displayName}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                            }
                        },
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    if (busy) return@IconButton
                                    listOpen = false
                                    viewModel.selectInstance(inst)
                                    viewModel.startInstance(inst)
                                },
                                enabled = !busy,
                            ) {
                                Icon(
                                    Icons.Filled.PlayArrow,
                                    contentDescription = "启动 ${inst.name}",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        },
                        onClick = {
                            listOpen = false
                            viewModel.selectInstance(inst)
                        },
                    )
                }
            }
        }

        // ── 主行动：启动 / 停止（connected button group）──
        if (current != null) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                val actionLabel = when {
                    running -> "停止服务端"
                    busy -> "取消启动"
                    else -> "启动服务端"
                }
                val actionIcon = if (running || busy) Icons.Filled.Stop else Icons.Filled.PlayArrow
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp)
                        .clip(M3Shape.groupFirst(56f)),
                    shape = M3Shape.groupFirst(56f),
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    onClick = {
                        // 运行中或启动中（部署/装 Java/下载核心/Forge 安装）都走停止。
                        // 旧版 busy 时按钮被禁用，用户在长达几分钟的启动过程里没有任何中止手段。
                        if (running || busy) viewModel.stopInstance(current) else viewModel.startInstance(current)
                    },
                ) {
                    Row(
                        Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(actionIcon, contentDescription = null, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(actionLabel, style = MaterialTheme.typography.labelLarge)
                    }
                }
                Surface(
                    modifier = Modifier
                        .height(56.dp)
                        .width(72.dp)
                        .clip(M3Shape.groupLast(56f)),
                    shape = M3Shape.groupLast(56f),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    onClick = { onNavigate(Dest.Console.route) },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Terminal, contentDescription = "打开控制台", modifier = Modifier.size(24.dp))
                    }
                }
            }
        }

        // ── 运行时长卡（服务器没跑时不留 00:00:00 的假数据）──
        if (running) {
            M3ECard(
                variant = M3ECardVariant.Outlined,
                title = formatUptime(uptime),
                titleIcon = null,
                supporting = "已运行 · 在线 ${onlinePlayers.size} 人 · 端口已监听",
                content = { },
            )
        }

        // ── eula 三步指示（首次启动流程）──
        if (serverState == ServerState.FirstRun || serverState == ServerState.AcceptingEula) {
            EulaSteps(serverState)
        }

        // ── 环境状态行 / 部署进度 ──
        if (envState == ProotEnvironment.State.SETTING_UP) {
            M3ECard(
                variant = M3ECardVariant.Outlined,
                title = "正在部署 Linux 环境",
                supporting = download.message.ifBlank { "准备部署…" },
            ) {
                WavyLinearProgress(
                    progress = download.progress.coerceIn(0f, 1f),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            M3EListItem(
                headline = when (envState) {
                    ProotEnvironment.State.READY -> "Linux 环境已就绪"
                    ProotEnvironment.State.ERROR -> "环境部署失败"
                    else -> "Linux 环境未部署"
                },
                supporting = if (javaVersions.isNotEmpty()) {
                    "proot + Ubuntu · 已安装 ${javaVersions.joinToString(" / ") { "Java $it" }}"
                } else {
                    "proot + Ubuntu · Java 在启动时按版本自动安装"
                },
                leadingIcon = when (envState) {
                    ProotEnvironment.State.READY -> Icons.Filled.CheckCircle
                    ProotEnvironment.State.ERROR -> Icons.Filled.ErrorOutline
                    else -> Icons.Filled.CloudDownload
                },
                iconContainer = when (envState) {
                    ProotEnvironment.State.READY -> statusPalette().running.copy(alpha = 0.18f)
                    ProotEnvironment.State.ERROR -> MaterialTheme.colorScheme.errorContainer
                    else -> MaterialTheme.colorScheme.secondaryContainer
                },
                shape = M3Shape.listSingle,
                trailing = {
                    if (envState != ProotEnvironment.State.READY) {
                        TextButton(onClick = { viewModel.setupEnv() }) { Text("部署") }
                    }
                },
            )
        }

        // ── 次要动作：一排图标按钮 ──
        if (current != null) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HomeSecondaryAction(Icons.Filled.Terminal, "控制台") { onNavigate(Dest.Console.route) }
                HomeSecondaryAction(Icons.Filled.FolderOpen, "实例目录") {
                    // 与「服务端」页的操作保持一致：交给系统文件管理器打开实例目录
                    runCatching {
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                            setDataAndType(
                                android.net.Uri.fromFile(current.dir),
                                "resource/folder",
                            )
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    }
                }
                HomeSecondaryAction(Icons.Filled.RestartAlt, "重启") {
                    if (!busy) viewModel.startInstance(current)
                }
                HomeSecondaryAction(Icons.Filled.MoreVert, "更多") { onNavigate(Dest.Server.route) }
            }
        } else {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(M3Shape.groupSingle(56f)),
                shape = M3Shape.groupSingle(56f),
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                onClick = onNewServer,
            ) {
                Row(
                    Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("新建服务端", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }

    // 主页会在 currentInstanceId 为空时用 firstOrNull 兜底显示某个实例，但 serverState/uptimeSec
    // 只由 currentInstanceId 派生 —— 两者不同源时（例如刚删掉当前实例），运行中的实例会显示
    // 「启动服务端」且点了没反应。这里把兜底选择同步回 ViewModel。
    LaunchedEffect(currentInstanceId, instances.size) {
        if (currentInstanceId == null && instances.isNotEmpty()) {
            viewModel.selectInstance(instances.first())
        }
    }
}

/** 次要动作：圆底图标按钮（tonal / outlined / text 三种容器观感轮换） */
@Composable
private fun HomeSecondaryAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.size(48.dp).clip(CircleShape),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        onClick = onClick,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(22.dp))
        }
    }
}

/** eula 三步指示：生成 eula.txt → 接受条款 → 启动服务端 */
@Composable
private fun EulaSteps(state: ServerState) {
    val currentStep = if (state == ServerState.FirstRun) 0 else 1
    val steps = listOf("生成 eula.txt", "接受条款", "启动服务端")
    M3ECard(
        variant = M3ECardVariant.Filled,
        title = "首次启动需要确认 Minecraft EULA",
        supporting = "服务端会先生成 eula.txt 并退出，随后自动改写为 eula=true 再启动",
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            steps.forEachIndexed { i, label ->
                val active = i <= currentStep
                val done = i < currentStep
                Column(
                    Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    done -> MaterialTheme.colorScheme.primary
                                    active -> MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                                    else -> MaterialTheme.colorScheme.outlineVariant
                                }
                            )
                    )
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        color = if (active) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
