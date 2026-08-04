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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mcserver.launcher.core.env.EnvManager
import com.mcserver.launcher.core.env.EnvState
import kotlinx.coroutines.launch

/**
 * 环境初始化:进入即自动部署(解压内置 proot + Ubuntu,零下载零操作)。
 * Java 不在初始化中下载——按需到设置页本地导入或下载。
 */
@Composable
fun EnvSetupScreen(onSetupComplete: () -> Unit) {
    val scope = rememberCoroutineScope()
    val state by EnvManager.state.collectAsState()
    val items by EnvManager.items.collectAsState()
    val logs by EnvManager.log.collectAsState()

    // 进入即自动开始部署(只装必需组件,不下载 Java)
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
                    else "正在初始化环境...",
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold)
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "自动解压内置的 proot 与 Ubuntu 24.04(不消耗流量);Java 不需要在这里安装,之后在「设置」中按需导入或下载",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(20.dp))

            // 部署项进度
            items.forEach { item ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (item.done) {
                        Icon(Icons.Filled.Check, null, tint = Color(0xFF4CAF50), modifier = Modifier.size(18.dp))
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
                    Text("部署失败:${logs.lastOrNull() ?: "未知错误"}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
                else -> {
                    Text("正在解压部署,请勿关闭应用...", color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
