package com.mcserver.launcher.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcserver.launcher.data.ServerState
import com.mcserver.launcher.server.ServerManager
import androidx.compose.runtime.derivedStateOf
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsoleScreen() {
    val serverManager = ServerManager.instance
    val serverStatus by serverManager.serverStatus.collectAsState()
    val consoleMessages = remember { mutableStateListOf<String>() }
    var commandInput by remember { mutableStateOf("") }
    var stickToBottom by remember { mutableStateOf(true) }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 搜索/过滤
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var filterLevel by remember { mutableStateOf("all") } // all, error, warn, info, chat, command

    // 快速命令面板
    var showQuickCommands by remember { mutableStateOf(false) }

    // 导出日志
    var showExportDialog by remember { mutableStateOf(false) }
    var exportFilter by remember { mutableStateOf("all") }
    var exportFromLine by remember { mutableStateOf("0") }
    var exportToLine by remember { mutableStateOf("") }
    var exporting by remember { mutableStateOf(false) }

    // 收集控制台输出
    LaunchedEffect(Unit) {
        serverManager.consoleOutput.collect { line ->
            consoleMessages.add(line)
            if (consoleMessages.size > 2000) {
                consoleMessages.removeRange(0, 500)
            }
        }
    }

    // 用户滚动时判断是否停留在底部
    val stickToBottomState = remember {
        derivedStateOf {
            val idx = listState.firstVisibleItemIndex
            val total = listState.layoutInfo.totalItemsCount
            total == 0 || idx >= total - 3
        }
    }
    LaunchedEffect(stickToBottomState.value) {
        stickToBottom = stickToBottomState.value
    }

    // 自动滚动到底部
    LaunchedEffect(consoleMessages.size) {
        if (consoleMessages.isNotEmpty() && stickToBottom) {
            listState.animateScrollToItem(consoleMessages.size - 1)
        }
    }

    // 过滤后的消息
    val filteredMessages = remember(consoleMessages, filterLevel, searchQuery) {
        derivedStateOf {
            consoleMessages.filter { msg ->
                val levelMatch = when (filterLevel) {
                    "all" -> true
                    "error" -> msg.contains("ERROR") || msg.contains("FATAL") || msg.contains("Exception")
                    "warn" -> msg.contains("WARN")
                    "info" -> msg.contains("INFO")
                    "chat" -> (msg.contains("joined") || msg.contains("left") || msg.contains("<"))
                    "command" -> msg.startsWith("> ")
                    else -> true
                }
                val searchMatch = searchQuery.isBlank() || msg.contains(searchQuery, ignoreCase = true)
                levelMatch && searchMatch
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部栏
        TopAppBar(
            title = { Text("控制台") },
            actions = {
                // 快速命令
                IconButton(onClick = { showQuickCommands = !showQuickCommands }) {
                    Icon(Icons.Filled.SmartButton, contentDescription = "快速命令")
                }
                // 搜索
                IconButton(onClick = { showSearch = !showSearch; if (!showSearch) { searchQuery = ""; filterLevel = "all" } }) {
                    Icon(
                        if (showSearch) Icons.Filled.SearchOff else Icons.Filled.Search,
                        contentDescription = "搜索"
                    )
                }
                // 复制
                IconButton(onClick = {
                    val text = consoleMessages.joinToString("\n")
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("console", text))
                    Toast.makeText(context, "已复制 ${consoleMessages.size} 行日志", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "复制日志")
                }
                // 导出
                IconButton(onClick = {
                    exportToLine = consoleMessages.size.toString()
                    showExportDialog = true
                }) {
                    Icon(Icons.Filled.FileDownload, contentDescription = "导出日志")
                }
                // 清除
                IconButton(onClick = { consoleMessages.clear() }) {
                    Icon(Icons.Filled.DeleteSweep, contentDescription = "清除")
                }
                // 停止按钮
                if (serverStatus.state == ServerState.RUNNING) {
                    IconButton(onClick = { serverManager.stopServer() }) {
                        Icon(Icons.Filled.Stop, contentDescription = "停止", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        )

        // 搜索栏
        if (showSearch) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                tonalElevation = 2.dp
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("搜索日志...") },
                            leadingIcon = { Icon(Icons.Filled.Search, null, Modifier.size(20.dp)) },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(Icons.Filled.Clear, "清除", Modifier.size(18.dp))
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f).height(52.dp),
                            singleLine = true,
                            textStyle = TextStyle(fontSize = 14.sp)
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val levels = listOf(
                            "all" to "全部", "error" to "错误", "warn" to "警告",
                            "info" to "信息", "chat" to "聊天", "command" to "命令"
                        )
                        levels.forEach { (key, label) ->
                            FilterChip(
                                selected = filterLevel == key,
                                onClick = { filterLevel = key },
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                    Text(
                        "显示 ${filteredMessages.value.size} / ${consoleMessages.size} 条",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }

        // 快速命令面板
        if (showQuickCommands && serverStatus.state == ServerState.RUNNING) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                tonalElevation = 2.dp
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("快速命令", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val quickCommands = listOf(
                            "list" to "在线列表",
                            "stop" to "停止",
                            "save-all" to "保存",
                            "say Hello!" to "广播",
                            "op " to "OP",
                            "deop " to "取消OP",
                            "whitelist add " to "加白",
                            "whitelist remove " to "移白",
                            "ban " to "封禁",
                            "pardon " to "解封",
                            "gamemode creative " to "创造",
                            "gamemode survival " to "生存",
                            "time set day" to "白天",
                            "weather clear" to "晴天",
                            "difficulty peaceful" to "和平",
                            "difficulty hard" to "困难",
                            "tp " to "传送",
                            "give @a diamond 64" to "钻石"
                        )
                        quickCommands.forEach { (cmd, label) ->
                            SuggestionChip(
                                onClick = {
                                    if (cmd.endsWith(" ")) {
                                        commandInput = cmd
                                    } else {
                                        serverManager.sendCommand(cmd)
                                    }
                                    showQuickCommands = false
                                },
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                                icon = {
                                    Icon(
                                        if (cmd.endsWith(" ")) Icons.Filled.Edit else Icons.AutoMirrored.Filled.Send,
                                        null,
                                        Modifier.size(14.dp)
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }

        // 控制台输出区
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF0D1117))
                    .padding(12.dp)
            ) {
                if (filteredMessages.value.isEmpty() && consoleMessages.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "控制台输出将显示在这里",
                                style = TextStyle(color = Color(0xFF666666), fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                            )
                            Text(
                                text = "启动服务器以查看日志",
                                style = TextStyle(color = Color(0xFF555555), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            )
                        }
                    }
                } else if (filteredMessages.value.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "没有匹配的日志",
                            style = TextStyle(color = Color(0xFF666666), fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                        )
                    }
                } else {
                    LazyColumn(state = listState) {
                        items(filteredMessages.value.size) { index ->
                            val message = filteredMessages.value[index]
                            val color = getConsoleLineColor(message)
                            val background = if (index % 2 == 0) Color.Transparent else Color(0xFF161B22)
                            Text(
                                text = message,
                                style = TextStyle(
                                    color = color,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 17.sp
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(background)
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
            }
            // 「回到底部」按钮
            if (!stickToBottom && filteredMessages.value.isNotEmpty()) {
                FloatingActionButton(
                    onClick = {
                        stickToBottom = true
                        scope.launch { listState.animateScrollToItem(filteredMessages.value.size - 1) }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp),
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Icon(Icons.Filled.ArrowDownward, contentDescription = "回到底部", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }

        // 命令输入区
        if (serverStatus.state == ServerState.RUNNING) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                tonalElevation = 3.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    BasicTextField(
                        value = commandInput,
                        onValueChange = { commandInput = it },
                        modifier = Modifier.weight(1f),
                        textStyle = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        singleLine = true,
                        decorationBox = { innerTextField ->
                            Box {
                                if (commandInput.isEmpty()) {
                                    Text(
                                        "输入服务器命令...",
                                        style = TextStyle(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 14.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            if (commandInput.isNotBlank()) {
                                serverManager.sendCommand(commandInput.trim())
                                commandInput = ""
                            }
                        }
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "发送",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        } else {
            // 未运行时显示提示
            Surface(
                modifier = Modifier.fillMaxWidth(),
                tonalElevation = 3.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Info,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "启动服务器后即可输入命令",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 导出日志对话框
        if (showExportDialog) {
            AlertDialog(
                onDismissRequest = { showExportDialog = false },
                icon = { Icon(Icons.Filled.FileDownload, null, tint = MaterialTheme.colorScheme.primary) },
                title = { Text("导出日志") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("将控制台日志导出为 .log 文件到下载目录。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("日志级别过滤：", style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.width(8.dp))
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf("all" to "全部", "error" to "错误", "warn" to "警告", "info" to "信息", "chat" to "聊天").forEach { (k, v) ->
                                    FilterChip(
                                        selected = exportFilter == k,
                                        onClick = { exportFilter = k },
                                        label = { Text(v, style = MaterialTheme.typography.labelSmall) }
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = exportFromLine,
                                onValueChange = { exportFromLine = it.filter { c -> c.isDigit() } },
                                label = { Text("起始行") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                enabled = !exporting
                            )
                            OutlinedTextField(
                                value = exportToLine,
                                onValueChange = { exportToLine = it.filter { c -> c.isDigit() } },
                                label = { Text("结束行") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                enabled = !exporting
                            )
                        }
                        Text("共 ${consoleMessages.size} 行日志",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            scope.launch {
                                exporting = true
                                try {
                                    val from = exportFromLine.toIntOrNull()?.coerceAtLeast(0) ?: 0
                                    val to = exportToLine.toIntOrNull()?.coerceAtMost(consoleMessages.size) ?: consoleMessages.size
                                    val filtered = consoleMessages.subList(from, to).filter { msg ->
                                        when (exportFilter) {
                                            "all" -> true
                                            "error" -> msg.contains("ERROR") || msg.contains("FATAL") || msg.contains("Exception")
                                            "warn" -> msg.contains("WARN")
                                            "info" -> msg.contains("INFO")
                                            "chat" -> msg.contains("joined") || msg.contains("left") || msg.contains("<")
                                            else -> true
                                        }
                                    }

                                    val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                                        .format(java.util.Date())
                                    val fileName = "mcserver_console_$timestamp.log"
                                    val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                                        android.os.Environment.DIRECTORY_DOWNLOADS)
                                    downloadsDir.mkdirs()
                                    val logFile = java.io.File(downloadsDir, fileName)
                                    logFile.writeText(filtered.joinToString("\n"))

                                    Toast.makeText(context,
                                        "已导出 ${filtered.size} 行日志到 Downloads/$fileName",
                                        Toast.LENGTH_LONG).show()
                                    showExportDialog = false
                                } catch (e: Exception) {
                                    Toast.makeText(context,
                                        "导出失败：${e.message}",
                                        Toast.LENGTH_SHORT).show()
                                }
                                exporting = false
                            }
                        },
                        enabled = !exporting
                    ) {
                        if (exporting) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text("导出")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showExportDialog = false }) { Text("取消") }
                }
            )
        }
    }
}

/** 根据日志内容返回对应颜色（终端风格高亮） */
private fun getConsoleLineColor(message: String): Color {
    return when {
        message.startsWith("> ") -> Color(0xFF58A6FF)
        message.contains("FATAL") || message.contains("Exception") -> Color(0xFFFF6B6B)
        message.contains("ERROR") || message.contains("Error") -> Color(0xFFF85149)
        message.contains("WARN") || message.contains("Warn") -> Color(0xFFD29922)
        message.contains("INFO") || message.contains("Info") -> Color(0xFF58A6FF)
        message.contains("DEBUG") || message.contains("Debug") -> Color(0xFF8B949E)
        message.contains("joined the game") -> Color(0xFF7EE787)
        message.contains("left the game") -> Color(0xFFF85149)
        message.contains("<") && message.contains(">") -> Color(0xFFE6EDF3)  // 聊天消息
        message.contains("Done") || message.contains("done") -> Color(0xFF7EE787)
        message.contains("Starting") || message.contains("Loading") -> Color(0xFF79C0FF)
        message.contains("Saved") || message.contains("saved") -> Color(0xFFA5D6FF)
        else -> Color(0xFFC9D1D9)
    }
}
