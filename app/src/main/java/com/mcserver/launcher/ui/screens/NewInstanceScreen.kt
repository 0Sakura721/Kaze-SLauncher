package com.mcserver.launcher.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mcserver.launcher.core.download.CoreSources
import com.mcserver.launcher.core.download.DownloadCenter
import com.mcserver.launcher.ui.components.PageTransition
import com.mcserver.launcher.ui.components.pressScale
import com.mcserver.launcher.ui.components.pressSource
import com.mcserver.launcher.core.server.InstanceStore
import com.mcserver.launcher.data.CoreType
import com.mcserver.launcher.data.InstanceConfig
import com.mcserver.launcher.util.FileImporter
import kotlinx.coroutines.launch

/** 从文件名猜测核心类型与 MC 版本(导入本地 JAR 用) */
private fun guessFromFileName(name: String): Pair<CoreType, String> {
    val lower = name.lowercase()
    val type = when {
        lower.contains("spigot") -> CoreType.SPIGOT
        lower.contains("paper") || lower.contains("purpur") -> CoreType.PAPER
        lower.contains("fabric") -> CoreType.FABRIC
        lower.contains("forge") -> CoreType.FORGE
        lower.contains("neoforge") -> CoreType.NEOFORGE
        else -> CoreType.VANILLA
    }
    val version = Regex("(\\d+\\.\\d+(\\.\\d+)?)").find(name)?.groupValues?.get(1) ?: "导入"
    return type to version
}

/**
 * 新建实例向导:选择核心类型 → MC 版本 → 创建实例并加入下载中心。
 */
