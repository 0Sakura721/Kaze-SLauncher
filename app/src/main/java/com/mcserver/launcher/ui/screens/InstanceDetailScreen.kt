package com.mcserver.launcher.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcserver.launcher.core.download.DownloadCenter
import com.mcserver.launcher.core.download.ModrinthApi
import com.mcserver.launcher.core.server.PluginManager
import com.mcserver.launcher.core.server.ServerManager
import com.mcserver.launcher.data.ServerInstance
import com.mcserver.launcher.util.FileImporter
import kotlinx.coroutines.launch
import java.io.File

/**
 * 实例详情:控制台(日志/命令)+ 插件模组管理(本地导入优先 + Modrinth 搜索)。
 */
@Composable
fun InstanceDetailScreen(instance: ServerInstance, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(0) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "返回") }
            Column(Modifier.weight(1f)) {
                Text(instance.name, style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Text(
                    "${instance.coreType.displayName} ${instance.mcVersion} · ${PluginManager.dirLabel(instance)} 目录",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            ServerControlButton(instance)
            IconButton(onClick = { showDeleteConfirm = true }) { Icon(Icons.Filled.Delete, "删除实例") }
        }
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("控制台") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("插件/模组") })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("配置") })
            Tab(selected = tab == 3, onClick = { tab = 3 }, text = { Text("世界") })
        }
        // 内容区固定高度(weight 1f),内部页面各自滚动
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> ConsoleTab()
                1 -> AddonTab(instance)
                2 -> ConfigTab(instance)
                3 -> WorldTab(instance)
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除实例") },
            text = { Text("将删除「${instance.name}」及其全部文件(核心/世界/插件/模组),此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    scope.launch {
                        com.mcserver.launcher.core.server.InstanceStore.delete(instance.id)
                        onBack()
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") } }
        )
    }
}

/** 启动/停止按钮 */
@Composable
private fun ServerControlButton(instance: ServerInstance) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by ServerManager.status.collectAsState()
    val running = ServerManager.isRunningFor(instance.id) && status == com.mcserver.launcher.data.InstanceStatus.RUNNING
    if (running) {
        Button(onClick = { scope.launch { ServerManager.stop() } },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
            Icon(Icons.Filled.Stop, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("停止")
        }
    } else {
        Button(onClick = {
            scope.launch {
                val r = ServerManager.start(instance)
                if (r.isFailure) android.widget.Toast.makeText(context, r.exceptionOrNull()?.message ?: "启动失败", android.widget.Toast.LENGTH_LONG).show()
            }
        }) {
            Icon(Icons.Filled.PlayArrow, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("启动")
        }
    }
}

// ═══════════ 控制台 ═══════════

@Composable
private fun ConsoleTab() {
    val scope = rememberCoroutineScope()
    val status by ServerManager.status.collectAsState()
    val players by ServerManager.players.collectAsState()
    val uptime by ServerManager.uptimeSec.collectAsState()
    var command by remember { mutableStateOf("") }
    val logLines = remember { mutableStateListOf<String>() }

    LaunchedEffect(Unit) {
        ServerManager.console.collect { line ->
            logLines.add(line)
            if (logLines.size > 1000) logLines.removeRange(0, logLines.size - 1000)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Text(
            "${status.name} · 玩家 ${players.size} · 运行 ${uptime / 60}分${uptime % 60}秒",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        )
        LazyColumn(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                .padding(8.dp)
        ) {
            items(logLines.takeLast(500)) { line ->
                Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                    color = if (line.contains("ERROR") || line.contains("Exception")) MaterialTheme.colorScheme.error
                            else if (line.startsWith(">")) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 1.dp))
            }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = command, onValueChange = { command = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("输入命令,如: op Steve / say hi") },
                singleLine = true
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                if (command.isNotBlank()) { ServerManager.sendCommand(command.trim()); command = "" }
            }, enabled = status == com.mcserver.launcher.data.InstanceStatus.RUNNING) { Text("发送") }
        }
    }
}

