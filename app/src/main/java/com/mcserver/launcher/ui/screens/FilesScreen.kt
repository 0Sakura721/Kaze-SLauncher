package com.mcserver.launcher.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcserver.launcher.server.FileManager
import com.mcserver.launcher.server.ProotServerManager
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen() {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("文件浏览", "世界", "崩溃报告")

    var currentDir by remember { mutableStateOf(ProotServerManager.serverDir(context)) }
    var entries by remember { mutableStateOf<List<FileManager.FileEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    var worlds by remember { mutableStateOf<List<FileManager.FileEntry>>(emptyList()) }
    var crashReports by remember { mutableStateOf<List<FileManager.FileEntry>>(emptyList()) }
    var diskUsage by remember { mutableStateOf<FileManager.DiskUsage?>(null) }

    var selectedCrash by remember { mutableStateOf<File?>(null) }
    var crashContent by remember { mutableStateOf("") }
    var selectedConfigFile by remember { mutableStateOf<File?>(null) }
    var configContent by remember { mutableStateOf("") }
    var editingConfig by remember { mutableStateOf(false) }
    var showPropertiesEditor by remember { mutableStateOf(false) }
    var propertiesEditorFile by remember { mutableStateOf<File?>(null) }

    var confirmDelete by remember { mutableStateOf<FileManager.FileEntry?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    fun refreshDir() {
        scope.launch {
            loading = true
            entries = FileManager.listDirectory(currentDir)
            diskUsage = FileManager.getDiskUsage()
            loading = false
        }
    }

    fun refreshAll() {
        scope.launch {
            refreshDir()
            worlds = FileManager.listWorlds()
            crashReports = FileManager.listCrashReports()
        }
    }

    LaunchedEffect(Unit) { refreshAll() }
    LaunchedEffect(currentDir) { refreshDir() }
    LaunchedEffect(message) {
        if (message != null) {
            kotlinx.coroutines.delay(3000)
            message = null
        }
    }

    confirmDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            icon = { Icon(Icons.Filled.DeleteForever, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("删除${if (entry.isDirectory) "目录" else "文件"}") },
            text = { Text("确定删除「${entry.name}」吗？此操作不可撤销。") },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            FileManager.deleteFile(File(entry.path))
                            confirmDelete = null
                            refreshAll()
                            message = "已删除：${entry.name}"
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("取消") } }
        )
    }

    // 崩溃报告查看
    selectedCrash?.let { file ->
        AlertDialog(
            onDismissRequest = { selectedCrash = null; crashContent = "" },
            title = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    Text("最近 200 行:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)
                    ) {
                        Text(
                            crashContent.ifEmpty { "加载中..." },
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                            modifier = Modifier.padding(8.dp).verticalScroll(rememberScrollState())
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selectedCrash = null; crashContent = "" }) { Text("关闭") } }
        )
    }

    // 配置文件编辑
    selectedConfigFile?.let { file ->
        AlertDialog(
            onDismissRequest = { selectedConfigFile = null; configContent = ""; editingConfig = false },
            title = { Text("编辑: ${file.name}") },
            text = {
                Column {
                    if (editingConfig) {
                        OutlinedTextField(
                            value = configContent,
                            onValueChange = { configContent = it },
                            modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            minLines = 8
                        )
                    } else {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)
                        ) {
                            Text(
                                configContent.ifEmpty { "加载中..." },
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                                modifier = Modifier.padding(8.dp).verticalScroll(rememberScrollState())
                            )
                        }
                    }
                }
            },
            confirmButton = {
                if (editingConfig) {
                    Button(
                        onClick = {
                            scope.launch {
                                FileManager.writeFileContent(file, configContent)
                                    .onSuccess { message = "已保存：${file.name}" }
                                    .onFailure { message = "保存失败：${it.message}" }
                                selectedConfigFile = null; configContent = ""; editingConfig = false
                            }
                        }
                    ) { Text("保存") }
                } else {
                    Button(onClick = { editingConfig = true }) { Text("编辑") }
                }
            },
            dismissButton = { TextButton(onClick = { selectedConfigFile = null; configContent = ""; editingConfig = false }) { Text("关闭") } }
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("文件管理", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        // 磁盘使用
        diskUsage?.let { usage ->
            Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("磁盘使用", style = MaterialTheme.typography.labelMedium)
                    Text("${usage.totalFiles} 个文件 | ${FileManager.formatFileSize(usage.totalSize)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        TabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { index, title ->
                Tab(selected = selectedTab == index, onClick = { selectedTab = index }, text = { Text(title) })
            }
        }

        when (selectedTab) {
            0 -> {
                // 面包屑导航
                if (currentDir.absolutePath != ProotServerManager.serverDir(context).absolutePath) {
                    TextButton(
                        onClick = {
                            currentDir = ProotServerManager.serverDir(context)
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("返回根目录")
                    }
                }

                if (loading) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else if (entries.isEmpty()) {
                    Text("目录为空", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(entries, key = { it.path }) { entry ->
                            FileCard(
                                entry = entry,
                                onClick = {
                                    if (entry.isDirectory) {
                                        currentDir = File(entry.path)
                                    } else if (entry.isLog && entry.name.startsWith("crash-")) {
                                        scope.launch {
                                            crashContent = FileManager.readCrashReport(File(entry.path))
                                            selectedCrash = File(entry.path)
                                        }
                                    } else if (entry.isConfig) {
                                        scope.launch {
                                            val file = File(entry.path)
                                            // server.properties 使用增强的结构化编辑器
                                            if (file.name == "server.properties") {
                                                propertiesEditorFile = file
                                                showPropertiesEditor = true
                                            } else {
                                                configContent = FileManager.readFileContent(file)
                                                selectedConfigFile = file
                                            }
                                        }
                                    }
                                },
                                onDelete = { confirmDelete = entry }
                            )
                        }
                    }
                }
            }
            1 -> {
                if (worlds.isEmpty()) {
                    Text("未找到世界文件夹。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(worlds, key = { it.path }) { world ->
                            Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Public, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(world.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                        Text(
                                            "${FileManager.formatFileSize(world.size)} | ${FileManager.formatTime(world.lastModified)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    IconButton(onClick = { confirmDelete = world }) {
                                        Icon(Icons.Filled.DeleteOutline, "删除", tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            2 -> {
                if (crashReports.isEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.CheckCircle, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text("没有崩溃报告。一切正常！", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(crashReports, key = { it.path }) { report ->
                            Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
                                Row(
                                    Modifier.fillMaxWidth().padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                        Icon(Icons.Filled.BugReport, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
                                        Spacer(Modifier.width(12.dp))
                                        Column {
                                            Text(report.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(
                                                "${FileManager.formatFileSize(report.size)} | ${FileManager.formatTime(report.lastModified)}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    TextButton(
                                        onClick = {
                                            scope.launch {
                                                crashContent = FileManager.readCrashReport(File(report.path))
                                                selectedCrash = File(report.path)
                                            }
                                        }
                                    ) { Text("查看") }
                                }
                            }
                        }
                    }
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

    // server.properties 结构化编辑器（全屏覆盖）
    propertiesEditorFile?.let { file ->
        if (showPropertiesEditor) {
            ServerPropertiesEditor(
                configFile = file,
            onSaved = {
                showPropertiesEditor = false
                propertiesEditorFile = null
                message = "server.properties 已保存"
                scope.launch { refreshAll() }
            },
                onDismiss = {
                    showPropertiesEditor = false
                    propertiesEditorFile = null
                }
            )
        }
    }
}

@Composable
private fun FileCard(
    entry: FileManager.FileEntry,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        onClick = onClick
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(
                    when {
                        entry.isDirectory -> Icons.Filled.Folder
                        entry.isJar -> Icons.AutoMirrored.Filled.InsertDriveFile
                        entry.isLog -> Icons.Filled.BugReport
                        entry.isConfig -> Icons.Filled.Settings
                        else -> Icons.Filled.Description
                    },
                    null,
                    Modifier.size(22.dp),
                    tint = when {
                        entry.isDirectory -> MaterialTheme.colorScheme.primary
                        entry.isJar -> MaterialTheme.colorScheme.tertiary
                        entry.isLog -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(entry.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!entry.isDirectory) {
                        Text(
                            "${FileManager.formatFileSize(entry.size)} | ${FileManager.formatTime(entry.lastModified)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (!entry.isDirectory) {
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.DeleteOutline, "删除", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

// FilesScreen 中渲染 ServerPropertiesEditor 的逻辑（在 message 渲染之前）
// 放在 FileCard composable 定义之后
