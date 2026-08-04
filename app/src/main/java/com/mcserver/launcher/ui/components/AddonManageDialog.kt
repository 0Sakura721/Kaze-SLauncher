package com.mcserver.launcher.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import com.mcserver.launcher.core.server.InstalledAddon
import com.mcserver.launcher.core.server.PluginManager
import com.mcserver.launcher.data.CoreType
import com.mcserver.launcher.data.ServerInstance
import com.mcserver.launcher.util.FileImporter
import kotlinx.coroutines.launch

/**
 * 插件/模组管理对话框(全局入口,下载页/详情页复用):
 * 本地导入(优先,不耗流量)+ Modrinth 在线搜索(备选)+ 已装列表(启用/禁用/删除)
 */
@Composable
fun AddonManageDialog(
    instance: ServerInstance,
    onDismiss: () -> Unit,
    onInstalled: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var addons by remember { mutableStateOf<List<InstalledAddon>>(emptyList()) }
    var showSearch by remember { mutableStateOf(false) }

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
                onInstalled()
            }
        }
    }

    LaunchedEffect(instance.id) { addons = PluginManager.list(instance) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("插件/模组 · ${instance.name}") },
        text = {
            Column {
                Row {
                    Button(onClick = {
                        importLauncher.launch(arrayOf("application/java-archive", "application/octet-stream"))
                    }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.FolderOpen, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("本地导入")
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { showSearch = true }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Search, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("在线搜索")
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("目录:${PluginManager.dirLabel(instance)} · 已装 ${addons.size} 个",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))

                if (addons.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                        Text("还没有${PluginManager.dirLabel(instance)},点上方按钮导入本地文件或在线搜索",
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(Modifier.heightIn(max = 280.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(addons, key = { it.file.name }) { addon ->
                            val disabled = addon.file.name.endsWith(".disabled")
                            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.fillMaxWidth()) {
                                Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(addon.name, style = MaterialTheme.typography.bodyMedium,
                                            color = if (disabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                                        Text(if (disabled) "已禁用" else "已启用", style = MaterialTheme.typography.labelSmall,
                                            color = if (disabled) MaterialTheme.colorScheme.error else Color(0xFF4CAF50))
                                    }
                                    TextButton(onClick = {
                                        scope.launch {
                                            PluginManager.toggleEnabled(instance, addon.file.name)
                                            addons = PluginManager.list(instance)
                                        }
                                    }) { Text(if (disabled) "启用" else "禁用") }
                                    IconButton(onClick = {
                                        scope.launch {
                                            PluginManager.delete(instance, addon.file.name)
                                            addons = PluginManager.list(instance)
                                        }
                                    }) { Icon(Icons.Filled.Delete, "删除", Modifier.size(18.dp)) }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )

    if (showSearch) {
        ModrinthSearchDialog(
            instance = instance,
            onDismiss = { showSearch = false },
            onInstalled = { scope.launch { addons = PluginManager.list(instance); onInstalled() } }
        )
    }
}

/** Modrinth 在线搜索对话框(备选,需网络) */
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
