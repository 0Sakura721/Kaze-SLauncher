package com.kaze.newage.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kaze.newage.core.server.ServerProperties
import com.kaze.newage.data.model.CoreType
import com.kaze.newage.data.model.GameVersion
import com.kaze.newage.data.model.JavaVersionInference
import com.kaze.newage.ui.AppViewModel
import com.kaze.newage.ui.components.BackgroundCard
import com.kaze.newage.ui.components.CardTitleLayout
import com.kaze.newage.ui.components.CheckChip
import com.kaze.newage.ui.components.InstanceIcon
import com.kaze.newage.ui.theme.cardBorderColor
import com.kaze.newage.ui.theme.cardShape

/**
 * 新建服务端（全屏流程页，替代旧 AlertDialog）。
 * 结构仿 FCL VersionInstallPage（筛选 + 实时搜索 + 版本列表）
 * 与 Zalith DownloadGame 系（加载器卡片选择 + 底部安装动作），GPL-3.0。
 *
 * 两段式：
 *  ① 选择核心类型（大卡片网格）→ ② 搜索/筛选版本 + 配置（名称/内存/Java）
 */
@Composable
fun NewServerScreen(
    viewModel: AppViewModel,
    onBack: () -> Unit,
) {
    var coreType by remember { mutableStateOf<CoreType?>(null) }
    val appContext = LocalContext.current.applicationContext

    // 导入 jar（核心类型选择「导入 jar」时直接走文件选择）
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val name = "导入-${System.currentTimeMillis() % 10000}"
            val dir = viewModel.instanceStore.createInstanceDir(name)
            val target = java.io.File(dir, "server.jar")
            try {
                appContext.contentResolver.openInputStream(uri)?.use { ins ->
                    target.outputStream().use { outs -> ins.copyTo(outs) }
                }
                viewModel.importJar(target, name, javaMajor = 17, memoryMb = 1024)
                onBack()
            } catch (_: Exception) { }
        }
    }

    if (coreType == null) {
        CoreSelectPhase(
            onSelect = { type ->
                if (type == CoreType.CUSTOM) {
                    importLauncher.launch(arrayOf("application/java-archive", "application/octet-stream", "*/*"))
                } else {
                    coreType = type
                }
            },
            onBack = onBack,
        )
    } else {
        VersionConfigPhase(
            viewModel = viewModel,
            coreType = coreType!!,
            onBackToCore = { coreType = null },
            onExit = onBack,
        )
    }
}

// ───────────────────────────────────────────────
// 阶段 ①：选择核心类型（Zalith 加载器卡片风格）
// ───────────────────────────────────────────────

/** 核心类型描述（卡片副标题） */
private fun CoreType.description(): String = when (this) {
    CoreType.VANILLA -> "Mojang 官方原版"
    CoreType.PAPER -> "高性能 · 支持插件"
    CoreType.PURPUR -> "Paper 增强 · 更多配置"
    CoreType.SPIGOT -> "经典插件生态"
    CoreType.FABRIC -> "轻量模组加载器"
    CoreType.FORGE -> "经典模组加载器"
    CoreType.NEOFORGE -> "现代模组加载器"
    CoreType.CUSTOM -> "选择本地 server.jar"
}

