package com.mcserver.launcher.ui.screens.tabs

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.VerticalAlignCenter
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcserver.launcher.data.InstanceStatus
import com.mcserver.launcher.data.ServerInstance
import com.mcserver.launcher.ui.components.LocalUiMessenger
import com.mcserver.launcher.ui.components.pressSource
import com.mcserver.launcher.core.server.ServerManager
import com.mcserver.launcher.ui.theme.KazeWarning
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 控制台 Tab:实时日志 + 命令输入 + 工具栏(清空/保存/复制/暂停/滚动)。
 *
 * 行协议:`<instanceId>|<line>`,按实例过滤防止多实例串台。
 *
 * @param onStart 服务器未运行时点击"启动"按钮的回调(由父级处理启动逻辑)
 */
@Immutable
private data class ConsoleLogLine(
    val key: Long,
    val text: String,
    val color: Color
)

@Composable
fun ConsoleTab(instance: ServerInstance, commandHistory: MutableList<String> = androidx.compose.runtime.mutableStateListOf(), onStart: () -> Unit = {}) {
    val context = LocalContext.current
    val messenger = LocalUiMessenger.current
    val clipboard = LocalClipboardManager.current
    val status by ServerManager.status.collectAsState()
    val players by ServerManager.players.collectAsState()
    val uptime by ServerManager.uptimeSec.collectAsState()
    var command by remember { mutableStateOf("") }
    var historyIndex by remember { mutableStateOf(-1) } // -1=新输入;否则指向 commandHistory 下标
    val logLines = remember { mutableStateListOf<ConsoleLogLine>() }
    val errColor = MaterialTheme.colorScheme.error
    val primaryColor = MaterialTheme.colorScheme.primary
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    val warnColor = KazeWarning
    var autoOutput by remember { mutableStateOf(true) }
    var autoScroll by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()

    val saveLogLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(logLines.joinToString("\n") { it.text }.toByteArray(Charsets.UTF_8))
                }
                messenger.toast("日志已保存(${logLines.size} 行)")
            } catch (e: Exception) {
                messenger.toastError("保存失败:${e.message}")
            }
        }
    }

    var logCounter by remember { mutableStateOf(0) }
    var logSeq by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        ServerManager.console.collect { raw ->
            val sep = raw.indexOf('|')
            val instId = if (sep > 0) raw.substring(0, sep) else ""
            if (instId != instance.id) return@collect
            val line = if (sep > 0) raw.substring(sep + 1) else raw
            if (!autoOutput) return@collect
            logLines.add(
                ConsoleLogLine(
                    key = logSeq++,
                    text = line,
                    color = classifyConsoleColor(line, errColor, warnColor, primaryColor, textColor)
                )
            )
            logCounter++
            if (logLines.size > 1000) logLines.removeRange(0, logLines.size - 1000)
        }
    }

    LaunchedEffect(logCounter, autoScroll) {
        if (autoScroll && logLines.isNotEmpty()) {
            listState.scrollToItem((logLines.size - 1).coerceAtLeast(0))
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        ConsoleToolbar(
            statusText = "${status.name} · ${players.size}人 · ${uptime / 60}分${uptime % 60}秒",
            logCount = logLines.size,
            autoOutput = autoOutput,
            autoScroll = autoScroll,
            onClear = {
                logLines.clear()
                messenger.toast("已清空控制台")
            },
            onSave = {
                val name = "server_log_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.txt"
                saveLogLauncher.launch(name)
            },
            onCopy = {
                val text = logLines.joinToString("\n") { it.text }
                clipboard.setText(AnnotatedString(text))
                messenger.toast("已复制全部日志(${logLines.size} 行)")
            },
            onToggleOutput = { autoOutput = !autoOutput },
            onToggleScroll = { autoScroll = !autoScroll }
        )

        SelectionContainer {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                items(logLines, key = { it.key }) { entry ->
                    Text(
                        entry.text,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = entry.color,
                        modifier = Modifier.padding(vertical = 1.dp)
                    )
                }
            }
        }

        if (status == InstanceStatus.RUNNING) {
            CommandInputRow(
                command = command,
                onCommandChange = { command = it; historyIndex = -1 },
                onSend = {
                    if (command.isNotBlank()) {
                        val cmd = command.trim()
                        ServerManager.sendCommand(cmd)
                        if (commandHistory.isEmpty() || commandHistory.last() != cmd) {
                            commandHistory.add(cmd)
                            if (commandHistory.size > 100) commandHistory.removeAt(0)
                        }
                        historyIndex = -1
                        command = ""
                    }
                },
                onHistoryUp = {
                    if (commandHistory.isEmpty()) return@CommandInputRow
                    val newIdx = if (historyIndex == -1) commandHistory.size - 1
                        else (historyIndex - 1).coerceAtLeast(0)
                    if (newIdx != historyIndex) {
                        historyIndex = newIdx
                        command = commandHistory[newIdx]
                    }
                },
                onHistoryDown = {
                    if (commandHistory.isEmpty() || historyIndex == -1) return@CommandInputRow
                    val newIdx = historyIndex + 1
                    if (newIdx >= commandHistory.size) {
                        historyIndex = -1
                        command = ""
                    } else {
                        historyIndex = newIdx
                        command = commandHistory[newIdx]
                    }
                },
                historySize = commandHistory.size
            )
        } else {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "服务器未运行,启动后可输入命令",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                val (pGo, sGo) = pressSource()
                TextButton(
                    onClick = onStart,
                    interactionSource = sGo,
                    modifier = pGo,
                    contentPadding = PaddingValues(horizontal = 6.dp)
                ) { Text("启动", style = MaterialTheme.typography.labelMedium) }
            }
        }
    }
}

