package com.mcserver.launcher.ui.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mcserver.launcher.data.ServerConfig
import com.mcserver.launcher.utils.MemoryInfo
import com.mcserver.launcher.utils.getDeviceMemoryInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerConfigScreen(
    config: ServerConfig,
    onConfigSave: (ServerConfig) -> Unit
) {
    var name by remember { mutableStateOf(config.name) }
    var jarPath by remember { mutableStateOf(config.jarPath) }
    var allocatedMemory by remember { mutableIntStateOf(config.allocatedMemoryMB) }
    var port by remember { mutableStateOf(config.serverPort.toString()) }
    var extraArgs by remember { mutableStateOf(config.additionalArgs) }
    var autoRestart by remember { mutableStateOf(config.autoRestart) }
    var nogui by remember { mutableStateOf(config.nogui) }
    // server.properties 项
    var motd by remember { mutableStateOf(config.motd) }
    var maxPlayers by remember { mutableStateOf(config.maxPlayers.toString()) }
    var gamemode by remember { mutableStateOf(config.gamemode) }
    var difficulty by remember { mutableStateOf(config.difficulty) }
    var pvp by remember { mutableStateOf(config.pvp) }
    var onlineMode by remember { mutableStateOf(config.onlineMode) }
    var whiteList by remember { mutableStateOf(config.whiteList) }
    var spawnProtection by remember { mutableStateOf(config.spawnProtection.toString()) }
    var viewDistance by remember { mutableStateOf(config.viewDistance.toString()) }
    // 自动重启保护
    // 自动重启保护
    var maxRestarts by remember { mutableStateOf(config.maxRestarts.toString()) }
    var restartCooldown by remember { mutableStateOf(config.restartCooldownSec.toString()) }
    // RCON
    var rconEnabled by remember { mutableStateOf(config.rconEnabled) }
    var rconPort by remember { mutableStateOf(config.rconPort.toString()) }
    var backupOnStop by remember { mutableStateOf(config.backupOnStop) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var memoryInfo by remember { mutableStateOf(context.getDeviceMemoryInfo()) }
    LaunchedEffect(Unit) {
        while (true) {
            memoryInfo = context.getDeviceMemoryInfo()
            delay(500)
        }
    }

    val jarPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { selectedUri ->
            // 持有持久读取权限
            try {
                context.contentResolver.takePersistableUriPermission(
                    selectedUri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
            scope.launch {
                try {
                    jarPath = copyContentUriToLocal(context, selectedUri)
                } catch (e: Exception) {
                    // 复制失败，仍存 URI 作为兜底
                    jarPath = selectedUri.toString()
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "服务器配置",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        // 配置模板选择器（借鉴 Pterodactyl Eggs）
        var showTemplateSelector by remember { mutableStateOf(false) }
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.AutoAwesome, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    Spacer(Modifier.width(8.dp))
                    Text("快速模板", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
                Spacer(Modifier.height(4.dp))
                Text("选择预设模板自动填充推荐配置（借鉴 Pterodactyl Eggs 设计）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f))
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        Triple("small", "小型", "1-2人"),
                        Triple("medium", "中型", "5-10人"),
                        Triple("large", "大型", "20+人")
                    ).forEach { (key, label, desc) ->
                        OutlinedButton(
                            onClick = {
                                applyTemplate(key, memoryInfo.totalMB.toInt()) { newMem, newArgs, newView ->
                                    allocatedMemory = newMem
                                    extraArgs = newArgs
                                    viewDistance = newView.toString()
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(label, style = MaterialTheme.typography.labelLarge)
                                Text(desc, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("服务器名称") },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Label, contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        OutlinedTextField(
            value = jarPath.substringAfterLast("/").ifEmpty { jarPath },
            onValueChange = {},
            label = { Text("服务器 JAR 文件") },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.InsertDriveFile, contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
            readOnly = true,
            trailingIcon = {
                IconButton(onClick = { jarPicker.launch(arrayOf("*/*")) }) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = "选择文件")
                }
            },
            supportingText = {
                if (jarPath.isNotBlank()) {
                    Text(jarPath, style = MaterialTheme.typography.bodySmall)
                }
            }
        )

        MemoryAllocationCard(memoryInfo, allocatedMemory) { allocatedMemory = it }

        // 网络
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("网络", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter { c -> c.isDigit() } },
                    label = { Text("服务器端口") },
                    leadingIcon = { Icon(Icons.Filled.Dns, null) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        }

        // 游戏设置（写入 server.properties）
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("游戏设置", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = motd,
                    onValueChange = { motd = it },
                    label = { Text("服务器描述 (MOTD)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = maxPlayers,
                        onValueChange = { maxPlayers = it.filter { c -> c.isDigit() } },
                        label = { Text("最大玩家") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = viewDistance,
                        onValueChange = { viewDistance = it.filter { c -> c.isDigit() } },
                        label = { Text("视距") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GameModeSelector("游戏模式", gamemode, { gamemode = it }, Modifier.weight(1f))
                    GameModeSelector("难度", difficulty, { difficulty = it }, Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = spawnProtection,
                    onValueChange = { spawnProtection = it.filter { c -> c.isDigit() } },
                    label = { Text("出生点保护范围 (方块)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("PVP"); Spacer(Modifier.width(8.dp))
                        Switch(checked = pvp, onCheckedChange = { pvp = it })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("正版验证"); Spacer(Modifier.width(8.dp))
                        Switch(checked = onlineMode, onCheckedChange = { onlineMode = it })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("白名单"); Spacer(Modifier.width(8.dp))
                        Switch(checked = whiteList, onCheckedChange = { whiteList = it })
                    }
                }
                if (!onlineMode) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "离线模式已开启：允许未登录正版的玩家加入（存在冒名风险）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        }

        // 高级
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("高级", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))

                // JVM 参数模板选择（借鉴 Aikar's Flags / Pterodactyl Egg）
                var showJvmTemplates by remember { mutableStateOf(false) }
                var selectedJvmTemplate by remember { mutableStateOf("default") }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("JVM 参数", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    TextButton(onClick = { showJvmTemplates = !showJvmTemplates }) {
                        Icon(Icons.Filled.AutoFixHigh, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("模板")
                    }
                }
                if (showJvmTemplates) {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            com.mcserver.launcher.server.JvmFlags.templates.forEach { template ->
                                Row(
                                    Modifier.fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = selectedJvmTemplate == template.id,
                                        onClick = {
                                            selectedJvmTemplate = template.id
                                            if (template.id != "custom" && template.flags.isNotBlank()) {
                                                extraArgs = template.flags
                                            }
                                            if (template.id == "custom") {
                                                showJvmTemplates = false
                                            }
                                        }
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(template.name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                        Text(template.recommendedFor, style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = extraArgs,
                    onValueChange = { extraArgs = it; selectedJvmTemplate = "custom" },
                    label = { Text("JVM 参数") },
                    supportingText = { Text("额外的 Java 虚拟机启动参数") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2, maxLines = 4
                )
                Spacer(Modifier.height(8.dp))

                // 自动重启保护（仿 Pterodactyl restart policy）
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("自动重启（崩溃时）"); Spacer(Modifier.width(8.dp))
                            Switch(checked = autoRestart, onCheckedChange = { autoRestart = it })
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = maxRestarts,
                            onValueChange = { maxRestarts = it.filter { c -> c.isDigit() } },
                            label = { Text("最大重启次数 (0=不限)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = restartCooldown,
                            onValueChange = { restartCooldown = it.filter { c -> c.isDigit() } },
                            label = { Text("重启冷却 (秒)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("无 GUI 模式"); Spacer(Modifier.width(8.dp))
                        Switch(checked = nogui, onCheckedChange = { nogui = it })
                    }
                }
            }
        }

        // RCON 与备份设置
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("高级选项", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))

                // RCON
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Computer, null, Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("RCON 远程控制"); Spacer(Modifier.width(8.dp))
                        Switch(checked = rconEnabled, onCheckedChange = { rconEnabled = it })
                    }
                }
                if (rconEnabled) {
                    Spacer(Modifier.height(4.dp))
                    Text("启用后可通过标准 RCON 协议发送命令（更快、有返回值）。密码自动生成。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = rconPort,
                        onValueChange = { rconPort = it.filter { c -> c.isDigit() } },
                        label = { Text("RCON 端口") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = rconEnabled
                    )
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                // 备份
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Backup, null, Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.tertiary)
                        Spacer(Modifier.width(8.dp))
                        Text("停止时自动备份"); Spacer(Modifier.width(8.dp))
                        Switch(checked = backupOnStop, onCheckedChange = { backupOnStop = it })
                    }
                }
                if (backupOnStop) {
                    Spacer(Modifier.height(4.dp))
                    Text("每次停止服务器前自动创建完整备份。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Button(
            onClick = {
                scope.launch {
                    val finalJarPath = if (jarPath.startsWith("content://")) {
                        copyContentUriToLocal(context, Uri.parse(jarPath))
                    } else jarPath
                    onConfigSave(config.copy(
                        name = name, jarPath = finalJarPath,
                        allocatedMemoryMB = allocatedMemory,
                        serverPort = port.toIntOrNull() ?: 25565,
                        additionalArgs = extraArgs,
                        autoRestart = autoRestart, nogui = nogui,
                        motd = motd.ifBlank { "A Minecraft Server" },
                        maxPlayers = maxPlayers.toIntOrNull() ?: 20,
                        gamemode = gamemode, difficulty = difficulty,
                        pvp = pvp, onlineMode = onlineMode, whiteList = whiteList,
                        spawnProtection = spawnProtection.toIntOrNull() ?: 16,
                        viewDistance = viewDistance.toIntOrNull()?.coerceIn(2, 32) ?: 10,
                        maxRestarts = maxRestarts.toIntOrNull() ?: 3,
                        restartCooldownSec = restartCooldown.toIntOrNull()?.coerceAtLeast(0) ?: 5,
                        rconEnabled = rconEnabled,
                        rconPort = rconPort.toIntOrNull() ?: 25575,
                        backupOnStop = backupOnStop
                    ))
                }
            },
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(16.dp)
        ) {
            Icon(Icons.Filled.Save, null)
            Spacer(Modifier.width(8.dp))
            Text("保存配置", style = MaterialTheme.typography.titleSmall)
        }

        Spacer(Modifier.height(32.dp))
    }
}

// ─── 内存分配卡片 ───

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GameModeSelector(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val options = if (label == "游戏模式")
        listOf("survival" to "生存", "creative" to "创造", "adventure" to "冒险", "spectator" to "观察者")
    else
        listOf("peaceful" to "和平", "easy" to "简单", "normal" to "普通", "hard" to "困难")

    var expanded by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.first == value }?.second ?: value

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = current,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
            singleLine = true
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { (key, display) ->
                DropdownMenuItem(
                    text = { Text(display) },
                    onClick = {
                        onValueChange(key)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun MemoryAllocationCard(
    memoryInfo: MemoryInfo,
    allocatedMemory: Int,
    onMemoryChange: (Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("内存分配", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                "控制给 Minecraft 分配多少内存",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            // 预设内存选项
            val deviceMax = memoryInfo.totalMB.toInt()
            val presets = listOf(
                512 to "512M", 1024 to "1G", 1536 to "1.5G",
                2048 to "2G", 3072 to "3G", 4096 to "4G",
                6144 to "6G", 8192 to "8G"
            ).filter { it.first <= deviceMax }

            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                presets.forEach { (mb, label) ->
                    FilterChip(
                        selected = allocatedMemory == mb,
                        onClick = { onMemoryChange(mb) },
                        label = {
                            Text(
                                label,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (allocatedMemory == mb) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val step = when {
                    allocatedMemory < 1024 -> 128
                    allocatedMemory < 2048 -> 256
                    else -> 512
                }
                Slider(
                    value = allocatedMemory.toFloat(),
                    onValueChange = {
                        val snapped = (it / step).roundToInt() * step
                        onMemoryChange(snapped.coerceIn(256, deviceMax))
                    },
                    valueRange = 256f..deviceMax.toFloat().coerceAtLeast(1024f),
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.height(40.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        IconButton(
                            onClick = { onMemoryChange((allocatedMemory - step).coerceAtLeast(256)) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Filled.ChevronLeft, "减少", Modifier.size(16.dp))
                        }
                        Text(
                            if (allocatedMemory >= 1024) "%.1fG".format(allocatedMemory / 1024f)
                            else "${allocatedMemory}MB",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.widthIn(min = 60.dp)
                        )
                        IconButton(
                            onClick = { onMemoryChange((allocatedMemory + step).coerceAtMost(deviceMax)) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Filled.ChevronRight, "增加", Modifier.size(16.dp))
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // 实时内存条
            DeviceMemoryBar(memoryInfo, allocatedMemory)
        }
    }
}

@Composable
private fun DeviceMemoryBar(memoryInfo: MemoryInfo, allocatedMemory: Int) {
    val total = memoryInfo.totalMB.toFloat()
    val usedRatio = memoryInfo.usedMB.toFloat() / total
    val allocatedRatio = allocatedMemory.toFloat() / total

    Column {
        Box(
            modifier = Modifier.fillMaxWidth().height(28.dp).clip(MaterialTheme.shapes.small)
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {}
            Surface(
                modifier = Modifier.fillMaxHeight().fillMaxWidth(usedRatio.coerceIn(0f, 1f)),
                color = MaterialTheme.colorScheme.primary
            ) {}
            Surface(
                modifier = Modifier.fillMaxHeight().fillMaxWidth((usedRatio + allocatedRatio).coerceIn(0f, 1f)),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
            ) {}
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "已使用：${memoryInfo.usedMB}MB / ${memoryInfo.totalMB}MB",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "已分配：${allocatedMemory}MB",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

private suspend fun copyContentUriToLocal(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
    var name = "server.jar"
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0) name = cursor.getString(idx)
        }
    }
    val target = java.io.File(context.filesDir, "servers/$name")
    target.parentFile?.mkdirs()
    context.contentResolver.openInputStream(uri)?.use { input ->
        java.io.FileOutputStream(target).use { output -> input.copyTo(output) }
    } ?: throw IllegalStateException("无法读取 JAR 文件")
    target.absolutePath
}

/**
 * 应用预设配置模板（借鉴 Pterodactyl Eggs / MCSManager 模板系统）。
 *
 * 小型 (1-2人)：适合低配设备，保守内存分配
 * 中型 (5-10人)：均衡配置，适合大多数场景
 * 大型 (20+人)：高性能配置，需要充足设备资源
 */
private fun applyTemplate(
    template: String,
    deviceTotalMemMB: Int,
    onApplied: (Int, String, Int) -> Unit
) {
    when (template) {
        "small" -> {
            val mem = (deviceTotalMemMB / 4).coerceIn(512, 1536)
            val args = "-XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+ParallelRefProcEnabled " +
                       "-XX:+DisableExplicitGC -XX:+AlwaysPreTouch"
            onApplied(mem, args, 8)
        }
        "medium" -> {
            val mem = (deviceTotalMemMB / 3).coerceIn(1536, 4096)
            val args = "-XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+ParallelRefProcEnabled " +
                       "-XX:+DisableExplicitGC -XX:+AlwaysPreTouch " +
                       "-XX:G1HeapRegionSize=4M -XX:+UnlockExperimentalVMOptions " +
                       "-XX:G1NewSizePercent=30 -XX:G1MaxNewSizePercent=40"
            onApplied(mem, args, 12)
        }
        "large" -> {
            val mem = (deviceTotalMemMB / 2).coerceIn(4096, 8192)
            val args = "-XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+ParallelRefProcEnabled " +
                       "-XX:+DisableExplicitGC -XX:+AlwaysPreTouch " +
                       "-XX:G1HeapRegionSize=4M -XX:+UnlockExperimentalVMOptions " +
                       "-XX:G1NewSizePercent=30 -XX:G1MaxNewSizePercent=40 " +
                       "-XX:G1MixedGCLiveThresholdPercent=90 -XX:G1RSetUpdatingPauseTimePercent=5 " +
                       "-XX:+PerfDisableSharedMem -XX:+UseStringDeduplication"
            onApplied(mem, args, 16)
        }
    }
}
