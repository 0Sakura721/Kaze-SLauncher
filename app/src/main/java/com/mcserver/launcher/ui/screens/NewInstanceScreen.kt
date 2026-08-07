package com.mcserver.launcher.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mcserver.launcher.core.download.CoreSources
import com.mcserver.launcher.core.download.DownloadCenter
import com.mcserver.launcher.ui.components.PageTransition
import com.mcserver.launcher.ui.components.pressScale
import com.mcserver.launcher.ui.components.pressSource
import com.mcserver.launcher.core.server.InstanceStore
import com.mcserver.launcher.data.CoreType
import com.mcserver.launcher.data.InstanceConfig
import com.mcserver.launcher.util.FileImporter
import kotlinx.coroutines.launch

/** 从文件名猜测核心类型与 MC 版本(导入本地 JAR 用) */
private fun guessFromFileName(name: String): Pair<CoreType, String> {
    val lower = name.lowercase()
    val type = when {
        lower.contains("spigot") -> CoreType.SPIGOT
        lower.contains("paper") || lower.contains("purpur") -> CoreType.PAPER
        lower.contains("fabric") -> CoreType.FABRIC
        lower.contains("forge") -> CoreType.FORGE
        lower.contains("neoforge") -> CoreType.NEOFORGE
        else -> CoreType.VANILLA
    }
    val version = Regex("(\\d+\\.\\d+(\\.\\d+)?)").find(name)?.groupValues?.get(1) ?: "导入"
    return type to version
}

/**
 * 新建实例向导:选择核心类型 → MC 版本 → 创建实例并加入下载中心。
 */
@Composable
fun NewInstanceScreen(onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var step by remember { mutableStateOf(1) }
    var coreType by remember { mutableStateOf(CoreType.PAPER) }
    var versions by remember { mutableStateOf<List<com.mcserver.launcher.core.download.CoreVersion>>(emptyList()) }
    var selectedVersion by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }

    // SAF:选择本地服务端 JAR(本地导入优先,不消耗流量)
    val importJarLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            importing = true
            scope.launch {
                // 先建临时目录复制,再创建实例
                val importDir = java.io.File(com.mcserver.launcher.KazeApp.instance.filesDir, "import_jar")
                if (importDir.exists()) importDir.deleteRecursively()
                FileImporter.copyFile(context, uri, importDir)
                    .onSuccess { jarFile ->
                        val (type, version) = guessFromFileName(jarFile.name)
                        val instance = InstanceStore.create(
                            name = jarFile.name.removeSuffix(".jar"),
                            coreType = type,
                            mcVersion = version
                        )
                        // 移动 jar 到实例目录
                        jarFile.copyTo(java.io.File(instance.dir(InstanceStore.instancesDir), jarFile.name), overwrite = true)
                        importDir.deleteRecursively()
                        importing = false
                        onDone()
                    }
                    .onFailure { err ->
                        importing = false
                        error = "导入失败:${err.message}"
                    }
            }
        }
    }

    // 系统返回键:版本页 → 回类型页;类型页 → 关闭新建
    androidx.activity.compose.BackHandler { if (step > 1) step-- else onDone() }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (pressBack, srcBack) = pressSource()
            IconButton(onClick = { if (step > 1) step-- else onDone() }, interactionSource = srcBack, modifier = pressBack) {
                Icon(Icons.Filled.ArrowBack, "返回")
            }
            Spacer(Modifier.width(8.dp))
            Text(if (step == 1) "选择服务端类型" else "选择 MC 版本",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
        }
        Spacer(Modifier.height(16.dp))

        PageTransition(step) { s ->
            when (s) {
                1 -> {
                Column(Modifier.fillMaxSize()) {
                    // 本地导入优先入口(不消耗流量)
                    val (pressImport, srcImport) = pressSource()
                    Button(
                        onClick = { importJarLauncher.launch(arrayOf("application/java-archive", "application/octet-stream")) },
                        modifier = pressImport.then(Modifier.fillMaxWidth()),
                        interactionSource = srcImport
                    ) {
                        Icon(Icons.Filled.FolderOpen, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (importing) "正在导入..." else "从本地导入服务端 JAR(推荐,不消耗流量)")
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("或从以下来源下载:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    // 核心类型网格
                    val types = CoreType.entries
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(types) { type ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (type == coreType) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surface,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .pressScale(remember { MutableInteractionSource() })
                                    .clickable {
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
        }
        if (creating) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("正在创建实例并解析下载链接...", Modifier.padding(vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}