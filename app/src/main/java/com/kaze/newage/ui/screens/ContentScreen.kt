package com.kaze.newage.ui.screens

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaze.newage.core.addons.ModrinthProject
import com.kaze.newage.core.addons.ModrinthSearchHit
import com.kaze.newage.core.bedrock.BedrockPack
import com.kaze.newage.core.bedrock.BedrockServer
import com.kaze.newage.core.content.ContentCatalog
import com.kaze.newage.core.content.ContentKind
import com.kaze.newage.core.world.WorldInfo
import com.kaze.newage.ui.AppViewModel
import com.kaze.newage.ui.components.CheckChip
import com.kaze.newage.ui.components.ExpressiveLoadingRing
import com.kaze.newage.ui.components.FileImageIcon
import com.kaze.newage.ui.components.ProjectDetailDialog
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import com.kaze.newage.ui.components.M3ECard
import com.kaze.newage.ui.components.M3ECardVariant
import com.kaze.newage.ui.components.M3EConnectedList
import com.kaze.newage.ui.components.M3EListItem
import com.kaze.newage.ui.components.M3EScreenHeader
import com.kaze.newage.ui.components.M3ESwitch
import com.kaze.newage.ui.components.RemoteIcon
import com.kaze.newage.ui.components.WavyLinearProgress
import com.kaze.newage.ui.theme.M3Shape
import com.kaze.newage.ui.theme.M3Spacing
import java.io.File
import java.util.Locale
import com.kaze.newage.core.i18n.t

/** 资源中心的四个分区（模组与插件分开：它们的加载器过滤完全不同） */
private enum class ContentTab(val label: String) {
    Mods("模组"),
    Plugins("插件"),
    Packs("资源包"),
    Worlds("地图"),
    Bedrock("基岩版"),
    ;

    val kind: ContentKind?
        get() = when (this) {
            Mods -> ContentKind.MOD
            Plugins -> ContentKind.PLUGIN
            Packs -> ContentKind.RESOURCEPACK
            else -> null
        }
}

/**
 * 资源中心：模组 / 插件 / 资源包 / 地图 / 基岩版。
 *
 * 与「附加管理页」（AddonsScreen）的分工：
 *  - 附加管理页 = **已安装**的启停/删除/更新；
 *  - 资源中心 = **还没下载**的内容：搜索、推荐、下载、以及地图与基岩版这两类
 *    「不属于 mods/」的东西。
 *
 * 所有列表项都带真实图标：Modrinth 项目图标（远程）、世界存档的 `icon.png`、
 * 基岩版附加包的 `pack_icon.png` —— "没下载的模组也要显示图标"就是这么做到的。
 */
