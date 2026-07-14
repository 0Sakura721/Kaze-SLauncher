package com.mcserver.launcher.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mcserver.launcher.server.ResourcePackManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResourcePacksScreen() {
    val scope = rememberCoroutineScope()
    var packs by remember { mutableStateOf<List<ResourcePackManager.PackInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<ResourcePackManager.PackInfo?>(null) }

    // 资源包配置
    var packConfig by remember { mutableStateOf(ResourcePackManager.PackConfig()) }
    var showConfigDialog by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            loading = true
            packs = ResourcePackManager.listPacks()
            packConfig = ResourcePackManager.getPackConfig()
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }
    LaunchedEffect(message) {
        if (message != null) {
            kotlinx.coroutines.delay(3000)
            message = null
        }
    }

    confirmDelete?.let { pack ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            icon = { Icon(Icons.Filled.DeleteForever, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("删除资源包") },
            text = { Text("确定删除「${pack.name}」吗？此操作不可撤销。") },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            ResourcePackManager.deletePack(pack)
                            confirmDelete = null
                            refresh()
                            message = "已删除：${pack.name}"
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("取消") } }
        )
    }

    // 配置对话框
    if (showConfigDialog) {
        var requirePack by remember { mutableStateOf(packConfig.requireResourcePack) }
        var packUrl by remember { mutableStateOf(packConfig.resourcePackUrl) }
        var packSha1 by remember { mutableStateOf(packConfig.resourcePackSha1) }
        var packPrompt by remember { mutableStateOf(packConfig.resourcePackPrompt) }

        AlertDialog(
            onDismissRequest = { showConfigDialog = false },
            icon = { Icon(Icons.Filled.Settings, null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("资源包设置") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("这些设置将写入 server.properties，重启服务器后生效。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("强制资源包")
                        Switch(checked = requirePack, onCheckedChange = { requirePack = it })
                    }
                    OutlinedTextField(
                        value = packUrl,
                        onValueChange = { packUrl = it },
                        label = { Text("资源包 URL") },
                        placeholder = { Text("https://example.com/pack.zip") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = packSha1,
                        onValueChange = { packSha1 = it },
                        label = { Text("SHA1 哈希（可选）") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = packPrompt,
                        onValueChange = { packPrompt = it },
                        label = { Text("提示文本（可选）") },
                        placeholder = { Text("请安装服务器资源包") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        val newConfig = packConfig.copy(
                            requireResourcePack = requirePack,
                            resourcePackUrl = packUrl,
                            resourcePackSha1 = packSha1,
                            resourcePackPrompt = packPrompt
                        )
                        ResourcePackManager.savePackConfig(newConfig)
                        packConfig = newConfig
                        showConfigDialog = false
                        message = "资源包设置已保存"
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showConfigDialog = false }) { Text("取消") } }
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("资源包管理", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            IconButton(onClick = { refresh() }) { Icon(Icons.Filled.Refresh, "刷新") }
        }

        // 配置概览
        Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("资源包设置", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { showConfigDialog = true }) {
                        Icon(Icons.Filled.Edit, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("编辑")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("强制资源包", style = MaterialTheme.typography.bodySmall)
                    Text(if (packConfig.requireResourcePack) "是" else "否",
                        style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                }
                if (packConfig.resourcePackUrl.isNotBlank()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("URL", style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(0.3f))
                        Text(packConfig.resourcePackUrl, style = MaterialTheme.typography.bodySmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(0.7f),
                            color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }

        // 资源包列表
        Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("本地资源包", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text("${packs.size} 个", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "将 .zip 资源包文件放入服务器的 resourcepacks/ 目录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (loading) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (packs.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Inventory, null, Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("暂无资源包", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(
                            "将资源包 .zip 文件放入 resourcepacks/ 目录。\n通过上方设置可配置强制资源包和下载 URL。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(packs, key = { it.fileName }) { pack ->
                    PackCard(
                        pack = pack,
                        onToggle = {
                            scope.launch {
                                ResourcePackManager.togglePack(pack)
                                refresh()
                                message = if (pack.enabled) "已禁用：${pack.name}" else "已启用：${pack.name}"
                            }
                        },
                        onDelete = { confirmDelete = pack }
                    )
                }
            }
        }

        message?.let {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Info, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun PackCard(
    pack: ResourcePackManager.PackInfo,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (pack.enabled) Icons.Filled.Inventory else Icons.Filled.Inventory2,
                null,
                tint = if (pack.enabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(pack.name, style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium, maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        shape = MaterialTheme.shapes.extraSmall,
                        color = if (pack.enabled) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            if (pack.enabled) "启用" else "禁用",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            color = if (pack.enabled) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    "${ResourcePackManager.formatSize(pack.fileSize)} | ${if (pack.isZip) "ZIP" else "文件"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onToggle) {
                Icon(
                    if (pack.enabled) Icons.Filled.Block else Icons.Filled.CheckCircle,
                    if (pack.enabled) "禁用" else "启用",
                    tint = if (pack.enabled) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.primary
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.DeleteOutline, "删除", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}
