package com.mcserver.launcher.ui.screens

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
            scope.launch {
                val localPath = withContext(Dispatchers.IO) {
                    // 从 content:// URI 复制 JAR 到内部存储
                    var name = "server.jar"
                    context.contentResolver.query(selectedUri, null, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            if (idx >= 0) name = cursor.getString(idx)
                        }
                    }
                    val target = java.io.File(context.filesDir, "servers/$name")
                    target.parentFile?.mkdirs()
                    context.contentResolver.openInputStream(selectedUri)?.use { input ->
                        java.io.FileOutputStream(target).use { output -> input.copyTo(output) }
                    }
                    target.absolutePath
                }
                jarPath = localPath
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
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("无 GUI 模式"); Spacer(Modifier.width(8.dp))
                        Switch(checked = nogui, onCheckedChange = { nogui = it })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("自动重启"); Spacer(Modifier.width(8.dp))
                        Switch(checked = autoRestart, onCheckedChange = { autoRestart = it })
                    }
                }
            }
        }

        Button(
            onClick = {
                onConfigSave(config.copy(
                    name = name, jarPath = jarPath,
                    allocatedMemoryMB = allocatedMemory,
                    serverPort = port.toIntOrNull() ?: 25565,
                    additionalArgs = extraArgs,
                    autoRestart = autoRestart, nogui = nogui
                ))
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
