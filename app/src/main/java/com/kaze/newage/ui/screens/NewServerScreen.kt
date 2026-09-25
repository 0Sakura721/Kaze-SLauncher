package com.kaze.newage.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale
import com.kaze.newage.core.download.CoreBuild
import com.kaze.newage.core.server.ServerProperties
import com.kaze.newage.data.model.CoreCategory
import com.kaze.newage.data.model.CoreType
import com.kaze.newage.data.model.GameVersion
import com.kaze.newage.data.model.JavaVersionInference
import com.kaze.newage.data.model.VersionType
import com.kaze.newage.ui.AppViewModel
import com.kaze.newage.ui.DownloadState
import com.kaze.newage.ui.components.InstanceIcon
import com.kaze.newage.ui.components.M3ECard
import com.kaze.newage.ui.components.M3ECardVariant
import com.kaze.newage.ui.components.M3EListItem
import com.kaze.newage.ui.components.M3EScreenHeader
import com.kaze.newage.ui.components.M3ESegmentedRow
import com.kaze.newage.ui.components.M3EStatusChip
import com.kaze.newage.ui.components.WavyLinearProgress
import com.kaze.newage.ui.theme.M3Motion
import com.kaze.newage.ui.theme.M3Shape
import com.kaze.newage.ui.theme.M3Spacing
import androidx.compose.material3.OutlinedButton

/**
 * 新建服务端 —— 三步向导：选核心 → 选版本与资源 → 确认安装。
 *
 * 版式来自 m3e-canvas 生成的 `docs/m3e/prompt-新建服务端.md`：
 *   标题 + 步骤标题 → 波浪形向导进度 → 核心选择卡（选中=主色容器底 + 对勾）
 *   → 下一步按钮；进入第 2 步后是版本列表（搜索 + 类型筛选），选中版本进第 3 步
 *   确认页（实例名 / 内存 / Java / 构建 / 服务器设置 / EULA / 下载进度）。
 *
 * 页面流转仍是组件内部的 `coreType` / `selected` 两个状态，**没有**引入 NavHost：
 * 调用方只拿到 onBack，向导内部的"上一步"由本文件自己管（与旧版完全一致）。
 * 每个阶段都是「标题栏 → 可滚动内容（占满剩余高度）→ 固定在底部的动作」，
 * 底部动作不会随内容滚走，长列表上也不用翻到底才能按"下一步"。
 */
@Composable
fun NewServerScreen(
    viewModel: AppViewModel,
    onBack: () -> Unit,
) {
    var coreType by remember { mutableStateOf<CoreType?>(null) }

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
// 向导进度
// ───────────────────────────────────────────────

/**
 * 三步向导的进度条（波浪形）。
 *
 * 波浪进度条本身只有 10dp 高、没有文字位，所以步数写在下面一行 labelLarge 里；
 * 进度按"第 N 步"给 (N-1)/3 —— 第 1 步时是 0 而不是 1/3，这样进度条的推进
 * 与"走完一步"对齐（三步都填完时正好 2/3，等创建完成的动作接手）。
 */
@Composable
private fun WizardProgress(step: Int, total: Int, title: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "第 $step 步，共 $total 步 · $title",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // animated = false：这是"第几步"的静态指示，不是正在跑的任务。
        // 开着动画时相位会一直流动，观感像有个进度条卡在那儿转。
        WavyLinearProgress(progress = (step - 1).toFloat() / total, animated = false)
    }
}

// ───────────────────────────────────────────────
// 阶段 ①：选择核心类型
// ───────────────────────────────────────────────

/** 核心类型描述（卡片副标题）：与 CoreType.displayName 分开，卡片标题短、正文说清区别 */
private fun CoreType.description(): String = when (this) {
    CoreType.VANILLA -> "Mojang 官方 · 兼容性最好"
    CoreType.PAPER -> "插件生态最好 · 性能更强"
    CoreType.PURPUR -> "Paper 增强 · 更多可调项"
    CoreType.SPIGOT -> "经典插件生态 · 构建较慢"
    CoreType.FABRIC -> "轻量模组加载器 · 启动快"
    CoreType.FORGE -> "经典模组加载器 · 兼容老模组"
    CoreType.NEOFORGE -> "现代模组加载器 · 面向新版本"
    CoreType.CUSTOM -> "已经有 server.jar？直接导入"
}