@Composable
fun ContentScreen(
    viewModel: AppViewModel,
    onBack: () -> Unit,
    onOpenServers: () -> Unit = {},
) {
    val instances by viewModel.instances.collectAsStateWithLifecycle()
    val currentId by viewModel.currentInstanceId.collectAsStateWithLifecycle()
    val instance = instances.firstOrNull { it.id == currentId }
    val controller = viewModel.contentController
    val content by controller.content.collectAsStateWithLifecycle()
    val worlds by controller.worlds.collectAsStateWithLifecycle()
    val bedrock by controller.bedrock.collectAsStateWithLifecycle()
    val sourceTests by controller.sourceTests.collectAsStateWithLifecycle()
    val versionsPanel by controller.versionsPanel.collectAsStateWithLifecycle()
    val detail by controller.detail.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var tab by rememberSaveable { mutableStateOf(ContentTab.Mods) }
    var query by remember { mutableStateOf("") }
    var showBedrockCreate by remember { mutableStateOf(false) }
    var showWorldDownload by remember { mutableStateOf(false) }
    var showSourceDialog by remember { mutableStateOf(false) }

    // SAF：世界存档 zip、基岩版附加包
    val worldImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null && instance != null) {
            controller.importWorld(instance, uri, displayNameOf(context, uri))
        }
    }
    val packImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null && instance != null) {
            controller.importBedrockPack(instance, uri, displayNameOf(context, uri))
        }
    }

    // 分区切换时加载对应数据（推荐/世界/基岩版版本），同一实例只拉一次（控制器内部有缓存）
    LaunchedEffect(tab, instance?.id) {
        when (tab) {
            ContentTab.Mods, ContentTab.Plugins, ContentTab.Packs -> {
                val kind = tab.kind ?: return@LaunchedEffect
                controller.setKind(instance, kind)
            }
            ContentTab.Worlds -> instance?.let { controller.loadWorlds(it) }
            ContentTab.Bedrock -> controller.loadBedrock(instance)
        }
    }

    if (detail.visible) {
        ProjectDetailDialog(
            state = detail,
            favorite = detail.project?.id?.let { it in content.favorites } == true,
            onToggleFavorite = { detail.project?.let { controller.toggleFavorite(it.id) } },
            onInstall = {
                val project = detail.project
                controller.dismissProjectDetail()
                instance?.let { inst -> project?.let { p -> controller.toggleVersionsPanel(inst, p) } }
            },
            onDismiss = { controller.dismissProjectDetail() },
        )
    }

    if (detail.visible) {
        ProjectDetailDialog(
            state = detail,
            favorite = detail.project?.id?.let { it in content.favorites } == true,
            onToggleFavorite = { detail.project?.let { controller.toggleFavorite(it.id) } },
            onInstall = {
                val project = detail.project
                controller.dismissProjectDetail()
                instance?.let { inst -> project?.let { p -> controller.toggleVersionsPanel(inst, p) } }
            },
            onDismiss = { controller.dismissProjectDetail() },
        )
    }


    Column(Modifier.fillMaxSize().imePadding()) {
        M3EScreenHeader(
            title = t("资源中心"),
            subtitle = if (instance != null) {
                "${instance.name} · MC ${instance.mcVersion.ifBlank { t("自定义") }}" +
                    if (instance.bedrock) " · ${t("基岩版")}" else ""
            } else {
                t("先在「服务端」页选择或创建一个实例")
            },
            leading = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = t("返回"))
                }
            },
            trailing = {
                // 内容源：默认自动（官方→镜像）；官方不通时用户也可以手动锁定镜像
                TextButton(onClick = { showSourceDialog = true }) {
                    Text(
                        t("源：%s", com.kaze.newage.core.addons.ModrinthApi.activeSourceLabel()),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            },
        )

        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = M3Spacing.screenMargin, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ContentTab.entries.forEach { entry ->
                CheckChip(
                    selected = tab == entry,
                    label = t(entry.label),
                    onClick = {
                        tab = entry
                        query = ""
                    },
                )
            }
        }

        if (instance == null) {
            Box(Modifier.fillMaxSize().padding(M3Spacing.screenMargin)) {
                M3EListItem(
                    headline = t("还没有服务端实例"),
                    supporting = t("资源中心的内容都要装进某个实例：先去「服务端」页新建或导入一个。"),
                    leadingIcon = Icons.Filled.Info,
                    onClick = onOpenServers,
                    shape = M3Shape.listSingle,
                )
            }
            return@Column
        }

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = M3Spacing.screenMargin)
                .padding(top = M3Spacing.betweenGroups, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(M3Spacing.betweenParts),
        ) {
            when (tab) {
                ContentTab.Mods, ContentTab.Plugins, ContentTab.Packs -> BrowseSection(
                    instance = instance,
                    kind = tab.kind ?: ContentKind.MOD,
                    controller = controller,
                    content = content,
                    versionsPanel = versionsPanel,
                    query = query,
                    onQueryChange = { query = it },
                    onSearch = { controller.search(instance, query) },
                )
                ContentTab.Worlds -> WorldsSection(
                    instance = instance,
                    worlds = worlds,
                    onImport = {
                        worldImportLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                    },
                    onDownload = { showWorldDownload = true },
                    onRefresh = { controller.loadWorlds(instance) },
                    onActivate = { controller.activateWorld(instance, it) },
                    onDelete = { controller.deleteWorld(instance, it) },
                )
                ContentTab.Bedrock -> BedrockSection(
                    instance = instance,
                    bedrock = bedrock,
                    onRefresh = { controller.loadBedrock(instance, forceRelease = true) },
                    onCreate = { showBedrockCreate = true },
                    onImportPack = {
                        packImportLauncher.launch(arrayOf("application/octet-stream", "application/zip", "*/*"))
                    },
                    onTogglePack = { controller.toggleBedrockPack(instance, it) },
                    onDeletePack = { controller.deleteBedrockPack(instance, it) },
                )
            }
        }
    }

    if (showSourceDialog) {
        ContentSourceDialog(
            initialMode = viewModel.uiPrefs.contentSource.value,
            initialUrl = viewModel.uiPrefs.contentSourceCustomUrl.value,
            tests = sourceTests,
            onTest = { controller.testSources() },
            onDismiss = { showSourceDialog = false },
            onSave = { mode, url ->
                viewModel.uiPrefs.setContentSource(mode, url)
                showSourceDialog = false
                if (instance != null) {
                    controller.loadRecommended(instance, content.kind, force = true)
                    if (query.isNotBlank()) controller.search(instance, query)
                }
            },
        )
    }
    if (showBedrockCreate) {
        BedrockCreateDialog(
            defaultName = "基岩服",
            onDismiss = { showBedrockCreate = false },
            onConfirm = { name, memory ->
                showBedrockCreate = false
                controller.createBedrockServer(name, memory)
            },
        )
    }
    if (showWorldDownload && instance != null) {
        WorldDownloadDialog(
            onDismiss = { showWorldDownload = false },
            onConfirm = { url, name ->
                showWorldDownload = false
                controller.downloadWorld(instance, url, name)
            },
        )
    }
}

