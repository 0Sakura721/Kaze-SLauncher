package com.mcserver.launcher.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
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
import com.mcserver.launcher.ui.components.ModrinthSearchDialog
import com.mcserver.launcher.ui.components.pressSource
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
    var showRename by remember { mutableStateOf(false) }
    var currentName by remember { mutableStateOf(instance.name) }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val (pressBack, srcBack) = pressSource()
            IconButton(onClick = onBack, interactionSource = srcBack, modifier = pressBack) { Icon(Icons.Filled.ArrowBack, "返回") }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(currentName, style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    val (pressRename, srcRename) = pressSource()
                    IconButton(onClick = { showRename = true }, interactionSource = srcRename, modifier = pressRename.then(Modifier.size(28.dp))) {
                        Icon(Icons.Filled.Edit, "重命名", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(
                    "${instance.coreType.displayName} ${instance.mcVersion} · ${PluginManager.dirLabel(instance)} 目录",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            ServerControlButton(instance)
            val (pressDel, srcDel) = pressSource()
            IconButton(onClick = { showDeleteConfirm = true }, interactionSource = srcDel, modifier = pressDel) { Icon(Icons.Filled.Delete, "删除实例") }
        }

        if (showRename) {
            RenameInstanceDialog(
                instance = instance,
                initialName = currentName,
                onDismiss = { showRename = false },
                onRenamed = { newName -> currentName = newName; showRename = false }
            )
        }
        TabRow(selectedTabIndex = tab) {
            listOf("控制台", "插件/模组", "配置", "世界").forEachIndexed { index, label ->
                val (pressTab, srcTab) = pressSource()
                Tab(
                    selected = tab == index,
                    onClick = { tab = index },
                    interactionSource = srcTab,
                    modifier = pressTab,
                    text = { Text(label) }
                )
            }
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
        val (press, src) = pressSource()
        Button(onClick = { scope.launch { ServerManager.stop() } },
            interactionSource = src,
            modifier = press,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
            Icon(Icons.Filled.Stop, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("停止")
        }
    } else {
        val (press, src) = pressSource()
        Button(onClick = {
            scope.launch {
                val r = ServerManager.start(instance)
                if (r.isFailure) android.widget.Toast.makeText(context, r.exceptionOrNull()?.message ?: "启动失败", android.widget.Toast.LENGTH_LONG).show()
            }
        }, interactionSource = src, modifier = press) {
            Icon(Icons.Filled.PlayArrow, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("启动")
        }
    }
}

// ═══════════ 控制台 ═══════════

@androidx.compose.runtime.Immutable
private data class ConsoleLogLine(val text: String, val color: androidx.compose.ui.graphics.Color)

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ConsoleTab() {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val status by ServerManager.status.collectAsState()
    val players by ServerManager.players.collectAsState()
    val uptime by ServerManager.uptimeSec.collectAsState()
    var command by remember { mutableStateOf("") }
    val logLines = remember { mutableStateListOf<ConsoleLogLine>() }
    val errColor = MaterialTheme.colorScheme.error
    val primaryColor = MaterialTheme.colorScheme.primary
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    // 控制台工具状态:自动输出(暂停接收)、自动滚动
    var autoOutput by remember { mutableStateOf(true) }
    var autoScroll by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()

    fun copyToClipboard(text: String, hint: String) {
        if (text.isBlank()) return
        clipboard.setText(androidx.compose.ui.text.AnnotatedString(text))
        android.widget.Toast.makeText(context, hint, android.widget.Toast.LENGTH_SHORT).show()
    }

    // 保存日志:SAF 让用户选择保存位置(默认 Download),无需存储权限
    val saveLogLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(logLines.joinToString("\n") { it.text }.toByteArray(Charsets.UTF_8))
                }
                android.widget.Toast.makeText(context, "日志已保存(${logLines.size} 行)", android.widget.Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, "保存失败:${e.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(Unit) {
        ServerManager.console.collect { line ->
            // 自动输出关闭时暂停接收(恢复后从当前继续)
            if (!autoOutput) return@collect
            logLines.add(
                ConsoleLogLine(
                    text = line,
                    color = if (line.contains("ERROR") || line.contains("Exception")) errColor
                            else if (line.startsWith(">")) primaryColor
                            else textColor
                )
            )
            if (logLines.size > 1000) logLines.removeRange(0, logLines.size - 1000)
        }
    }

    // 自动滚动:新日志到达时滚到底部
    LaunchedEffect(logLines.size, autoScroll) {
        if (autoScroll && logLines.isNotEmpty()) {
            listState.scrollToItem((logLines.size - 1).coerceAtLeast(0))
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "${status.name} · 玩家 ${players.size} · 运行 ${uptime / 60}分${uptime % 60}秒",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            val (pressCopy, srcCopy) = pressSource()
            TextButton(
                onClick = {
                    copyToClipboard(logLines.joinToString("\n") { it.text }, "已复制全部日志(${logLines.size} 行)")
                },
                enabled = logLines.isNotEmpty(),
                interactionSource = srcCopy,
                modifier = pressCopy
            ) {
                Text("复制日志", style = MaterialTheme.typography.labelSmall)
            }
        }
        // 控制台工具行:清空 / 保存 / 自动输出 / 自动滚动
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val (p1, s1) = pressSource()
            TextButton(onClick = {
                logLines.clear()
                android.widget.Toast.makeText(context, "已清空控制台", android.widget.Toast.LENGTH_SHORT).show()
            }, enabled = logLines.isNotEmpty(), interactionSource = s1, modifier = p1) {
                Text("清空", style = MaterialTheme.typography.labelSmall)
            }
            val (p2, s2) = pressSource()
            TextButton(onClick = {
                val defaultName = "server_log_${java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())}.txt"
                saveLogLauncher.launch(defaultName)
            }, enabled = logLines.isNotEmpty(), interactionSource = s2, modifier = p2) {
                Text("保存", style = MaterialTheme.typography.labelSmall)
            }
            val (p3, s3) = pressSource()
            TextButton(onClick = { autoOutput = !autoOutput }, interactionSource = s3, modifier = p3) {
                Text(if (autoOutput) "自动输出:开" else "自动输出:关", style = MaterialTheme.typography.labelSmall)
            }
            val (p4, s4) = pressSource()
            TextButton(onClick = { autoScroll = !autoScroll }, interactionSource = s4, modifier = p4) {
                Text(if (autoScroll) "自动滚动:开" else "自动滚动:关", style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.weight(1f))
            if (!autoOutput) {
                Text("已暂停接收", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error)
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                .padding(8.dp)
        ) {
            items(logLines.takeLast(500)) { entry ->
                Text(entry.text, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                    color = entry.color,
                    modifier = Modifier
                        .padding(vertical = 1.dp)
                        .combinedClickable(
                            onClick = {},
                            onLongClick = { copyToClipboard(entry.text, "已复制该行日志") }
                        ))
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

/** 重命名实例对话框 */
@Composable
private fun RenameInstanceDialog(
    instance: com.mcserver.launcher.data.ServerInstance,
    initialName: String,
    onDismiss: () -> Unit,
    onRenamed: (String) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名实例") },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(30) },
                label = { Text("实例标题") },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = {
                val newName = name.trim()
                if (newName.isNotEmpty() && newName != instance.name) {
                    com.mcserver.launcher.core.server.InstanceStore.update(instance.copy(name = newName))
                }
                onRenamed(if (newName.isEmpty()) instance.name else newName)
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}