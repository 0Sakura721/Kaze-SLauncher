package com.mcserver.launcher.ui.screens.tabs

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mcserver.launcher.core.server.InstalledAddon
import com.mcserver.launcher.core.server.PluginManager
import com.mcserver.launcher.data.ServerInstance
import com.mcserver.launcher.ui.components.LocalUiMessenger
import com.mcserver.launcher.ui.components.ModrinthSearchDialog
import com.mcserver.launcher.ui.theme.KazeCorners
import com.mcserver.launcher.ui.theme.KazeSpacing
import com.mcserver.launcher.util.FileImporter
import kotlinx.coroutines.launch

private val ADDON_GREEN = Color(0xFF4CAF50)

/**
 * 插件/模组 Tab:本地导入(优先)+ Modrinth 在线搜索 + 已装列表(启用/禁用/删除)。
 */
@Composable
fun AddonTab(instance: ServerInstance) {
    val context = LocalContext.current
    val messenger = LocalUiMessenger.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var addons by remember { mutableStateOf<List<InstalledAddon>>(emptyList()) }
    var showSearch by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                val dest = PluginManager.addonDir(instance)
                uris.forEach { uri ->
                    FileImporter.copyFile(context, uri, dest).onFailure {
                        messenger.toastError("导入失败:${it.message}")
                    }
                }
                addons = PluginManager.list(instance)
            }
        }
    }

    LaunchedEffect(instance.id) { addons = PluginManager.list(instance) }

    Column(Modifier.fillMaxSize().padding(KazeSpacing.md)) {
        Row {
            Button(
                onClick = { importLauncher.launch(arrayOf("application/java-archive", "application/octet-stream")) },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Filled.FolderOpen, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("从本地导入(推荐,不耗流量)")
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { showSearch = true }) {
                Icon(Icons.Filled.Search, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("在线搜索")
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "目录:${PluginManager.dirLabel(instance)} · 已装 ${addons.size} 个(禁用文件以 .disabled 结尾)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))

        if (addons.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "还没有${PluginManager.dirLabel(instance)},点上方按钮导入本地文件或在线搜索",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(addons, key = { it.file.name }) { addon ->
                    AddonRow(
                        addon = addon,
                        onToggle = {
                            scope.launch {
                                PluginManager.toggleEnabled(instance, addon.file.name)
                                addons = PluginManager.list(instance)
                            }
                        },
                        onDelete = {
                            scope.launch {
                                PluginManager.delete(instance, addon.file.name)
                                addons = PluginManager.list(instance)
                            }
                        }
                    )
                }
            }
        }
    }

    if (showSearch) {
        ModrinthSearchDialog(
            instance = instance,
            onDismiss = { showSearch = false },
            onInstalled = { scope.launch { addons = PluginManager.list(instance) } }
        )
    }
}

@Composable
private fun AddonRow(
    addon: InstalledAddon,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    val disabled = addon.file.name.endsWith(".disabled")
    Surface(
        shape = KazeCorners.medium,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    addon.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (disabled) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    if (disabled) "已禁用" else "已启用",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (disabled) MaterialTheme.colorScheme.error else ADDON_GREEN
                )
            }
            TextButton(onClick = onToggle) { Text(if (disabled) "启用" else "禁用") }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, "删除", Modifier.size(18.dp))
            }
        }
    }
}
