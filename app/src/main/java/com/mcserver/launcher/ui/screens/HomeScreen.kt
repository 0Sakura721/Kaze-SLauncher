package com.mcserver.launcher.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.widget.Toast
import com.mcserver.launcher.ui.components.PageTransition
import com.mcserver.launcher.ui.components.pressScale
import com.mcserver.launcher.ui.components.pressSource
import com.mcserver.launcher.core.env.EnvManager
import com.mcserver.launcher.core.server.InstanceStore
import com.mcserver.launcher.data.InstanceStatus
import com.mcserver.launcher.data.ServerInstance
import com.mcserver.launcher.util.Logger
import kotlinx.coroutines.launch

/** 首页:实例列表 + 新建入口 */
@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val instances by InstanceStore.instances.collectAsState()
    val envReady = EnvManager.isEnvironmentReady()
    var showNew by remember { mutableStateOf(false) }
    var selectedInstance by remember { mutableStateOf<ServerInstance?>(null) }

    val current = selectedInstance
    val navTarget = when {
        current != null -> 1
        showNew -> 2
        else -> 0
    }

    // 系统返回键:先关详情页/新建页,而不是直接退出 App
    androidx.activity.compose.BackHandler(enabled = selectedInstance != null) { selectedInstance = null }
    androidx.activity.compose.BackHandler(enabled = showNew) { showNew = false }

    PageTransition(navTarget, modifier) { target ->
        when (target) {
            1 -> {
                // 用 remember 固定本次进入详情页的实例:
                // AnimatedContent 退出动画期间会重组旧 content,
                // 此时 selectedInstance 已为 null,若直接引用 current!! 会 NPE 崩溃
                val detail = remember { current }
                if (detail != null) {
                    InstanceDetailScreen(instance = detail, onBack = { selectedInstance = null })
                }
            }
            2 -> NewInstanceScreen(onDone = { showNew = false })
            else -> HomeContent(
                instances = instances,
                envReady = envReady,
                onNew = { showNew = true },
                onOpen = { selectedInstance = it },
                onStart = { scope.launch {
                    val result = com.mcserver.launcher.core.server.ServerManager.start(it)
                    if (result.isFailure) {
                        Toast.makeText(context, result.exceptionOrNull()?.message ?: "启动失败", Toast.LENGTH_LONG).show()
                    }
                } },
                onStop = { scope.launch { com.mcserver.launcher.core.server.ServerManager.stop() } }
            )
        }
    }
}

@Composable
private fun HomeContent(
    instances: List<ServerInstance>,
    envReady: Boolean,
    onNew: () -> Unit,
    onOpen: (ServerInstance) -> Unit,
    onStart: (ServerInstance) -> Unit,
    onStop: (ServerInstance) -> Unit
) {
    Scaffold(
        floatingActionButton = {
            val (pressFab, srcFab) = pressSource()
            FloatingActionButton(onClick = onNew, interactionSource = srcFab, modifier = pressFab) {
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
                        InstanceCard(
                            instance,
                            onClick = { onOpen(instance) },
                            onStart = { onStart(instance) },
                            onStop = { onStop(instance) }
                        )
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
    val interaction = remember { MutableInteractionSource() }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .pressScale(interaction)
            .clickable(interactionSource = interaction, indication = ripple(), onClick = onClick)
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
                Text("端口 ${instance.config.serverPort} · 最多 ${instance.config.maxPlayers} 人",
                    style = MaterialTheme.typography.labelSmall,
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
