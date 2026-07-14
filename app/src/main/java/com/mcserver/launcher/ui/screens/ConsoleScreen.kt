package com.mcserver.launcher.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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

    // 收集控制台输出
    LaunchedEffect(Unit) {
        serverManager.consoleOutput.collect { line ->
            consoleMessages.add(line)
            if (consoleMessages.size > 500) {
                consoleMessages.removeAt(0)
            }
        }
    }

    // 用户滚动时判断是否停留在底部（决定是否自动跟随）
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

    // 自动滚动到底部（仅当用户本就停在底部时）
    LaunchedEffect(consoleMessages.size) {
        if (consoleMessages.isNotEmpty() && stickToBottom) {
            listState.animateScrollToItem(consoleMessages.size - 1)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部栏
        TopAppBar(
            title = {
                Text("控制台")
            },
            actions = {
                // 清除控制台
                IconButton(onClick = { consoleMessages.clear() }) {
                    Icon(Icons.Filled.DeleteSweep, contentDescription = "清除")
                }
                // 停止按钮（仅在运行时显示）
                if (serverStatus.state == ServerState.RUNNING) {
                    IconButton(
                        onClick = { serverManager.stopServer() }
                    ) {
                        Icon(
                            Icons.Filled.Stop,
                            contentDescription = "停止",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        )

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
                    .background(Color(0xFF0D1117)) // 终端风格深色背景
                    .padding(12.dp)
            ) {
                if (consoleMessages.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "控制台输出将显示在这里\n启动服务器以查看日志",
                            style = TextStyle(
                                color = Color(0xFF666666),
                                fontSize = 14.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        )
                    }
                } else {
                    LazyColumn(state = listState) {
                        items(consoleMessages) { message ->
                            val color = when {
                                message.startsWith("> ") -> Color(0xFF58A6FF) // 用户命令
                                message.contains("ERROR") || message.contains("FATAL") -> Color(0xFFF85149)
                                message.contains("WARN") -> Color(0xFFD29922)
                                message.contains("INFO") -> Color(0xFF58A6FF)
                                message.contains("joined") -> Color(0xFF7EE787) // 玩家加入
                                message.contains("left") -> Color(0xFFF85149)  // 玩家离开
                                else -> Color(0xFFC9D1D9) // 默认
                            }
                            Text(
                                text = message,
                                style = TextStyle(
                                    color = color,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 18.sp
                                ),
                                modifier = Modifier.padding(vertical = 1.dp)
                            )
                        }
                    }
                }
            }
            // 向上翻看时显示「回到底部」按钮
            if (!stickToBottom && consoleMessages.isNotEmpty()) {
                FloatingActionButton(
                    onClick = {
                        stickToBottom = true
                        scope.launch { listState.animateScrollToItem(consoleMessages.size - 1) }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp),
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Icon(
                        Icons.Filled.ArrowDownward,
                        contentDescription = "回到底部",
                        tint = MaterialTheme.colorScheme.primary
                    )
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
    }
}
