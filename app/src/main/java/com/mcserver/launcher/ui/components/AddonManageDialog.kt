package com.mcserver.launcher.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mcserver.launcher.core.download.DownloadCenter
import com.mcserver.launcher.core.download.ModrinthApi
import com.mcserver.launcher.core.download.ModrinthVersion
import com.mcserver.launcher.core.server.InstalledAddon
import com.mcserver.launcher.core.server.PluginManager
import com.mcserver.launcher.data.CoreType
import com.mcserver.launcher.data.ServerInstance
import com.mcserver.launcher.util.FileImporter
import kotlinx.coroutines.launch

/**
 * 插件/模组管理(全屏页面):
 * - 插件 / 模组两个区域(加载器不同)
 * - MC 版本可选(默认实例版本)
 * - 搜索结果可再选具体版本下载
 * - 本地导入(优先)+ 已装列表(启用/禁用/删除)
 */
@Composable
fun AddonManageScreen(instance: ServerInstance, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var addons by remember { mutableStateOf<List<InstalledAddon>>(emptyList()) }

    // 插件区 / 模组区
    var section by remember { mutableStateOf(if (instance.coreType in listOf(CoreType.FABRIC, CoreType.FORGE, CoreType.NEOFORGE)) 1 else 0) }
    val pluginLoaders = listOf("paper", "spigot", "bukkit", "velocity")
    val modLoaders = listOf("fabric", "forge", "neoforge", "quilt")
    var loaderIndex by remember { mutableStateOf(0) }
    val loader = if (section == 0) pluginLoaders[loaderIndex] else modLoaders[loaderIndex]
    var showVersionPicker by remember { mutableStateOf(false) }
    var showLoaderPicker by remember { mutableStateOf(false) }

    // MC 版本选择(默认实例版本)
    val mcVersions = remember { listOf(instance.mcVersion) + listOf("1.21.11", "1.21.10", "1.21.9", "1.21.8", "1.21.7", "1.21.6", "1.21.5", "1.21.4", "1.21.3", "1.21.2", "1.21.1", "1.21", "1.20.6", "1.20.4", "1.20.1", "1.19.4", "1.18.2", "1.17.1", "1.16.5", "1.15.2", "1.14.4", "1.12.2", "1.8.9").distinct() }
    var mcVersion by remember { mutableStateOf(instance.mcVersion) }

    // 搜索状态
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<com.mcserver.launcher.core.download.ModrinthHit>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf("") }
    var versionTarget by remember { mutableStateOf<com.mcserver.launcher.core.download.ModrinthHit?>(null) }
    var versions by remember { mutableStateOf<List<ModrinthVersion>>(emptyList()) }
    var loadingVersions by remember { mutableStateOf(false) }

    // 本地导入(优先,不消耗流量)
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri>? ->
        if (!uris.isNullOrEmpty()) {
            scope.launch {
                val dest = PluginManager.addonDir(instance)
                uris.forEach { uri ->
                    FileImporter.copyFile(context, uri, dest).onFailure {
                        android.widget.Toast.makeText(context, "导入失败:${it.message}", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
                addons = PluginManager.list(instance)
            }
        }
    }

    LaunchedEffect(instance.id) { addons = PluginManager.list(instance) }

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // 顶部栏
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            Column(Modifier.weight(1f)) {
                Text("插件/模组管理", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("${instance.name} · ${PluginManager.dirLabel(instance)}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // 插件 / 模组 两个区域
        TabRow(selectedTabIndex = section) {
            Tab(selected = section == 0, onClick = { section = 0; loaderIndex = 0 }, text = { Text("插件") })
            Tab(selected = section == 1, onClick = { section = 1; loaderIndex = 0 }, text = { Text("模组") })
        }

        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            // 下拉选择器(用 AlertDialog 实现)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { showVersionPicker = true }, modifier = Modifier.height(32.dp)) {
                    Text("${mcVersion} ▾", style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { showLoaderPicker = true }, modifier = Modifier.height(32.dp)) {
                    Text("${loader} ▾", style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = {
                    importLauncher.launch(arrayOf("application/java-archive", "application/octet-stream"))
                }, modifier = Modifier.height(32.dp)) {
                    Icon(Icons.Filled.FolderOpen, null, Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("本地导入", style = MaterialTheme.typography.labelMedium)
                }
            }

            // 搜索
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("搜索名称,如: EssentialsX / Lithium") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    if (query.isBlank()) return@Button
                    searching = true; searchError = ""
                    scope.launch {
                        ModrinthApi.search(query, mcVersion, loader)
                            .onSuccess { results = it }
                            .onFailure { searchError = "搜索失败:${it.message}" }
                        searching = false
                    }
                }) { Text("搜索") }
            }

            // 已装列表
            Spacer(Modifier.height(8.dp))
            Text("已装 ${addons.size} 个 · ${PluginManager.dirLabel(instance)}",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (addons.isNotEmpty()) {
                LazyColumn(Modifier.heightIn(max = 160.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(addons, key = { it.file.name }) { addon ->
                        val disabled = addon.file.name.endsWith(".disabled")
                        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(horizontal = 10.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(addon.name, style = MaterialTheme.typography.bodySmall,
                                        color = if (disabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                                    Text(if (disabled) "已禁用" else "已启用", style = MaterialTheme.typography.labelSmall,
                                        color = if (disabled) MaterialTheme.colorScheme.error else Color(0xFF4CAF50))
                                }
                                TextButton(onClick = {
                                    scope.launch {
                                        PluginManager.toggleEnabled(instance, addon.file.name)
                                        addons = PluginManager.list(instance)
                                    }
                                }) { Text(if (disabled) "启用" else "禁用", style = MaterialTheme.typography.labelSmall) }
                                IconButton(onClick = {
                                    scope.launch {
                                        PluginManager.delete(instance, addon.file.name)
                                        addons = PluginManager.list(instance)
                                    }
                                }, modifier = Modifier.size(28.dp)) { Icon(Icons.Filled.Delete, "删除", Modifier.size(14.dp)) }
                            }
                        }
                    }
                }
            }
        }

        // 搜索结果
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                searching -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                searchError.isNotBlank() -> Text(searchError, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center))
                results.isEmpty() -> Text("输入关键词搜索 $loader 加载器的${if (section == 0) "插件" else "模组"}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center))
                else -> LazyColumn(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(results, key = { it.projectId }) { hit ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surface,
                            tonalElevation = 2.dp,
                            modifier = Modifier.fillMaxWidth().clickable {
                                // 点结果 → 选版本
                                versionTarget = hit
                                loadingVersions = true
                                scope.launch {
                                    ModrinthApi.fetchVersions(hit.projectId, mcVersion, loader)
                                        .onSuccess { versions = it }
                                        .onFailure { versions = emptyList(); searchError = "获取版本失败:${it.message}" }
                                    loadingVersions = false
                                }
                            }
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(hit.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                Text(hit.description.take(70), style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }

    // MC 版本选择
    if (showVersionPicker) {
        AlertDialog(
            onDismissRequest = { showVersionPicker = false },
            title = { Text("选择 MC 版本") },
            text = {
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(mcVersions) { v ->
                        TextButton(onClick = { mcVersion = v; showVersionPicker = false }, modifier = Modifier.fillMaxWidth()) {
                            Text(v, fontWeight = if (v == mcVersion) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showVersionPicker = false }) { Text("关闭") } }
        )
    }

    // 加载器选择
    if (showLoaderPicker) {
        val list = if (section == 0) pluginLoaders else modLoaders
        AlertDialog(
            onDismissRequest = { showLoaderPicker = false },
            title = { Text("选择加载器") },
            text = {
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(list) { l ->
                        TextButton(onClick = { loaderIndex = list.indexOf(l); showLoaderPicker = false }, modifier = Modifier.fillMaxWidth()) {
                            Text(l, fontWeight = if (l == loader) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showLoaderPicker = false }) { Text("关闭") } }
        )
    }

    // 版本选择下载
    versionTarget?.let { hit ->
        AlertDialog(
            onDismissRequest = { versionTarget = null },
            title = { Text("选择版本 · ${hit.title}") },
            text = {
                if (loadingVersions) {
                    Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp))
                    }
                } else if (versions.isEmpty()) {
                    Text("该 MC 版本 + 加载器没有可用文件", color = MaterialTheme.colorScheme.error)
                } else {
                    LazyColumn(Modifier.heightIn(max = 340.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(versions, key = { it.id }) { v ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    if (v.url.isBlank()) return@clickable
                                    DownloadCenter.enqueue(
                                        id = "addon-${v.id}",
                                        title = "${hit.title} ${v.versionNumber}",
                                        urls = listOf(v.url),
                                        destFile = java.io.File(PluginManager.addonDir(instance), v.fileName)
                                    )
                                    versionTarget = null
                                    scope.launch { addons = PluginManager.list(instance) }
                                    android.widget.Toast.makeText(context, "已加入下载队列", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Column(Modifier.padding(10.dp)) {
                                    Text(v.name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                    Text("${v.versionNumber} · ${v.datePublished}", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { versionTarget = null }) { Text("关闭") } }
        )
    }
}

/** Modrinth 在线搜索对话框(详情页插件 tab 用,备选) */
@Composable
fun ModrinthSearchDialog(
    instance: ServerInstance,
    onDismiss: () -> Unit,
    onInstalled: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<com.mcserver.launcher.core.download.ModrinthHit>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    // 加载器:模组核心 fabric/forge/neoforge,插件核心 paper
    val loader = when (instance.coreType) {
        CoreType.FABRIC -> "fabric"
        CoreType.FORGE -> "forge"
        CoreType.NEOFORGE -> "neoforge"
        else -> "paper"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("搜索 ${PluginManager.dirLabel(instance)}") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("名称,如: EssentialsX / Lithium") },
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    if (query.isBlank()) return@Button
                    loading = true; error = ""
                    scope.launch {
                        ModrinthApi.search(query, instance.mcVersion, loader)
                            .onSuccess { results = it }
                            .onFailure { error = "搜索失败:${it.message}" }
                        loading = false
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("搜索(需网络,${instance.mcVersion} ${loader})") }
                Spacer(Modifier.height(8.dp))
                if (loading) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                } else if (error.isNotBlank()) {
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                } else {
                    LazyColumn(Modifier.heightIn(max = 260.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(results) { hit ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    loading = true
                                    scope.launch {
                                        ModrinthApi.resolveDownload(hit.projectId, instance.mcVersion, loader)
                                            .onSuccess { dl ->
                                                DownloadCenter.enqueue(
                                                    id = "addon-${hit.projectId}",
                                                    title = hit.title,
                                                    urls = listOf(dl.url),
                                                    destFile = java.io.File(PluginManager.addonDir(instance), dl.fileName)
                                                )
                                                loading = false
                                                onInstalled()
                                                onDismiss()
                                            }
                                            .onFailure { error = "获取下载失败:${it.message}"; loading = false }
                                    }
                                }
                            ) {
                                Column(Modifier.padding(10.dp)) {
                                    Text(hit.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                    Text(hit.description.take(60), style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}
