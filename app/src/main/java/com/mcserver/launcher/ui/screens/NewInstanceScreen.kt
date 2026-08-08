package com.mcserver.launcher.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcserver.launcher.core.download.CoreSources
import com.mcserver.launcher.core.download.DownloadCenter
import com.mcserver.launcher.core.server.InstanceStore
import com.mcserver.launcher.data.CoreType
import com.mcserver.launcher.ui.components.KazeTopBar
import com.mcserver.launcher.ui.theme.KazeCorners
import com.mcserver.launcher.ui.theme.KazeSizes
import com.mcserver.launcher.ui.theme.KazeSpacing
import com.mcserver.launcher.ui.theme.KazeType
import com.mcserver.launcher.ui.theme.badgeColor
import com.mcserver.launcher.ui.theme.badgeLetter
import com.mcserver.launcher.ui.theme.shortDesc
import com.mcserver.launcher.util.FileFormat
import com.mcserver.launcher.util.FileImporter
import kotlinx.coroutines.launch
import java.io.File

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

private enum class CreateMode { NEW, IMPORT }

@Composable
fun NewInstanceScreen(onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val systemPaddings = WindowInsets.systemBars.asPaddingValues()
    var step by remember { mutableStateOf(1) }
    var mode by remember { mutableStateOf(CreateMode.NEW) }
    var coreType by remember { mutableStateOf(CoreType.PAPER) }
    var instanceName by remember { mutableStateOf("") }
    var versions by remember { mutableStateOf<List<com.mcserver.launcher.core.download.CoreVersion>>(emptyList()) }
    var selectedVersionId by remember { mutableStateOf<String?>(null) }
    var versionFilter by remember { mutableStateOf(VersionFilter.ALL) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var showDownloadedCores by remember { mutableStateOf(false) }

    val importJarLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            importing = true
            scope.launch {
                val importDir = File(com.mcserver.launcher.KazeApp.instance.filesDir, "import_jar")
                if (importDir.exists()) importDir.deleteRecursively()
                FileImporter.copyFile(context, uri, importDir)
                    .onSuccess { jarFile ->
                        val (type, version) = guessFromFileName(jarFile.name)
                        val finalName = instanceName.ifBlank { jarFile.name.removeSuffix(".jar") }
                        val instance = InstanceStore.create(
                            name = finalName,
                            coreType = type,
                            mcVersion = version
                        )
                        try {
                            jarFile.copyTo(File(instance.dir(InstanceStore.instancesDir), jarFile.name), overwrite = true)
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

    val importPackLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            importing = true
            scope.launch {
                val tmpDir = File(com.mcserver.launcher.KazeApp.instance.filesDir, "import_pack")
                if (tmpDir.exists()) tmpDir.deleteRecursively()
                FileImporter.copyFile(context, uri, tmpDir)
                    .onSuccess { zipFile ->
                        try {
                            val packDir = File(tmpDir, "unpacked")
                            unzipTo(zipFile, packDir)
                            val jars = packDir.walkTopDown()
                                .filter { it.isFile && it.extension == "jar" && !it.name.contains("installer") }
                                .toList()
                            val mainJar = jars.firstOrNull { it.name.contains("server", ignoreCase = true) }
                                ?: jars.maxByOrNull { it.length() }
                            val (type, version) = mainJar?.let { guessFromFileName(it.name) }
                                ?: (CoreType.FORGE to "整合包")
                            val finalName = instanceName.ifBlank { zipFile.name.removeSuffix(".zip").take(40) }
                            val instance = InstanceStore.create(
                                name = finalName,
                                coreType = type,
                                mcVersion = version
                            )
                            val instDir = instance.dir(InstanceStore.instancesDir)
                            packDir.listFiles()?.forEach { f ->
                                if (f.isDirectory) {
                                    copyTree(f, File(instDir, f.name))
                                } else if (f.extension == "jar" && f == mainJar) {
                                    f.copyTo(File(instDir, f.name), overwrite = true)
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

    androidx.activity.compose.BackHandler { if (step > 1) step-- else onDone() }

    fun goStep2() {
        loading = true
        error = ""
        selectedVersionId = null
        scope.launch {
            CoreSources.fetchVersions(coreType)
                .onSuccess { versions = it; loading = false }
                .onFailure { error = "获取版本列表失败:${it.message}"; loading = false }
        }
        step = 2
    }

    fun createInstance() {
        if (step == 1) {
            if (mode == CreateMode.NEW) {
                goStep2()
            }
            return
        }
        val vId = selectedVersionId ?: return
        creating = true
        scope.launch {
            val finalName = instanceName.ifBlank { "$vId ${coreType.displayName}" }
            val instance = InstanceStore.create(
                name = finalName,
                coreType = coreType,
                mcVersion = vId
            )
            CoreSources.resolveDownload(coreType, vId)
                .onSuccess { download ->
                    val jarName = download.fileName
                    DownloadCenter.enqueue(
                        id = "core-${instance.id}",
                        title = "${coreType.displayName} $jarName",
                        urls = listOf(download.url),
                        destFile = File(instance.dir(InstanceStore.instancesDir), jarName)
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

    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(systemPaddings.calculateTopPadding()))
        KazeTopBar(
            title = if (step == 1) "新建实例" else "选择版本",
            subtitle = if (step == 1) "选择创建方式与核心类型" else "${coreType.displayName} · 选择 MC 版本",
            onBack = { if (step > 1) step-- else onDone() }
        )

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = KazeSpacing.pageHorizontal, vertical = KazeSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KazeSpacing.sm)
        ) {
            StepDot(active = step >= 1, done = step > 1, label = "核心")
            Box(
                Modifier
                    .weight(1f)
                    .height(2.dp)
                    .clip(KazeCorners.pill)
                    .background(
                        if (step > 1) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
            )
            StepDot(active = step >= 2, done = false, label = "版本")
        }

        if (step == 1 && mode == CreateMode.IMPORT) {
            ImportActions(
                modifier = Modifier.padding(horizontal = KazeSpacing.pageHorizontal),
                importing = importing,
                onImportJar = { importJarLauncher.launch(arrayOf("application/java-archive", "application/octet-stream")) },
                onImportPack = { importPackLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
                onShowDownloaded = { showDownloadedCores = true }
            )
        }

        Box(Modifier.weight(1f)) {
            when (step) {
                1 -> StepOne(
                    mode = mode,
                    onModeChange = { mode = it },
                    coreType = coreType,
                    onCorePick = { coreType = it },
                    instanceName = instanceName,
                    onNameChange = { instanceName = it }
                )
                2 -> StepTwo(
                    coreType = coreType,
                    versions = versions,
                    filter = versionFilter,
                    onFilterChange = { versionFilter = it },
                    selectedId = selectedVersionId,
                    onSelect = { selectedVersionId = it },
                    loading = loading,
                    error = error,
                    creating = creating,
                    onRetry = {
                        loading = true; error = ""
                        scope.launch {
                            CoreSources.fetchVersions(coreType)
                                .onSuccess { versions = it; loading = false }
                                .onFailure { error = "获取版本列表失败:${it.message}"; loading = false }
                        }
                    }
                )
            }
        }

        if (creating || importing) {
            Column(Modifier.padding(horizontal = KazeSpacing.pageHorizontal)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Spacer(Modifier.height(KazeSpacing.sm))
                Text(
                    if (creating) "正在创建实例并解析下载链接…" else "正在导入文件…",
                    style = KazeType.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(KazeSpacing.sm))
            }
        }

        BottomBar(
            step = step,
            mode = mode,
            canNext = step == 1 || selectedVersionId != null,
            loading = creating || importing || (step == 2 && loading),
            onCancel = onDone,
            onPrev = { if (step > 1) step-- else onDone() },
            onNext = { createInstance() }
        )
        Spacer(Modifier.height(systemPaddings.calculateBottomPadding()))
    }

    if (showDownloadedCores) {
        val downloaded = buildList {
            DownloadCenter.tasks.value
                .filter { it.status == com.mcserver.launcher.data.DownloadStatus.COMPLETED && it.destFile.exists() && it.destFile.extension == "jar" }
                .forEach { add(it.destFile) }
            InstanceStore.instancesDir.listFiles()?.forEach { instDir ->
                instDir.listFiles()?.filter {
                    it.extension == "jar" && !it.name.contains("installer") && !it.name.endsWith(".bak")
                }?.forEach { add(it) }
            }
        }.distinctBy { it.absolutePath }

        AlertDialog(
            onDismissRequest = { showDownloadedCores = false },
            title = {
                Text("从已下载核心创建", style = KazeType.title)
            },
            text = {
                if (downloaded.isEmpty()) {
                    Text(
                        "暂无已下载的核心文件。先到下载中心下载,或从本地导入 JAR。",
                        style = KazeType.body,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(
                        Modifier.heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(KazeSpacing.xs)
                    ) {
                        items(downloaded, key = { it.absolutePath }) { jar ->
                            Surface(
                                onClick = {
                                    showDownloadedCores = false
                                    importing = true
                                    scope.launch {
                                        val (type, version) = guessFromFileName(jar.name)
                                        val finalName = instanceName.ifBlank { jar.name.removeSuffix(".jar") }
                                        val instance = InstanceStore.create(
                                            name = finalName,
                                            coreType = type,
                                            mcVersion = version
                                        )
                                        try {
                                            jar.copyTo(
                                                File(instance.dir(InstanceStore.instancesDir), jar.name),
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
                                },
                                shape = KazeCorners.medium,
                                color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(KazeSizes.strokeThin, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(Modifier.padding(KazeSpacing.md)) {
                                    Text(jar.name, style = KazeType.title)
                                    Text(
                                        "${FileFormat.size(jar.length())} · 点击创建实例",
                                        style = KazeType.caption,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showDownloadedCores = false }) {
                    Text("关闭", style = KazeType.body)
                }
            }
        )
    }
}

@Composable
private fun StepDot(active: Boolean, done: Boolean, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(50))
                .background(
                    if (active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (done) "\u2713" else "",
                style = androidx.compose.ui.text.TextStyle(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
        Spacer(Modifier.width(KazeSpacing.sm))
        Text(
            label,
            style = KazeType.caption,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ModeSwitcher(
    mode: CreateMode,
    onModeChange: (CreateMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = KazeCorners.pill,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(Modifier.padding(KazeSpacing.xxs)) {
            ModeTab(
                selected = mode == CreateMode.NEW,
                label = "新建下载",
                onClick = { onModeChange(CreateMode.NEW) }
            )
            ModeTab(
                selected = mode == CreateMode.IMPORT,
                label = "本地导入",
                onClick = { onModeChange(CreateMode.IMPORT) }
            )
        }
    }
}

@Composable
private fun ModeTab(selected: Boolean, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(KazeCorners.pill)
            .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = KazeSpacing.lg, vertical = KazeSpacing.sm),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = KazeType.subtitle,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StepOne(
    mode: CreateMode,
    onModeChange: (CreateMode) -> Unit,
    coreType: CoreType,
    onCorePick: (CoreType) -> Unit,
    instanceName: String,
    onNameChange: (String) -> Unit
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = KazeSpacing.md,
            bottom = KazeSpacing.xl,
            start = KazeSpacing.pageHorizontal,
            end = KazeSpacing.pageHorizontal
        ),
        verticalArrangement = Arrangement.spacedBy(KazeSpacing.lg)
    ) {
        item {
            ModeSwitcher(
                mode = mode,
                onModeChange = onModeChange,
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            Column {
                Text(
                    "实例名称",
                    style = KazeType.subtitle,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(KazeSpacing.xs))
                OutlinedTextField(
                    value = instanceName,
                    onValueChange = onNameChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            "留空将自动命名",
                            style = KazeType.body,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    singleLine = true,
                    shape = KazeCorners.medium,
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        focusedBorderColor = MaterialTheme.colorScheme.primary
                    )
                )
            }
        }

        if (mode == CreateMode.NEW) {
            item {
                Text(
                    "选择核心类型",
                    style = KazeType.subtitle,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            item {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 320.dp),
                    contentPadding = PaddingValues(vertical = KazeSpacing.xs),
                    horizontalArrangement = Arrangement.spacedBy(KazeSpacing.sm),
                    verticalArrangement = Arrangement.spacedBy(KazeSpacing.sm),
                    userScrollEnabled = false
                ) {
                    items(CoreType.entries, key = { it.name }) { type ->
                        CoreGridItem(
                            type = type,
                            selected = coreType == type,
                            onClick = { onCorePick(type) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CoreGridItem(type: CoreType, selected: Boolean, onClick: () -> Unit) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val bgColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface
    Surface(
        onClick = onClick,
        shape = KazeCorners.medium,
        color = bgColor,
        border = BorderStroke(
            width = if (selected) KazeSizes.strokeThick else KazeSizes.strokeThin,
            color = borderColor
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier.padding(KazeSpacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(KazeSpacing.xs)
        ) {
            Box(
                Modifier
                    .size(KazeSizes.badgeMedium)
                    .clip(KazeCorners.medium)
                    .background(type.badgeColor()),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    type.badgeLetter(),
                    style = androidx.compose.ui.text.TextStyle(
                        fontSize = 16.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White
                    )
                )
            }
            Text(type.displayName, style = KazeType.title)
            Text(
                type.shortDesc(),
                style = KazeType.tiny,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ImportActions(
    modifier: Modifier = Modifier,
    importing: Boolean,
    onImportJar: () -> Unit,
    onImportPack: () -> Unit,
    onShowDownloaded: () -> Unit
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(KazeSpacing.sm)
    ) {
        ImportButton(
            onClick = onImportJar,
            label = if (importing) "正在导入…" else "导入服务端 JAR",
            icon = Icons.Filled.FolderOpen,
            enabled = !importing,
            primary = true
        )
        ImportButton(
            onClick = onImportPack,
            label = if (importing) "正在导入…" else "导入整合包 (.zip)",
            icon = Icons.Filled.Archive,
            enabled = !importing,
            primary = false
        )
        ImportButton(
            onClick = onShowDownloaded,
            label = "从已下载核心创建",
            icon = Icons.Filled.CheckCircle,
            enabled = !importing,
            primary = false
        )
    }
}

@Composable
private fun ImportButton(
    onClick: () -> Unit,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    primary: Boolean
) {
    val bg = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    val fg = if (primary) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val border = if (primary) null else BorderStroke(KazeSizes.strokeThin, MaterialTheme.colorScheme.outlineVariant)
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = KazeCorners.medium,
        color = bg,
        border = border,
        modifier = Modifier
            .fillMaxWidth()
            .height(KazeSizes.buttonHeight)
    ) {
        Row(
            Modifier.padding(horizontal = KazeSpacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(icon, null, Modifier.size(KazeSizes.iconSmall), tint = fg)
            Spacer(Modifier.width(KazeSpacing.sm))
            Text(label, style = KazeType.title, color = fg)
        }
    }
}

private enum class VersionFilter { ALL, STABLE, SNAPSHOT }

@Composable
private fun StepTwo(
    coreType: CoreType,
    versions: List<com.mcserver.launcher.core.download.CoreVersion>,
    filter: VersionFilter,
    onFilterChange: (VersionFilter) -> Unit,
    selectedId: String?,
    onSelect: (String) -> Unit,
    loading: Boolean,
    error: String,
    creating: Boolean,
    onRetry: () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = KazeSpacing.pageHorizontal, vertical = KazeSpacing.sm),
            horizontalArrangement = Arrangement.spacedBy(KazeSpacing.sm)
        ) {
            FilterChip(
                selected = filter == VersionFilter.ALL,
                onClick = { onFilterChange(VersionFilter.ALL) },
                label = { Text("全部", style = KazeType.caption) },
                shape = KazeCorners.pill,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                )
            )
            FilterChip(
                selected = filter == VersionFilter.STABLE,
                onClick = { onFilterChange(VersionFilter.STABLE) },
                label = { Text("稳定版", style = KazeType.caption) },
                shape = KazeCorners.pill,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                )
            )
            FilterChip(
                selected = filter == VersionFilter.SNAPSHOT,
                onClick = { onFilterChange(VersionFilter.SNAPSHOT) },
                label = { Text("快照版", style = KazeType.caption) },
                shape = KazeCorners.pill,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }

        val filtered = when (filter) {
            VersionFilter.ALL -> versions
            VersionFilter.STABLE -> versions.filter { it.isStable }
            VersionFilter.SNAPSHOT -> versions.filter { !it.isStable }
        }

        Box(Modifier.weight(1f)) {
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(KazeSpacing.md))
                        Text(
                            "正在获取版本列表…",
                            style = KazeType.caption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                error.isNotBlank() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        Modifier.padding(KazeSpacing.xxl),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            Modifier
                                .size(KazeSizes.badgeHuge)
                                .clip(KazeCorners.large)
                                .background(MaterialTheme.colorScheme.error),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "!",
                                style = androidx.compose.ui.text.TextStyle(
                                    fontSize = 40.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color.White
                                )
                            )
                        }
                        Spacer(Modifier.height(KazeSpacing.lg))
                        Text(error, style = KazeType.body, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(KazeSpacing.md))
                        Surface(
                            onClick = onRetry,
                            shape = KazeCorners.medium,
                            color = Color.Transparent,
                            border = BorderStroke(KazeSizes.strokeThick, MaterialTheme.colorScheme.primary),
                            modifier = Modifier.height(KazeSizes.buttonHeight)
                        ) {
                            Row(
                                Modifier.padding(horizontal = KazeSpacing.lg),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Filled.Add,
                                    null,
                                    Modifier.size(KazeSizes.iconSmall),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(KazeSpacing.sm))
                                Text("重试", style = KazeType.title, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        top = KazeSpacing.sm,
                        bottom = KazeSpacing.xl,
                        start = KazeSpacing.pageHorizontal,
                        end = KazeSpacing.pageHorizontal
                    ),
                    verticalArrangement = Arrangement.spacedBy(KazeSpacing.xs)
                ) {
                    items(filtered.take(60), key = { it.id }) { v ->
                        VersionListItem(
                            version = v,
                            coreType = coreType,
                            selected = selectedId == v.id,
                            enabled = !creating,
                            onClick = { onSelect(v.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VersionListItem(
    version: com.mcserver.launcher.core.download.CoreVersion,
    coreType: CoreType,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = KazeCorners.medium,
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = if (selected) KazeSizes.strokeThick else KazeSizes.strokeThin,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = KazeSpacing.md, vertical = KazeSpacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(KazeSizes.badgeSmall)
                    .clip(KazeCorners.medium)
                    .background(coreType.badgeColor()),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    coreType.badgeLetter(),
                    style = androidx.compose.ui.text.TextStyle(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White
                    )
                )
            }
            Spacer(Modifier.width(KazeSpacing.md))
            Text(
                version.id,
                style = KazeType.title.copy(fontSize = 18.sp),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(KazeSpacing.sm))
            Text(
                if (version.isStable) "稳定" else "快照",
                style = KazeType.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(KazeCorners.small)
                    .background(
                        if (version.isStable) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                        else MaterialTheme.colorScheme.tertiary.copy(alpha = 0.1f)
                    )
                    .padding(horizontal = KazeSpacing.sm, vertical = KazeSpacing.xxs)
            )
            Spacer(Modifier.width(KazeSpacing.sm))
            RadioButton(
                selected = selected,
                onClick = onClick,
                enabled = enabled,
                colors = RadioButtonDefaults.colors(
                    selectedColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    }
}

@Composable
private fun BottomBar(
    step: Int,
    mode: CreateMode,
    canNext: Boolean,
    loading: Boolean,
    onCancel: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = KazeSpacing.pageHorizontal, vertical = KazeSpacing.md),
            horizontalArrangement = Arrangement.spacedBy(KazeSpacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                onClick = onCancel,
                shape = KazeCorners.medium,
                color = Color.Transparent,
                border = BorderStroke(KazeSizes.strokeThin, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .weight(1f)
                    .height(KazeSizes.buttonHeight)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("取消", style = KazeType.title, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Surface(
                onClick = onPrev,
                shape = KazeCorners.medium,
                color = Color.Transparent,
                border = BorderStroke(KazeSizes.strokeThick, MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .weight(1f)
                    .height(KazeSizes.buttonHeight)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        if (step > 1) "上一步" else "取消",
                        style = KazeType.title,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            val nextLabel = when {
                step == 1 && mode == CreateMode.NEW -> "下一步"
                step == 1 && mode == CreateMode.IMPORT -> "导入方式（见上方）"
                else -> "创建"
            }
            val nextEnabled = canNext && !loading && !(step == 1 && mode == CreateMode.IMPORT)
            Surface(
                onClick = onNext,
                enabled = nextEnabled,
                shape = KazeCorners.medium,
                color = if (nextEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .weight(1.2f)
                    .height(KazeSizes.buttonHeight)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = if (nextEnabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(KazeSpacing.sm))
                        }
                        Text(
                            nextLabel,
                            style = KazeType.title,
                            color = if (nextEnabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

private fun unzipTo(zipFile: File, destDir: File) {
    destDir.mkdirs()
    java.util.zip.ZipInputStream(zipFile.inputStream().buffered()).use { zin ->
        var entry = zin.nextEntry
        while (entry != null) {
            val name = entry.name
            if (!entry.isDirectory && !name.contains("..") && !name.startsWith("/")) {
                val dest = File(destDir, name)
                dest.parentFile?.mkdirs()
                dest.outputStream().use { out -> zin.copyTo(out) }
            }
            zin.closeEntry()
            entry = zin.nextEntry
        }
    }
}

private fun copyTree(src: File, dst: File) {
    dst.mkdirs()
    src.listFiles()?.forEach { f ->
        if (f.isDirectory) copyTree(f, File(dst, f.name))
        else f.copyTo(File(dst, f.name), overwrite = true)
    }
}
