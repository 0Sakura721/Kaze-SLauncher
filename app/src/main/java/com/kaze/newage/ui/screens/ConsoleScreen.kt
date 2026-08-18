package com.kaze.newage.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.kaze.newage.core.server.ServerState
import com.kaze.newage.ui.AppViewModel
import com.kaze.newage.ui.components.StatusOrb
import com.kaze.newage.ui.components.StatusTone
import com.kaze.newage.ui.isBusy
import com.kaze.newage.ui.theme.consoleBackgroundColor
import com.kaze.newage.ui.theme.consoleLineColor
import com.kaze.newage.ui.theme.statusPalette
import com.kaze.newage.ui.toLabel
import com.kaze.newage.ui.toTone

/** 控制台：实时日志（主题化深色终端）+ 命令输入 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ConsoleScreen(viewModel: AppViewModel) {
    val lines by viewModel.consoleLines.collectAsState()
    val serverState by viewModel.serverState.collectAsState()
    var input by remember { mutableStateOf("") }
    var follow by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()
    val tone = serverState.toTone()

    // 停止时清空输入框残留：保证 placeholder「服务端运行后可输入命令」恒定可见
    LaunchedEffect(serverState) {
        if (serverState != ServerState.Running) input = ""
    }
    val palette = statusPalette()
    val stateColor = when (tone) {
        StatusTone.Running -> palette.running
        StatusTone.Busy -> palette.busy
        StatusTone.Idle -> palette.idle
        StatusTone.Error -> palette.error
    }

    // 键盘/底栏动态避让：
    // 输入框位置 = 窗口底 - max(imeInset, 96dp) —— 键盘弹出时贴键盘顶（inset>96dp），
    // 收起动画期间 inset 连续减小，padding 同步补偿（96-inset），输入框恒不穿过底栏，
    // 平稳回到 96dp 位置。不能只用 isImeVisible 切换（收起动画期间 inset>0 仍为 true，
    // padding 保持 0 会让输入框沉到窗口底部穿过底栏再弹回）。
    val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    val bottomPad = (96.dp - imeBottom).coerceAtLeast(0.dp)

    // 新日志自动滚到底部（可暂停跟随）
    LaunchedEffect(lines.size) {
        if (follow && lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
    }

    // 键盘弹出瞬间重新滚到底：日志区被键盘压缩，最新一行可能被盖住。
    // 只在"无键盘→有键盘"沿触发一次（imeBottom 动画中连续变化，不重复滚动）
    var wasImeVisible by remember { mutableStateOf(false) }
    LaunchedEffect(imeBottom) {
        val visible = imeBottom > 0.dp
        if (visible && !wasImeVisible && follow && lines.isNotEmpty()) {
            listState.scrollToItem(lines.size - 1)
        }
        wasImeVisible = visible
    }

    // 实例切换器（多开：每实例独立控制台）
    val instances by viewModel.instances.collectAsState()
    val currentInstanceId by viewModel.currentInstanceId.collectAsState()
    val current = instances.firstOrNull { it.id == currentInstanceId }
    val states by viewModel.serverStates.collectAsState()
    var showSwitcher by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            // 底部空白承载常驻栏：bottomPad 随 IME inset 连续补偿（见上方注释），输入框不穿过底栏
            .padding(bottom = bottomPad)
    ) {
        // ── 头部 ──
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusOrb(tone, Modifier.size(20.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(current?.name ?: "未选择实例", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        androidx.compose.foundation.layout.Box {
                            IconButton(
                                onClick = { showSwitcher = true },
                                enabled = instances.isNotEmpty(),
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(
                                    Icons.Filled.ArrowDropDown,
                                    contentDescription = "切换实例",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            DropdownMenu(
                                expanded = showSwitcher,
                                onDismissRequest = { showSwitcher = false },
                                shape = RoundedCornerShape(14.dp),
                            ) {
                                instances.forEach { inst ->
                                    val s = states[inst.id] ?: ServerState.Idle
                                    val p = statusPalette()
                                    val dot = when {
                                        s == ServerState.Running -> p.running
                                        s.isBusy() -> p.busy
                                        s == ServerState.Error -> p.error
                                        else -> p.idle
                                    }
                                    DropdownMenuItem(
                                        text = {
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
                                                Text(inst.name, maxLines = 1)
                                            }
                                        },
                                        onClick = {
                                            viewModel.selectInstance(inst)
                                            showSwitcher = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        serverState.toLabel(),
                        style = MaterialTheme.typography.labelSmall,
                        color = stateColor,
                    )
                }
            }
            Row {
                // 复制日志：一键复制当前实例完整日志到剪贴板
                IconButton(
                    onClick = { viewModel.copyConsoleLog() },
                    enabled = current != null,
                ) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = "复制日志",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // 保存日志：SAF 选择目标位置，一次性导出当前实例完整日志
                val saveLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument("text/plain")
                ) { uri ->
                    uri?.let { viewModel.saveConsoleLog(it) }
                }
                IconButton(
                    onClick = {
                        val name = "${current?.name ?: "server"}-${
                            java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                                .format(java.util.Date())
                        }.log"
                        saveLauncher.launch(name)
                    },
                    enabled = current != null,
                ) {
                    Icon(
                        Icons.Filled.Save,
                        contentDescription = "保存日志",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { follow = !follow }) {
                    Icon(
                        Icons.Filled.ArrowDownward,
                        contentDescription = "自动滚动",
                        tint = if (follow) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { viewModel.clearConsole() }) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "清空日志",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ── 日志面板（深色终端，随主题微调）──
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(consoleBackgroundColor())
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                if (lines.isEmpty()) {
                    item {
                        Text(
                            "启动服务端后，日志将实时显示在这里。\n首次启动会自动生成 eula.txt 并接受条款后重启。",
                            color = consoleLineColor(com.kaze.newage.core.console.LineType.System),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }
                items(lines.size, key = { "${lines[it].timestamp}-${lines[it].text}" }) { i ->
                    Text(
                        lines[i].text,
                        color = consoleLineColor(lines[i].type),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 1.dp),
                    )
                }
            }
        }

        // ── 命令输入 ──
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        if (serverState == ServerState.Running) "输入命令（stop / op 玩家名 / say …）"
                        else "服务端运行后可输入命令"
                    )
                },
                singleLine = true,
                // 停止时禁用：避免能输入但发不出去，且残留文字顶掉 placeholder
                enabled = serverState == ServerState.Running,
            )
            IconButton(
                onClick = {
                    if (input.isNotBlank()) {
                        viewModel.sendCommand(input.trim())
                        input = ""
                    }
                },
                enabled = serverState == ServerState.Running,
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
            }
        }
    }
}
