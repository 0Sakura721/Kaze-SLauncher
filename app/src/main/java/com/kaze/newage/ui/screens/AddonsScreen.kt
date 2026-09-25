package com.kaze.newage.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaze.newage.core.addons.AddonKind
import com.kaze.newage.core.addons.AddonManager
import com.kaze.newage.ui.AppViewModel
import com.kaze.newage.ui.components.CheckChip
import com.kaze.newage.ui.components.ExpressiveLoadingRing
import com.kaze.newage.ui.components.M3ECard
import com.kaze.newage.ui.components.M3ECardVariant
import com.kaze.newage.ui.components.M3EConnectedList
import com.kaze.newage.ui.components.M3EListItem
import com.kaze.newage.ui.components.M3EScreenHeader
import com.kaze.newage.ui.components.WavyLinearProgress
import com.kaze.newage.ui.theme.M3Shape
import com.kaze.newage.ui.theme.M3Spacing
import java.util.Locale

/**
 * 插件 / 模组管理页：Modrinth 搜索下载 + 已安装列表（启用/禁用/删除）。
 * 数据源：Modrinth v2 API（开放 API）；文件惯例：plugins/、mods/、*.jar.disabled。
 *
 * 版式来自 m3e-canvas 生成的 `docs/m3e/prompt-插件与模组.md`：
 *   标题（插件 / 模组，跟着实例核心类型变）→ 搜索栏 → 标签片（排序）→ 反馈 →
 *   搜索结果行（行尾安装按钮，安装中变成进度）→ 已安装列表（启用/禁用/删除）
 *
 * 搜索栏与标签片、安装反馈固定在滚动区之上：滚到结果末尾时也要看得到搜索框、
 * 下载进度和错误——这些是必须马上能操作/看到的东西。
 */