@Composable
private fun ConsoleToolbar(
    statusText: String,
    logCount: Int,
    autoOutput: Boolean,
    autoScroll: Boolean,
    onClear: () -> Unit,
    onSave: () -> Unit,
    onCopy: () -> Unit,
    onToggleOutput: () -> Unit,
    onToggleScroll: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            statusText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        ToolIconButton(Icons.Filled.Clear, "清空", enabled = logCount > 0, onClick = onClear)
        ToolIconButton(Icons.Filled.SaveAlt, "保存日志", enabled = logCount > 0, onClick = onSave)
        ToolIconButton(Icons.Filled.Share, "复制日志", enabled = logCount > 0, onClick = onCopy)
        ToolIconButton(
            if (autoOutput) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            "输出:${if (autoOutput) "开" else "关"}",
            onClick = onToggleOutput,
            tint = if (autoOutput) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
        ToolIconButton(
            if (autoScroll) Icons.Filled.VerticalAlignBottom else Icons.Filled.VerticalAlignCenter,
            "滚动:${if (autoScroll) "开" else "关"}",
            onClick = onToggleScroll,
            tint = if (autoScroll) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ToolIconButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: () -> Unit
) {
    val (press, src) = pressSource()
    IconButton(
        onClick = onClick,
        enabled = enabled,
        interactionSource = src,
        modifier = press.then(Modifier.size(32.dp))
    ) {
        Icon(icon, contentDescription, Modifier.size(16.dp), tint = tint)
    }
}

@Composable
private fun CommandInputRow(
    command: String,
    onCommandChange: (String) -> Unit,
    onSend: () -> Unit,
    onHistoryUp: () -> Unit,
    onHistoryDown: () -> Unit,
    historySize: Int
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = command,
            onValueChange = onCommandChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("输入命令,如: op Steve / say hi") },
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() })
        )
        Spacer(Modifier.width(4.dp))
        // 命令历史导航(↑上一条 / ↓下一条)
        IconButton(
            onClick = onHistoryUp,
            enabled = historySize > 0,
            modifier = Modifier.size(40.dp)
        ) {
            Icon(Icons.Filled.KeyboardArrowUp, "上一条命令",
                Modifier.size(22.dp),
                tint = if (historySize > 0) MaterialTheme.colorScheme.onSurfaceVariant
                       else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f))
        }
        IconButton(
            onClick = onHistoryDown,
            enabled = historySize > 0,
            modifier = Modifier.size(40.dp)
        ) {
            Icon(Icons.Filled.KeyboardArrowDown, "下一条命令",
                Modifier.size(22.dp),
                tint = if (historySize > 0) MaterialTheme.colorScheme.onSurfaceVariant
                       else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f))
        }
        Spacer(Modifier.width(4.dp))
        Button(
            onClick = onSend,
            modifier = Modifier.height(48.dp)
        ) { Text("发送") }
    }
}

/**
 * 按日志级别/堆栈特征着色,避免玩家聊天含 "Exception" 等关键字误判为错误。
 * MC 日志常见格式:`[HH:MM:SS INFO]:` / `[Server thread/WARN]:` / `Caused by:` / `at ...`
 */
private fun classifyConsoleColor(
    line: String,
    errColor: Color,
    warnColor: Color,
    primaryColor: Color,
    textColor: Color
): Color {
    if (line.startsWith(">")) return primaryColor
    val upper = line.uppercase()
    return when {
        upper.contains("/ERROR]") || upper.contains(" ERROR]") ||
            upper.contains("/FATAL]") || upper.contains("/SEVERE]") -> errColor
        upper.contains("/WARN]") || upper.contains(" WARN]") ||
            upper.contains("WARNING") -> warnColor
        line.startsWith("at ") || line.startsWith("Caused by:") ||
            line.startsWith("... ") || line.startsWith("java.lang.") ||
            line.startsWith("org.bukkit.") -> errColor
        else -> textColor
    }
}


