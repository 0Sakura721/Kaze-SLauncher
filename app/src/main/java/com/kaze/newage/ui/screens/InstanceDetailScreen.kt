package com.kaze.newage.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kaze.newage.core.addons.AddonKind
import com.kaze.newage.core.addons.AddonManager
import com.kaze.newage.core.server.BackupManager
import com.kaze.newage.core.server.ServerProperties
import com.kaze.newage.core.server.ServerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.kaze.newage.ui.AppViewModel
import com.kaze.newage.ui.components.BackgroundCard
import com.kaze.newage.ui.components.CardTitleLayout
import com.kaze.newage.ui.components.CheckChip
import com.kaze.newage.ui.components.StatusOrb
import com.kaze.newage.ui.components.StatusTone
import com.kaze.newage.ui.formatUptime
import com.kaze.newage.ui.isBusy
import com.kaze.newage.ui.theme.statusPalette
import com.kaze.newage.ui.toLabel
import com.kaze.newage.ui.toTone

/**
 * 实例详情页：运行总览 + server.properties 可视化编辑器。
 * （插件 / 模组 / 世界 / 日志管理入口将在此页扩展）
 */
@Composable
fun InstanceDetailScreen(
    viewModel: AppViewModel,
    instanceId: String,
    onBack: () -> Unit,
    onOpenAddons: (AddonKind) -> Unit,
    onOpenLogs: () -> Unit,
) {
    val instances by viewModel.instances.collectAsState()
    val states by viewModel.serverStates.collectAsState()
    val uptime by viewModel.uptimeSec.collectAsState()
    val instance = instances.firstOrNull { it.id == instanceId }
    if (instance == null) {
        androidx.compose.runtime.LaunchedEffect(Unit) { onBack() }
        return
    }
    val state = states[instanceId] ?: ServerState.Idle
    // 进入详情即把该实例设为当前实例（玩家管理/控制台命令按当前实例下发）
    androidx.compose.runtime.LaunchedEffect(instanceId) {
        viewModel.selectInstance(instance)
    }
    val tone = state.toTone()
    val palette = statusPalette()
    val stateColor = when (tone) {
        StatusTone.Running -> palette.running
        StatusTone.Busy -> palette.busy
        StatusTone.Idle -> palette.idle
        StatusTone.Error -> palette.error
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ── 顶部栏 ──
        var showRenameDialog by remember(instanceId) { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Column(Modifier.weight(1f)) {
                Text(instance.name, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                Text(state.toLabel(), style = MaterialTheme.typography.labelMedium, color = stateColor)
            }
            // 改名：只改软件里显示的名字，与服务器 MOTD（server.properties）独立
            IconButton(onClick = { showRenameDialog = true }) {
                Icon(Icons.Filled.Edit, contentDescription = "重命名实例")
            }
        }

        // ── 重命名对话框 ──
        if (showRenameDialog) {
            var newName by remember(instanceId) { mutableStateOf(instance.name) }
            AlertDialog(
                onDismissRequest = { showRenameDialog = false },
                title = { Text("重命名实例") },
                text = {
                    Column {
                        OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            label = { Text("实例名称") },
                            singleLine = true,
                        )
                        Text(
                            "仅修改软件内显示名；服务器 MOTD 独立保存在 server.properties 中，不受影响。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (newName.isNotBlank() && newName.trim() != instance.name) {
                                viewModel.renameInstance(instanceId, newName)
                            }
                            showRenameDialog = false
                        },
                        enabled = newName.isNotBlank(),
                    ) { Text("确定") }
                },
                dismissButton = {
                    TextButton(onClick = { showRenameDialog = false }) { Text("取消") }
                },
            )
        }

        // ── 运行总览 ──
        BackgroundCard(Modifier.fillMaxWidth()) {
            CardTitleLayout("运行总览") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    StatusOrb(tone, Modifier.size(56.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "${instance.coreType.displayName} · MC ${instance.mcVersion.ifBlank { "自定义" }}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "Java ${instance.javaMajor} · ${instance.memoryMb} MB",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (state == ServerState.Running) {
                            Text(
                                "已运行 ${formatUptime(uptime)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = stateColor,
                            )
                        }
                    }
                }
                Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state == ServerState.Running) {
                        Button(onClick = { viewModel.stopInstance(instance) }) {
                            Icon(Icons.Filled.Stop, null, Modifier.size(18.dp))
                            Text("停止", Modifier.padding(start = 4.dp))
                        }
                    } else {
                        Button(onClick = { viewModel.startInstance(instance) }, enabled = !state.isBusy()) {
                            Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                            Text(if (state.isBusy()) "处理中…" else "启动", Modifier.padding(start = 4.dp))
                        }
                    }
                }
            }
        }

        // ── 附加组件入口 ──
        BackgroundCard(Modifier.fillMaxWidth()) {
            CardTitleLayout("管理") {
                val pluginOk = AddonManager.supports(instance, AddonKind.PLUGIN)
                val modOk = AddonManager.supports(instance, AddonKind.MOD)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AddonEntryCard(
                        label = "插件",
                        hint = if (pluginOk) "下载/启停插件" else "需 Paper 类核心",
                        enabled = pluginOk,
                        modifier = Modifier.weight(1f),
                        onClick = { onOpenAddons(AddonKind.PLUGIN) },
                    )
                    AddonEntryCard(
                        label = "模组",
                        hint = if (modOk) "下载/启停模组" else "需 Fabric/Forge 类核心",
                        enabled = modOk,
                        modifier = Modifier.weight(1f),
                        onClick = { onOpenAddons(AddonKind.MOD) },
                    )
                    AddonEntryCard(
                        label = "日志",
                        hint = "崩溃报告 · latest.log",
                        enabled = true,
                        modifier = Modifier.weight(1f),
                        onClick = onOpenLogs,
                    )
                }
            }
        }

        // ── 玩家管理 ──
        PlayerManageCard(
            viewModel = viewModel,
            instanceId = instanceId,
            running = state == ServerState.Running,
        )

        // ── 备份管理 ──
        var backupRefresh by remember { mutableIntStateOf(0) }
        var backupBusy by remember { mutableStateOf(false) }
        var backupMsg by remember { mutableStateOf<String?>(null) }
        var exportTarget by remember { mutableStateOf<java.io.File?>(null) }
        val backups = remember(instanceId, backupRefresh) { BackupManager.list(instance) }
        val backupScope = rememberCoroutineScope()
        val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext

        // 导出（SAF 创建文档）与导入（SAF 打开文档）
        val exportLauncher = rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")
        ) { uri ->
            val src = exportTarget
            if (uri != null && src != null) {
                backupScope.launch(Dispatchers.IO) {
                    backupMsg = try {
                        appContext.contentResolver.openOutputStream(uri)?.use { out ->
                            BackupManager.export(src, out)
                        }
                        "已导出：${src.name}"
                    } catch (e: Exception) {
                        "导出失败：${e.message}"
                    }
                    exportTarget = null
                }
            }
        }
        val importLauncher = rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) {
                backupScope.launch(Dispatchers.IO) {
                    backupMsg = try {
                        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "imported.zip"
                        appContext.contentResolver.openInputStream(uri)?.use { ins ->
                            val f = BackupManager.import(instance, ins, name)
                            "已导入：${f.name}"
                        } ?: "导入失败：无法读取文件"
                    } catch (e: Exception) {
                        "导入失败：${e.message}"
                    }
                    backupRefresh++
                }
            }
        }
        BackgroundCard(Modifier.fillMaxWidth()) {
            CardTitleLayout("备份（世界 + 配置全量）", trailing = {
                Button(
                    onClick = {
                        if (backupBusy) return@Button
                        backupBusy = true
                        backupScope.launch(Dispatchers.IO) {
                            backupMsg = try {
                                val f = BackupManager.backup(instance)
                                "已备份：${f.name}"
                            } catch (e: Exception) {
                                "备份失败：${e.message}"
                            }
                            backupRefresh++
                            backupBusy = false
                        }
                    },
                    enabled = !backupBusy,
                ) { Text(if (backupBusy) "备份中…" else "立即备份") }
            }) {
                if (backups.isEmpty()) {
                    Text(
                        "还没有备份。世界数据珍贵，建议开服前/后定期备份。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }) {
                        Text("导入备份")
                    }
                }
                backups.forEach { f ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(f.name.removeSuffix(".zip"), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                            Text(
                                "${f.length() / 1024 / 1024} MB · ${java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(f.lastModified()))}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            "恢复",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable {
                                    if (state == ServerState.Running) {
                                        backupMsg = "请先停止服务端再恢复备份"
                                    } else {
                                        backupScope.launch(Dispatchers.IO) {
                                            backupMsg = try {
                                                BackupManager.restore(instance, f)
                                                "已恢复：${f.name}"
                                            } catch (e: Exception) {
                                                "恢复失败：${e.message}"
                                            }
                                        }
                                    }
                                }
                                .padding(4.dp),
                        )
                        Text(
                            "导出",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable {
                                    exportTarget = f
                                    exportLauncher.launch(f.name)
                                }
                                .padding(4.dp),
                        )
                        IconButton(onClick = {
                            BackupManager.delete(f)
                            backupRefresh++
                        }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "删除备份",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                backupMsg?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (it.startsWith("已")) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }

        // ── server.properties 编辑器 ──
        PropertiesEditor(
            dir = instance.dir,
            instanceName = instance.name,
            isRunning = state == ServerState.Running,
            onSave = { props -> ServerProperties.save(instance.dir, props) },
        )
    }
}

/** 附加组件入口小卡 */
@Composable
private fun AddonEntryCard(
    label: String,
    hint: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    BackgroundCard(modifier, onClick = if (enabled) onClick else null) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 玩家管理：在线列表（list 命令解析 + 进出事件跟踪）+ OP/白名单/踢出快捷操作 */
@Composable
private fun PlayerManageCard(
    viewModel: AppViewModel,
    instanceId: String,
    running: Boolean,
) {
    val players by viewModel.onlinePlayers.collectAsState()
    var name by remember(instanceId) { mutableStateOf("") }

    fun doCmd(cmd: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        viewModel.sendCommand("$cmd $n")
        name = ""
    }

    BackgroundCard(Modifier.fillMaxWidth()) {
        CardTitleLayout("玩家管理", trailing = {
            if (running) {
                IconButton(onClick = { viewModel.refreshPlayers() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新在线列表")
                }
            }
        }) {
            if (!running) {
                Text(
                    "服务端运行后可管理玩家：查看在线列表、设置 OP、白名单、踢出。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    if (players.isEmpty()) "在线玩家：暂无（点右上角刷新）" else "在线玩家：${players.joinToString("、")}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("玩家名") },
                    placeholder = { Text("如 Steve") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                )
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { doCmd("op") }) { Text("设为 OP") }
                    OutlinedButton(onClick = { doCmd("deop") }) { Text("取消 OP") }
                    OutlinedButton(onClick = { doCmd("kick") }) { Text("踢出") }
                }
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { doCmd("whitelist add") }) { Text("白名单 +") }
                    OutlinedButton(onClick = { doCmd("whitelist remove") }) { Text("白名单 −") }
                }
            }
        }
    }
}

