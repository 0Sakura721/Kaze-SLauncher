package com.kaze.newage.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.util.Locale
import com.kaze.newage.core.download.CoreBuild
import com.kaze.newage.core.download.CoreSources
import com.kaze.newage.core.server.ServerProperties
import com.kaze.newage.data.model.CoreType
import com.kaze.newage.data.model.GameVersion
import com.kaze.newage.data.model.JavaVersionInference
import com.kaze.newage.data.model.VersionType
import com.kaze.newage.ui.AppViewModel
import com.kaze.newage.ui.DownloadState
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
        // 同 ServerScreen：复制在 ViewModel 的 IO 协程里做，失败有 Toast（原实现主线程拷贝 + 静默吞异常）
        uri?.let {
            viewModel.importJar(
                uri = it,
                name = "导入-" + java.text.SimpleDateFormat("MMdd-HHmmss", java.util.Locale.US)
                    .format(java.util.Date()),
                memoryMb = 1024,
            )
            onBack()
        }
    }

    // 系统返回键先回退向导**内部**的一步，而不是整页弹出（否则页内箭头只退一级、
    // 系统返回直接关掉整个向导，已填的名称/端口/EULA 一起丢）。
    // 配置页还有一层更深的 BackHandler（回版本列表），它注册得更晚、优先级更高。
    BackHandler(enabled = coreType != null) { coreType = null }

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
            // 统一高度：描述 1/2 行时卡片高度一致，网格不零乱
            Modifier.fillMaxWidth().height(118.dp).padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            InstanceIcon(type, Modifier.size(40.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(type.displayName, style = MaterialTheme.typography.titleSmall)
                Text(
                    type.description(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
    }
}

/** 核心类型分组（官方 / 性能 / 模组 / 导入） */
private fun coreGroups(): List<Pair<String, List<CoreType>>> =
    com.kaze.newage.data.model.CoreCategory.entries.map { category ->
        category.displayName to CoreType.entries.filter { it.category == category }
    }.filter { it.second.isNotEmpty() }

// ───────────────────────────────────────────────
// 阶段 ②：选择版本（FCL 列表页）→ 配置页（独立页面，滑动切换动画）
// ───────────────────────────────────────────────

@Composable
private fun VersionConfigPhase(
    viewModel: AppViewModel,
    coreType: CoreType,
    onBackToCore: () -> Unit,
    onExit: () -> Unit,
) {
    val versions by viewModel.versions.collectAsStateWithLifecycle()
    val versionsLoading by viewModel.versionsLoading.collectAsStateWithLifecycle()
    val download by viewModel.download.collectAsStateWithLifecycle()
    // 选定版本后的可选构建（Paper 有构建列表，其余核心为空）
    val builds by viewModel.builds.collectAsStateWithLifecycle()
    val buildsLoading by viewModel.buildsLoading.collectAsStateWithLifecycle()
    var buildId by remember { mutableStateOf("") }          // "" = 最新构建
    var showBuildPicker by remember { mutableStateOf(false) }

    var query by rememberSaveable { mutableStateOf("") }
    // 版本类型筛选：null = 全部。此前只有「正式版 / 快照版」两档，而快照那一档其实是
    // "所有非正式版"——Vanilla 清单里的 old_beta / old_alpha 被误计入快照版且无法单独查看。
    // FCL 是四档（正式版 / 快照版 / 旧测试版 / 旧预览版），这里对齐。
    // 存 name 字符串而不是枚举本身：rememberSaveable 只保证 Bundle 可存类型。
    var typeFilterName by rememberSaveable { mutableStateOf(VersionType.RELEASE.name) }
    val typeFilter: VersionType? = VersionType.entries.firstOrNull { it.name == typeFilterName }
    var selected by remember { mutableStateOf<GameVersion?>(null) }
    // 配置状态提升到本层：返回列表再选其他版本时，已填内容保留
    var name by rememberSaveable { mutableStateOf("") }
    // 游戏内存：自动分配开关（开=系统建议，不可手动改；关=滑块手动分配）
    var autoMemory by rememberSaveable { mutableStateOf(true) }
    var memoryMb by rememberSaveable { mutableFloatStateOf(2048f) }    // 手动模式「游戏分配」
    var showMemoryDialog by remember { mutableStateOf(false) }

    // ── 安装时的服务器参数（参考 FCL 安装页的"可选项"：建服时就把关键参数定下来）──
    // 端口留空 = 用自动分配的空闲端口（多开时不会撞端口）
    var portText by rememberSaveable { mutableStateOf("") }
    var maxPlayersText by rememberSaveable { mutableStateOf("20") }
    var onlineMode by rememberSaveable { mutableStateOf(false) }
    var gameMode by rememberSaveable { mutableStateOf("survival") }
    // EULA 必须显式同意：此前是首次启动时静默写入 eula=true
    var eulaAgreed by rememberSaveable { mutableStateOf(false) }

    // 设备内存信息（GB）：/proc/meminfo 实读（真机实测 ActivityManager.getMemoryInfo().availMem 返回异常=totalMem）
    val mem = remember {
        fun readMem(key: String): Long = try {
            java.io.File("/proc/meminfo").useLines { lines ->
                lines.firstOrNull { it.startsWith(key) }
                    ?.split(Regex("\\s+"))?.get(1)?.toLongOrNull() ?: 0L
            }
        } catch (_: Exception) { 0L }
        val totalKb = readMem("MemTotal")
        val availKb = readMem("MemAvailable")
        totalKb to availKb
    }
    val totalMemGb = mem.first / 1048576f
    val usedMemGb = (mem.first - mem.second) / 1048576f
    val availMemGb = mem.second / 1048576f
    // 自动分配建议 = 可用内存一半（MB），256MB 对齐；
    // 下限 1024MB：512MB 连原版服务端都起不来（用户反馈"有的太小开都开不了"），上限 8192
    val suggestMb = remember(availMemGb) {
        (((availMemGb * 1024f * 0.5f) / 256f).toInt() * 256f).coerceIn(1024f, 8192f)
    }
    // 实际生效内存：自动 = 系统建议（不可手动改）；手动 = 滑块值
    val effectiveMb = if (autoMemory) suggestMb else memoryMb
    // 端口自动分配起点
    val freePort = remember { ServerProperties.findFreePort(viewModel.instanceStore.instances.value) }
    // 已被其它实例占用的端口：手填端口时给冲突提示（自动分配会避让，手填此前没有任何校验）
    val usedPorts = remember(viewModel.instanceStore.instances.value) {
        viewModel.instanceStore.instances.value.mapNotNull { inst ->
            ServerProperties.load(inst.dir)["server-port"]?.toIntOrNull()
        }.toSet()
    }

    // 进入阶段 ② 时加载版本列表
    LaunchedEffect(coreType) {
        viewModel.loadVersions(coreType)
    }

    // 配置页按系统返回 → 回版本列表（与页内返回箭头行为一致）
    BackHandler(enabled = selected != null) { selected = null }

    // 选中版本进配置页时：拉该版本的可选构建 + 给一个可改的默认实例名（FCL 行为）
    LaunchedEffect(selected) {
        val sel = selected
        if (sel != null) {
            buildId = ""                       // 换版本就回到"最新构建"
            viewModel.loadBuilds(coreType, sel.id)
            if (name.isBlank()) name = "${coreType.displayName}-${sel.id}"
        }
    }

    // 选中版本 → 配置页从右滑入；返回时反向滑出（导航式动画）
    AnimatedContent(
        targetState = selected,
        transitionSpec = {
            (slideInHorizontally(tween(300)) { it / 3 } + fadeIn(tween(300)))
                .togetherWith(slideOutHorizontally(tween(300)) { -it / 4 } + fadeOut(tween(300)))
        },
        label = "new-server-pages",
    ) { sel ->
        if (sel == null) {
            VersionListPage(
                coreType = coreType,
                versions = versions,
                loading = versionsLoading,
                query = query,
                onQueryChange = { query = it },
                typeFilter = typeFilter,
                // "" = 全部；rememberSaveable 存字符串
                onTypeFilter = { typeFilterName = it?.name ?: "" },
                onSelect = { selected = it },
                onBack = onBackToCore,
            )
        } else {
            ConfigPage(
                version = sel,
                coreType = coreType,
                name = name,
                onNameChange = { name = it },
                autoMemory = autoMemory,
                onAutoMemory = { autoMemory = it },
                suggestMb = suggestMb,
                memoryMb = memoryMb,
                onMemoryMb = { memoryMb = it },
                showMemoryDialog = showMemoryDialog,
                onShowMemoryDialog = { showMemoryDialog = it },
                effectiveMb = effectiveMb,
                totalMemGb = totalMemGb,
                usedMemGb = usedMemGb,
                freePort = freePort,
                usedPorts = usedPorts,
                download = download,
                portText = portText,
                onPortTextChange = { v -> portText = v.filter { it.isDigit() }.take(5) },
                maxPlayersText = maxPlayersText,
                onMaxPlayersTextChange = { v -> maxPlayersText = v.filter { it.isDigit() }.take(3) },
                onlineMode = onlineMode,
                onOnlineModeChange = { onlineMode = it },
                gameMode = gameMode,
                onGameModeChange = { gameMode = it },
                eulaAgreed = eulaAgreed,
                onEulaAgreedChange = { eulaAgreed = it },
                builds = builds,
                buildsLoading = buildsLoading,
                buildId = buildId,
                onShowBuildPicker = { showBuildPicker = true },
                onCreate = {
                    // 端口非法（空/越界）时回落到自动分配的端口
                    val port = portText.toIntOrNull()?.takeIf { it in 1024..65535 } ?: freePort
                    val maxPlayers = maxPlayersText.toIntOrNull()?.coerceIn(1, 999) ?: 20
                    viewModel.downloadAndCreate(
                        name = name.trim(),
                        type = coreType,
                        mcVersion = sel.id,
                        memoryMb = effectiveMb.toInt(),
                        // Java 不给用户选择：javaMajorOverride 保持 0，downloadAndCreate 内自动推断最优版本
                        propsOverride = mapOf(
                            "server-port" to port.toString(),
                            "max-players" to maxPlayers.toString(),
                            "online-mode" to onlineMode.toString(),
                            "gamemode" to gameMode,
                        ),
                        buildId = buildId,
                        onComplete = { if (it != null) onExit() },
                    )
                },
                onCancelDownload = { viewModel.cancelDownload() },
                onBack = { selected = null },
            )
        }
    }

    // 构建选择对话框（FCL「加载器版本」对应交互）
    if (showBuildPicker) {
        BuildPickerDialog(
            noun = buildNounOf(coreType),
            builds = builds,
            selectedId = buildId,
            onPick = { buildId = it; showBuildPicker = false },
            onDismiss = { showBuildPicker = false },
        )
    }
}

/** Paper 叫「构建」，Fabric 叫「加载器」——同一处 UI 按核心类型换词 */
private fun buildNounOf(type: CoreType): String =
    if (type == CoreType.FABRIC) "加载器" else "构建"

@Composable
private fun BuildPickerDialog(
    noun: String,
    builds: List<CoreBuild>,
    selectedId: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择核心$noun") },
        text = {
            Column(Modifier.height(360.dp)) {
                LazyColumn(Modifier.weight(1f)) {
                    item {
                        BuildRow(
                            label = "最新",
                            hint = builds.firstOrNull()?.name ?: "",
                            selected = selectedId.isBlank(),
                            onClick = { onPick("") },
                        )
                    }
                    items(builds) { b ->
                        BuildRow(
                            label = b.name,
                            hint = b.fileName ?: "",
                            selected = selectedId == b.id,
                            onClick = { onPick(b.id) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

@Composable
private fun BuildRow(label: String, hint: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (hint.isNotBlank()) {
                Text(
                    hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

// ───────────────────────────────────────────────
// 页面 A：版本选择（FCL 筛选/搜索，整行点击即进入配置）
// ───────────────────────────────────────────────

@Composable
private fun VersionListPage(
    coreType: CoreType,
    versions: List<GameVersion>,
    loading: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    typeFilter: VersionType?,
    onTypeFilter: (VersionType?) -> Unit,
    onSelect: (GameVersion) -> Unit,
    onBack: () -> Unit,
) {
    val q = query.trim()
    // 类型筛选 + 关键词搜索（FCL：先按类型分档，再在档内搜）
    val filtered = versions
        .filter { typeFilter == null || it.type == typeFilter }
        .filter { q.isEmpty() || it.id.contains(q, ignoreCase = true) }
    // 每档的数量（用于 chip 上的计数）
    val countOf: (VersionType?) -> Int = { t ->
        if (t == null) versions.size else versions.count { it.type == t }
    }

    // adjustNothing 下窗口不随键盘缩放：不主动避让，搜索框会被键盘盖住
    Column(Modifier.fillMaxSize().imePadding().padding(16.dp)) {
        // 顶部栏
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回选择核心")
            }
            Column {
                Text(coreType.displayName, style = MaterialTheme.typography.titleLarge)
                Text("第 2 步 · 选择版本", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // 搜索 + 类型筛选（FCL 模式）
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            placeholder = { Text("搜索版本（如 1.21 / 26.2）") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            singleLine = true,
        )
        // 类型筛选（FCL 四档 + 全部）。用 FlowRow 换行而不是横向滚动：
        // 5 个 chip 在窄屏放不下，横向滚动会让后两档（远古测试版/远古预览版）完全看不见。
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        androidx.compose.foundation.layout.FlowRow(
            Modifier.padding(top = 10.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip(
                selected = typeFilter == null,
                onClick = { onTypeFilter(null) },
                label = { Text("全部（${countOf(null)}）") },
            )
            VersionType.entries.forEach { t ->
                val n = countOf(t)
                // 该核心没有这类版本时不显示，避免一排 0 的 chip
                if (n > 0) {
                    FilterChip(
                        selected = typeFilter == t,
                        onClick = { onTypeFilter(t) },
                        label = { Text("${t.displayName}（$n）") },
                    )
                }
            }
        }

        // 结果反馈：换筛选后列表顶部**可能看起来完全一样** —— 快照版往往就是最新的那些版本，
        // 例如 Vanilla 的「快照版(750)」与「全部(913)」首屏都是 26w14a，用户会以为筛选没生效。
        // 把匹配数量常驻在顶部（原来只在列表底部、且 >200 才显示，等于看不到）。
        if (!loading && versions.isNotEmpty()) {
            Text(
                buildString {
                    append("匹配 ${filtered.size} 个版本")
                    if (filtered.size != versions.size) append("（共 ${versions.size} 个）")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, start = 4.dp),
            )
        }

        // 版本列表：独立页内直接用 LazyColumn（合理利用整页空间）
        when {
            loading -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            filtered.isEmpty() -> {
                Text(
                    if (versions.isEmpty()) "版本列表加载失败，请检查网络" else "没有匹配的版本",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 16.dp),
                )
            }
            else -> {
                val listState = rememberLazyListState()
                // 换筛选/改搜索词后回到顶部：LazyColumn 会保留滚动位置，
                // 用户可能停在列表中间，看到的内容与刚选中的分类对不上
                LaunchedEffect(typeFilter, q) { listState.scrollToItem(0) }
                // 列表全量展示（云端获取，LazyColumn 懒加载无压力；可搜索过滤）
                LazyColumn(state = listState, modifier = Modifier.weight(1f).padding(top = 8.dp)) {
                    items(filtered) { v ->
                        VersionRow(version = v, onClick = { onSelect(v) })
                    }
                }
            }
        }
    }
}

// ───────────────────────────────────────────────
// 页面 B：配置（名称 / FCL 内存 / Java 自动适配 / 端口），底部创建动作
// ───────────────────────────────────────────────

@Composable
private fun ConfigPage(
    version: GameVersion,
    coreType: CoreType,
    name: String,
    onNameChange: (String) -> Unit,
    autoMemory: Boolean,
    onAutoMemory: (Boolean) -> Unit,
    suggestMb: Float,
    memoryMb: Float,
    onMemoryMb: (Float) -> Unit,
    showMemoryDialog: Boolean,
    onShowMemoryDialog: (Boolean) -> Unit,
    effectiveMb: Float,
    totalMemGb: Float,
    usedMemGb: Float,
    freePort: Int,
    usedPorts: Set<Int>,
    download: DownloadState,
    portText: String,
    onPortTextChange: (String) -> Unit,
    maxPlayersText: String,
    onMaxPlayersTextChange: (String) -> Unit,
    onlineMode: Boolean,
    onOnlineModeChange: (Boolean) -> Unit,
    gameMode: String,
    onGameModeChange: (String) -> Unit,
    eulaAgreed: Boolean,
    onEulaAgreedChange: (Boolean) -> Unit,
    builds: List<CoreBuild>,
    buildsLoading: Boolean,
    buildId: String,
    onShowBuildPicker: () -> Unit,
    onCreate: () -> Unit,
    onCancelDownload: () -> Unit,
    onBack: () -> Unit,
) {
    // 滑块显示值：自动模式显示系统建议值（只读），手动模式显示滑块值
    val sliderMb = if (autoMemory) suggestMb else memoryMb
    val fmtGb: (Float) -> String = { java.lang.String.format(Locale.US, "%.1f GB", it / 1024f) }
    // Java 不给用户选择：按 MC 版本自动推断最优版本
    val autoJava = JavaVersionInference.infer(version.id)
    val exceeded = effectiveMb > totalMemGb * 1024f * 0.8f
    // EULA 未同意时不允许创建（此前是首次启动静默写入 eula=true）；
    // 端口冲突/越界同样阻断——只给红字提示却照样能点，会建出两个抢同一端口的实例，
    // 后启动的那个 bind 失败直接退出。
    val portValue = portText.toIntOrNull()
    val portBad = portValue != null && (portValue !in 1024..65535 || portValue in usedPorts)
    val canCreate = name.isNotBlank() && !download.running && eulaAgreed && !portBad

    // adjustNothing 下窗口不随键盘缩放：实例名/端口等输入框与底部按钮都会被键盘盖住
    Column(Modifier.fillMaxSize().imePadding().padding(16.dp)) {
        // 顶部栏：返回版本列表 + 版本徽标
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回选择版本")
            }
            Column(Modifier.weight(1f)) {
                Text("配置", style = MaterialTheme.typography.titleLarge)
                Text(
                    "MC ${version.id} · ${coreType.displayName}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        if (version.isRelease) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    if (version.isRelease) "正式版" else "快照版",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (version.isRelease) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 内容：直接铺背景，分区标题 + 分隔线（与设置页一致的轻框架风格）
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── 节① 实例名称 ──
            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                label = { Text("实例名称") },
                placeholder = { Text("如 我的生存服") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            // ── 节② 游戏内存（FCL 全局游戏设置布局）──
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("游戏内存", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (autoMemory) "自动分配内存" else "手动分配",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Switch(
                        checked = autoMemory,
                        onCheckedChange = onAutoMemory,
                    )
                }
            }
            // 滑块标签（自动→系统建议只读 / 手动→可拖游戏分配）
            Text(
                if (autoMemory) "自动分配（建议 ${fmtGb(suggestMb)}）" else "游戏分配",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // FCLNumberSeekBar 风格滑块：thumb 处显示当前值；自动模式禁用（不可手动改动）
            @OptIn(ExperimentalMaterial3Api::class)
            Slider(
                value = sliderMb,
                onValueChange = { onMemoryMb(it) },
                enabled = !autoMemory,
                valueRange = 512f..8192f,
                steps = 29, // 每 256MB 一档
                thumb = {
                    Box(
                        Modifier
                            .size(width = 64.dp, height = 30.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (exceeded) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            fmtGb(sliderMb),
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                },
            )
            // 状态文案：自动→系统建议（只读）；手动→游戏分配
            val statusText = if (autoMemory) {
                "自动分配 ${fmtGb(effectiveMb)} · 根据设备可用内存，不可手动修改"
            } else {
                "游戏分配 ${fmtGb(effectiveMb)}"
            }
            Text(
                if (exceeded) "$statusText（设备仅 ${fmtGb(totalMemGb * 1024f)} 可用）"
                else statusText,
                style = MaterialTheme.typography.bodySmall,
                color = if (exceeded) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // FCL 设备信息行（strings: settings_memory_used_per_total / settings_physical_memory）
            Text(
                "设备中已使用 ${fmtGb(usedMemGb * 1024f)} / 设备总内存 ${fmtGb(totalMemGb * 1024f)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "范围 512 MB ~ 8192 MB",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = { onShowMemoryDialog(true) },
                    enabled = !autoMemory, // 自动模式不可手动改动
                ) {
                    Text("精确输入", style = MaterialTheme.typography.labelMedium)
                }
            }
            HorizontalDivider()

            // ── 节③ Java（只读：自动适配最优版本，不给用户选择）──
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(
                    Icons.Filled.Build,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Column {
                    Text("Java 版本", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "自动适配 Java $autoJava · 按 MC 版本推荐，无需手动选择",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            HorizontalDivider()

            // ── 节④ 核心构建 / 加载器（有列表的核心才出现；FCL「加载器版本」的对应物）──
            if (buildsLoading || builds.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("核心${buildNounOf(coreType)}", style = MaterialTheme.typography.titleSmall)
                        Text(
                            when {
                                buildsLoading -> "正在获取列表…"
                                buildId.isBlank() ->
                                    "最新（${builds.firstOrNull()?.name ?: "?"}）"
                                else ->
                                    "已选：${builds.firstOrNull { it.id == buildId }?.name ?: buildId}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onShowBuildPicker, enabled = builds.isNotEmpty()) {
                        Text("选择")
                    }
                }
                HorizontalDivider()
            }

            // ── 节⑤ 服务器设置（安装时就定下基础参数，参考 FCL 安装页的"可选项"）──
            Text("服务器设置", style = MaterialTheme.typography.titleSmall)
            Text(
                "会写入 server.properties，创建后也能在实例详情里修改",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = portText,
                    onValueChange = onPortTextChange,
                    label = { Text("端口") },
                    placeholder = { Text(freePort.toString()) },
                    supportingText = {
                        val p = portText.toIntOrNull()
                        val invalid = p != null && p !in 1024..65535
                        val conflict = p != null && p in usedPorts
                        Text(
                            when {
                                portText.isBlank() -> "留空自动分配"
                                invalid -> "端口需在 1024 ~ 65535"
                                conflict -> "该端口已被其它实例占用"
                                else -> "将使用端口 $p"
                            },
                            color = if (invalid || conflict) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = maxPlayersText,
                    onValueChange = onMaxPlayersTextChange,
                    label = { Text("最大玩家") },
                    supportingText = { Text("1 ~ 999") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("正版验证", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (onlineMode) "仅正版账号可进入" else "离线客户端也能进入（局域网常用）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = onlineMode, onCheckedChange = onOnlineModeChange)
            }
            Text("默认游戏模式", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "survival" to "生存",
                    "creative" to "创造",
                    "adventure" to "冒险",
                    "spectator" to "旁观",
                ).forEach { (value, label) ->
                    FilterChip(
                        selected = gameMode == value,
                        onClick = { onGameModeChange(value) },
                        label = { Text(label) },
                    )
                }
            }
            HorizontalDivider()

            // ── 节⑤ EULA：必须显式同意 ──
            // 此前是首次启动时静默写入 eula=true（用户从未看到过条款）
            val context = LocalContext.current
            Row(
                Modifier.fillMaxWidth().toggleable(value = eulaAgreed, role = Role.Checkbox, onValueChange = onEulaAgreedChange),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // onCheckedChange = null：整行的 clickable 已是唯一触控目标。
                    // 否则同一个动作有两个可聚焦节点，TalkBack 会把"同意"读两遍。
                    Checkbox(checked = eulaAgreed, onCheckedChange = null)
                Column(Modifier.weight(1f)) {
                    Text(
                        "我已阅读并同意 Minecraft EULA",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "点此查看条款",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse("https://aka.ms/MinecraftEULA"))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        },
                    )
                }
            }

            // ── 下载进度 / 错误 ──
            if (download.running) {
                LinearProgressIndicator(
                    progress = { download.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    download.message.let {
                        if (it.isNotBlank()) {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    // 取消：下载中止保留断点（重试续传）
                    TextButton(onClick = onCancelDownload) { Text("取消") }
                }
            }
            download.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }

        // ── 底部创建动作 ──
        Button(
            onClick = onCreate,
            enabled = canCreate,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        ) {
            Text(
                when {
                    download.running -> "下载中…"
                    download.error != null -> "重试下载（断点续传）"
                    else -> "下载并创建"
                }
            )
        }

        // ── 精确输入内存对话框（FCLNumberSeekBar 点击数值行为）──
        if (showMemoryDialog) {
            var inputGb by remember { mutableStateOf(fmtGb(sliderMb).removeSuffix(" GB")) }
            AlertDialog(
                onDismissRequest = { onShowMemoryDialog(false) },
                title = { Text("精确设置内存") },
                text = {
                    OutlinedTextField(
                        value = inputGb,
                        onValueChange = { inputGb = it },
                        label = { Text("内存（GB）") },
                        placeholder = { Text("2.0 ~ 8.0") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        inputGb.toFloatOrNull()?.let { gb ->
                            onMemoryMb((gb * 1024f).coerceIn(512f, 8192f))
                        }
                        onShowMemoryDialog(false)
                    }) { Text("确定") }
                },
                dismissButton = {
                    TextButton(onClick = { onShowMemoryDialog(false) }) { Text("取消") }
                },
            )
        }
    }
}

/** 版本行：FCL 版本列表风格（图标位 + 版本号加粗 + 类型徽标 + 箭头，整行点击进入配置） */
@Composable
private fun VersionRow(
    version: GameVersion,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 图标位：主色 12% 圆角方块 + 版本主号（FCL 方块图标位等效）
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                version.id.split('.').take(2).joinToString("."),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                version.id,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            )
            Box(
                Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        if (version.isRelease) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .padding(horizontal = 6.dp, vertical = 1.dp)
            ) {
                Text(
                    if (version.isRelease) "正式版" else "快照版",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (version.isRelease) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = "配置",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
    }
}
