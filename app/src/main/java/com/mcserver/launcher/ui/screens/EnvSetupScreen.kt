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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mcserver.launcher.core.env.EnvManager
import com.mcserver.launcher.core.env.EnvState
import kotlinx.coroutines.launch

/** 可选的 Java 版本 */
private val jdkOptions = listOf(
    8 to "Java 8(Minecraft 1.8-1.12)",
    11 to "Java 11(Minecraft 1.13-1.16)",
    17 to "Java 17(Minecraft 1.17-1.20.4)",
    21 to "Java 21(Minecraft 1.20.5+)"
)

/**
 * 环境部署向导:必需组件(proot/Ubuntu,内置)锁定 + Java 可选勾选。
 */
@Composable
fun EnvSetupScreen(onSetupComplete: () -> Unit) {
    val scope = rememberCoroutineScope()
    val state by EnvManager.state.collectAsState()
    val items by EnvManager.items.collectAsState()
    val logs by EnvManager.log.collectAsState()

    var selectedJdks by remember { mutableStateOf(setOf(8, 11, 17, 21)) }
    var started by remember { mutableStateOf(false) }

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
            Text("环境初始化", style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold))
            Text(
                "部署 proot + Ubuntu 24.04(内置,不消耗流量),并按需安装 Java",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(20.dp))

            if (!started) {
                // ── 选择阶段 ──
                Text("必需组件(已内置,无需下载):", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                listOf("proot 运行时(解压即用)", "Ubuntu 24.04(解压即用,约 30 MB)")
                    .forEach { label ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                            Checkbox(checked = true, onCheckedChange = null, enabled = false)
                            Text(label, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                Spacer(Modifier.height(12.dp))
                Text("选择需要安装的 Java 版本(可选,不勾选则跳过,之后可在设置页补装):",
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                jdkOptions.forEach { (version, label) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .padding(vertical = 2.dp)
                    ) {
                        Checkbox(
                            checked = version in selectedJdks,
                            onCheckedChange = {
                                selectedJdks = if (version in selectedJdks) selectedJdks - version else selectedJdks + version
                            }
                        )
                        Text(label, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (selectedJdks.isEmpty()) {
                    Text("不勾选任何 Java 也可以完成部署,之后可在「设置」中随时补装",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("预计需下载 ~${selectedJdks.size * 80} MB(每个 Java 约 80 MB),请确保网络已连接",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(20.dp))
                Button(onClick = {
                    started = true
                    scope.launch { EnvManager.runFullSetup(selectedJdks.toList()) }
                }) {
                    Icon(Icons.Filled.Download, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("开始部署")
                }
            } else {
                // ── 部署进度 ──
                items.forEach { item ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (item.done) {
                            Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        } else {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(item.name, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.width(8.dp))
                        Text(item.desc, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(16.dp))
                // 日志
                Surface(
                    Modifier.fillMaxWidth().heightIn(max = 220.dp),
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
                        Button(onClick = onSetupComplete) {
                            Icon(Icons.Filled.Check, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("完成,进入主界面")
                        }
                    }
                    EnvState.ERROR -> {
                        OutlinedButton(onClick = {
                            scope.launch { EnvManager.runFullSetup(selectedJdks.toList()) }
                        }) {
                            Text("重试")
                        }
                        Text("部署失败:${logs.lastOrNull() ?: ""}",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    else -> {
                        Text("部署中,请勿关闭应用...", color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
