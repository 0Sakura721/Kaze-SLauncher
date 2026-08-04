package com.mcserver.launcher.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcserver.launcher.core.server.ServerManager
import kotlinx.coroutines.launch

/**
 * 控制台:实时日志 + 命令输入 + 启动/停止 + 在线玩家。
 */
@Composable
fun ConsoleScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val status by ServerManager.status.collectAsState()
    val players by ServerManager.players.collectAsState()
    val uptime by ServerManager.uptimeSec.collectAsState()

    var command by remember { mutableStateOf("") }
    // 收集控制台日志(SharedFlow 回放最近记录)
    val logLines = remember { mutableStateListOf<String>() }
    LaunchedEffect(Unit) {
        ServerManager.console.collect { line ->
            logLines.add(line)
            if (logLines.size > 1000) logLines.removeRange(0, logLines.size - 1000)
        }
    }

    Column(Modifier.fillMaxSize()) {
        // 顶部栏
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "返回") }
            Column(Modifier.weight(1f)) {
                Text("服务器控制台", style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Text(
                    "${status.name} · 玩家 ${players.size} · 运行 ${uptime / 60}分${uptime % 60}秒",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val running = status == com.mcserver.launcher.data.InstanceStatus.RUNNING
            if (running) {
                Button(onClick = { scope.launch { ServerManager.stop() } },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                    Icon(Icons.Filled.Stop, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("停止")
                }
            } else {
                Button(onClick = {
                    val instance = com.mcserver.launcher.core.server.ServerManager.currentInstanceId
                        ?.let { com.mcserver.launcher.core.server.InstanceStore.instance(it) }
                    if (instance != null) scope.launch { ServerManager.start(instance) }
                }) {
                    Icon(Icons.Filled.PlayArrow, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("启动")
                }
            }
        }

        // 日志区
        LazyColumn(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                .padding(8.dp)
        ) {
            items(logLines.takeLast(500)) { line ->
                Text(
                    line,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = if (line.contains("ERROR") || line.contains("Exception"))
                        MaterialTheme.colorScheme.error
                    else if (line.startsWith(">"))
                        MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 1.dp)
                )
            }
        }

        // 命令输入
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("输入命令,如: say hello / op Steve / list") },
                singleLine = true
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    if (command.isNotBlank()) {
                        ServerManager.sendCommand(command.trim())
                        command = ""
                    }
                },
                enabled = status == com.mcserver.launcher.data.InstanceStatus.RUNNING
            ) { Text("发送") }
        }
    }
}
