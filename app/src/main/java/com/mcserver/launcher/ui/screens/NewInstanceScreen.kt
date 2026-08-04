package com.mcserver.launcher.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mcserver.launcher.core.download.CoreSources
import com.mcserver.launcher.core.download.DownloadCenter
import com.mcserver.launcher.core.server.InstanceStore
import com.mcserver.launcher.data.CoreType
import com.mcserver.launcher.data.InstanceConfig
import kotlinx.coroutines.launch

/**
 * 新建实例向导:选择核心类型 → MC 版本 → 创建实例并加入下载中心。
 */
@Composable
fun NewInstanceScreen(onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(1) }
    var coreType by remember { mutableStateOf(CoreType.PAPER) }
    var versions by remember { mutableStateOf<List<com.mcserver.launcher.core.download.CoreVersion>>(emptyList()) }
    var selectedVersion by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (step > 1) step-- else onDone() }) {
                Icon(Icons.Filled.ArrowBack, "返回")
            }
            Spacer(Modifier.width(8.dp))
            Text(if (step == 1) "选择服务端类型" else "选择 MC 版本",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
        }
        Spacer(Modifier.height(16.dp))

        when (step) {
            1 -> {
                // 核心类型网格
                val types = CoreType.entries
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(types) { type ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (type == coreType) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth().clickable {
                                coreType = type
                                loading = true
                                error = ""
                                scope.launch {
                                    CoreSources.fetchVersions(type)
                                        .onSuccess { versions = it; loading = false }
                                        .onFailure { error = "获取版本列表失败:${it.message}"; loading = false }
                                }
                                step = 2
                            }
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text(type.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    when (type) {
                                        CoreType.VANILLA -> "官方原版服务端"
                                        CoreType.PAPER -> "高性能,插件生态最丰富(推荐)"
                                        CoreType.PURPUR -> "Paper 分支,更多自定义"
                                        CoreType.SPIGOT -> "经典 Bukkit 分支"
                                        CoreType.FABRIC -> "模组向,轻量"
                                        CoreType.FORGE -> "经典模组加载器"
                                        CoreType.NEOFORGE -> "Forge 继任者,现代模组"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
            2 -> {
                // 版本列表
                if (loading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (error.isNotBlank()) {
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Spacer(Modifier.height(60.dp))
                        Text(error, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = {
                            loading = true; error = ""
                            scope.launch {
                                CoreSources.fetchVersions(coreType)
                                    .onSuccess { versions = it; loading = false }
                                    .onFailure { error = "获取版本列表失败:${it.message}"; loading = false }
                            }
                        }) { Text("重试") }
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(versions.take(40), key = { it.id }) { v ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (v.id == selectedVersion) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surface,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    selectedVersion = v.id
                                    creating = true
                                    // 创建实例并解析下载
                                    scope.launch {
                                        val instance = InstanceStore.create(
                                            name = "${v.id} ${coreType.displayName}",
                                            coreType = coreType,
                                            mcVersion = v.id
                                        )
                                        CoreSources.resolveDownload(coreType, v.id)
                                            .onSuccess { download ->
                                                val jarName = download.fileName
                                                DownloadCenter.enqueue(
                                                    id = "core-${instance.id}",
                                                    title = "${coreType.displayName} $jarName",
                                                    urls = listOf(download.url),
                                                    destFile = java.io.File(instance.dir(InstanceStore.instancesDir), jarName)
                                                )
                                                creating = false
                                                onDone()
                                            }
                                            .onFailure { err ->
                                                creating = false
                                                InstanceStore.delete(instance.id)
                                                error = "解析下载链接失败:${err.message}"
                                            }
                                    }
                                }
                            ) {
                                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(v.id, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                    Spacer(Modifier.weight(1f))
                                    if (!v.isStable) {
                                        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.errorContainer) {
                                            Text("测试版", Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onErrorContainer)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (creating) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("正在创建实例并解析下载链接...", Modifier.padding(vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