@Composable
private fun CoreSelectPhase(
    onSelect: (CoreType) -> Unit,
    onBack: () -> Unit,
) {
    // 第 1 步是"选中一种核心"，选中不立刻跳转 —— 和下面两步一样，
    // 统一由底部的下一步按钮推进（草图明确要求：第一步必须选中一个核心才能点）。
    var picked by remember { mutableStateOf<CoreType?>(null) }

    Column(Modifier.fillMaxSize()) {
        M3EScreenHeader(title = "新建服务端", subtitle = "选择服务端核心", leading = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
        })

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = M3Spacing.screenMargin),
            verticalArrangement = Arrangement.spacedBy(M3Spacing.betweenParts),
        ) {
            WizardProgress(step = 1, total = 3, title = "选核心")

            // 按分类分组：官方 / 性能优化 / 模组加载 / 导入
            coreGroups().forEach { (category, types) ->
                Text(
                    category.displayName,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = M3Spacing.betweenParts, start = 4.dp),
                )
                types.forEach { type ->
                    CoreChoiceCard(
                        type = type,
                        selected = picked == type,
                        onClick = { picked = type },
                    )
                }
            }
        }

        // 底部动作：选中一个核心之前不可点
        Column(Modifier.padding(horizontal = M3Spacing.screenMargin, vertical = M3Spacing.betweenGroups)) {
            PrimaryButton(
                label = "下一步",
                icon = Icons.AutoMirrored.Filled.ArrowForward,
                enabled = picked != null,
                onClick = { picked?.let(onSelect) },
            )
        }
    }
}

/**
 * 核心选择卡：选中 = 主色容器底 + 右上角对勾（草图指定的选中样式）。
 *
 * 不用 M3ECard 的 variant 来区分选中：M3ECard 的三种 variant 是容器色的固定映射，
 * 表达不了"主色容器底"；这里自己画一张卡，但圆角/内边距/标题正文的字阶
 * 与 M3ECard 完全对齐（20dp / 20dp / titleMedium + bodyMedium）。
 */