// ══════════════════════════════════════════════════════════════
// 模组 / 插件 / 资源包：搜索 + 推荐 + 结果
// ══════════════════════════════════════════════════════════════

@Composable
private fun BrowseSection(
    instance: com.kaze.newage.data.model.ServerInstance,
    kind: ContentKind,
    controller: com.kaze.newage.ui.ContentController,
    content: com.kaze.newage.ui.ContentController.ContentUiState,
    versionsPanel: com.kaze.newage.ui.ContentController.VersionsPanel,
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme

    // ── 搜索框 ──
    Surface(
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = CircleShape,
        color = scheme.surfaceContainerHigh,
    ) {
        Row(
            Modifier.fillMaxSize().padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = scheme.onSurfaceVariant)
            // BasicTextField 而不是 OutlinedTextField：后者会在胶囊里再画一圈描边，
            // 与外面的 Surface 叠成"双层边框"（截图核对时很明显）
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    Text(
                        t("搜索%s（Modrinth）", t(kind.label)),
                        style = MaterialTheme.typography.bodyLarge,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(scheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (content.loading) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    ExpressiveLoadingRing(size = 22.dp, speed = 1.6f)
                }
            } else {
                IconButton(onClick = onSearch, enabled = query.isNotBlank()) {
                    Icon(Icons.Filled.Search, contentDescription = t("搜索"))
                }
            }
        }
    }

    // ── 排序 ──
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ContentCatalog.Sort.entries.forEach { sort ->
            CheckChip(
                selected = content.sort == sort,
                label = t(sort.label),
                onClick = { controller.search(instance, query.ifBlank { "" }, sort) },
            )
        }
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CheckChip(
            selected = content.showAllVersions,
            label = t("全部版本"),
            onClick = { controller.setShowAllVersions(!content.showAllVersions) },
        )
        Text(
            t("忽略加载器与 MC 版本过滤"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (kind == ContentKind.MOD) {
            CheckChip(
                selected = content.serverOnly,
                label = t("仅服务端模组"),
                onClick = { controller.setServerOnly(!content.serverOnly) },
            )
        }
        CheckChip(
            selected = content.showFavoritesOnly,
            label = t("收藏（%s）", content.favorites.size),
            onClick = { controller.setShowFavoritesOnly(!content.showFavoritesOnly) },
        )
    }

    if (versionsPanel.visible) {
        M3ECard(
            variant = M3ECardVariant.Outlined,
            title = t("版本 · %s", versionsPanel.project?.title.orEmpty()),
            supporting = t("选一个版本直接安装；不需要时可点「收起」"),
            content = {
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Switch(
                            checked = versionsPanel.includeAll,
                            onCheckedChange = { controller.setPanelIncludeAll(it) },
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(t("显示全部 MC 版本"), style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { controller.closeVersionsPanel() }) { Text(t("收起")) }
                    }
                    if (versionsPanel.loading) {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) { ExpressiveLoadingRing(size = 20.dp, speed = 1.4f) }
                    } else {
                        versionsPanel.error?.let {
                            Text(
                                t("版本列表加载失败：%s", it),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (versionsPanel.versions.isEmpty() && versionsPanel.error == null) {
                            Text(
                                t("没有可用版本；可以打开「显示全部 MC 版本」再试"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        versionsPanel.versions.forEach { version ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        version.version_number.ifBlank { version.id.take(8) },
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        buildString {
                                            if (version.game_versions.isNotEmpty()) {
                                                append(t("MC %s", version.game_versions.take(4).joinToString("/")))
                                                if (version.game_versions.size > 4) append("…")
                                            }
                                            if (version.loaders.isNotEmpty()) {
                                                if (isNotEmpty()) append(" · ")
                                                append(version.loaders.joinToString("/"))
                                            }
                                        }.ifBlank { t("未标注兼容版本") },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(onClick = {
                                    instance?.let { controller.installPanelVersion(it, version) }
                                }) { Text(t("安装")) }
                            }
                        }
                    }
                }
            },
        )
    }

    if (content.showFavoritesOnly) {
        when {
            content.favoriteLoading -> Row(
                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
            ) { ExpressiveLoadingRing(size = 20.dp, speed = 1.4f) }
            content.favoriteProjects.isEmpty() -> Text(
                t("还没有收藏项目：点条目右侧的星标即可收藏"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            else -> M3EConnectedList(count = content.favoriteProjects.size) { index, shape ->
                val project = content.favoriteProjects[index]
                ProjectRow(
                    project = project,
                    supporting = project.description,
                    shape = shape,
                    busy = content.installing,
                    favorite = true,
                    onToggleFavorite = { controller.toggleFavorite(project.id) },
                    onOpenDetail = { controller.openProjectDetail(project) },
                    onInstall = { controller.toggleVersionsPanel(instance, project) },
                )
            }
        }
    }

    // ── 安装进度 / 结果 ──
    if (content.installing) {
        M3ECard(
            variant = M3ECardVariant.Outlined,
            title = t("正在安装"),
            supporting = content.installMessage.ifBlank { t("准备下载…") },
            content = {
                WavyLinearProgress(
                    progress = content.installProgress.coerceIn(0f, 1f),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )
    }
    content.installedMessage?.let {
        M3ECard(
            variant = M3ECardVariant.Filled,
            title = t("已安装"),
            supporting = it,
            content = {
                if (content.lastPack != null && !content.serverPackApplied) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { controller.applyAsServerResourcePack(instance) }) {
                            Text(t("设为服务器资源包"))
                        }
                        TextButton(onClick = { controller.clearInstallMessages() }) { Text(t("关闭")) }
                    }
                } else {
                    TextButton(onClick = { controller.clearInstallMessages() }) { Text(t("知道了")) }
                }
            },
        )
    }
    content.installError?.let {
        Text(it, color = scheme.error, style = MaterialTheme.typography.bodySmall)
    }
    content.error?.let {
        Text(it, color = scheme.error, style = MaterialTheme.typography.bodySmall)
    }

    // ── 推荐（没下载过也看得到图标）──
    // 展开状态放在本地（rememberSaveable）：它是纯界面偏好，控制器不需要知道
    var recommendExpanded by rememberSaveable { mutableStateOf(true) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            t("推荐%s", t(kind.label)),
            style = MaterialTheme.typography.titleMedium,
            color = scheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (content.recommendedLoading) {
            ExpressiveLoadingRing(size = 16.dp, speed = 1.6f)
        } else {
            IconButton(onClick = { controller.loadRecommended(instance, kind, force = true) }) {
                Icon(Icons.Filled.Refresh, contentDescription = t("刷新推荐"), tint = scheme.onSurfaceVariant)
            }
        }
        IconButton(onClick = { recommendExpanded = !recommendExpanded }) {
            Icon(
                if (recommendExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = t("展开/收起推荐"),
                tint = scheme.onSurfaceVariant,
            )
        }
    }
    if (recommendExpanded) {
        content.recommendedError?.let {
            Text(it, color = scheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (content.recommended.isNotEmpty()) {
            M3EConnectedList(count = content.recommended.size) { index, shape ->
                val project = content.recommended[index]
                ProjectRow(
                    project = project,
                    supporting = controller.reasonOf(kind, project.slug)
                        .ifBlank { project.description },
                    shape = shape,
                    busy = content.installing,
                    favorite = project.id in content.favorites,
                    onToggleFavorite = { controller.toggleFavorite(project.id) },
                    onOpenDetail = { controller.openProjectDetail(project) },
                    onInstall = { controller.toggleVersionsPanel(instance, project) },
                )
            }
        } else if (!content.recommendedLoading && content.recommendedError == null) {
            Text(
                t("这个实例（%s %s）暂时没有能用的推荐项。",
                    instance.coreType.displayName, instance.mcVersion.ifBlank { "?" }),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }

    // ── 搜索结果 ──
    if (!content.showFavoritesOnly && content.searched) {
        Text(
            if (content.totalHits > 0) t("搜索结果（%s）", content.totalHits) else t("搜索结果"),
            style = MaterialTheme.typography.titleMedium,
            color = scheme.onSurface,
            modifier = Modifier.padding(top = M3Spacing.betweenParts),
        )
        if (content.hits.isEmpty() && !content.loading) {
            M3EListItem(
                headline = t("没有找到「%s」", query),
                supporting = t("换个关键词，或确认实例的 MC 版本与加载器是否匹配"),
                leadingIcon = Icons.Filled.SearchOff,
                iconContainer = scheme.surfaceContainerHighest,
                shape = M3Shape.listSingle,
            )
        } else {
            M3EConnectedList(count = content.hits.size) { index, shape ->
                val hit = content.hits[index]
                HitRow(
                    hit = hit,
                    shape = shape,
                    busy = content.installing,
                    favorite = hit.project_id in content.favorites,
                    onToggleFavorite = { controller.toggleFavorite(hit.project_id) },
                    onOpenDetail = {
                        controller.openProjectDetail(
                            ModrinthProject(
                                id = hit.project_id,
                                slug = hit.slug,
                                title = hit.title,
                                description = hit.description,
                                project_type = hit.project_type,
                                icon_url = hit.icon_url,
                                downloads = hit.downloads.toLong(),
                            ),
                        )
                    },
                    onInstall = { controller.installHit(instance, hit) },
                )
            }
            if (!content.loading && (content.hits.isNotEmpty() || content.page > 0)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = M3Spacing.betweenParts),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { controller.prevPage() }, enabled = content.page > 0) {
                        Text(t("上一页"))
                    }
                    Text(
                        t("第 %s 页", content.page + 1),
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                    TextButton(onClick = { controller.nextPage() }, enabled = content.hasMore) {
                        Text(t("下一页"))
                    }
                }
            }
        }
    }
}

/** 推荐区展开状态由界面自己持有（见 BrowseSection 里的 rememberSaveable） */

@Composable
private fun ProjectRow(
    project: ModrinthProject,
    supporting: String,
    shape: androidx.compose.ui.graphics.Shape,
    busy: Boolean,
    favorite: Boolean,
    onToggleFavorite: () -> Unit,
    onOpenDetail: () -> Unit,
    onInstall: () -> Unit,
) {
    M3EListItem(
        headline = project.title,
        supporting = "${formatDownloads(project.downloads)} ${t("次下载")} · $supporting",
        leading = {
            RemoteIcon(
                url = project.icon_url,
                fallback = if (project.project_type == "resourcepack") Icons.Filled.FileDownload
                else Icons.Filled.Extension,
                size = 48.dp,
            )
        },
        onClick = onOpenDetail,
        shape = shape,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        if (favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        contentDescription = t("收藏"),
                        tint = if (favorite) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(onClick = onInstall, enabled = !busy) { Text(t("安装")) }
            }
        },
    )
}

@Composable
private fun HitRow(
    hit: ModrinthSearchHit,
    shape: androidx.compose.ui.graphics.Shape,
    busy: Boolean,
    favorite: Boolean,
    onToggleFavorite: () -> Unit,
    onOpenDetail: () -> Unit,
    onInstall: () -> Unit,
) {
    M3EListItem(
        headline = hit.title,
        supporting = "${formatDownloads(hit.downloads.toLong())} ${t("次下载")} · " +
            hit.description.ifBlank { t("（无描述）") },
        leading = {
            RemoteIcon(
                url = hit.icon_url,
                fallback = Icons.Filled.Extension,
                size = 48.dp,
            )
        },
        onClick = onOpenDetail,
        shape = shape,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        if (favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        contentDescription = t("收藏"),
                        tint = if (favorite) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(onClick = onInstall, enabled = !busy) { Text(t("安装")) }
            }
        },
    )
}

// ══════════════════════════════════════════════════════════════
// 地图（世界存档）
// ══════════════════════════════════════════════════════════════

@Composable
private fun WorldsSection(
    instance: com.kaze.newage.data.model.ServerInstance,
    worlds: com.kaze.newage.ui.ContentController.WorldsUiState,
    onImport: () -> Unit,
    onDownload: () -> Unit,
    onRefresh: () -> Unit,
    onActivate: (WorldInfo) -> Unit,
    onDelete: (WorldInfo) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var deleteTarget by remember { mutableStateOf<WorldInfo?>(null) }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(onClick = onImport, enabled = !worlds.busy) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(t("导入存档"))
        }
        TextButton(onClick = onDownload, enabled = !worlds.busy) {
            Icon(Icons.Filled.Link, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(t("从链接下载"))
        }
        if (worlds.loading) {
            ExpressiveLoadingRing(size = 18.dp, speed = 1.6f)
        } else {
            IconButton(onClick = onRefresh) {
                Icon(Icons.Filled.Refresh, contentDescription = t("刷新"), tint = scheme.onSurfaceVariant)
            }
        }
    }

    if (worlds.busy) {
        WavyLinearProgress(progress = worlds.progress.takeIf { it > 0f }, modifier = Modifier.fillMaxWidth())
        Text(
            worlds.message.ifBlank { t("处理中…") },
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
        )
    }
    worlds.message.takeIf { !worlds.busy && it.isNotBlank() }?.let {
        Text(it, style = MaterialTheme.typography.labelSmall, color = scheme.primary)
    }
    worlds.error?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.error)
    }

    M3ECard(
        variant = M3ECardVariant.Outlined,
        title = t("地图 / 世界存档"),
        titleIcon = Icons.Filled.Map,
        supporting = t("把地图 zip 装进实例，切换成全服的世界。切换前请先停止服务端；旧世界会自动备份。"),
    )

    if (worlds.worlds.isEmpty() && !worlds.loading) {
        M3EListItem(
            headline = t("还没有世界"),
            supporting = t("启动一次服务端会生成 world，或者用上面的按钮导入/下载一个地图"),
            leadingIcon = Icons.Filled.Map,
            iconContainer = scheme.surfaceContainerHighest,
            shape = M3Shape.listSingle,
        )
    } else {
        M3EConnectedList(count = worlds.worlds.size) { index, shape ->
            val world = worlds.worlds[index]
            M3EListItem(
                headline = world.displayName,
                supporting = buildString {
                    append(t("目录 %s", world.name))
                    if (world.versionName.isNotBlank()) append(" · ${world.versionName}")
                    append(" · ${ContentCatalog.human(world.sizeBytes)}")
                    if (world.active) append(" · ${t("当前世界")}")
                },
                leading = {
                    FileImageIcon(
                        file = world.iconFile,
                        fallback = Icons.Filled.Map,
                        size = 44.dp,
                    )
                },
                shape = shape,
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (world.active) {
                            Surface(
                                shape = M3Shape.groupSingle(20f),
                                color = scheme.primaryContainer,
                                contentColor = scheme.onPrimaryContainer,
                            ) {
                                Text(
                                    t("当前"),
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                )
                            }
                        } else {
                            TextButton(
                                onClick = { onActivate(world) },
                                enabled = !worlds.busy,
                            ) { Text(t("切换")) }
                        }
                        IconButton(onClick = { deleteTarget = world }, enabled = !world.active) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = t("删除"),
                                tint = if (world.active) scheme.onSurfaceVariant else scheme.error,
                            )
                        }
                    }
                },
            )
        }
        Text(
            t("提示：导入的地图若带多层文件夹，会自动找到 level.dat 所在的那一层。"),
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
        )
    }

    deleteTarget?.let { world ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(t("删除「%s」？", world.displayName)) },
            text = { Text(t("世界目录 %s 将被永久删除，无法恢复。", world.name)) },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    onDelete(world)
                }) { Text(t("删除"), color = scheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(t("取消")) }
            },
        )
    }
}

