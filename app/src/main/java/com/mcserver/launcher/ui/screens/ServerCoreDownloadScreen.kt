package com.mcserver.launcher.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mcserver.launcher.data.ServerConfig
import com.mcserver.launcher.server.ServerCoreManager
import com.mcserver.launcher.server.TermuxManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerCoreDownloadScreen(
    config: ServerConfig,
    onJarDownloaded: (String) -> Unit
) {
    val coreManager = remember { ServerCoreManager() }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    var selectedType by remember { mutableStateOf(ServerCoreManager.CoreType.PAPER) }
    var versions by remember { mutableStateOf<List<ServerCoreManager.CoreVersion>>(emptyList()) }
    var builds by remember { mutableStateOf<List<ServerCoreManager.CoreBuild>>(emptyList()) }
    var selectedVersion by remember { mutableStateOf("") }
    var selectedBuild by remember { mutableStateOf("") }
    var selectedFileName by remember { mutableStateOf("") }

    var loadingVersions by remember { mutableStateOf(false) }
    var loadingBuilds by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var downloadedBytes by remember { mutableLongStateOf(0L) }
    var totalBytes by remember { mutableLongStateOf(0L) }
    var downloadMessage by remember { mutableStateOf<String?>(null) }
    var versionError by remember { mutableStateOf<String?>(null) }
    var buildError by remember { mutableStateOf<String?>(null) }

    fun loadVersions() {
        scope.launch {
            loadingVersions = true; versionError = null; versions = emptyList()
            val result = when (selectedType) {
                ServerCoreManager.CoreType.PAPER -> coreManager.fetchPaperVersions()
                ServerCoreManager.CoreType.PURPUR -> coreManager.fetchPurpurVersions()
                ServerCoreManager.CoreType.FABRIC -> coreManager.fetchFabricVersions()
                ServerCoreManager.CoreType.VANILLA -> coreManager.fetchVanillaVersions()
                ServerCoreManager.CoreType.SPIGOT -> coreManager.fetchPaperVersions() // Spigot 使用 Paper API 兜底
            }
            result.fold(
                onSuccess = { versions = it; loadingVersions = false },
                onFailure = { versionError = it.message; loadingVersions = false }
            )
        }
    }

    fun loadBuilds(version: String) {
        scope.launch {
            loadingBuilds = true; buildError = null; builds = emptyList()
            when (selectedType) {
                ServerCoreManager.CoreType.PAPER -> {
                    coreManager.fetchPaperBuilds(version).fold(
                        onSuccess = { builds = it; loadingBuilds = false },
                        onFailure = { buildError = it.message; loadingBuilds = false }
                    )
                }
                ServerCoreManager.CoreType.PURPUR -> {
                    // Purpur 直接下载最新，无需选构建
                    val url = coreManager.getPurpurDownloadUrl(version)
                    val fileName = "purpur-$version.jar"
                    builds = listOf(ServerCoreManager.CoreBuild("latest", "Latest", fileName))
                    loadingBuilds = false
                }
                ServerCoreManager.CoreType.FABRIC -> {
                    coreManager.getFabricDownloadUrl(version).fold(
                        onSuccess = { url ->
                            builds = listOf(ServerCoreManager.CoreBuild("latest", "Latest", "fabric-server-$version.jar"))
                            loadingBuilds = false
                        },
                        onFailure = { buildError = it.message; loadingBuilds = false }
                    )
                }
                ServerCoreManager.CoreType.VANILLA -> {
                    builds = listOf(ServerCoreManager.CoreBuild("latest", "Latest", "minecraft_server.$version.jar"))
                    loadingBuilds = false
                }
                ServerCoreManager.CoreType.SPIGOT -> {
                    // Spigot 使用 BuildTools，不直接提供下载
                    buildError = "Spigot 需使用 BuildTools 构建，请选择 Paper 或 Purpur"
                    loadingBuilds = false
                }
            }
        }
    }

    fun startDownload() {
        scope.launch {
            downloading = true; downloadProgress = 0f; downloadMessage = null
            val serverDir = TermuxManager.serverDir(context)
            val fileName: String
            val url: String

            try {
                when (selectedType) {
                    ServerCoreManager.CoreType.PAPER -> {
                        fileName = selectedFileName.ifEmpty { "paper-$selectedVersion-$selectedBuild.jar" }
                        url = coreManager.getPaperDownloadUrl(selectedVersion, selectedBuild, fileName)
                    }
                    ServerCoreManager.CoreType.PURPUR -> {
                        fileName = "purpur-$selectedVersion.jar"
                        url = coreManager.getPurpurDownloadUrl(selectedVersion)
                    }
                    ServerCoreManager.CoreType.FABRIC -> {
                        fileName = "fabric-server-$selectedVersion.jar"
                        val result = coreManager.getFabricDownloadUrl(selectedVersion)
                        if (result.isFailure) {
                            downloadMessage = "获取 Fabric 下载链接失败：${result.exceptionOrNull()?.message}"
                            downloading = false; return@launch
                        }
                        url = result.getOrThrow()
                    }
                    ServerCoreManager.CoreType.VANILLA -> {
                        fileName = "minecraft_server.$selectedVersion.jar"
                        val result = coreManager.getVanillaDownloadUrl(selectedVersion)
                        if (result.isFailure) {
                            downloadMessage = "获取 Vanilla 下载链接失败：${result.exceptionOrNull()?.message}"
                            downloading = false; return@launch
                        }
                        url = result.getOrThrow()
                    }
                    else -> { downloading = false; return@launch }
                }

                val result = coreManager.downloadJar(url, serverDir, fileName) { p, d, t ->
                    downloadProgress = p; downloadedBytes = d; totalBytes = t
                }
                result.fold(
                    onSuccess = { file ->
                        downloadMessage = "下载完成：${file.name}"
                        onJarDownloaded(file.absolutePath)
                        downloading = false
                    },
                    onFailure = {
                        downloadMessage = "下载失败：${it.message}"
                        downloading = false
                    }
                )
            } catch (e: Exception) {
                downloadMessage = "下载失败：${e.message}"
                downloading = false
            }
        }
    }

    LaunchedEffect(selectedType) {
        loadVersions()
        selectedVersion = ""
        builds = emptyList()
        selectedBuild = ""
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("下载服务器核心", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "选择服务器类型和版本，自动下载 JAR 到服务器目录",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // 核心类型选择
        Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("服务器类型", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ServerCoreManager.CoreType.entries.forEach { type ->
                        FilterChip(
                            selected = selectedType == type,
                            onClick = { selectedType = type },
                            label = {
                                Column {
                                    Text(type.displayName, fontWeight = if (selectedType == type) FontWeight.Bold else FontWeight.Normal)
                                    Text(type.description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            leadingIcon = {
                                if (selectedType == type) Icon(Icons.Filled.Check, null, Modifier.size(16.dp))
                            }
                        )
                    }
                }
            }
        }

        // 版本列表
        Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("MC 版本", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    if (versionError != null) {
                        TextButton(onClick = { loadVersions() }) {
                            Icon(Icons.Filled.Refresh, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("重试")
                        }
                    }
                }
                if (loadingVersions) {
                    CircularProgressIndicator(Modifier.size(24.dp).padding(8.dp), strokeWidth = 2.dp)
                } else if (versionError != null) {
                    Text(versionError!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                } else {
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        versions.take(30).forEach { v ->
                            FilterChip(
                                selected = selectedVersion == v.id,
                                onClick = {
                                    selectedVersion = v.id
                                    loadBuilds(v.id)
                                },
                                label = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(v.id, style = MaterialTheme.typography.labelMedium)
                                        if (!v.isStable) {
                                            Spacer(Modifier.width(4.dp))
                                            Text("快照", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        // 构建选择（仅 Paper 需要）
        if (selectedType == ServerCoreManager.CoreType.PAPER && selectedVersion.isNotBlank()) {
            Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("构建版本", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    if (loadingBuilds) {
                        CircularProgressIndicator(Modifier.size(24.dp).padding(8.dp), strokeWidth = 2.dp)
                    } else if (buildError != null) {
                        Text(buildError!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    } else {
                        Spacer(Modifier.height(8.dp))
                        LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                            items(builds) { build ->
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(build.name, style = MaterialTheme.typography.bodyMedium)
                                    RadioButton(
                                        selected = selectedBuild == build.id,
                                        onClick = {
                                            selectedBuild = build.id
                                            selectedFileName = build.fileName
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 下载按钮与进度
        val canDownload = when (selectedType) {
            ServerCoreManager.CoreType.PAPER -> selectedVersion.isNotBlank() && selectedBuild.isNotBlank()
            ServerCoreManager.CoreType.PURPUR, ServerCoreManager.CoreType.FABRIC, ServerCoreManager.CoreType.VANILLA -> selectedVersion.isNotBlank()
            ServerCoreManager.CoreType.SPIGOT -> false
        }

        if (canDownload || downloading) {
            Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    if (downloading) {
                        Text("下载中...", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(progress = { downloadProgress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(4.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${formatBytes(downloadedBytes)} / ${formatBytes(totalBytes)}", style = MaterialTheme.typography.bodySmall)
                            Text("${(downloadProgress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                        }
                    } else {
                        Button(
                            onClick = { startDownload() },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(16.dp)
                        ) {
                            Icon(Icons.Filled.CloudDownload, null)
                            Spacer(Modifier.width(8.dp))
                            Text("下载 $selectedVersion ${selectedType.displayName} 核心")
                        }
                    }
                }
            }
        }

        downloadMessage?.let { msg ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (msg.startsWith("下载完成")) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (msg.startsWith("下载完成")) Icons.Filled.CheckCircle else Icons.Filled.Info,
                        null, Modifier.size(18.dp),
                        tint = if (msg.startsWith("下载完成")) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(msg, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0; if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0; if (mb < 1024) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}
