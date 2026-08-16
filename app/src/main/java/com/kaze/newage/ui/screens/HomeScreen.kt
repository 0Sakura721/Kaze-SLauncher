package com.kaze.newage.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kaze.newage.core.env.ProotEnvironment
import com.kaze.newage.core.server.ServerState
import com.kaze.newage.ui.AppViewModel
import com.kaze.newage.ui.Dest
import com.kaze.newage.ui.components.InstanceIcon
import com.kaze.newage.ui.components.StatusOrb
import com.kaze.newage.ui.components.StatusTone
import com.kaze.newage.ui.formatUptime
import com.kaze.newage.ui.isBusy
import com.kaze.newage.ui.theme.LocalDarkTheme
import com.kaze.newage.ui.theme.itemColor
import com.kaze.newage.ui.theme.statusPalette
import com.kaze.newage.ui.toLabel
import com.kaze.newage.ui.toTone

/**
 * 主页：ZalithLauncher2 LauncherScreen 右栏启动面板移植（GPL-3.0）——
 * 状态球（avatar 位）+ 当前实例选择器（VersionManagerLayout：图标+名称+摘要，
 * 点击弹 DropdownMenu，每项带 ▶ 直接启动）+ 运行信息行 + 底部全宽大启动按钮。
 * 导航一律走底栏（FCL/Zalith 同款），不再堆快捷入口。
 */
@Composable
fun HomeScreen(viewModel: AppViewModel, onNavigate: (String) -> Unit) {
    val envState by viewModel.envState.collectAsState()
    val javaVersions by viewModel.envJavaVersions.collectAsState()
    val download by viewModel.download.collectAsState()
    val serverState by viewModel.serverState.collectAsState()
    val instances by viewModel.instances.collectAsState()
    val currentInstanceId by viewModel.currentInstanceId.collectAsState()
    val uptime by viewModel.uptimeSec.collectAsState()
    val onlinePlayers by viewModel.onlinePlayers.collectAsState()

    val current = instances.firstOrNull { it.id == currentInstanceId } ?: instances.firstOrNull()
    val tone = serverState.toTone()
    val stateColor = toneColor(tone)
    val busy = serverState.isBusy()

    Column(
        Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ── 内容直接铺在背景上（无大面板框架），玻璃只用于小型元素 ──
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 顶部：状态球 + 品牌
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatusOrb(tone, size = 40.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Kaze SLauncher", style = MaterialTheme.typography.titleMedium)
                    Text(
                        serverState.toLabel(),
                        style = MaterialTheme.typography.bodySmall,
                        color = stateColor,
                    )
                }
            }

            // 当前实例选择器：小型玻璃 chip（Zalith VersionManagerLayout 同款内容）
            var listOpen by remember { mutableStateOf(false) }
            Box {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp)),
                    shape = RoundedCornerShape(16.dp),
                    color = itemColor(),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    onClick = { listOpen = true },
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (current != null) {
                            InstanceIcon(current.coreType, Modifier.size(28.dp))
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = current.name,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                    modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                                )
                                Text(
                                    text = "MC ${current.mcVersion.ifBlank { "自定义" }} · ${current.coreType.displayName} · Java ${current.javaMajor} · ${current.memoryMb}MB",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                                )
                            }
                        } else {
                            Text(
                                text = "还没有服务端",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                    // Zalith 版本切换下拉：点行切换当前实例，行尾 ▶ 直接启动
                    DropdownMenu(
                        expanded = listOpen,
                        onDismissRequest = { listOpen = false },
                        modifier = Modifier.width(300.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        instances.forEach { inst ->
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        InstanceIcon(inst.coreType, Modifier.size(28.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text(
                                                text = inst.name,
                                                style = MaterialTheme.typography.labelMedium,
                                                maxLines = 1,
                                            )
                                            Text(
                                                text = "MC ${inst.mcVersion.ifBlank { "自定义" }} · ${inst.coreType.displayName}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                            )
                                        }
                                    }
                                },
                                trailingIcon = {
                                    IconButton(onClick = {
                                        listOpen = false
                                        viewModel.selectInstance(inst)
                                        viewModel.startInstance(inst)
                                    }) {
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

                // eula 三步指示（首次启动流程）
                if (serverState == ServerState.FirstRun || serverState == ServerState.AcceptingEula) {
                    EulaSteps(serverState)
                }

                // 运行信息行（Zalith FlowRow labelSmall alpha 0.7 风）
                Row(
                    Modifier.fillMaxWidth().alpha(0.7f),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    InfoText("运行 ${formatUptime(uptime)}")
                    InfoText("内存 ${current?.memoryMb ?: "—"}MB")
                    InfoText("在线 ${if (serverState == ServerState.Running) onlinePlayers.size else "—"}")
                }

                // 环境状态（内联行）
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val (icon, color) = when (envState) {
                        ProotEnvironment.State.READY -> Icons.Filled.CheckCircle to
                            (if (LocalDarkTheme.current) Color(0xFF34D399) else Color(0xFF23A268))
                        ProotEnvironment.State.SETTING_UP -> Icons.Filled.CloudDownload to MaterialTheme.colorScheme.primary
                        ProotEnvironment.State.ERROR -> Icons.Filled.ErrorOutline to MaterialTheme.colorScheme.error
                        else -> Icons.Filled.CloudDownload to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            when (envState) {
                                ProotEnvironment.State.READY -> "Linux 环境已就绪"
                                ProotEnvironment.State.SETTING_UP -> "正在部署环境…"
                                ProotEnvironment.State.ERROR -> "环境部署失败"
                                else -> "Linux 环境未部署"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            if (javaVersions.isNotEmpty()) "Java：${javaVersions.joinToString(" / ") { "Java $it" }}"
                            else "Java：启动时按版本自动安装",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (envState != ProotEnvironment.State.READY && envState != ProotEnvironment.State.SETTING_UP) {
                        TextButton(onClick = { viewModel.setupEnv() }) { Text("部署") }
                    }
                }
                if (envState == ProotEnvironment.State.SETTING_UP) {
                    LinearProgressIndicator(
                        progress = { download.progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    download.message.let {
                        if (it.isNotBlank()) Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
        }

        // ── 底部大启动按钮（Zalith launch button，全宽锚底）──
        Button(
            onClick = {
                val c = current
                when {
                    c == null -> onNavigate(Dest.Server.route)
                    serverState == ServerState.Running -> viewModel.stopInstance(c)
                    else -> viewModel.startInstance(c)
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Text(
                when {
                    current == null -> "新建服务端"
                    serverState == ServerState.Running -> "停止服务端"
                    busy -> "处理中…"
                    else -> "启动服务端"
                }
            )
        }
    }
}

@Composable
private fun toneColor(tone: StatusTone): Color {
    val p = statusPalette()
    return when (tone) {
        StatusTone.Running -> p.running
        StatusTone.Busy -> p.busy
        StatusTone.Idle -> p.idle
        StatusTone.Error -> p.error
    }
}

/** 运行信息小字（Zalith labelSmall 风） */
@Composable
private fun InfoText(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** eula 三步指示：生成 eula.txt → 接受条款 → 启动服务端 */
@Composable
private fun EulaSteps(state: ServerState) {
    val currentStep = if (state == ServerState.FirstRun) 0 else 1
    val steps = listOf("生成 eula.txt", "接受条款", "启动服务端")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        steps.forEachIndexed { i, label ->
            val active = i <= currentStep
            val done = i < currentStep
            Column(
                Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
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
