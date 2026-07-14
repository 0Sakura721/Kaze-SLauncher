package com.mcserver.launcher.ui.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
    var maxRestarts by remember { mutableStateOf(config.maxRestarts.toString()) }
    var restartCooldown by remember { mutableStateOf(config.restartCooldownSec.toString()) }

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

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("服务器名称") },
            leadingIcon = { Icon(Icons.Filled.Label, contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        OutlinedTextField(
            value = jarPath.substringAfterLast("/").ifEmpty { jarPath },
            onValueChange = {},
            label = { Text("服务器 JAR 文件") },
            leadingIcon = { Icon(Icons.Filled.InsertDriveFile, contentDescription = null) },
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
                    GameModeSelector("游戏模式", gamemode) { gamemode = it }
                    GameModeSelector("难度", difficulty) { difficulty = it }
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
                OutlinedTextField(
                    value = extraArgs,
                    onValueChange = { extraArgs = it },
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
                        restartCooldownSec = restartCooldown.toIntOrNull()?.coerceAtLeast(0) ?: 5
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
    onValueChange: (String) -> Unit
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
        modifier = Modifier.weight(1f)
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
            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Slider(
                    value = allocatedMemory.toFloat(),
                    onValueChange = { onMemoryChange(it.toInt()) },
                    valueRange = 256f..memoryInfo.totalMB.toFloat().coerceAtLeast(1024f),
                    steps = 0,
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
                            onClick = { onMemoryChange((allocatedMemory - 256).coerceAtLeast(256)) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Filled.ChevronLeft, "减少", Modifier.size(16.dp))
                        }
                        Text(
                            "${allocatedMemory}MB",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.widthIn(min = 72.dp)
                        )
                        IconButton(
                            onClick = { onMemoryChange((allocatedMemory + 256).coerceAtMost(memoryInfo.totalMB.toInt())) },
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