@Composable
fun NewInstanceScreen(onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var step by remember { mutableStateOf(1) }
    var coreType by remember { mutableStateOf(CoreType.PAPER) }
    var versions by remember { mutableStateOf<List<com.mcserver.launcher.core.download.CoreVersion>>(emptyList()) }
    var selectedVersion by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var showDownloadedCores by remember { mutableStateOf(false) }

    // SAF:选择本地服务端 JAR(本地导入优先,不消耗流量)
    val importJarLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            importing = true
            scope.launch {
                // 先建临时目录复制,再创建实例
                val importDir = java.io.File(com.mcserver.launcher.KazeApp.instance.filesDir, "import_jar")
                if (importDir.exists()) importDir.deleteRecursively()
                FileImporter.copyFile(context, uri, importDir)
                    .onSuccess { jarFile ->
                        val (type, version) = guessFromFileName(jarFile.name)
                        val instance = InstanceStore.create(
                            name = jarFile.name.removeSuffix(".jar"),
                            coreType = type,
                            mcVersion = version
                        )
                        // 移动 jar 到实例目录
                        try {
                            jarFile.copyTo(java.io.File(instance.dir(InstanceStore.instancesDir), jarFile.name), overwrite = true)
                        } catch (e: Exception) {
                            importDir.deleteRecursively()
                            InstanceStore.delete(instance.id)
                            importing = false
                            error = "复制核心失败:${e.message}"
                            return@launch
                        }
                        importDir.deleteRecursively()
                        importing = false
                        onDone()
                    }
                    .onFailure { err ->
                        importing = false
                        error = "导入失败:${err.message}"
                    }
            }
        }
    }

    // SAF:导入整合包 / 外部包(zip,保留 mods/config/plugins 结构,自动识别核心)
    val importPackLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            importing = true
            scope.launch {
                val tmpDir = java.io.File(com.mcserver.launcher.KazeApp.instance.filesDir, "import_pack")
                if (tmpDir.exists()) tmpDir.deleteRecursively()
                FileImporter.copyFile(context, uri, tmpDir)
                    .onSuccess { zipFile ->
                        try {
                            val packDir = java.io.File(tmpDir, "unpacked")
                            unzipTo(zipFile, packDir)
                            // 识别主核心 jar(优先含 server 关键字,否则取最大 jar;排除 installer)
                            val jars = packDir.walkTopDown()
                                .filter { it.isFile && it.extension == "jar" && !it.name.contains("installer") }
                                .toList()
                            val mainJar = jars.firstOrNull { it.name.contains("server", ignoreCase = true) }
                                ?: jars.maxByOrNull { it.length() }
                            val (type, version) = mainJar?.let { guessFromFileName(it.name) }
                                ?: (CoreType.FORGE to "整合包")
                            val instance = InstanceStore.create(
                                name = zipFile.name.removeSuffix(".zip").take(40),
                                coreType = type,
                                mcVersion = version
                            )
                            val instDir = instance.dir(InstanceStore.instancesDir)
                            // 复制整合包内容(mods/config/plugins 等目录 + 核心 jar)
                            packDir.listFiles()?.forEach { f ->
                                if (f.isDirectory) {
                                    copyTree(f, java.io.File(instDir, f.name))
                                } else if (f.extension == "jar" && f == mainJar) {
                                    f.copyTo(java.io.File(instDir, f.name), overwrite = true)
                                }
                            }
                            tmpDir.deleteRecursively()
                            importing = false
                            val tip = if (mainJar == null) "整合包已导入(未发现服务端核心,请到实例目录补充核心 jar)" else "整合包导入完成(${mainJar.name})"
                            android.widget.Toast.makeText(context, tip, android.widget.Toast.LENGTH_LONG).show()
                            onDone()
                        } catch (e: Exception) {
                            importing = false
                            error = "整合包导入失败:${e.message}"
                        }
                    }
                    .onFailure { err ->
                        importing = false
                        error = "导入失败:${err.message}"
                    }
            }
        }
    }

    // 系统返回键:版本页 → 回类型页;类型页 → 关闭新建
    androidx.activity.compose.BackHandler { if (step > 1) step-- else onDone() }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (pressBack, srcBack) = pressSource()
            IconButton(onClick = { if (step > 1) step-- else onDone() }, interactionSource = srcBack, modifier = pressBack) {
                Icon(Icons.Filled.ArrowBack, "返回")
            }
            Spacer(Modifier.width(8.dp))
            Text(if (step == 1) "选择服务端类型" else "选择 MC 版本",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
        }
        Spacer(Modifier.height(16.dp))

        PageTransition(step) { s ->
            when (s) {
                1 -> {
                Column(Modifier.fillMaxSize()) {
                    // 本地导入优先入口(不消耗流量)
                    val (pressImport, srcImport) = pressSource()
                    Button(
                        onClick = { importJarLauncher.launch(arrayOf("application/java-archive", "application/octet-stream")) },
                        modifier = pressImport.then(Modifier.fillMaxWidth()),
                        interactionSource = srcImport
                    ) {
                        Icon(Icons.Filled.FolderOpen, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (importing) "正在导入..." else "从本地导入服务端 JAR(推荐,不消耗流量)")
                    }
                    Spacer(Modifier.height(8.dp))
                    // 整合包导入(外部包:mods/config/plugins 结构)
                    val (pressPack, srcPack) = pressSource()
                    OutlinedButton(
                        onClick = { importPackLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
                        modifier = pressPack.then(Modifier.fillMaxWidth()),
                        interactionSource = srcPack,
                        enabled = !importing
                    ) {
                        Icon(Icons.Filled.Archive, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (importing) "正在导入..." else "导入整合包 / 外部包(zip,不耗流量)")
                    }
                    Spacer(Modifier.height(8.dp))
                    // 从已下载核心创建(不重复下载)
                    val (pressDl, srcDl) = pressSource()
                    OutlinedButton(
                        onClick = { showDownloadedCores = true },
                        modifier = pressDl.then(Modifier.fillMaxWidth()),
                        interactionSource = srcDl,
                        enabled = !importing
                    ) {
                        Icon(Icons.Filled.CheckCircle, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("从已下载核心创建(不重复下载)")
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("或从以下来源下载:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    // 核心类型网格
                    val types = CoreType.entries
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(types) { type ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (type == coreType) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surface,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .pressScale(remember { MutableInteractionSource() })
                                    .clickable {
                                        coreType = type
                                        loading = true
                                        error = ""
                                        scope.launch {
                                            CoreSources.fetchVersions(type)
                                                .onSuccess { versions = it; loading = false }
                                                .onFailure { error = "获取版本列表失败:${it.message}"; loading = false }
                                        }
                                        step = 2
                                    }
                            ) {
                                Column(Modifier.padding(16.dp)) {
                                    Text(type.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        when (type) {
                                            CoreType.VANILLA -> "官方原版服务端"
                                            CoreType.PAPER -> "高性能,插件生态最丰富(推荐)"
                                            CoreType.PURPUR -> "Paper 分支,更多自定义"
                                            CoreType.SPIGOT -> "经典 Bukkit 分支"
                                            CoreType.FABRIC -> "模组向,轻量"
                                            CoreType.FORGE -> "经典模组加载器"
                                            CoreType.NEOFORGE -> "Forge 继任者,现代模组"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
            2 -> {
                // 版本列表
                if (loading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (error.isNotBlank()) {
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Spacer(Modifier.height(60.dp))
                        Text(error, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = {
                            loading = true; error = ""
                            scope.launch {
                                CoreSources.fetchVersions(coreType)
                                    .onSuccess { versions = it; loading = false }
                                    .onFailure { error = "获取版本列表失败:${it.message}"; loading = false }
                            }
                        }) { Text("重试") }
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(versions.take(40), key = { it.id }) { v ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (v.id == selectedVersion) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surface,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    selectedVersion = v.id
                                    creating = true
                                    // 创建实例并解析下载
                                    scope.launch {
                                        val instance = InstanceStore.create(
                                            name = "${v.id} ${coreType.displayName}",
                                            coreType = coreType,
                                            mcVersion = v.id
                                        )
                                        CoreSources.resolveDownload(coreType, v.id)
                                            .onSuccess { download ->
                                                val jarName = download.fileName
                                                DownloadCenter.enqueue(
                                                    id = "core-${instance.id}",
                                                    title = "${coreType.displayName} $jarName",
                                                    urls = listOf(download.url),
                                                    destFile = java.io.File(instance.dir(InstanceStore.instancesDir), jarName)
                                                )
                                                creating = false
                                                onDone()
                                            }
                                            .onFailure { err ->
                                                creating = false
                                                InstanceStore.delete(instance.id)
                                                error = "解析下载链接失败:${err.message}"
                                            }
                                    }
                                }
                            ) {
                                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(v.id, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                    Spacer(Modifier.weight(1f))
                                    if (!v.isStable) {
                                        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.errorContainer) {
                                            Text("测试版", Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onErrorContainer)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            }
        }
        if (creating) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("正在创建实例并解析下载链接...", Modifier.padding(vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    // ── 从已下载核心创建 ──
    if (showDownloadedCores) {
        // 数据源:下载中心已完成任务 + 扫描所有实例目录里的核心 jar(内存任务重启会丢,实例 jar 更可靠)
        val downloaded = buildList {
            com.mcserver.launcher.core.download.DownloadCenter.tasks.value
                .filter { it.status == com.mcserver.launcher.data.DownloadStatus.COMPLETED && it.destFile.exists() && it.destFile.extension == "jar" }
                .forEach { add(it.destFile) }
            // 所有实例目录的 jar(排除 .bak 与 installer)
            com.mcserver.launcher.core.server.InstanceStore.instancesDir.listFiles()?.forEach { instDir ->
                instDir.listFiles()?.filter {
                    it.extension == "jar" && !it.name.contains("installer") && !it.name.endsWith(".bak")
                }?.forEach { add(it) }
            }
        }.distinctBy { it.absolutePath }
        AlertDialog(
            onDismissRequest = { showDownloadedCores = false },
            title = { Text("从已下载核心创建") },
            text = {
                if (downloaded.isEmpty()) {
                    Text("暂无已下载的核心文件。先到下载中心下载,或从本地导入 JAR。", style = MaterialTheme.typography.bodySmall)
                } else {
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(downloaded, key = { it.absolutePath }) { jar ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                                    .clickable {
                                        showDownloadedCores = false
                                        importing = true
                                        scope.launch {
                                            val (type, version) = guessFromFileName(jar.name)
                                            val instance = InstanceStore.create(
                                                name = jar.name.removeSuffix(".jar"),
                                                coreType = type,
                                                mcVersion = version
                                            )
                                            try {
                                                jar.copyTo(
                                                    java.io.File(instance.dir(InstanceStore.instancesDir), jar.name),
                                                    overwrite = true
                                                )
                                                importing = false
                                                onDone()
                                            } catch (e: Exception) {
                                                importing = false
                                                InstanceStore.delete(instance.id)
                                                error = "创建失败:${e.message}"
                                            }
                                        }
                                    }
                            ) {
                                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                    Text(jar.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                    Text("${formatSize(jar.length())} · 点击创建实例",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showDownloadedCores = false }) { Text("关闭") } }
        )
    }
}
/** 解压 zip 到目标目录(防路径穿越) */
private fun unzipTo(zipFile: java.io.File, destDir: java.io.File) {
    destDir.mkdirs()
    java.util.zip.ZipInputStream(zipFile.inputStream().buffered()).use { zin ->
        var entry = zin.nextEntry
        while (entry != null) {
            val name = entry.name
            // 防路径穿越:跳过 ../ 或绝对路径
            if (!entry.isDirectory && !name.contains("..") && !name.startsWith("/")) {
                val dest = java.io.File(destDir, name)
                dest.parentFile?.mkdirs()
                dest.outputStream().use { out -> zin.copyTo(out) }
            }
            zin.closeEntry()
            entry = zin.nextEntry
        }
    }
}

/** 递归复制目录 */
private fun copyTree(src: java.io.File, dst: java.io.File) {
    dst.mkdirs()
    src.listFiles()?.forEach { f ->
        if (f.isDirectory) copyTree(f, java.io.File(dst, f.name))
        else f.copyTo(java.io.File(dst, f.name), overwrite = true)
    }
}