// ══════════════════════════════════════════════════════════════
// 基岩版
// ══════════════════════════════════════════════════════════════

@Composable
private fun BedrockSection(
    instance: com.kaze.newage.data.model.ServerInstance,
    bedrock: com.kaze.newage.ui.ContentController.BedrockUiState,
    onRefresh: () -> Unit,
    onCreate: () -> Unit,
    onImportPack: () -> Unit,
    onTogglePack: (BedrockPack) -> Unit,
    onDeletePack: (BedrockPack) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val release = bedrock.release

    M3ECard(
        variant = M3ECardVariant.Filled,
        title = t("基岩版服务端 · PowerNukkitX"),
        titleIcon = Icons.Filled.Public,
        supporting = t("官方 Bedrock 服务端只有 x86_64 版，手机（ARM）跑不了；PowerNukkitX 是 Java 实现，能在本应用的 Java 环境里启动。"),
        content = {
            when {
                release != null -> {
                    Text(
                        t("最新版 %s · 支持基岩版 %s · %s",
                            release.version,
                            release.minecraftVersion.ifBlank { "?" },
                            ContentCatalog.human(release.sizeBytes),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurface,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(onClick = onCreate, enabled = !bedrock.busy) {
                            Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(t("下载并创建基岩版服务端"))
                        }
                        TextButton(onClick = onRefresh) { Text(t("检查更新")) }
                    }
                }
                bedrock.loadingRelease -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ExpressiveLoadingRing(size = 18.dp, speed = 1.6f)
                        Spacer(Modifier.width(8.dp))
                        Text(t("正在获取版本信息…"), style = MaterialTheme.typography.bodySmall)
                    }
                }
                else -> {
                    bedrock.releaseError?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.error)
                    }
                    TextButton(onClick = onRefresh) { Text(t("重试")) }
                }
            }
            if (bedrock.busy) {
                WavyLinearProgress(progress = bedrock.progress.takeIf { it > 0f }, modifier = Modifier.fillMaxWidth())
                Text(
                    bedrock.message.ifBlank { t("下载中…") },
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            bedrock.message.takeIf { !bedrock.busy && it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = scheme.primary)
            }
            bedrock.error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.error)
            }
        },
    )

    M3ECard(
        variant = M3ECardVariant.Outlined,
        title = t("基岩版附加包（含图标）"),
        titleIcon = Icons.Filled.Extension,
        supporting = t("支持 .mcpack / .mcaddon：自动按 manifest 分辨资源包/行为包，并读取包里的 pack_icon.png 作为图标。"),
        content = {
            if (!instance.bedrock) {
                Text(
                    t("附加包属于基岩版实例。先在上一张卡片里创建一个基岩版服务端，再回来管理它的附加包。"),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = onImportPack, enabled = !bedrock.busy) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(t("导入附加包"))
                    }
                    Text(
                        t("已装 %s 个", bedrock.packs.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        },
    )

    if (instance.bedrock) {
        if (bedrock.packs.isEmpty()) {
            M3EListItem(
                headline = t("还没有附加包"),
                supporting = t("导入 .mcpack 或 .mcaddon；启用状态会写进当前世界的 world_*_packs.json"),
                leadingIcon = Icons.Filled.ViewInAr,
                iconContainer = scheme.surfaceContainerHighest,
                shape = M3Shape.listSingle,
            )
        } else {
            M3EConnectedList(count = bedrock.packs.size) { index, shape ->
                val pack = bedrock.packs[index]
                M3EListItem(
                    headline = pack.name,
                    supporting = "${pack.kind.label} · v${pack.versionText}" +
                        (if (pack.minEngine.isNotBlank()) " · ≥${pack.minEngine}" else ""),
                    leading = {
                        FileImageIcon(
                            file = pack.iconFile,
                            fallback = if (pack.kind == BedrockPack.Kind.RESOURCE) Icons.Filled.FileDownload
                            else Icons.Filled.ViewInAr,
                            size = 44.dp,
                        )
                    },
                    shape = shape,
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            M3ESwitch(
                                checked = pack.active,
                                onCheckedChange = { onTogglePack(pack) },
                            )
                            IconButton(onClick = { onDeletePack(pack) }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = t("删除"),
                                    tint = scheme.error,
                                )
                            }
                        }
                    },
                )
            }
            Text(
                t("开关会立即改写世界配置，重启服务端生效。"),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

// ══════════════════════════════════════════════════════════════
// 弹窗与工具
// ══════════════════════════════════════════════════════════════

@Composable
private fun BedrockCreateDialog(
    defaultName: String,
    onDismiss: () -> Unit,
    onConfirm: (String, Int) -> Unit,
) {
    var name by remember { mutableStateOf(defaultName) }
    var memory by remember { mutableStateOf("2048") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("创建基岩版服务端")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(24) },
                    label = { Text(t("名字")) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = memory,
                    onValueChange = { s -> memory = s.filter { it.isDigit() }.take(5) },
                    label = { Text(t("内存上限（MB）")) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Text(
                    t("会下载 PowerNukkitX（约 90 MB）到新实例目录。基岩版默认端口 19132（UDP），与 Java 版不同。"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val mb = (memory.toIntOrNull() ?: 2048).coerceIn(512, 16384)
                onConfirm(name, mb)
            }) { Text(t("创建")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(t("取消")) }
        },
    )
}

@Composable
private fun WorldDownloadDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("从链接下载地图")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it.trim() },
                    label = { Text(t("zip 直链（https://）")) },
                    singleLine = true,
                    placeholder = { Text("https://…/map.zip") },
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    label = { Text(t("保存为（可留空）")) },
                    singleLine = true,
                )
                Text(
                    t("把地图站里的下载直链粘进来即可。只支持 https，并且必须是含 level.dat 的 zip 存档。"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(url, name) },
                enabled = url.startsWith("https://", ignoreCase = true),
            ) { Text(t("下载")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(t("取消")) }
        },
    )
}

