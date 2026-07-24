package com.mcserver.launcher.ui.screens

import androidx.compose.foundation.horizontalScroll
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
import com.mcserver.launcher.data.StartupMode
import com.mcserver.launcher.server.ServerCoreManager
import com.mcserver.launcher.server.ProotServerManager
import com.mcserver.launcher.server.LinuxEnvironmentManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun ServerCoreDownloadScreen(
    config: ServerConfig,
    onJarDownloaded: (String, StartupMode) -> Unit
) {
    val coreManager = remember { ServerCoreManager() }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    var selectedType by remember { mutableStateOf(ServerCoreManager.CoreType.PAPER) }
    var versions by remember { mutableStateOf<List<ServerCoreManager.CoreVersion>>(emptyList()) }
    var forgeVersions by remember { mutableStateOf<List<String>>(emptyList()) }
    var builds by remember { mutableStateOf<List<ServerCoreManager.CoreBuild>>(emptyList()) }
    var selectedVersion by remember { mutableStateOf("") }
    var selectedForgeVersion by remember { mutableStateOf("") }
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
    var installing by remember { mutableStateOf(false) }
    var installMessage by remember { mutableStateOf<String?>(null) }

    fun loadVersions() {
        scope.launch {
            loadingVersions = true; versionError = null; versions = emptyList()
            val result = when (selectedType) {
                ServerCoreManager.CoreType.PAPER -> coreManager.fetchPaperVersions()
                ServerCoreManager.CoreType.PURPUR -> coreManager.fetchPurpurVersions()
                ServerCoreManager.CoreType.FABRIC -> coreManager.fetchFabricVersions()
                ServerCoreManager.CoreType.VANILLA -> coreManager.fetchVanillaVersions()
                ServerCoreManager.CoreType.SPIGOT -> coreManager.fetchSpigotVersions()
                ServerCoreManager.CoreType.FORGE -> coreManager.fetchVanillaVersions() // Forge 版本与 Vanilla 同步
                ServerCoreManager.CoreType.NEOFORGE -> coreManager.fetchVanillaVersions() // NeoForge 版本与 Vanilla 同步
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
                    val fileName = "purpur-$version.jar"
                    builds = listOf(ServerCoreManager.CoreBuild("latest", "Latest", fileName))
                    loadingBuilds = false
                }
                ServerCoreManager.CoreType.FABRIC -> {
                    coreManager.getFabricDownloadUrl(version).fold(
                        onSuccess = {
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
                    val fileName = "spigot-$version.jar"
                    builds = listOf(ServerCoreManager.CoreBuild("latest", "Latest", fileName))
                    loadingBuilds = false
                }
                ServerCoreManager.CoreType.FORGE -> {
                    coreManager.fetchForgeVersions(version).fold(
                        onSuccess = { fvs ->
                            forgeVersions = fvs
                            builds = fvs.map { v ->
                                val fv = v.substringAfter("$version-", v)
                                ServerCoreManager.CoreBuild(fv, "Forge $fv", "forge-$v-installer.jar")
                            }
                            loadingBuilds = false
                        },
                        onFailure = { buildError = "获取 Forge 版本失败：${it.message}。请检查网络或稍后重试。"; loadingBuilds = false }
                    )
                }
                ServerCoreManager.CoreType.NEOFORGE -> {
                    coreManager.fetchNeoForgeVersions(version).fold(
                        onSuccess = { fvs ->
                            forgeVersions = fvs
                            builds = fvs.map { v ->
                                val nv = v.substringAfter("$version-", v)
                                ServerCoreManager.CoreBuild(nv, "NeoForge $nv", "neoforge-$v-installer.jar")
                            }
                            loadingBuilds = false
                        },
                        onFailure = { buildError = "获取 NeoForge 版本失败：${it.message}。请检查网络或稍后重试。"; loadingBuilds = false }
                    )
                }
            }
        }
    }

    fun startDownload() {
        scope.launch {
            downloading = true; downloadProgress = 0f; downloadMessage = null
            val serverDir = ProotServerManager.serverDir(context)
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
                    ServerCoreManager.CoreType.FORGE -> {
                        val fv = selectedBuild.ifEmpty { "latest" }
                        val fullVer = "$selectedVersion-$fv"
                        fileName = "forge-$fullVer-installer.jar"
                        url = coreManager.getForgeDownloadUrl(selectedVersion, fv)
                    }
                    ServerCoreManager.CoreType.NEOFORGE -> {
                        val nv = selectedBuild.ifEmpty { "latest" }
                        val fullVer = "$selectedVersion-$nv"
                        fileName = "neoforge-$fullVer-installer.jar"
                        url = coreManager.getNeoForgeDownloadUrl(selectedVersion, nv)
                    }
                    ServerCoreManager.CoreType.SPIGOT -> {
                        fileName = "spigot-$selectedVersion.jar"
                        url = coreManager.getSpigotDownloadUrl(selectedVersion)
                    }
                    else -> { downloading = false; return@launch }
                }

                val result = coreManager.downloadJar(url, serverDir, fileName) { p, d, t ->
                    downloadProgress = p; downloadedBytes = d; totalBytes = t
                }
                result.fold(
                    onSuccess = { file ->
                        // Forge / NeoForge 需要额外运行安装器
                        if (selectedType == ServerCoreManager.CoreType.FORGE ||
                            selectedType == ServerCoreManager.CoreType.NEOFORGE
                        ) {
                            scope.launch {
                                downloading = false
                                installing = true
                                installMessage = "> 正在运行安装器，请耐心等待..."

                                // 按优先级获取 proot 内 Java 路径
                                val javaPath = listOf(21, 17, 11, 8).firstNotNullOfOrNull {
                                    if (LinuxEnvironmentManager.isJdkInstalled(it))
                                        LinuxEnvironmentManager.getJavaPath(it)
                                    else null
                                } ?: run {
                                    installMessage = "> 错误：proot 内未找到可用 JDK，请先部署 Linux 环境"
                                    installing = false
                                    return@launch
                                }

                                val installResult = coreManager.installForgeServer(
                                    installerFile = file,
                                    serverDir = serverDir,
                                    javaPath = javaPath,
                                    onOutput = { line ->
                                        // executeCommand 在后台线程回调，需切到主线程更新 Compose 状态
                                        scope.launch(Dispatchers.Main) {
                                            installMessage = line
                                        }
                                    }
                                )

                                installResult.fold(
                                    onSuccess = { entry ->
                                        installMessage = "> 安装完成：${entry.name}"
                                        val mode = if (entry.name.endsWith(".sh"))
                                            StartupMode.SHELL_SCRIPT else StartupMode.DIRECT_JAR
                                        onJarDownloaded(entry.absolutePath, mode)
                                        installing = false
                                    },
                                    onFailure = { err ->
                                        installMessage = "> 安装失败：${err.message}"
                                        installing = false
                                    }
                                )
                            }
                        } else {
                            downloadMessage = "下载完成：${file.name}"
                            onJarDownloaded(file.absolutePath, StartupMode.DIRECT_JAR)
                            downloading = false
                        }
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
                    Text(versionError ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
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
                        Text(buildError ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
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
            ServerCoreManager.CoreType.PURPUR, ServerCoreManager.CoreType.FABRIC, ServerCoreManager.CoreType.VANILLA, ServerCoreManager.CoreType.SPIGOT -> selectedVersion.isNotBlank()
            ServerCoreManager.CoreType.FORGE, ServerCoreManager.CoreType.NEOFORGE -> selectedVersion.isNotBlank() && selectedBuild.isNotBlank()
        }

        if (canDownload || downloading || installing) {
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
                    } else if (installing) {
                        Text("正在运行 Forge/NeoForge 安装器...", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(4.dp))
                        Text(
                            installMessage ?: "请耐心等待，安装过程可能需要几分钟",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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

        installMessage?.let { msg ->
            if (!installing && (msg.startsWith("> 安装完成") || msg.startsWith("> 安装失败") || msg.startsWith("> 错误"))) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (msg.startsWith("> 安装完成")) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (msg.startsWith("> 安装完成")) Icons.Filled.CheckCircle else Icons.Filled.Info,
                            null, Modifier.size(18.dp),
                            tint = if (msg.startsWith("> 安装完成")) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(msg, style = MaterialTheme.typography.bodySmall)
                    }
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