// ═══════════ 插件/模组 ═══════════

@Composable
private fun AddonTab(instance: ServerInstance) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var addons by remember { mutableStateOf<List<com.mcserver.launcher.core.server.InstalledAddon>>(emptyList()) }
    var showSearch by remember { mutableStateOf(false) }

    // 本地导入(优先,不消耗流量)
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
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

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row {
            Button(onClick = { importLauncher.launch(arrayOf("application/java-archive", "application/octet-stream")) },
                modifier = Modifier.weight(1f)) {
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
        Text("目录:${PluginManager.dirLabel(instance)} · 已装 ${addons.size} 个(禁用文件以 .disabled 结尾)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))

        if (addons.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("还没有${PluginManager.dirLabel(instance)},点上方按钮导入本地文件或在线搜索",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(addons, key = { it.file.name }) { addon ->
                    val disabled = addon.file.name.endsWith(".disabled")
                    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(addon.name, style = MaterialTheme.typography.bodyMedium,
                                    color = if (disabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                                Text(if (disabled) "已禁用" else "已启用",
                                    style = MaterialTheme.typography.labelSmall,
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

    if (showSearch) {
        ModrinthSearchDialog(
            instance = instance,
            onDismiss = { showSearch = false },
            onInstalled = { scope.launch { addons = PluginManager.list(instance) } }
        )
    }
}

/** Modrinth 在线搜索对话框(备选) */
@Composable
private fun ModrinthSearchDialog(
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
        com.mcserver.launcher.data.CoreType.FABRIC -> "fabric"
        com.mcserver.launcher.data.CoreType.FORGE -> "forge"
        com.mcserver.launcher.data.CoreType.NEOFORGE -> "neoforge"
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
                                    Text(hit.title, style = MaterialTheme.typography.bodyMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
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

// ═══════════ 配置 ═══════════

@Composable
private fun ConfigTab(instance: ServerInstance) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val cfg = instance.config
    var port by remember { mutableStateOf(cfg.serverPort.toString()) }
    var maxRam by remember { mutableStateOf(cfg.maxRamMB.toString()) }
    var maxPlayers by remember { mutableStateOf(cfg.maxPlayers.toString()) }
    var motd by remember { mutableStateOf(cfg.motd) }
    var onlineMode by remember { mutableStateOf(cfg.onlineMode) }
    var whiteList by remember { mutableStateOf(cfg.whiteList) }
    var pvp by remember { mutableStateOf(cfg.pvp) }
    var gamemode by remember { mutableStateOf(cfg.gamemode) }
    var difficulty by remember { mutableStateOf(cfg.difficulty) }

    Column(Modifier.fillMaxSize()) {
    LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp)) {
        item {
        Text("服务器配置(保存后重启生效)", style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(value = port, onValueChange = { port = it.filter { c -> c.isDigit() }.take(5) },
            label = { Text("端口") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = maxRam, onValueChange = { maxRam = it.filter { c -> c.isDigit() }.take(5) },
            label = { Text("最大内存(MB),如 2048") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = maxPlayers, onValueChange = { maxPlayers = it.filter { c -> c.isDigit() }.take(4) },
            label = { Text("最大玩家数") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = motd, onValueChange = { motd = it },
            label = { Text("服务器描述(MOTD)") }, singleLine = true, modifier = Modifier.fillMaxWidth())

        Spacer(Modifier.height(12.dp))
        SwitchRow("在线模式(正版验证)", onlineMode) { onlineMode = it }
        SwitchRow("白名单", whiteList) { whiteList = it }
        SwitchRow("允许 PvP", pvp) { pvp = it }

        Spacer(Modifier.height(12.dp))
        Text("游戏模式", style = MaterialTheme.typography.labelMedium)
        Row {
            listOf("survival", "creative", "adventure").forEach { gm ->
                FilterChip(
                    selected = gamemode == gm,
                    onClick = { gamemode = gm },
                    label = { Text(gm) },
                    modifier = Modifier.padding(end = 6.dp)
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("难度", style = MaterialTheme.typography.labelMedium)
        Row {
            listOf("peaceful", "easy", "normal", "hard").forEach { d ->
                FilterChip(
                    selected = difficulty == d,
                    onClick = { difficulty = d },
                    label = { Text(d) },
                    modifier = Modifier.padding(end = 6.dp)
                )
            }
        }

        }
    }

    // 保存按钮固定在底部,不受滚动影响
    Button(onClick = {
        val newCfg = cfg.copy(
            serverPort = port.toIntOrNull() ?: cfg.serverPort,
            maxRamMB = maxRam.toIntOrNull()?.coerceAtLeast(512) ?: cfg.maxRamMB,
            maxPlayers = maxPlayers.toIntOrNull() ?: cfg.maxPlayers,
            motd = motd,
            onlineMode = onlineMode,
            whiteList = whiteList,
            pvp = pvp,
            gamemode = gamemode,
            difficulty = difficulty
        )
        com.mcserver.launcher.core.server.InstanceStore.update(instance.copy(config = newCfg))
        android.widget.Toast.makeText(context, "配置已保存,重启服务器后生效", android.widget.Toast.LENGTH_SHORT).show()
    }, modifier = Modifier.fillMaxWidth().padding(16.dp)) { Text("保存配置") }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// ═══════════ 世界管理 ═══════════

@Composable
private fun WorldTab(instance: ServerInstance) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var worlds by remember { mutableStateOf<List<File>>(emptyList()) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val dest = java.io.File(instance.dir(com.mcserver.launcher.core.server.InstanceStore.instancesDir), "world_import_tmp")
                if (dest.exists()) dest.deleteRecursively()
                com.mcserver.launcher.util.FileImporter.copyTree(context, uri, dest)
                    .onSuccess { count ->
                        if (count == 0) {
                            dest.deleteRecursively()
                            android.widget.Toast.makeText(context, "所选目录为空", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            // 导入为 world_<时间戳>
                            val name = "world_" + System.currentTimeMillis().toString().takeLast(8)
                            val target = java.io.File(instance.dir(com.mcserver.launcher.core.server.InstanceStore.instancesDir), name)
                            dest.renameTo(target)
                            android.widget.Toast.makeText(context, "已导入世界 $name(可在 server.properties 设置 level-name)", android.widget.Toast.LENGTH_SHORT).show()
                            worlds = listWorlds(instance)
                        }
                    }
                    .onFailure { err -> android.widget.Toast.makeText(context, "导入失败:${err.message}", android.widget.Toast.LENGTH_LONG).show() }
            }
        }
    }

    LaunchedEffect(instance.id) { worlds = listWorlds(instance) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("世界管理", style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text("导入本地世界目录(优先本地,不耗流量);导入后可在配置页将 level-name 设为该目录名", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Button(onClick = { importLauncher.launch(null) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.FolderOpen, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("从本地导入世界(不耗流量)")
        }
        Spacer(Modifier.height(12.dp))
        if (worlds.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("还没有世界,点上方导入", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(worlds, key = { it.name }) { world ->
                    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(world.name, style = MaterialTheme.typography.bodyMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                                Text(formatSize(world.length()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = {
                                scope.launch {
                                    world.deleteRecursively()
                                    worlds = listWorlds(instance)
                                }
                            }) { Icon(Icons.Filled.Delete, "删除世界", Modifier.size(18.dp)) }
                        }
                    }
                }
            }
        }
    }
}

/** 列出实例中的世界目录(含 level.dat 或 data/ 的目录) */
private fun listWorlds(instance: ServerInstance): List<File> =
    instance.dir(com.mcserver.launcher.core.server.InstanceStore.instancesDir).listFiles()
        ?.filter { it.isDirectory && it.name != "plugins" && it.name != "mods" && it.name != "world_import_tmp" &&
            (File(it, "level.dat").exists() || File(it, "data").exists()) }
        ?.sortedBy { it.name } ?: emptyList()