@Composable
private fun CoreSelectPhase(
    onSelect: (CoreType) -> Unit,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        // 顶部栏
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Column {
                Text("新建服务端", style = MaterialTheme.typography.titleLarge)
                Text("第 1 步 · 选择核心类型", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 分组：官方 / 性能优化 / 模组加载 / 导入
            coreGroups().forEach { (category, types) ->
                Text(category, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    types.chunked(2).forEach { rowTypes ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            rowTypes.forEach { type ->
                                CoreCard(type, Modifier.weight(1f)) { onSelect(type) }
                            }
                            if (rowTypes.size == 1) Box(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CoreCard(type: CoreType, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = cardShape()
    Surface(
        modifier = modifier,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceBright.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, cardBorderColor()),
        onClick = onClick,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            InstanceIcon(type, Modifier.size(40.dp))
            Text(type.displayName, style = MaterialTheme.typography.titleSmall)
            Text(
                type.description(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 核心类型分组（官方 / 性能 / 模组 / 导入） */
private fun coreGroups(): List<Pair<String, List<CoreType>>> =
    com.kaze.newage.data.model.CoreCategory.entries.map { category ->
        category.displayName to CoreType.entries.filter { it.category == category }
    }.filter { it.second.isNotEmpty() }

// ───────────────────────────────────────────────
// 阶段 ②：版本选择 + 配置（FCL 筛选/搜索 + Zalith 底部动作）
// ───────────────────────────────────────────────

@Composable
private fun VersionConfigPhase(
    viewModel: AppViewModel,
    coreType: CoreType,
    onBackToCore: () -> Unit,
    onExit: () -> Unit,
) {
    val versions by viewModel.versions.collectAsState()
    val versionsLoading by viewModel.versionsLoading.collectAsState()
    val download by viewModel.download.collectAsState()

    var query by remember { mutableStateOf("") }
    var showSnapshots by remember { mutableStateOf(false) }
    var mcVersion by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var memoryMb by remember { mutableFloatStateOf(2048f) }
    var javaOverride by remember { mutableIntStateOf(0) }

    // 进入阶段 ② 时加载版本列表
    LaunchedEffect(coreType) {
        viewModel.loadVersions(coreType)
    }

    val releaseVersions = versions.filter { it.isRelease }
    val snapshotVersions = versions.filter { !it.isRelease }
    val filtered = versions
        .filter { showSnapshots || it.isRelease }
        .filter { it.id.contains(query.trim(), ignoreCase = true) }

    val autoJava = if (mcVersion.isBlank()) 17 else JavaVersionInference.infer(mcVersion)
    val canCreate = name.isNotBlank() && mcVersion.isNotBlank() && !download.running

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        // 顶部栏
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = onBackToCore) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回选择核心")
            }
            Column {
                Text(coreType.displayName, style = MaterialTheme.typography.titleLarge)
                Text("第 2 步 · 选择版本与配置", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── 搜索 + 类型筛选（FCL 模式）──
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("搜索版本（如 1.21 / 26.2）") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                singleLine = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = !showSnapshots,
                    onClick = { showSnapshots = false },
                    label = { Text("正式版（${releaseVersions.size}）") },
                )
                FilterChip(
                    selected = showSnapshots,
                    onClick = { showSnapshots = true },
                    label = { Text("快照版（${snapshotVersions.size}）") },
                )
            }

            // ── 版本列表 ──
            // borderless：选择版本的「MC 版本」框不带边框（用户指定），内容直接铺在卡片上
            BackgroundCard(Modifier.fillMaxWidth(), borderless = true) {
                CardTitleLayout("MC 版本", trailing = {
                    if (versionsLoading) {
                        Text("加载中…", style = MaterialTheme.typography.labelSmall)
                    }
                }) {
                    when {
                        versionsLoading -> {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        filtered.isEmpty() -> {
                            Text(
                                if (versions.isEmpty()) "版本列表加载失败，请检查网络" else "没有匹配的版本",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        else -> {
                            // 版本行用普通 Column（父级为滚动容器，避免嵌套 LazyColumn 崩溃）；
                            // 数量上限保护，超出提示用搜索缩小范围
                            val shown = filtered.take(40)
                            Column(Modifier.fillMaxWidth()) {
                                shown.forEach { v ->
                                    VersionRow(
                                        version = v,
                                        selected = mcVersion == v.id,
                                        onSelect = { mcVersion = v.id },
                                    )
                                }
                                if (filtered.size > shown.size) {
                                    Text(
                                        "共 ${filtered.size} 个版本，输入关键字缩小范围",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── 配置（选中版本后显示）──
            if (mcVersion.isNotBlank()) {
                BackgroundCard(Modifier.fillMaxWidth()) {
                    CardTitleLayout("配置") {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("实例名称") },
                            placeholder = { Text("如 我的生存服") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        Text("内存：${memoryMb.toInt()} MB", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
                        Slider(
                            value = memoryMb,
                            onValueChange = { memoryMb = it },
                            valueRange = 512f..8192f,
                            steps = 14,
                        )
                        Text("Java 版本", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                        Text(
                            "默认按 MC 版本自动推断；如遇兼容问题可手动指定。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                        androidx.compose.foundation.layout.FlowRow(
                            Modifier.padding(top = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            CheckChip(selected = javaOverride == 0, label = "自动（Java $autoJava）", onClick = { javaOverride = 0 })
                            listOf(8, 17, 21, 25).forEach { v ->
                                CheckChip(selected = javaOverride == v, label = "Java $v", onClick = { javaOverride = v })
                            }
                        }
                        Text(
                            "端口将自动分配（${ServerProperties.findFreePort(viewModel.instanceStore.instances.value)} 起），可在实例详情页修改。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }

            // ── 下载进度 ──
            if (download.running) {
                LinearProgressIndicator(
                    progress = { download.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                download.message.let {
                    if (it.isNotBlank()) Text(it, style = MaterialTheme.typography.bodySmall)
                }
            }
            download.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }

        // ── 底部动作（Zalith 模式）──
        Button(
            onClick = {
                viewModel.downloadAndCreate(
                    name = name.trim(),
                    type = coreType,
                    mcVersion = mcVersion,
                    memoryMb = memoryMb.toInt(),
                    javaMajorOverride = javaOverride,
                    onComplete = { if (it != null) onExit() },
                )
            },
            enabled = canCreate,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        ) {
            Text(
                when {
                    download.running -> "下载中…"
                    mcVersion.isBlank() -> "请先选择版本"
                    else -> "下载并创建"
                }
            )
        }
    }
}

/** 版本行：FCL 版本列表风格（整行可点，名称 + 类型徽标 + 选中高亮） */
@Composable
private fun VersionRow(
    version: GameVersion,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(cardShape())
            .clickable(onClick = onSelect)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(Modifier.weight(1f)) {
            Text(version.id, style = MaterialTheme.typography.bodyMedium)
            Text(
                if (version.isRelease) "正式版" else "快照版",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "已选择",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
