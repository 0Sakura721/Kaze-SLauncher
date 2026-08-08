package com.mcserver.launcher.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mcserver.launcher.core.env.EnvManager
import com.mcserver.launcher.core.env.EnvState
import com.mcserver.launcher.ui.theme.KazeSuccess
import com.mcserver.launcher.util.FileFormat
import kotlinx.coroutines.launch

/**
 * 环境初始化页(可选):设置页里手动触发部署兼容环境(proot 旧兼容模式)。
 * Java 运行时不在此页部署——请在设置页「Java 运行时」本地导入或在线下载。
 */
@Composable
fun EnvSetupScreen(onSetupComplete: () -> Unit) {
    val scope = rememberCoroutineScope()
    val state by EnvManager.state.collectAsState()
    val items by EnvManager.items.collectAsState()
    val logs by EnvManager.log.collectAsState()

    // 进入即尝试部署(proot 兼容层,不包含 Java)
    LaunchedEffect(Unit) {
        if (EnvManager.state.value != EnvState.SETTING_UP) {
            scope.launch { EnvManager.runFullSetup(emptyList()) }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
        ) {
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state != EnvState.READY) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.width(12.dp))
                }
                Text(
                    if (state == EnvState.READY) "环境已就绪"
                    else if (state == EnvState.ERROR) "环境部署失败"
                    else "正在初始化兼容环境…",
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold)
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "部署旧版 proot + rootfs 兼容层(给 glibc 版 JDK 兜底使用);\n" +
                    "Java 运行时请另行到「设置 → Java 运行时」本地导入或下载。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(20.dp))

            // 部署项进度(带进度条与数据)
            items.forEach { item ->
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (item.done) {
                            Icon(Icons.Filled.Check, null, tint = KazeSuccess, modifier = Modifier.size(18.dp))
                        } else {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(item.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (item.done) "完成"
                            else if (item.phase.isNotBlank()) item.phase
                            else "等待中",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (item.done) KazeSuccess else MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.weight(1f))
                        // 数据:已处理 / 总大小 / 速度
                        if (item.processedBytes > 0 || item.totalBytes > 0) {
                            Text(
                                "${FileFormat.size(item.processedBytes)} / ${FileFormat.size(item.totalBytes)}" +
                                    if (item.speedBytes > 0) " · ${FileFormat.size(item.speedBytes)}/s" else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (!item.done && item.totalBytes > 0) {
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { item.progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(6.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            // 日志
            Surface(
                Modifier.fillMaxWidth().heightIn(max = 260.dp),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                LazyColumn(Modifier.padding(10.dp)) {
                    items(logs.takeLast(40)) { line ->
                        Text(line, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            when (state) {
                EnvState.READY -> {
                    Button(onClick = onSetupComplete, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Check, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("进入主界面")
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("提示:服务器需要 Java,请到「设置 → Java 运行时」本地导入或下载",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                EnvState.ERROR -> {
                    Button(onClick = {
                        scope.launch { EnvManager.runFullSetup(emptyList()) }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("重试部署")
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onSetupComplete, modifier = Modifier.fillMaxWidth()) {
                        Text("跳过,直接进入主界面(稍后在设置中处理)")
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("部署失败:${logs.lastOrNull() ?: "未知错误"}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
                else -> {
                    Text("正在解压部署,请勿关闭应用…", color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
