package com.mcserver.launcher.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mcserver.launcher.core.env.EnvManager
import com.mcserver.launcher.core.server.InstanceStore
import com.mcserver.launcher.data.InstanceStatus
import com.mcserver.launcher.data.ServerInstance
import com.mcserver.launcher.util.Logger
import kotlinx.coroutines.launch

/** 首页:实例列表 + 新建入口 */
@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val instances by InstanceStore.instances.collectAsState()
    val envReady = EnvManager.isEnvironmentReady()
    var showNew by remember { mutableStateOf(false) }
    var showConsole by remember { mutableStateOf(false) }

    if (showConsole) {
        ConsoleScreen(onBack = { showConsole = false })
        return
    }

    if (showNew) {
        NewInstanceScreen(onDone = { showNew = false })
        return
    }

    Scaffold(
        modifier = modifier,
        floatingActionButton = {
            FloatingActionButton(onClick = { showNew = true }) {
                Icon(Icons.Filled.Add, "新建服务端")
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Text(
                "我的服务端",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
            )
            if (!envReady) {
                Surface(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Text(
                        "Linux 环境未就绪,请先在设置页重新部署",
                        Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
            if (instances.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("还没有服务端实例", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text("点击右下角 + 新建:选择核心类型与 MC 版本,自动下载",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(instances, key = { it.id }) { instance ->
                        InstanceCard(instance, onClick = { showConsole = true }, onStart = {
                            scope.launch {
                                val result = com.mcserver.launcher.core.server.ServerManager.start(instance)
                                if (result.isFailure) Logger.w(result.exceptionOrNull()?.message ?: "启动失败")
                            }
                        }, onStop = {
                            scope.launch { com.mcserver.launcher.core.server.ServerManager.stop() }
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun InstanceCard(instance: ServerInstance, onClick: () -> Unit, onStart: () -> Unit, onStop: () -> Unit) {
    val status by com.mcserver.launcher.core.server.ServerManager.status.collectAsState()
    val running = com.mcserver.launcher.core.server.ServerManager.isRunningFor(instance.id)

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(instance.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${instance.coreType.displayName} ${instance.mcVersion}${if (instance.buildId.isNotBlank()) " (build ${instance.buildId})" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                StatusBadge(if (running) status else InstanceStatus.STOPPED)
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("端口 ${instance.config.serverPort}", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                if (running) {
                    Button(onClick = onStop, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                        Icon(Icons.Filled.Stop, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("停止")
                    }
                } else {
                    Button(onClick = onStart) {
                        Icon(Icons.Filled.PlayArrow, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("启动")
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(status: InstanceStatus) {
    val (text, color) = when (status) {
        InstanceStatus.RUNNING -> "运行中" to Color(0xFF4CAF50)
        InstanceStatus.STARTING -> "启动中" to Color(0xFFFFA726)
        InstanceStatus.STOPPING -> "停止中" to Color(0xFFFFA726)
        InstanceStatus.ERROR -> "错误" to MaterialTheme.colorScheme.error
        InstanceStatus.STOPPED -> "已停止" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.15f)) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            color = color, style = MaterialTheme.typography.labelSmall)
    }
}
