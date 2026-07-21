package com.mcserver.launcher.ui.screens

import androidx.compose.foundation.clickable
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
import com.mcserver.launcher.server.ModrinthManager
import com.mcserver.launcher.server.ProotServerManager
import com.mcserver.launcher.server.ServerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModrinthScreen() {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    var searchQuery by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf("plugin") }
    var results by remember { mutableStateOf<List<ModrinthManager.ModrinthProject>>(emptyList()) }
    var totalHits by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedProject by remember { mutableStateOf<ModrinthManager.ModrinthProject?>(null) }
    var projectVersions by remember { mutableStateOf<List<ModrinthManager.ModrinthVersion>>(emptyList()) }
    var downloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var message by remember { mutableStateOf<String?>(null) }

    // 多服务器安装选择
    var showInstallDialog by remember { mutableStateOf(false) }
    var pendingDownloadFile by remember { mutableStateOf<File?>(null) }
    var pendingInstallType by remember { mutableStateOf("") }
    var selectedTargetServerId by remember { mutableStateOf("") }
    val serverList by ServerManager.instance.serverList.collectAsState()
    val currentServerId by ServerManager.instance.currentServerId.collectAsState()

    fun search() {
        if (searchQuery.isBlank()) return
        scope.launch {
            loading = true; error = null
            try {
                val types = if (selectedType == "all") listOf("plugin", "mod", "datapack", "resourcepack")
                            else listOf(selectedType)
                val result = ModrinthManager.search(searchQuery, types)
                results = result.projects
                totalHits = result.totalHits
                loading = false
            } catch (e: Exception) {
                error = e.message ?: "搜索失败"
                loading = false
            }
        }
    }

    fun loadVersions(project: ModrinthManager.ModrinthProject) {
        selectedProject = project
        scope.launch {
            loading = true
            try {
                val loaders = when (selectedType) {
                    "plugin" -> listOf("paper", "spigot", "purpur", "bungeecord", "velocity")
                    "mod" -> listOf("fabric", "forge", "neoforge", "quilt")
                    "datapack" -> listOf("datapack")
                    "resourcepack" -> listOf("minecraft")
                    else -> emptyList()
                }
                projectVersions = ModrinthManager.getVersions(project.id, loaders)
                loading = false
            } catch (e: Exception) {
                error = e.message ?: "加载版本失败"
                loading = false
            }
        }
    }

    fun downloadVersion(version: ModrinthManager.ModrinthVersion) {
        scope.launch {
            downloading = true; downloadProgress = 0f; message = null
            try {
                val cacheDir = File(ProotServerManager.serverDir(context), "downloads")
                cacheDir.mkdirs()
                val file = version.files.firstOrNull { it.primary } ?: version.files.firstOrNull()
                if (file == null) {
                    message = "下载失败：没有可用文件"
                    downloading = false
                    return@launch
                }
                val downloadedFile = ModrinthManager.downloadFile(file, cacheDir) { p ->
                    downloadProgress = p
                }
                pendingDownloadFile = downloadedFile
                pendingInstallType = selectedType
                selectedTargetServerId = currentServerId
                showInstallDialog = true
                message = "下载完成：${downloadedFile.name}"
                downloading = false
            } catch (e: Exception) {
                message = "下载失败：${e.message}"
                downloading = false
            }
        }
    }

    fun installToServer(targetServerId: String) {
        val file = pendingDownloadFile ?: return
        val type = pendingInstallType
        scope.launch(Dispatchers.IO) {
            try {
                val targetDir = ProotServerManager.serverDir(context, targetServerId)
                val subDir = when (type) {
                    "plugin" -> File(targetDir, "plugins")
                    "mod" -> File(targetDir, "mods")
                    "datapack" -> File(targetDir, "datapacks")
                    "resourcepack" -> File(targetDir, "resourcepacks")
                    else -> targetDir
                }
                subDir.mkdirs()
                val destFile = File(subDir, file.name)
                file.copyTo(destFile, overwrite = true)
                file.delete()
                withContext(Dispatchers.Main) {
                    message = "已安装到 ${targetDir.name}/${subDir.name}"
                    showInstallDialog = false
                    pendingDownloadFile = null
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    message = "安装失败：${e.message}"
                }
            }
        }
    }

    // 安装目标选择对话框
    if (showInstallDialog) {
        AlertDialog(
            onDismissRequest = { showInstallDialog = false },
            icon = { Icon(Icons.Filled.Download, null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("安装到哪个服务器？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    serverList.forEach { server ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedTargetServerId = server.id }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedTargetServerId == server.id,
                                onClick = { selectedTargetServerId = server.id }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(server.name)
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { installToServer(selectedTargetServerId) }) {
                    Text("安装")
                }
            },
            dismissButton = {
                TextButton(onClick = { showInstallDialog = false }) { Text("取消") }
            }
        )
    }

    // 详情视图
    selectedProject?.let { project ->
        ProjectDetailView(
            project = project,
            versions = projectVersions,
            loading = loading,
            downloading = downloading,
            downloadProgress = downloadProgress,
            onDownload = { version -> downloadVersion(version) },
            onBack = { selectedProject = null; projectVersions = emptyList() }
        )
        return
    }

    // 搜索列表视图
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Modrinth 浏览", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "搜索并下载 Modrinth 上的插件、模组、数据包和资源包",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // 搜索栏
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("搜索插件/模组...") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = ""; results = emptyList() }) {
                        Icon(Icons.Filled.Clear, "清除")
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        // 类型过滤
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val types = listOf(
                "plugin" to "插件",
                "mod" to "模组",
                "datapack" to "数据包",
                "resourcepack" to "资源包"
            )
            types.forEach { (type, label) ->
                FilterChip(
                    selected = selectedType == type,
                    onClick = { selectedType = type },
                    label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }

        // 搜索按钮
        Button(
            onClick = { search() },
            modifier = Modifier.fillMaxWidth(),
            enabled = searchQuery.isNotBlank() && !loading
        ) {
            if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Icon(Icons.Filled.Search, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("搜索 Modrinth")
        }

        // 错误提示
        if (error != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Info, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(8.dp))
                    Text(error ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }

        // 结果列表
        if (results.isNotEmpty()) {
            Text(
                "${results.size} 个结果（共 $totalHits 个）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(results) { project ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { loadVersions(project) },
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                modifier = Modifier.size(48.dp),
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Filled.Extension,
                                        null,
                                        Modifier.size(28.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    project.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    project.description.take(80),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(4.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        "下载: ${project.downloads}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    if (project.categories.isNotEmpty()) {
                                        Text(
                                            project.categories.first(),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                            Icon(
                                Icons.Filled.ChevronRight,
                                null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        } else if (!loading && searchQuery.isNotBlank() && error == null) {
            Text(
                "没有找到结果，请尝试其他关键词",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 消息提示
        message?.let { msg ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (msg.contains("完成")) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.errorContainer
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (msg.contains("完成")) Icons.Filled.CheckCircle else Icons.Filled.Info,
                        null, Modifier.size(18.dp),
                        tint = if (msg.contains("完成")) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(msg, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectDetailView(
    project: ModrinthManager.ModrinthProject,
    versions: List<ModrinthManager.ModrinthVersion>,
    loading: Boolean,
    downloading: Boolean,
    downloadProgress: Float,
    onDownload: (ModrinthManager.ModrinthVersion) -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 标题栏
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, "返回")
            }
            Text(
                project.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
        }

        // 项目描述
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    project.description.ifBlank { "无描述" },
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        "下载: ${project.downloads}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "关注: ${project.followers}",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }

        // 版本列表
        Text(
            "可用版本",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )

        if (loading) {
            CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
        } else if (versions.isEmpty()) {
            Text(
                "没有找到可用版本",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(versions) { version ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Row(
                            Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    version.name.ifBlank { version.versionNumber },
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(Modifier.height(4.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    version.gameVersions.take(3).forEach { gv ->
                                        Surface(
                                            shape = MaterialTheme.shapes.extraSmall,
                                            color = MaterialTheme.colorScheme.secondaryContainer
                                        ) {
                                            Text(
                                                gv,
                                                style = MaterialTheme.typography.labelSmall,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                    version.loaders.take(2).forEach { loader ->
                                        Surface(
                                            shape = MaterialTheme.shapes.extraSmall,
                                            color = MaterialTheme.colorScheme.tertiaryContainer
                                        ) {
                                            Text(
                                                loader,
                                                style = MaterialTheme.typography.labelSmall,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                }
                            }
                            Button(
                                onClick = { onDownload(version) },
                                enabled = !downloading,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                if (downloading) {
                                    CircularProgressIndicator(
                                        Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(Icons.Filled.Download, null, Modifier.size(16.dp))
                                }
                                Spacer(Modifier.width(4.dp))
                                Text("下载")
                            }
                        }
                    }
                }
            }
        }

        // 下载进度
        if (downloading) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("下载中...", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { downloadProgress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${(downloadProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}
