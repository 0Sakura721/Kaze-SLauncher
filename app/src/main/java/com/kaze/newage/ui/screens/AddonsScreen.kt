package com.kaze.newage.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaze.newage.core.addons.AddonImporter
import com.kaze.newage.core.addons.AddonKind
import com.kaze.newage.core.addons.AddonManager
import com.kaze.newage.core.addons.ModCompatibility
import com.kaze.newage.core.addons.ModLoader
import com.kaze.newage.core.addons.ModMetadata
import com.kaze.newage.core.addons.ModMetadataReader
import com.kaze.newage.core.addons.ModrinthSearchHit
import com.kaze.newage.core.content.ContentKind
import com.kaze.newage.core.content.RecommendedProjects
import com.kaze.newage.core.sync.ModSyncServer
import com.kaze.newage.data.model.ServerInstance
import com.kaze.newage.ui.AppViewModel
import com.kaze.newage.ui.components.CheckChip
import com.kaze.newage.ui.components.ExpressiveLoadingRing
import com.kaze.newage.ui.components.M3ECard
import com.kaze.newage.ui.components.M3ECardVariant
import com.kaze.newage.ui.components.M3EConnectedList
import com.kaze.newage.ui.components.M3EListItem
import com.kaze.newage.ui.components.M3EScreenHeader
import com.kaze.newage.ui.components.M3ESwitch
import com.kaze.newage.ui.components.ModIcon
import com.kaze.newage.ui.components.RemoteIcon
import com.kaze.newage.ui.components.WavyLinearProgress
import com.kaze.newage.ui.theme.M3Shape
import com.kaze.newage.ui.theme.M3Spacing
import java.io.File
import java.util.Locale
import com.kaze.newage.core.i18n.t

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

    val kindLabel = if (kind == AddonKind.PLUGIN) t("插件") else t("模组")
    // 核心不支持这类附件时整页只读：搜索/安装全部禁用。
    // 原来只有一条警示横幅，搜索与安装照常可用 —— 用户能把 Fabric 模组装进 Paper 实例
    // （mods/ 目录对 Paper 毫无意义，白下几十 MB 还不报错）。
    // 基岩版实例的附加包不是 mods/plugins：整页按"不支持"处理，并在警示卡里指路 资源中心 → 基岩版
    val supported = AddonManager.supports(instance, kind) && !instance.bedrock

    var query by remember { mutableStateOf("") }
    var searched by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    val installed = remember(instanceId, refresh) { AddonManager.installed(instance, kind) }
    // 插件更新检查的状态与进度（ViewModel 里，见 AddonUpdateState）
    val addonUpdateState by viewModel.addonUpdate.collectAsStateWithLifecycle()
    val addonBusy = addonUpdateState is AppViewModel.AddonUpdateState.Checking ||
        addonUpdateState is AppViewModel.AddonUpdateState.Upgrading
    val results by viewModel.addonResults.collectAsStateWithLifecycle()
    val searching by viewModel.addonSearching.collectAsStateWithLifecycle()
    val searchError by viewModel.addonSearchError.collectAsStateWithLifecycle()
    val installState by viewModel.addonInstall.collectAsStateWithLifecycle()

    // 安装是异步的：原来只在点击「安装」时 refresh++，那时文件还没落盘，
    // `remember(instanceId, refresh)` 读到的是旧目录内容，于是「已安装（N）」列表与
    // 计数停在上一次快照，必须退出页面再进才更新。改为安装真正完成后刷新。
    LaunchedEffect(installState.done) {
        if (installState.done) refresh++
    }

    // ── 本地导入 / 客户端同步 / 导出 ──
    val importState by viewModel.addonImport.collectAsStateWithLifecycle()
    val syncStates by viewModel.modSyncStates.collectAsStateWithLifecycle()
    val sync = syncStates[instanceId] ?: ModSyncServer.Snapshot()
    val exportState by viewModel.packExport.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    // 导出格式：两个按钮共用同一个 SAF 回调，靠它区分
    var exportMrpack by remember { mutableStateOf(false) }
    var showSyncSettings by remember { mutableStateOf(false) }
    // 点行看详情（元数据 / 兼容性 / 文件路径）
    var detailsTarget by remember { mutableStateOf<File?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        // 不同来源给的 MIME 五花八门（QQ/浏览器常给 octet-stream），所以带上 */* 兜底
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.importAddonsFromUris(instance, kind, uris)
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri != null) viewModel.exportClientPack(instance, uri, exportMrpack)
    }

    // 导入是异步的：文件真正落盘之后才刷新已安装列表（同安装流程的处理）
    LaunchedEffect(importState.running) {
        if (!importState.running && importState.report != null) refresh++
    }
    // 进页面时清掉上次遗留的导出成功/失败提示
    LaunchedEffect(Unit) {
        if (!viewModel.packExport.value.running) viewModel.clearPackExport()
    }

    // 进入页面时清掉上一次遗留的「已安装 / 失败」横幅：下载状态活在共享 ViewModel 里，
    // 跨页面存活，不清的话每次进来都会重现一条早就过期的提示。
    // 正在安装时不动它，否则会把进行中的进度显示抹掉。
    LaunchedEffect(Unit) {
        if (!viewModel.addonInstall.value.running) viewModel.clearAddonInstallState()
        viewModel.clearAddonSearchError()
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
    // 删除已下载的插件/模组：文件是用户花流量下下来的，删掉只能重下，必须二次确认
    // （同一页其它不可逆操作——实例、备份——都有确认框）
    var deleteTarget by remember { mutableStateOf<File?>(null) }
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
        if (!supported || query.isBlank() || searching) return
        searched = true
        viewModel.searchAddons(query.trim(), kind)
    }

    // 同名替换确认框：installAddonChecked 在解析出目标文件名与已装文件相同时停在这里。
    // 点的是哪一行要记下来 —— 确认时要按同一条项目继续安装。
    var replaceTarget by remember { mutableStateOf<ModrinthSearchHit?>(null) }
    LaunchedEffect(installState.replacePending) {
        if (!installState.replacePending) replaceTarget = null
    }
    replaceTarget?.takeIf { installState.replacePending }?.let { hit ->
        AlertDialog(
            onDismissRequest = { viewModel.cancelReplaceAddon() },
            title = { Text(t("已存在同名文件")) },
            text = {
                Text(
                    "${installState.message}\n\n" +
                        t("继续安装会用它替换掉原文件（Bukkit / Fabric 按文件名加载，改名会让同一个") +
                        t("%s被加载两遍，所以只能替换）。原文件将被覆盖，无法撤销。", kindLabel)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.confirmReplaceAddon(instance, kind, hit)
                    refresh++
                }) { Text(t("替换"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelReplaceAddon() }) { Text(t("取消")) }
            },
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            // adjustNothing 下窗口不随键盘缩放，不主动避让的话搜索框会被键盘整块盖住
            .imePadding()
    ) {
        // ── 顶部栏 ──
        M3EScreenHeader(
            title = t("%s管理", kindLabel),
            subtitle = "${instance.name} · MC ${instance.mcVersion.ifBlank { "自定义" }} · " +
                t("安装时按 %s 过滤", AddonManager.loaderFor(instance, kind)),
            leading = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = t("返回"))
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
            if (!supported) {
                M3ECard(
                    variant = M3ECardVariant.Filled,
                    title = t("当前核心不支持%s", kindLabel),
                    titleIcon = Icons.Filled.WarningAmber,
                    supporting = when {
                        instance.bedrock -> t(
                            "这是基岩版实例：附加包（.mcpack / .mcaddon）请到「资源中心 → 基岩版」里管理，" +
                                "它们不放在 mods / plugins 目录。",
                        )
                        kind == AddonKind.PLUGIN -> t(
                            "插件适用于 Paper / Purpur / Spigot 类服务端（当前：%s）。",
                            instance.coreType.displayName,
                        )
                        else -> t(
                            "模组适用于 Fabric / Forge / NeoForge 类服务端（当前：%s）。",
                            instance.coreType.displayName,
                        )
                    },
                )
            }

            // ── 本地导入 / 客户端同步入口 ──
            // 只在核心支持这类附件时出现：不支持时导进去也不会被加载，
            // 给一个能点的按钮等于骗用户白折腾（与上面警示卡的判断同源）。
            if (supported) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = {
                            importLauncher.launch(
                                arrayOf(
                                    "application/java-archive",
                                    "application/zip",
                                    "application/x-zip-compressed",
                                    "application/octet-stream",
                                    // 国内来源（QQ/网盘/浏览器）经常不给 MIME，带 */* 兜底
                                    "*/*",
                                ),
                            )
                        },
                        enabled = !importState.running,
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(t("本地导入"))
                    }
                    if (kind == AddonKind.MOD) {
                        OutlinedButton(
                            onClick = {
                                if (sync.running) viewModel.stopModSync(instance)
                                else viewModel.startModSync(instance)
                            },
                        ) {
                            Icon(
                                if (sync.running) Icons.Filled.Stop else Icons.Filled.Wifi,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(if (sync.running) t("停止同步") else t("客户端同步"))
                        }
                    }
                }
            }

            // 导入进度 / 结果：本地导入是"用户主动点的一次操作"，结果必须显式给出
            if (importState.running) {
                M3ECard(
                    variant = M3ECardVariant.Outlined,
                    title = t("正在导入"),
                    supporting = importState.message.ifBlank { t("准备中…") },
                    content = {
                        WavyLinearProgress(
                            progress = importState.progress.coerceIn(0f, 1f),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                )
            } else {
                importState.report?.let { report ->
                    ImportResultPanel(
                        report = report,
                        conflictCount = importState.conflictCount,
                        onOverwrite = { viewModel.confirmImportOverwrite() },
                        onDismiss = { viewModel.clearAddonImportState() },
                    )
                }
            }
            importState.error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // 客户端同步卡片：把服务端启用中的模组发给玩家（插件页没有这回事）
            if (kind == AddonKind.MOD && supported) {
                ClientSyncCard(
                    state = sync,
                    enabledMods = installed.count { AddonManager.isEnabled(it) },
                    exporting = exportState.running,
                    exportMessage = exportState.message,
                    exportError = exportState.error,
                    onStart = { viewModel.startModSync(instance) },
                    onStop = { viewModel.stopModSync(instance) },
                    onCopy = { url -> clipboard.setText(AnnotatedString(url)) },
                    onExportZip = {
                        exportMrpack = false
                        exportLauncher.launch("${instance.name}-client-pack.zip")
                    },
                    onExportMrpack = {
                        exportMrpack = true
                        exportLauncher.launch("${instance.name}.mrpack")
                    },
                    onSettings = { showSyncSettings = true },
                )
            }

            // ── 搜索/排序/安装进度：只在核心支持时出现 ──
            // 不支持时这些全是僵尸控件（搜索永远发不出去、排序点了没反应），
            // 留着只会占掉 ~200dp 并暗示"能搜" —— 只留上面的警示卡和已安装区
            if (supported) {
            // ── 搜索栏（高 56dp、全圆角、surfaceContainerHigh）──
            SearchField(
                query = query,
                searching = searching,
                enabled = supported,
                onQueryChange = {
                    query = it
                    // 开始改关键词就把上一次的失败提示收起来：否则旧报错会挂在输入框下面，
                    // 看起来像"这次搜索也失败了"
                    viewModel.clearAddonSearchError()
                },
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
                    label = t("相关度"),
                    onClick = { sortByDownloads = false },
                )
                CheckChip(
                    selected = sortByDownloads,
                    label = t("下载最多"),
                    onClick = { sortByDownloads = true },
                )
            }
            }

            // ── 安装 / 搜索反馈（常驻：滚到列表末尾也要看得到）──
            if (installState.running) {
                M3ECard(
                    variant = M3ECardVariant.Outlined,
                    title = t("正在安装"),
                    supporting = installState.message.ifBlank { t("准备下载…") },
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
            // 搜索失败走**独立**槽位：写进 addonInstall 会让"安装"的空态判断跟着变
            searchError?.let {
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
                // 本页是不显示底栏的子路由，底部只留导航栏避让量（与实例详情/日志页同一约定）
                .padding(top = M3Spacing.betweenGroups, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(M3Spacing.betweenParts),
        ) {
            // ── 客户端专用模组检测（找服务端不需要的渲染/界面类模组）──
            ModAuditCard(viewModel = viewModel, instance = instance)

            // ── 推荐（没下载过的也在这里，带图标）──
            // 放在滚动区最前面：用户进来先看到"该装什么"，再往下是搜索结果与已安装列表
            AddonRecommendSection(viewModel = viewModel, instance = instance, kind = kind)

            // 搜索结果
            // `searched` 这个条件是必须的：addonResults 是 ViewModel 里**跨页面共享**的槽位，
            // 只判 isNotEmpty 的话，搜完插件再进模组页（本页 searched 还是 false）会照常渲染
            // 上一批插件结果，行尾「安装」还会按模组（fabric 过滤）去装配件 —— 错装。
            if (searched && shown.isNotEmpty()) {
                Text(
                    t("搜索结果（%s）", shown.size),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                M3EConnectedList(count = shown.size) { index, shape ->
                    val hit = shown[index]
                    M3EListItem(
                        headline = hit.title,
                        // 下载数放前面：辅助文本只有两行，长描述会把行尾的计数挤掉
                        supporting = t("%s 次下载 · ", formatDownloads(hit.downloads)) +
                            hit.description.ifBlank { t("（无描述）") },
                        // 搜索结果的图标直接来自 Modrinth 的 project icon：
                        // 同样叫 "Sodium" 的项目有好几个，图标比标题更容易一眼认对
                        leading = {
                            RemoteIcon(
                                url = hit.icon_url,
                                fallback = if (kind == AddonKind.PLUGIN) Icons.Filled.Extension
                                else Icons.Filled.ViewInAr,
                                size = 40.dp,
                            )
                        },
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
                                        replaceTarget = hit
                                        // 先解析目标文件名、与已装文件比对：同名时弹确认
                                        viewModel.installAddonChecked(instance, kind, hit)
                                    },
                                    enabled = supported && !installState.running,
                                ) { Text(t("安装")) }
                            }
                        },
                    )
                }
            } else if (searched && !searching && results.isEmpty() && installState.error == null) {
                // 空状态：搜过了、不在搜、没结果、也没报错
                M3EListItem(
                    headline = t("没有找到相关%s", kindLabel),
                    supporting = t("换个关键词试试（支持 Modrinth 上的项目名，如 EssentialsX、lithium）"),
                    leadingIcon = Icons.Filled.SearchOff,
                    iconContainer = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = M3Shape.listSingle,
                )
            } else if (!searched && supported) {
                // 还没搜过：说清楚怎么开始，别留一片空白（不支持核心时不给这条假入口）
                M3EListItem(
                    headline = t("搜索 Modrinth"),
                    supporting = t("输入关键词后回车，或点右侧搜索图标；结果按上面的排序方式显示"),
                    leadingIcon = Icons.Filled.Search,
                    iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                    shape = M3Shape.listSingle,
                )
            }

            // 已安装（启用 / 禁用 / 删除）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = M3Spacing.betweenParts),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    t("已安装（%s）", installed.size),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                // 检查更新：有插件才显示（零安装时点它没有意义）
                if (installed.isNotEmpty() && !addonBusy) {
                    TextButton(onClick = { viewModel.checkAddonUpdate(instance, kind) }) {
                        Text(t("检查更新"))
                    }
                }
            }
            AddonUpdatePanel(
                state = addonUpdateState,
                kindLabel = kindLabel,
                onUpgradeAll = { updates -> viewModel.upgradeAddons(instance, kind, updates) },
                onDismiss = { viewModel.clearAddonUpdateState() },
            )
            // 过滤 chip 只在有东西可滤时出现：零安装时三颗全是 0、点了没效果
            if (installed.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CheckChip(
                        selected = installedFilter == 0,
                        label = t("全部 %s", installed.size),
                        onClick = { installedFilter = 0 },
                    )
                    CheckChip(
                        selected = installedFilter == 1,
                        label = t("已启用 %s", enabledCount),
                        onClick = { installedFilter = 1 },
                    )
                    CheckChip(
                        selected = installedFilter == 2,
                        label = t("已禁用 %s", installed.size - enabledCount),
                        onClick = { installedFilter = 2 },
                    )
                }
            }
            if (installed.isEmpty()) {
                Text(
                    t("还没有安装%s。搜索并安装后在此管理启用状态。", kindLabel),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (shownInstalled.isEmpty()) {
                Text(
                    t("当前筛选下没有%s。", kindLabel),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                M3EConnectedList(count = shownInstalled.size) { index, shape ->
                    val file = shownInstalled[index]
                    val enabled = AddonManager.isEnabled(file)
                    // 元数据按 (路径, 大小, 时间) 缓存：滚动/开关重组时不会重开 jar
                    val meta = remember(file.absolutePath, file.length(), file.lastModified()) {
                        ModMetadataReader.read(file)
                    }
                    val notes = remember(file.absolutePath, file.length(), file.lastModified()) {
                        if (kind == AddonKind.MOD) {
                            ModCompatibility.check(instance, kind, meta)
                        } else {
                            emptyList()
                        }
                    }
                    val hasWarn = notes.any { it.level == ModCompatibility.Level.Warn }
                    M3EListItem(
                        headline = meta.displayName.ifBlank { file.name.removeSuffix(".disabled") },
                        supporting = buildString {
                            append(if (enabled) t("已启用") else t("已禁用"))
                            if (meta.version.isNotBlank()) append(" · v").append(meta.version)
                            append(" · ").append(file.length() / 1024).append(" KB")
                            if (meta.loader != ModLoader.UNKNOWN) append(" · ").append(meta.loader.label)
                            if (hasWarn) append(" · ⚠ ").append(t("兼容性提示"))
                        },
                        leading = {
                            ModIcon(
                                jar = file,
                                fallback = if (kind == AddonKind.PLUGIN) Icons.Filled.Extension
                                else Icons.Filled.ViewInAr,
                                size = 40.dp,
                            )
                        },
                        onClick = { detailsTarget = file },
                        shape = shape,
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                M3ESwitch(
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
                                IconButton(onClick = { deleteTarget = file }) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = t("删除"),
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        },
                    )
                }
                Text(
                    t("更改启用状态或增删后，重启服务端生效。"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除「${target.name.removeSuffix(".disabled")}」？") },
            text = { Text("文件将从${kindLabel}目录里永久删除，需要时只能重新下载。") },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    AddonManager.delete(target)
                    refresh++
                }) { Text(t("删除"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(t("取消")) }
            },
        )
    }

    // 行详情：元数据 + 兼容性提示 + 真实文件路径（都是"看不到就问不出来"的信息）
    detailsTarget?.let { file ->
        ModDetailsDialog(instance = instance, kind = kind, file = file, onDismiss = { detailsTarget = null })
    }

    if (showSyncSettings) {
        SyncSettingsDialog(viewModel = viewModel, onDismiss = { showSyncSettings = false })
    }
}

/**
 * 本地导入结果面板。
 *
 * 三种结果分开列（成功 / 跳过 / 失败），而不是一句"导入完成"：
 * 跳过与失败的原因（同名冲突、内容重复、不是 jar）恰恰是用户需要知道怎么处理的，
 * 只报"完成"会让人以为全都成功了、进服才发现少模组。
 */
@Composable
private fun ImportResultPanel(
    report: AddonImporter.Report,
    conflictCount: Int,
    onOverwrite: () -> Unit,
    onDismiss: () -> Unit,
) {
    val warnColor = MaterialTheme.colorScheme.tertiary
    M3ECard(
        variant = M3ECardVariant.Filled,
        title = t("导入结果"),
        supporting = buildString {
            append(t("成功 %s 个", report.okCount))
            if (report.skipped.isNotEmpty()) append(t(" · 跳过 %s 个", report.skipped.size))
            if (report.failed.isNotEmpty()) append(t(" · 失败 %s 个", report.failed.size))
        },
        content = {
            report.imported.take(6).forEach { item ->
                Text(
                    "✓ ${item.fileName}" + if (item.replaced) t("（已替换原文件）") else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                item.notes.filter { it.level == ModCompatibility.Level.Warn }.take(1).forEach { note ->
                    Text(
                        "   ⚠ ${note.text}",
                        style = MaterialTheme.typography.labelSmall,
                        color = warnColor,
                    )
                }
            }
            report.skipped.take(6).forEach { item ->
                Text(
                    "⊘ ${item.sourceName}：${item.reason}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            report.failed.take(6).forEach { item ->
                Text(
                    "✗ ${item.sourceName}：${item.reason}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            val hidden = (report.imported.size + report.skipped.size + report.failed.size) -
                (minOf(6, report.imported.size) + minOf(6, report.skipped.size) + minOf(6, report.failed.size))
            if (hidden > 0) {
                Text(
                    t("…还有 %s 条未显示", hidden),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                Modifier.padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (conflictCount > 0) {
                    Button(onClick = onOverwrite) { Text(t("覆盖并重新导入")) }
                }
                TextButton(onClick = onDismiss) { Text(t("关闭")) }
            }
        },
    )
}

/**
 * 客户端同步卡片。
 *
 * 未开启时给一个"开启同步"的主行动；开启后把**完整地址（带令牌）**摆出来，
 * 一键复制就能发给玩家 —— 让用户自己去拼 `?token=` 是不现实的。
 */
@Composable
private fun ClientSyncCard(
    state: ModSyncServer.Snapshot,
    enabledMods: Int,
    exporting: Boolean,
    exportMessage: String,
    exportError: String?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCopy: (String) -> Unit,
    onExportZip: () -> Unit,
    onExportMrpack: () -> Unit,
    onSettings: () -> Unit,
) {
    M3ECard(
        variant = if (state.running) M3ECardVariant.Filled else M3ECardVariant.Outlined,
        title = t("客户端模组同步"),
        titleIcon = if (state.running) Icons.Filled.Wifi else Icons.Filled.FileDownload,
        supporting = if (state.running) {
            t("已开启 · 玩家可同步 %s 个模组", enabledMods)
        } else {
            t("把服务端启用中的模组打包给玩家：整包下载或按 sha1 增量同步")
        },
        content = {
            if (state.running) {
                Text(
                    t("把这个地址发给玩家（含令牌，浏览器直接可下）"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    state.shareUrl ?: t("（没有可用的局域网地址，检查 Wi-Fi 连接）"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(onClick = { state.shareUrl?.let(onCopy) }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(t("复制"))
                    }
                    TextButton(onClick = onSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(t("端口/令牌"))
                    }
                    TextButton(onClick = onStop) {
                        Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(t("停止"))
                    }
                }
                Text(
                    t("已处理 %s 个请求", state.requests) +
                        if (state.lastRequest.isBlank()) "" else " · ${state.lastRequest}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.error?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            } else {
                state.error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = onStart) { Text(t("开启同步")) }
                    TextButton(onClick = onSettings) { Text(t("端口/令牌")) }
                }
            }

            // 导出：即使不开同步也能用（玩家把包拷进手机/网盘即可）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(onClick = onExportZip, enabled = !exporting) {
                    Text(t("导出客户端包 (.zip)"))
                }
                TextButton(onClick = onExportMrpack, enabled = !exporting) {
                    Text(t("导出 .mrpack"))
                }
            }
            if (exporting) {
                WavyLinearProgress(progress = null, modifier = Modifier.fillMaxWidth())
                Text(
                    exportMessage.ifBlank { t("正在打包…") },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (exportMessage.isNotBlank()) {
                Text(
                    exportMessage,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            exportError?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
            Text(
                t("玩家用 Modrinth App / Prism / HMCL 导入整合包即可；也可运行包里的 kaze-sync 脚本按 sha1 增量更新。"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        },
    )
}

/** 单个模组/插件的详情：读出来的元数据 + 兼容性提示 + 文件路径 */
@Composable
private fun ModDetailsDialog(
    instance: ServerInstance,
    kind: AddonKind,
    file: File,
    onDismiss: () -> Unit,
) {
    val meta: ModMetadata = remember(file.absolutePath, file.length(), file.lastModified()) {
        ModMetadataReader.read(file)
    }
    val notes = remember(file.absolutePath, file.length(), file.lastModified()) {
        if (kind == AddonKind.MOD) ModCompatibility.check(instance, kind, meta) else emptyList()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            ModIcon(
                jar = file,
                fallback = if (kind == AddonKind.PLUGIN) Icons.Filled.Extension else Icons.Filled.ViewInAr,
                size = 56.dp,
            )
        },
        title = {
            Text(
                meta.displayName.ifBlank { file.name },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                DetailRow(t("文件"), file.name)
                DetailRow(t("大小"), "${file.length() / 1024} KB")
                DetailRow(t("状态"), if (AddonManager.isEnabled(file)) t("已启用") else t("已禁用"))
                if (meta.version.isNotBlank()) DetailRow(t("版本"), meta.version)
                DetailRow(t("模组 id"), meta.modId)
                DetailRow(t("加载器"), meta.loader.label)
                if (meta.gameVersionRange.isNotBlank()) DetailRow(t("适配版本"), meta.gameVersionRange)
                if (meta.authorText.isNotBlank()) DetailRow(t("作者"), meta.authorText)
                if (meta.depends.isNotEmpty()) DetailRow(t("依赖"), meta.depends.joinToString(", "))
                if (meta.description.isNotBlank()) {
                    Text(
                        meta.description.take(400),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                notes.forEach { note ->
                    Text(
                        (if (note.level == ModCompatibility.Level.Warn) "⚠ " else "· ") + note.text,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (note.level == ModCompatibility.Level.Warn) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Text(
                    file.absolutePath,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(t("关闭")) }
        },
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 同步服务的端口与令牌设置 */
@Composable
private fun SyncSettingsDialog(viewModel: AppViewModel, onDismiss: () -> Unit) {
    val prefs = viewModel.uiPrefs
    var portText by remember { mutableStateOf(prefs.modSyncPort.value.toString()) }
    var tokenText by remember { mutableStateOf(prefs.modSyncToken.value) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("同步设置")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = portText,
                    onValueChange = { s -> portText = s.filter { it.isDigit() }.take(5) },
                    label = { Text(t("端口")) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = tokenText,
                    onValueChange = { tokenText = it.take(64) },
                    label = { Text(t("访问令牌")) },
                    singleLine = true,
                    supportingText = {
                        Text(
                            t("留空 = 不校验：同一局域网内任何人都能下载你的模组包"),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { tokenText = prefs.regenerateModSyncToken() }) {
                        Text(t("重新生成"))
                    }
                }
                Text(
                    t("改端口后需要先停止同步、再重新开启才会生效。"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                portText.toIntOrNull()?.let { prefs.setModSyncPort(it) }
                prefs.setModSyncToken(tokenText)
                onDismiss()
            }) { Text(t("保存")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(t("取消")) }
        },
    )
}

/**
 * Modrinth 搜索栏：高 56dp、全圆角、surfaceContainerHigh 底。
 * 左侧搜索图标；右侧是搜索动作（搜索中换成加载指示器）；回车同样发起搜索。
 *
 * [enabled] = false（当前核心不支持这类附件）时整条搜索栏不可点、不可输入：
 * 只挂一条警示横幅却照常能搜能装，等于把"不支持"说成了一句废话。
 */
@Composable
private fun SearchField(
    query: String,
    searching: Boolean,
    enabled: Boolean,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    Surface(
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (enabled) MaterialTheme.colorScheme.onSurface
        else MaterialTheme.colorScheme.onSurfaceVariant,
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
                        enabled = enabled,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { focusRequester.requestFocus() },
                contentAlignment = Alignment.CenterStart,
            ) {
                if (query.isEmpty()) {
                    Text(
                        if (enabled) t("搜索 Modrinth") else t("当前核心不支持这类附件"),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    enabled = enabled,
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
                IconButton(onClick = onSubmit, enabled = enabled && query.isNotBlank()) {
                    Icon(Icons.Filled.Search, contentDescription = t("搜索"))
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

/**
 * 插件更新检查的结果面板。
 *
 * 分四段展示（而不是一句"检查完毕"）：
 *  - **可更新**：带版本号与一键全部更新；
 *  - **已是最新**：给个数就行，不占地方；
 *  - **认不出来源**：必须单独说 —— 这些插件不在管理范围内，用户需要知道"没被检查到"，
 *    否则会以为全都检查过了；
 *  - **上游无兼容版本**：插件已停更 / 不支持当前 MC 版本，这也是用户需要知道的。
 */
@Composable
private fun AddonUpdatePanel(
    state: AppViewModel.AddonUpdateState,
    kindLabel: String,
    onUpgradeAll: (List<com.kaze.newage.core.addons.AddonUpdate.Verdict.Update>) -> Unit,
    onDismiss: () -> Unit,
) {
    when (state) {
        AppViewModel.AddonUpdateState.Idle -> Unit

        is AppViewModel.AddonUpdateState.Checking -> {
            Text(
                t("正在检查（%s/%s）%s", state.done, state.total, state.current),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        is AppViewModel.AddonUpdateState.Upgrading -> {
            Text(
                t("正在更新（%s/%s）%s", state.done, state.total, state.current),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        is AppViewModel.AddonUpdateState.Failed -> {
            Text(
                t("检查失败：%s", state.reason),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
            TextButton(onClick = onDismiss) { Text(t("关闭")) }
        }

        is AppViewModel.AddonUpdateState.Upgraded -> {
            Text(
                if (state.failed.isEmpty()) {
                    t("已更新 %s 个%s（重启服务端生效）", state.okCount, kindLabel)
                } else {
                    t("已更新 %s 个，%s 个失败", state.okCount, state.failed.size)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (state.failed.isEmpty()) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
                modifier = Modifier.padding(top = 4.dp),
            )
            state.failed.forEach {
                Text(
                    "· $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            TextButton(onClick = onDismiss) { Text(t("知道了")) }
        }

        is AppViewModel.AddonUpdateState.Done -> {
            val s = state.summary
            if (s.total == 0) {
                Text(
                    t("没有已装的%s 可检查。", kindLabel),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                return
            }
            if (s.hasAny) {
                Text(
                    t("%s 个%s 有新版本", s.updates.size, kindLabel),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
                s.updates.forEach { u ->
                    Text(
                        "· ${u.fileName}：${u.currentVersion} → ${u.next.version_number}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(
                    onClick = { onUpgradeAll(s.updates) },
                    modifier = Modifier.padding(top = 4.dp),
                ) { Text(t("全部更新")) }
            } else {
                Text(
                    t("已是最新（%s 个已检查）", s.latest.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            // 认不出来源的必须单独说明：这些不在管理范围内
            if (s.unknown.isNotEmpty()) {
                Text(
                    t("%s 个认不出来源，未纳入检查", s.unknown.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                s.unknown.take(5).forEach { u ->
                    Text(
                        "· ${u.fileName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (s.unknown.size > 5) {
                    Text(
                        "· 还有 ${s.unknown.size - 5} 个…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (s.noCompatible.isNotEmpty()) {
                Text(
                    t("%s 个在当前核心/MC 版本下没有可用的新版本", s.noCompatible.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            TextButton(onClick = onDismiss) { Text(t("关闭")) }
        }
    }
}

/**
 * 推荐列表（PCL2 / ZL2 启动器那种"没下载也看得到图标"的区域）。
 *
 * 数据来自 ViewModel 的 [AppViewModel.addonRecommend]（`/v2/projects` 批量接口，
 * 按当前实例的加载器与 MC 版本过滤过），所以每一条都能直接安装、不会点下去才发现不兼容。
 * 展开状态也放在 ViewModel 里：切页再回来不该被重置。
 */
@Composable
private fun AddonRecommendSection(
    viewModel: AppViewModel,
    instance: ServerInstance,
    kind: AddonKind,
) {
    val state by viewModel.addonRecommend.collectAsStateWithLifecycle()
    val installState by viewModel.addonInstall.collectAsStateWithLifecycle()
    val kindLabel = if (kind == AddonKind.PLUGIN) t("插件") else t("模组")

    LaunchedEffect(instance.id, kind) {
        viewModel.loadRecommendedAddons(instance, kind)
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                t("推荐%s", kindLabel),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (state.loading) {
                ExpressiveLoadingRing(size = 16.dp, speed = 1.6f)
            } else {
                IconButton(onClick = { viewModel.loadRecommendedAddons(instance, kind, force = true) }) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = t("刷新推荐"),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = { viewModel.setRecommendedExpanded(!state.expanded) }) {
                Icon(
                    if (state.expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = t("展开/收起推荐"),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (state.expanded) {
            state.error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (state.items.isNotEmpty()) {
                val contentKind = if (kind == AddonKind.MOD) ContentKind.MOD else ContentKind.PLUGIN
                M3EConnectedList(count = state.items.size) { index, shape ->
                    val project = state.items[index]
                    val reason = RecommendedProjects.reasonOf(contentKind, project.slug)
                        .ifBlank { project.description }
                    M3EListItem(
                        headline = project.title,
                        supporting = t("%s 次下载 · ", formatDownloads(project.downloads.toInt())) + reason,
                        leading = {
                            RemoteIcon(
                                url = project.icon_url,
                                fallback = if (kind == AddonKind.PLUGIN) Icons.Filled.Extension
                                else Icons.Filled.ViewInAr,
                                size = 40.dp,
                            )
                        },
                        shape = shape,
                        trailing = {
                            Button(
                                onClick = { viewModel.installRecommendedAddon(instance, kind, project) },
                                enabled = !installState.running,
                            ) { Text(t("安装")) }
                        },
                    )
                }
            } else if (!state.loading && state.error == null) {
                Text(
                    t(
                        "这个实例（%s %s）暂时没有可用的推荐项。",
                        instance.coreType.displayName,
                        instance.mcVersion.ifBlank { "?" },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
/**
 * 客户端专用模组检测卡。
 *
 * 交互刻意做成"默认勾选 + 逐项可取消 + 两个动作按钮"：
 *  - 「停用所选」只处理用户勾选的（手动一键停用的"手动"部分）；
 *  - 「全部停用」处理所有被判定为客户端专用的（一键部分）。
 * 停用只改名 `.disabled`，不删文件 —— 误判时在下面的「已安装」列表里一拨就能开回来。
 */
@Composable
private fun ModAuditCard(
    viewModel: AppViewModel,
    instance: ServerInstance,
) {
    val state by viewModel.modAudit.collectAsStateWithLifecycle()
    val selectedCount = state.items.count { it.enabled && it.file.absolutePath in state.selected }
    val clientOnlyCount = state.items.count { it.clientOnly && it.enabled }

    M3ECard(
        variant = M3ECardVariant.Outlined,
        title = t("客户端专用模组检测"),
        titleIcon = Icons.Filled.FactCheck,
        supporting = t(
            "找出服务端不需要的渲染/界面类模组（Sodium、Iris、ModMenu…）；停用只改名为 .disabled，不删文件。",
        ),
        content = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = { viewModel.auditMods(instance) }, enabled = !state.running) {
                    Text(if (state.running) t("检测中…") else t("开始检测"))
                }
                if (state.items.isNotEmpty() && !state.running) {
                    TextButton(onClick = { viewModel.clearModAudit() }) { Text(t("关闭")) }
                }
            }
            if (state.running) {
                WavyLinearProgress(
                    progress = state.progress.takeIf { it > 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    state.message.ifBlank { t("扫描中…") },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            state.doneMessage?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            if (state.items.isNotEmpty()) {
                M3EConnectedList(count = state.items.size) { index, shape ->
                    val item = state.items[index]
                    val checked = item.file.absolutePath in state.selected
                    M3EListItem(
                        headline = item.meta.displayName.ifBlank { item.file.name.removeSuffix(".disabled") },
                        supporting = buildString {
                            if (item.clientOnly) append("⚠ ")
                            append(item.reason)
                            if (!item.enabled) append(" · ").append(t("已停用"))
                        },
                        leading = {
                            ModIcon(
                                jar = item.file,
                                fallback = if (item.clientOnly) Icons.Filled.ViewInAr else Icons.Filled.Extension,
                                size = 40.dp,
                            )
                        },
                        shape = shape,
                        trailing = {
                            M3ESwitch(
                                checked = checked,
                                onCheckedChange = {
                                    viewModel.toggleAuditSelection(item.file.absolutePath)
                                },
                                enabled = item.enabled,
                            )
                        },
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { viewModel.disableAuditSelected(instance) },
                        enabled = selectedCount > 0,
                    ) { Text(t("停用所选（%s）", selectedCount)) }
                    if (clientOnlyCount > 0) {
                        TextButton(onClick = { viewModel.disableAllAuditClientOnly(instance) }) {
                            Text(t("全部停用（%s）", clientOnlyCount))
                        }
                    }
                }
            }
        },
    )
}