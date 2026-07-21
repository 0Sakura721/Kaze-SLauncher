package com.mcserver.launcher.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcserver.launcher.server.LinuxEnvironmentManager
import com.mcserver.launcher.server.ProotServerManager
import kotlinx.coroutines.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val terminalLines = remember { mutableStateListOf<String>() }
    var commandInput by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    var isShellRunning by remember { mutableStateOf(false) }
    var sessionKey by remember { mutableIntStateOf(0) }

    var processRef by remember { mutableStateOf<java.lang.Process?>(null) }

    val serverDir = remember { ProotServerManager.serverDir(context) }

    fun addLine(line: String) {
        terminalLines.add(line)
        if (terminalLines.size > 3000) {
            terminalLines.removeRange(0, 500)
        }
    }

    DisposableEffect(sessionKey) {
        val job = scope.launch(Dispatchers.IO) {
            try {
                isShellRunning = true
                addLine("> 正在启动 Linux 终端...")
                addLine("> 工作目录: ${serverDir.absolutePath}")

                if (!LinuxEnvironmentManager.isEnvironmentReady()) {
                    withContext(Dispatchers.Main) {
                        isShellRunning = false
                        addLine("> 错误: Linux 环境尚未初始化，请先完成环境部署")
                    }
                    return@launch
                }

                val pb = LinuxEnvironmentManager.buildProotCommand(
                    command = "bash --norc --noediting",
                    workDir = serverDir.absolutePath
                )
                val proc = pb.start()
                processRef = proc

                addLine("> 终端已就绪，请输入命令")

                val reader = proc.inputStream.bufferedReader()
                try {
                    while (isActive) {
                        val line = withContext(Dispatchers.IO) { reader.readLine() }
                        if (line == null) break
                        withContext(Dispatchers.Main) {
                            addLine(line)
                        }
                    }
                } catch (_: Exception) {
                    // 流被关闭或协程被取消
                }

                proc.waitFor()
                withContext(Dispatchers.Main) {
                    isShellRunning = false
                    addLine("> 终端进程已退出（退出码: ${proc.exitValue()}）")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isShellRunning = false
                    addLine("> 错误: ${e.message}")
                }
            }
        }

        onDispose {
            job.cancel()
            processRef?.destroy()
        }
    }

    LaunchedEffect(terminalLines.size) {
        if (terminalLines.isNotEmpty()) {
            listState.animateScrollToItem(terminalLines.size - 1)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Linux 终端") },
            actions = {
                IconButton(onClick = { terminalLines.clear() }) {
                    Icon(Icons.Filled.DeleteSweep, contentDescription = "清除")
                }
                IconButton(onClick = {
                    processRef?.destroy()
                    sessionKey++
                }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "重置终端")
                }
            }
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(MaterialTheme.shapes.medium)
                    .background(Color(0xFF0D1117))
                    .padding(12.dp)
            ) {
                if (terminalLines.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "终端初始化中...",
                            style = TextStyle(
                                color = Color(0xFF666666),
                                fontSize = 14.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        )
                    }
                } else {
                    LazyColumn(state = listState) {
                        items(terminalLines.size) { index ->
                            val line = terminalLines[index]
                            val color = when {
                                line.startsWith("> ") -> Color(0xFF58A6FF)
                                line.startsWith("Error", ignoreCase = true) -> Color(0xFFF85149)
                                line.startsWith("bash:") -> Color(0xFFD29922)
                                else -> Color(0xFFC9D1D9)
                            }
                            Text(
                                text = line,
                                style = TextStyle(
                                    color = color,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 16.sp
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 0.5.dp)
                            )
                        }
                    }
                }
            }
        }

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
                Text(
                    text = "$",
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
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
                    enabled = isShellRunning,
                    decorationBox = { innerTextField ->
                        Box {
                            if (commandInput.isEmpty()) {
                                Text(
                                    if (isShellRunning) "输入 Linux 命令..." else "终端未就绪",
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
                        if (commandInput.isNotBlank() && processRef != null) {
                            val cmd = commandInput.trim()
                            addLine("> $cmd")
                            scope.launch(Dispatchers.IO) {
                                try {
                                    val stdin = processRef!!.outputStream
                                    stdin.write("$cmd\n".toByteArray())
                                    stdin.flush()
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        addLine("> 发送失败: ${e.message}")
                                    }
                                }
                            }
                            commandInput = ""
                        }
                    },
                    enabled = isShellRunning && commandInput.isNotBlank()
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "发送",
                        tint = if (isShellRunning) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