@Composable
fun AddonsScreen(
    viewModel: AppViewModel,
    instanceId: String,
    kind: AddonKind,
    onBack: () -> Unit,
) {
    val instances by viewModel.instances.collectAsStateWithLifecycle()
    val instance = instances.firstOrNull { it.id == instanceId }
    if (instance == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val kindLabel = if (kind == AddonKind.PLUGIN) "插件" else "模组"

    var query by remember { mutableStateOf("") }
    var searched by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    val installed = remember(instanceId, refresh) { AddonManager.installed(instance, kind) }
    val results by viewModel.addonResults.collectAsStateWithLifecycle()
    val searching by viewModel.addonSearching.collectAsStateWithLifecycle()
    val installState by viewModel.addonInstall.collectAsStateWithLifecycle()

    // 安装是异步的：原来只在点击「安装」时 refresh++，那时文件还没落盘，
    // `remember(instanceId, refresh)` 读到的是旧目录内容，于是「已安装（N）」列表与
    // 计数停在上一次快照，必须退出页面再进才更新。改为安装真正完成后刷新。
    LaunchedEffect(installState.done) {
        if (installState.done) refresh++
    }

    // 进入页面时清掉上一次遗留的「已安装 / 失败」横幅：下载状态活在共享 ViewModel 里，
    // 跨页面存活，不清的话每次进来都会重现一条早就过期的提示。
    // 正在安装时不动它，否则会把进行中的进度显示抹掉。
    LaunchedEffect(Unit) {
        if (!viewModel.addonInstall.value.running) viewModel.clearAddonInstallState()
    }

    // 排序：搜索接口的 index 固定为 relevance，下载量排序在客户端做（结果集只有 20 条）
    var sortByDownloads by rememberSaveable { mutableStateOf(false) }
    val shown = remember(results, sortByDownloads) {
        if (sortByDownloads) results.sortedByDescending { it.downloads } else results
    }

    // 哪一行正在安装：DownloadState 里没有 project_id，用本地记录把进度落到对应行上
    var installingId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(installState.running) {
        if (!installState.running) installingId = null
    }

    // 已安装筛选：0 全部 / 1 已启用 / 2 已禁用
    var installedFilter by rememberSaveable { mutableIntStateOf(0) }
    val enabledCount = installed.count { AddonManager.isEnabled(it) }
    val shownInstalled = remember(installed, installedFilter) {
        when (installedFilter) {
            1 -> installed.filter { AddonManager.isEnabled(it) }
            2 -> installed.filter { !AddonManager.isEnabled(it) }
            else -> installed
        }
    }

    // 回车与右侧图标都走这里；状态在调用时现读，避免闭包捕获到过期的可点状态
    fun submitSearch() {
        if (query.isBlank() || searching) return
        searched = true
        viewModel.searchAddons(query.trim(), kind)
    }

    Column(
        Modifier
            .fillMaxSize()
            // adjustNothing 下窗口不随键盘缩放，不主动避让的话搜索框会被键盘整块盖住
            .imePadding()
    ) {
        // ── 顶部栏 ──
        M3EScreenHeader(
            title = "${kindLabel}管理",
            subtitle = "${instance.name} · MC ${instance.mcVersion.ifBlank { "自定义" }} · " +
                "安装时按 ${AddonManager.loaderFor(instance, kind)} 过滤",
            leading = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
        )

        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = M3Spacing.screenMargin),
            verticalArrangement = Arrangement.spacedBy(M3Spacing.betweenParts),
        ) {
            // 核心不支持：常驻在搜索框上方，不能只藏在结果为空里
            if (!AddonManager.supports(instance, kind)) {
                M3ECard(
                    variant = M3ECardVariant.Filled,
                    title = "当前核心不支持$kindLabel",
                    titleIcon = Icons.Filled.WarningAmber,
                    supporting = if (kind == AddonKind.PLUGIN) {
                        "插件适用于 Paper / Purpur / Spigot 类服务端（当前：${instance.coreType.displayName}）。"
                    } else {
                        "模组适用于 Fabric / Forge / NeoForge 类服务端（当前：${instance.coreType.displayName}）。"
                    },
                )
            }

            // ── 搜索栏（高 56dp、全圆角、surfaceContainerHigh）──
            SearchField(
                query = query,
                searching = searching,
                onQueryChange = { query = it },
                onSubmit = { submitSearch() },
            )

            // 搜索中给一条确定的加载反馈（搜索栏右侧图标同时也变成加载指示器）
            if (searching) {
                WavyLinearProgress(progress = null, modifier = Modifier.fillMaxWidth())
            }

            // ── 标签片：排序方式（草图「下载最多」标签片的位置）──
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CheckChip(
                    selected = !sortByDownloads,
                    label = "相关度",
                    onClick = { sortByDownloads = false },
                )
                CheckChip(
                    selected = sortByDownloads,
                    label = "下载最多",
                    onClick = { sortByDownloads = true },
                )
            }

            // ── 安装 / 搜索反馈（常驻：滚到列表末尾也要看得到）──
            if (installState.running) {
                M3ECard(
                    variant = M3ECardVariant.Outlined,
                    title = "正在安装",
                    supporting = installState.message.ifBlank { "准备下载…" },
                    content = {
                        WavyLinearProgress(
                            progress = installState.progress.coerceIn(0f, 1f),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                )
            }
            installState.error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (installState.done) {
                Text(
                    installState.message,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // ── 结果与已安装列表（滚动区）──
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = M3Spacing.screenMargin)
                .padding(top = M3Spacing.betweenGroups, bottom = M3Spacing.bottomBarSpace),
            verticalArrangement = Arrangement.spacedBy(M3Spacing.betweenParts),
        ) {
            // 搜索结果
            if (shown.isNotEmpty()) {
                Text(
                    "搜索结果（${shown.size}）",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                M3EConnectedList(count = shown.size) { index, shape ->
                    val hit = shown[index]
                    M3EListItem(
                        headline = hit.title,
                        // 下载数放前面：辅助文本只有两行，长描述会把行尾的计数挤掉
                        supporting = "${formatDownloads(hit.downloads)} 次下载 · " +
                            hit.description.ifBlank { "（无描述）" },
                        leadingIcon = if (kind == AddonKind.PLUGIN) Icons.Filled.Extension
                        else Icons.Filled.ViewInAr,
                        iconContainer = MaterialTheme.colorScheme.primaryContainer,
                        shape = shape,
                        trailing = {
                            if (installState.running && installingId == hit.project_id) {
                                // 行内进度：正在下载这一条
                                InstallProgress(installState.progress)
                            } else {
                                Button(
                                    onClick = {
                                        // 先记下是哪一行在装，进度才能落到这一行上
                                        installingId = hit.project_id
                                        viewModel.installAddon(instance, kind, hit)
                                        refresh++
                                    },
                                    enabled = !installState.running,
                                ) { Text("安装") }
                            }
                        },
                    )
                }
            } else if (searched && !searching && results.isEmpty() && installState.error == null) {
                // 空状态：搜过了、不在搜、没结果、也没报错
                M3EListItem(
                    headline = "没有找到相关$kindLabel",
                    supporting = "换个关键词试试（支持 Modrinth 上的项目名，如 EssentialsX、lithium）",
                    leadingIcon = Icons.Filled.SearchOff,
                    iconContainer = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = M3Shape.listSingle,
                )
            } else if (!searched) {
                // 还没搜过：说清楚怎么开始，别留一片空白
                M3EListItem(
                    headline = "搜索 Modrinth",
                    supporting = "输入关键词后回车，或点右侧搜索图标；结果按上面的排序方式显示",
                    leadingIcon = Icons.Filled.Search,
                    iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                    shape = M3Shape.listSingle,
                )
            }

            // 已安装（启用 / 禁用 / 删除）
            Text(
                "已安装（${installed.size}）",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = M3Spacing.betweenParts),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CheckChip(
                    selected = installedFilter == 0,
                    label = "全部 ${installed.size}",
                    onClick = { installedFilter = 0 },
                )
                CheckChip(
                    selected = installedFilter == 1,
                    label = "已启用 $enabledCount",
                    onClick = { installedFilter = 1 },
                )
                CheckChip(
                    selected = installedFilter == 2,
                    label = "已禁用 ${installed.size - enabledCount}",
                    onClick = { installedFilter = 2 },
                )
            }
            if (installed.isEmpty()) {
                Text(
                    "还没有安装$kindLabel。搜索并安装后在此管理启用状态。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (shownInstalled.isEmpty()) {
                Text(
                    "当前筛选下没有$kindLabel。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                M3EConnectedList(count = shownInstalled.size) { index, shape ->
                    val file = shownInstalled[index]
                    val enabled = AddonManager.isEnabled(file)
                    M3EListItem(
                        headline = file.name.removeSuffix(".disabled"),
                        supporting = "${if (enabled) "已启用" else "已禁用"} · ${file.length() / 1024} KB",
                        leadingIcon = if (kind == AddonKind.PLUGIN) Icons.Filled.Extension
                        else Icons.Filled.ViewInAr,
                        iconContainer = if (enabled) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = shape,
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(
                                    checked = enabled,
                                    onCheckedChange = {
                                        AddonManager.toggleEnabled(file)
                                        refresh++
                                    },
                                    // TalkBack：开关本身没有文字（文件名在旁边），补上语义标签，
                                    // 否则读屏只会念一个孤立的"开关"，不知道是哪个插件、开还是关
                                    modifier = Modifier.semantics {
                                        contentDescription =
                                            "${file.name}，${if (enabled) "已启用" else "已禁用"}"
                                    },
                                )
                                IconButton(onClick = {
                                    AddonManager.delete(file)
                                    refresh++
                                }) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = "删除",
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        },
                    )
                }
                Text(
                    "更改启用状态或增删后，重启服务端生效。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Modrinth 搜索栏：高 56dp、全圆角、surfaceContainerHigh 底。
 * 左侧搜索图标；右侧是搜索动作（搜索中换成加载指示器）；回车同样发起搜索。
 */
@Composable
private fun SearchField(
    query: String,
    searching: Boolean,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    Surface(
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            Modifier.fillMaxSize().padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    // 整条胶囊都要能点进输入框：BasicTextField 本身只有一行文字那么高，
                    // 点它上下两侧原本是死区（不发 ripple，只是把焦点交给输入框）
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { focusRequester.requestFocus() },
                contentAlignment = Alignment.CenterStart,
            ) {
                if (query.isEmpty()) {
                    Text(
                        "搜索 Modrinth",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                )
            }
            // 搜索动作：搜索中变成加载指示器（同一个位置，不另起一条进度条）
            if (searching) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    ExpressiveLoadingRing(size = 22.dp, speed = 1.6f)
                }
            } else {
                IconButton(onClick = onSubmit, enabled = query.isNotBlank()) {
                    Icon(Icons.Filled.Search, contentDescription = "搜索")
                }
            }
        }
    }
}

/** 结果行尾的安装进度：刚开始（还没拿到字节数）转圈，有进度后走波浪进度条 */
@Composable
private fun InstallProgress(progress: Float) {
    Box(
        Modifier.width(72.dp).height(48.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (progress <= 0f) {
            ExpressiveLoadingRing(size = 22.dp, speed = 1.6f)
        } else {
            WavyLinearProgress(progress = progress.coerceIn(0f, 1f))
        }
    }
}

/** 下载数格式化：1.2K / 3.4M */
private fun formatDownloads(n: Int): String = when {
    n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000f)
    n >= 1_000 -> String.format(Locale.US, "%.1fK", n / 1_000f)
    else -> n.toString()
}