/** SAF 展示名（世界/附加包导入时用来给目录起名） */
private fun displayNameOf(context: android.content.Context, uri: android.net.Uri): String? =
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

/** 下载数格式化：1.2K / 3.4M（与附加管理页同一套显示规则） */
private fun formatDownloads(n: Long): String = when {
    n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1_000_000f)
    n >= 1_000 -> String.format(Locale.US, "%.1fK", n / 1_000f)
    else -> n.toString()
}

/** 内容源选择：自动 / 官方 / MCIM 镜像 / 自定义（自定义必须是同构的 Modrinth v2 地址） */
@Composable
private fun ContentSourceDialog(
    initialMode: String,
    initialUrl: String,
    tests: List<com.kaze.newage.ui.ContentController.SourceTest>,
    onTest: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var mode by remember { mutableStateOf(initialMode) }
    var url by remember { mutableStateOf(initialUrl) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(t("内容源")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CheckChip(
                        selected = mode == com.kaze.newage.core.content.ContentSources.MODE_AUTO,
                        label = t("自动"),
                        onClick = { mode = com.kaze.newage.core.content.ContentSources.MODE_AUTO },
                    )
                    CheckChip(
                        selected = mode == "official",
                        label = t("官方"),
                        onClick = { mode = "official" },
                    )
                    CheckChip(
                        selected = mode == "mcim",
                        label = t("国内镜像"),
                        onClick = { mode = "mcim" },
                    )
                    CheckChip(
                        selected = mode == com.kaze.newage.core.content.ContentSources.MODE_CUSTOM,
                        label = t("自定义"),
                        onClick = { mode = com.kaze.newage.core.content.ContentSources.MODE_CUSTOM },
                    )
                }
                if (mode == com.kaze.newage.core.content.ContentSources.MODE_CUSTOM) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it.trim() },
                        label = { Text(t("镜像地址（…/v2）")) },
                        placeholder = { Text("https://example.com/modrinth/v2") },
                        singleLine = true,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onTest) { Text(t("检测各源")) }
                    Text(t("显示每个源是否可用（404/超时会标出来）"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                tests.forEach { test ->
                    Text(
                        (if (test.ok) "✓ " else "✗ ") + test.label + "  " + test.ms + "ms · " + test.detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (test.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    t("自动 = 官方优先，失败自动切 MCIM 镜像；镜像更新可能滞后几小时，搜不到新项目时切回官方。"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(mode, url) }) { Text(t("保存")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(t("取消")) }
        },
    )
}