/** server.properties 可视化编辑器 */
@Composable
private fun PropertiesEditor(
    dir: java.io.File,
    instanceName: String,
    isRunning: Boolean,
    onSave: (Map<String, String>) -> Unit,
) {
    var props by remember(dir) { mutableStateOf(ServerProperties.load(dir)) }
    var saved by remember(dir) { mutableStateOf(false) }
    val p = props

    fun value(key: String, default: String): String = p[key] ?: default
    fun set(key: String, v: String) {
        val next = LinkedHashMap(p)
        next[key] = v
        props = next
        saved = false
    }

    val motd = value("motd", instanceName)
    val port = value("server-port", "25565")
    val maxPlayers = value("max-players", "20").toIntOrNull() ?: 20
    val gamemode = value("gamemode", "survival")
    val difficulty = value("difficulty", "easy")
    val pvp = value("pvp", "true").toBoolean()
    val onlineMode = value("online-mode", "true").toBoolean()
    val whiteList = value("white-list", "false").toBoolean()
    val allowFlight = value("allow-flight", "false").toBoolean()
    val commandBlock = value("enable-command-block", "false").toBoolean()
    val hardcore = value("hardcore", "false").toBoolean()
    val viewDistance = value("view-distance", "10").toIntOrNull() ?: 10
    val simDistance = value("simulation-distance", "10").toIntOrNull() ?: 10
    val spawnProtection = value("spawn-protection", "16").toIntOrNull() ?: 16
    val pauseSeconds = value("pause-when-empty-seconds", "-1").toIntOrNull() ?: -1

    BackgroundCard(Modifier.fillMaxWidth()) {
        CardTitleLayout("server.properties") {
            Text("服务器标题（MOTD）", style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(
                value = motd,
                onValueChange = { set("motd", it) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = port,
                    onValueChange = { v -> if (v.all { it.isDigit() }) set("server-port", v) },
                    label = { Text("端口") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = maxPlayers.toString(),
                    onValueChange = { v -> v.toIntOrNull()?.let { set("max-players", it.toString()) } },
                    label = { Text("最大人数") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
            }

            // 游戏模式
            Text("游戏模式", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("survival" to "生存", "creative" to "创造", "adventure" to "冒险", "spectator" to "旁观").forEach { (id, label) ->
                    CheckChip(selected = gamemode == id, label = label, onClick = { set("gamemode", id) })
                }
            }

            // 难度
            Text("难度", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("peaceful" to "和平", "easy" to "简单", "normal" to "普通", "hard" to "困难").forEach { (id, label) ->
                    CheckChip(selected = difficulty == id, label = label, onClick = { set("difficulty", id) })
                }
            }

            // 布尔开关
            Text("规则开关", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
            SwitchRow("允许 PVP", pvp) { set("pvp", it.toString()) }
            SwitchRow("正版验证（online-mode）", onlineMode) { set("online-mode", it.toString()) }
            SwitchRow("白名单", whiteList) { set("white-list", it.toString()) }
            SwitchRow("允许飞行", allowFlight) { set("allow-flight", it.toString()) }
            SwitchRow("命令方块", commandBlock) { set("enable-command-block", it.toString()) }
            SwitchRow("极限模式（hardcore）", hardcore) { set("hardcore", it.toString()) }

            // 空服自动暂停（MC 1.21.2+；-1 = 关闭）
            Text("空服自动暂停", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
            Text(
                "没有玩家在线时自动暂停服务器以节省资源（属性 pause-when-empty-seconds）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            androidx.compose.foundation.layout.FlowRow(
                Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf(-1 to "关闭", 30 to "30 秒", 60 to "60 秒", 120 to "2 分钟", 300 to "5 分钟").forEach { (sec, label) ->
                    CheckChip(
                        selected = pauseSeconds == sec,
                        label = label,
                        onClick = { set("pause-when-empty-seconds", sec.toString()) },
                    )
                }
            }

            // 距离滑块
            Text("视距：$viewDistance 区块", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
            Slider(
                value = viewDistance.toFloat(),
                onValueChange = { set("view-distance", it.toInt().toString()) },
                valueRange = 3f..32f,
            )
            Text("模拟距离：$simDistance 区块", style = MaterialTheme.typography.labelLarge)
            Slider(
                value = simDistance.toFloat(),
                onValueChange = { set("simulation-distance", it.toInt().toString()) },
                valueRange = 3f..32f,
            )
            Text("出生点保护：$spawnProtection 格", style = MaterialTheme.typography.labelLarge)
            Slider(
                value = spawnProtection.toFloat(),
                onValueChange = { set("spawn-protection", it.toInt().toString()) },
                valueRange = 0f..64f,
            )

            Button(
                onClick = {
                    onSave(p)
                    saved = true
                },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) {
                Icon(Icons.Filled.Save, null, Modifier.size(18.dp))
                Text(if (saved) "已保存" else "保存配置", Modifier.padding(start = 4.dp))
            }
            if (saved && isRunning) {
                Text(
                    "配置已保存；运行中的服务器将在重启后生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