@Composable
private fun CoreChoiceCard(
    type: CoreType,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(M3Shape.largeIncreased)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        shape = M3Shape.largeIncreased,
        color = if (selected) scheme.primaryContainer else scheme.surface,
        contentColor = if (selected) scheme.onPrimaryContainer else scheme.onSurface,
        border = if (selected) null else BorderStroke(1.dp, scheme.outlineVariant),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(M3Spacing.cardPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            InstanceIcon(type, Modifier.size(44.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    type.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    type.description(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) scheme.onPrimaryContainer.copy(alpha = 0.8f)
                    else scheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // 选中标记：对勾本身就是"当前选项"，不再额外给整卡描边
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "已选中",
                    tint = scheme.onPrimaryContainer,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

/** 核心类型分组（官方 / 性能优化 / 模组加载 / 导入），与 CoreCategory 的顺序一致 */
private fun coreGroups(): List<Pair<CoreCategory, List<CoreType>>> =
    CoreCategory.entries
        .map { category -> category to CoreType.entries.filter { it.category == category } }
        .filter { it.second.isNotEmpty() }

// ───────────────────────────────────────────────
// 阶段 ②：选择版本（③ 配置页从右侧滑入）
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
    // 游戏内存：自动分配开关（开 = 系统建议，不可手动改；关 = 滑块手动分配）
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

    // 选中版本 → 配置页从右滑入；返回时反向滑出（与 M3 的"屏幕过渡带轻微回弹"一致）
    AnimatedContent(
        targetState = selected,
        transitionSpec = {
            (
                slideInHorizontally(M3Motion.defaultSpatial()) { it / 3 } +
                    fadeIn(M3Motion.defaultEffects())
                ).togetherWith(
                slideOutHorizontally(M3Motion.defaultSpatial()) { -it / 4 } +
                    fadeOut(M3Motion.defaultEffects())
            )
        },
        label = "new-server-pages",
    ) { current ->
        if (current == null) {
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
                onRetry = { viewModel.loadVersions(coreType) },
            )
        } else {
            ConfigPage(
                version = current,
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
                        mcVersion = current.id,
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
                    items(builds, key = { it.id }) { b ->
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
            .clip(M3Shape.small)
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
// 页面 A：版本选择（筛选 + 搜索，整行点击即进入配置页）
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
    onRetry: () -> Unit,
) {
    val q = query.trim()
    // 类型筛选 + 关键词搜索（FCL：先按类型分档，再在档内搜）
    val filtered = versions
        .filter { typeFilter == null || it.type == typeFilter }
        .filter { q.isEmpty() || it.id.contains(q, ignoreCase = true) }
    // 每档的数量（用于档位标签上的计数）
    val countOf: (VersionType?) -> Int = { t ->
        if (t == null) versions.size else versions.count { it.type == t }
    }

    // adjustNothing 下窗口不随键盘缩放：不主动避让，搜索框会被键盘盖住
    Column(Modifier.fillMaxSize().imePadding()) {
        M3EScreenHeader(
            title = coreType.displayName,
            subtitle = "选择版本",
            leading = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回选择核心")
                }
            },
        )

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = M3Spacing.screenMargin),
            verticalArrangement = Arrangement.spacedBy(M3Spacing.betweenParts),
        ) {
            WizardProgress(step = 2, total = 3, title = "选版本与资源")

            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("搜索版本（如 1.21 / 26.2）") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                singleLine = true,
            )

            // 类型筛选（FCL 四档 + 全部）。用分段选择而不是横向滚动的 chip：
            // 五个档位在窄屏放不下，横向滚动会让后两档（远古测试版/远古预览版）完全看不见。
            // 该核心没有这类版本时该档隐藏，避免出现一排 0。
            val typeOptions: List<VersionType?> =
                buildList {
                    add(null)
                    VersionType.entries.forEach { t -> if (countOf(t) > 0) add(t) }
                }
            M3ESegmentedRow(
                options = typeOptions,
                selected = typeFilter,
                label = { t -> "${t?.displayName ?: "全部"} ${countOf(t)}" },
                onSelect = onTypeFilter,
                height = 40.dp,
                modifier = Modifier.padding(bottom = 4.dp),
            )

            // 结果反馈：换筛选后列表顶部**可能看起来完全一样** —— 快照版往往就是最新的那些版本，
            // 例如 Vanilla 的「快照版(750)」与「全部(913)」首屏都是 26w14a，用户会以为筛选没生效。
            // 把匹配数量常驻在顶部（原来只在列表底部、且 >200 才显示，等于看不到）。
            if (!loading && versions.isNotEmpty()) {
                Text(
                    buildString {
                        append("匹配 ${filtered.size} 个版本")
                        if (filtered.size != versions.size) append("（共 ${versions.size} 个）")
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }

            when {
                loading -> WavyLinearProgress(progress = null)
                filtered.isEmpty() -> Column(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        if (versions.isEmpty()) "版本列表加载失败，请检查网络" else "没有匹配的版本",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // 加载失败给一个就地重试：此前只能返回上一页再进来一次
                    // （离线/DNS 卡住时尤其需要，否则用户对着一个空页无路可走）
                    if (versions.isEmpty()) {
                        OutlinedButton(onClick = onRetry) { Text("重试") }
                    }
                }
                else -> {
                    val listState = rememberLazyListState()
                    // 换筛选/改搜索词后回到顶部：LazyColumn 会保留滚动位置，
                    // 用户可能停在列表中间，看到的内容与刚选中的分类对不上
                    LaunchedEffect(typeFilter, q) { listState.scrollToItem(0) }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(bottom = M3Spacing.betweenGroups),
                        // 版本行是独立卡片（不是相连列表），12dp 间距才不会糊成一片
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(filtered, key = { it.id }) { v ->
                            VersionRow(version = v, onClick = { onSelect(v) })
                        }
                    }
                }
            }
        }
    }
}

/** 版本行：版本号徽标 + 版本号 + 类型胶囊，行尾箭头（整行可点，进配置页） */
@Composable
private fun VersionRow(
    version: GameVersion,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clip(M3Shape.largeIncreased),
        shape = M3Shape.largeIncreased,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        onClick = onClick,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = M3Spacing.cardPadding, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 版本号徽标：主色容器圆角方块（官方列表项的"图标位"）
            Box(
                Modifier
                    .size(40.dp)
                    .clip(M3Shape.medium)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    version.id.split('.').take(2).joinToString("."),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    version.id,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                M3EStatusChip(
                    text = version.type.displayName,
                    color = if (version.isRelease) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "配置",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

// ───────────────────────────────────────────────
// 页面 B：配置（实例名 / 内存 / Java / 构建 / 服务器设置 / EULA / 下载）
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
    Column(Modifier.fillMaxSize().imePadding()) {
        M3EScreenHeader(
            title = "配置",
            subtitle = "MC ${version.id} · ${coreType.displayName}",
            leading = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回选择版本")
                }
            },
            trailing = {
                M3EStatusChip(
                    text = version.type.displayName,
                    color = if (version.isRelease) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            },
        )

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = M3Spacing.screenMargin),
            verticalArrangement = Arrangement.spacedBy(M3Spacing.betweenGroups),
        ) {
            WizardProgress(step = 3, total = 3, title = "确认安装")

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
            // 卡片正文放"设备中已使用 / 总内存"这类设备事实（FCL 的 settings_memory_used_per_total），
            // 本次分配的结果与警告在卡内紧随滑块显示，两者不重复。
            M3ECard(
                variant = M3ECardVariant.Outlined,
                title = "游戏内存",
                supporting = "设备中已使用 ${fmtGb(usedMemGb * 1024f)} / 设备总内存 ${fmtGb(totalMemGb * 1024f)}",
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (autoMemory) "自动分配内存" else "手动分配",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Switch(checked = autoMemory, onCheckedChange = onAutoMemory)
                }

                SliderRow(
                    sliderMb = sliderMb,
                    autoMemory = autoMemory,
                    exceeded = exceeded,
                    fmtGb = fmtGb,
                    onMemoryMb = onMemoryMb,
                )

                // 状态文案：自动→系统建议（只读）；手动→游戏分配
                val statusText = if (autoMemory) {
                    "自动分配 ${fmtGb(effectiveMb)} · 根据设备可用内存，不可手动修改"
                } else {
                    "游戏分配 ${fmtGb(effectiveMb)}"
                }
                Text(
                    if (exceeded) "超过设备内存的 80%（设备仅 ${fmtGb(totalMemGb * 1024f)}）" else statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (exceeded) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
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
            }

            // ── 节③ Java（只读：自动适配最优版本，不给用户选择）──
            M3EListItem(
                headline = "Java 版本",
                supporting = "自动适配 Java $autoJava · 按 MC 版本推荐，无需手动选择",
                leadingIcon = Icons.Filled.Build,
                iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                shape = M3Shape.listSingle,
            )

            // ── 节④ 核心构建 / 加载器（有列表的核心才出现；FCL「加载器版本」的对应物）──
            if (buildsLoading || builds.isNotEmpty()) {
                M3EListItem(
                    headline = "核心${buildNounOf(coreType)}",
                    supporting = when {
                        buildsLoading -> "正在获取列表…"
                        buildId.isBlank() -> "最新（${builds.firstOrNull()?.name ?: "?"}）"
                        else -> "已选：${builds.firstOrNull { it.id == buildId }?.name ?: buildId}"
                    },
                    leadingIcon = Icons.Filled.Tune,
                    iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                    shape = M3Shape.listSingle,
                    trailing = {
                        TextButton(onClick = onShowBuildPicker, enabled = builds.isNotEmpty()) {
                            Text("选择")
                        }
                    },
                )
            }

            // ── 节⑤ 服务器设置（安装时就定下基础参数，参考 FCL 安装页的"可选项"）──
            M3ECard(
                variant = M3ECardVariant.Outlined,
                title = "服务器设置",
                supporting = "会写入 server.properties，创建后也能在实例详情里修改",
            ) {
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
                GameModePicker(gameMode = gameMode, onGameModeChange = onGameModeChange)
            }

            // ── 节⑥ EULA：必须显式同意 ──
            // 此前是首次启动时静默写入 eula=true（用户从未看到过条款）
            EulaCard(agreed = eulaAgreed, onAgreedChange = onEulaAgreedChange)

            // ── 下载进度 / 错误 ──
            if (download.running) {
                M3ECard(
                    variant = M3ECardVariant.Filled,
                    title = "正在下载服务端核心",
                    supporting = download.message.ifBlank { "准备下载…" },
                ) {
                    WavyLinearProgress(progress = download.progress.coerceIn(0f, 1f))
                }
            }
            download.error?.let {
                M3ECard(
                    variant = M3ECardVariant.Outlined,
                    title = "下载失败",
                    supporting = it,
                    titleIcon = Icons.Filled.ErrorOutline,
                )
            }
        }

        // ── 底部动作：下载中就是"取消"，否则才是"下载并创建"（与旧版同一个位置、同一个判断）──
        Column(Modifier.padding(horizontal = M3Spacing.screenMargin, vertical = M3Spacing.betweenGroups)) {
            if (download.running) {
                TonalButton(
                    label = "取消下载",
                    enabled = true,
                    onClick = onCancelDownload,
                )
            } else {
                PrimaryButton(
                    // 上次下载失败时按钮直接说"重试"：断点保留在 .part 文件里
                    label = if (download.error != null) "重试下载（断点续传）" else "下载并创建",
                    enabled = canCreate,
                    onClick = onCreate,
                )
            }
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

/**
 * 内存滑块：自动模式下显示系统建议值并禁用拖动；手动模式下可拖，thumb 上直接写数值。
 * thumb 用主色胶囊，超过设备内存 80% 时换成 error 色（这是唯一的"内存警告"视觉通道）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SliderRow(
    sliderMb: Float,
    autoMemory: Boolean,
    exceeded: Boolean,
    fmtGb: (Float) -> String,
    onMemoryMb: (Float) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            if (autoMemory) "自动分配（建议 ${fmtGb(sliderMb)}）" else "游戏分配",
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant,
        )
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
                        .clip(M3Shape.small)
                        .background(if (exceeded) scheme.error else scheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        fmtGb(sliderMb),
                        color = if (exceeded) scheme.onError else scheme.onPrimary,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            },
        )
    }
}

/** 默认游戏模式：四选一 */
@Composable
private fun GameModePicker(gameMode: String, onGameModeChange: (String) -> Unit) {
    val modes = listOf(
        "survival" to "生存",
        "creative" to "创造",
        "adventure" to "冒险",
        "spectator" to "旁观",
    )
    M3ESegmentedRow(
        options = modes.map { it.first },
        selected = gameMode,
        label = { value -> modes.first { it.first == value }.second },
        onSelect = onGameModeChange,
        height = 40.dp,
    )
}

/**
 * EULA 卡片：整行是一个 toggleable(Checkbox)。
 *
 * Checkbox 的 onCheckedChange 必须是 null —— 整行的 toggleable 已经是唯一触控目标，
 * 否则同一个动作有两个可聚焦节点，TalkBack 会把"同意"读两遍。
 */
@Composable
private fun EulaCard(agreed: Boolean, onAgreedChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    M3ECard(
        variant = M3ECardVariant.Outlined,
        title = "Minecraft EULA",
        titleIcon = Icons.Filled.Description,
        supporting = "建服前必须同意 Mojang 的最终用户许可协议",
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(M3Shape.medium)
                .toggleable(value = agreed, role = Role.Checkbox, onValueChange = onAgreedChange)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = agreed, onCheckedChange = null)
            Column(Modifier.weight(1f)) {
                Text("我已阅读并同意 Minecraft EULA", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "点此查看条款",
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.primary,
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
    }
}

// ───────────────────────────────────────────────
// 底部动作按钮
// ───────────────────────────────────────────────

/** 主行动：填充胶囊按钮（下一步 / 下载并创建） */
@Composable
private fun PrimaryButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    icon: ImageVector? = null,
) {
    ActionButton(
        label = label,
        icon = icon,
        enabled = enabled,
        container = MaterialTheme.colorScheme.primary,
        content = MaterialTheme.colorScheme.onPrimary,
        onClick = onClick,
    )
}

/** 次行动：色调胶囊按钮（取消下载） */
@Composable
private fun TonalButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    ActionButton(
        label = label,
        icon = null,
        enabled = enabled,
        container = MaterialTheme.colorScheme.secondaryContainer,
        content = MaterialTheme.colorScheme.onSecondaryContainer,
        onClick = onClick,
    )
}

/**
 * 底部动作按钮的公共实现。
 *
 * 不用 material3 的 Button：它的高度与内边距会随主题变，这里要的是固定的 56dp 胶囊，
 * 与「服务端」页的相连按钮组、首页的主行动按钮保持同一尺寸。
 */
@Composable
private fun ActionButton(
    label: String,
    icon: ImageVector?,
    enabled: Boolean,
    container: Color,
    content: Color,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(CircleShape),
        shape = CircleShape,
        color = if (enabled) container else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (enabled) content else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
        onClick = onClick,
    ) {
        Row(
            Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
            }
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}
