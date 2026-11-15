package com.kaze.newage.ui

import android.content.Context
import android.net.Uri
import com.kaze.newage.core.addons.AddonKind
import com.kaze.newage.core.addons.AddonManager
import com.kaze.newage.core.addons.ModrinthVersion
import com.kaze.newage.core.addons.ModrinthApi
import com.kaze.newage.core.addons.ModrinthProject
import com.kaze.newage.core.addons.ModrinthSearchHit
import com.kaze.newage.core.bedrock.BedrockPack
import com.kaze.newage.core.bedrock.BedrockPacks
import com.kaze.newage.core.bedrock.BedrockRelease
import com.kaze.newage.core.bedrock.BedrockServer
import com.kaze.newage.core.content.ContentCatalog
import com.kaze.newage.core.content.ContentKind
import com.kaze.newage.core.content.ContentSources
import com.kaze.newage.core.content.RecommendedProjects
import com.kaze.newage.core.server.ServerProperties
import com.kaze.newage.core.world.WorldInfo
import com.kaze.newage.core.world.WorldManager
import com.kaze.newage.data.InstanceStore
import com.kaze.newage.data.model.CoreType
import com.kaze.newage.data.model.ServerInstance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 内容中心的业务逻辑（搜索、推荐、安装、地图、基岩版）。
 *
 * 单独抽出来而不是继续塞进 AppViewModel：AppViewModel 已经背着实例/服务端/AI/更新四条线，
 * 内容中心是**独立的一条**（它只依赖实例目录与网络），放一起会让那个 3000 行的类更难维护。
 * 依赖只有 `Context`（读 SAF 流）、`InstanceStore`（建基岩版实例）和一个协程作用域。
 */
class ContentController(
    private val context: Context,
    private val instanceStore: InstanceStore,
    private val scope: CoroutineScope,
    private val prefs: com.kaze.newage.data.prefs.SettingsPrefs,
    private val onInstanceCreated: (ServerInstance) -> Unit = {},
) {

    // ── 搜索 / 推荐 / 安装 ──

    data class ContentUiState(
        val kind: ContentKind = ContentKind.MOD,
        val query: String = "",
        val sort: ContentCatalog.Sort = ContentCatalog.Sort.Relevance,
        val loading: Boolean = false,
        val error: String? = null,
        val hits: List<ModrinthSearchHit> = emptyList(),
        val totalHits: Int = 0,
        val searched: Boolean = false,
        /** 搜索结果当前页码（0 起） */
        val page: Int = 0,
        val hasMore: Boolean = false,
        /** 搜索时是否忽略实例的加载器/MC 版本（"全部版本"） */
        val showAllVersions: Boolean = false,
        /** 只显示服务端可用的模组（过滤客户端专属） */
        val serverOnly: Boolean = true,
        /** 收藏的项目 id */
        val favorites: Set<String> = emptySet(),
        /** 只看收藏（替代搜索结果区） */
        val showFavoritesOnly: Boolean = false,
        /** 收藏项目的完整信息（打开"只看收藏"时拉取） */
        val favoriteProjects: List<ModrinthProject> = emptyList(),
        val favoriteLoading: Boolean = false,
        val recommended: List<ModrinthProject> = emptyList(),
        val recommendedLoading: Boolean = false,
        val recommendedError: String? = null,
        val installing: Boolean = false,
        val installProgress: Float = 0f,
        val installMessage: String = "",
        val installError: String? = null,
        val installedMessage: String? = null,
        /** 刚装好的资源包：界面据此显示「设为服务器资源包」 */
        val lastPack: ContentCatalog.Installed? = null,
        val serverPackApplied: Boolean = false,
    )

    private val _content = MutableStateFlow(ContentUiState())
    val content: StateFlow<ContentUiState> = _content.asStateFlow()

    /** 当前搜索请求的序号：只让最后一次搜索写结果（同 AppViewModel.searchAddons 的理由） */
    private var searchRequestId = 0

    fun setKind(instance: ServerInstance?, kind: ContentKind) {
        _content.value = _content.value.copy(
            kind = kind,
            hits = emptyList(),
            totalHits = 0,
            searched = false,
            error = null,
            installError = null,
            installedMessage = null,
            lastPack = null,
            serverPackApplied = false,
            // 必须清掉上一个分区的推荐：loadRecommended 在"已有数据"时会短路，
            // 不清就会出现"切到资源包分区、下面却还列着模组推荐"的错位。
            recommended = emptyList(),
            recommendedError = null,
        )
        loadRecommended(instance, kind, force = false)
    }

    /**
     * 加载推荐列表。失败时**不覆盖已有列表**（网络抖动不该让页面清空），只在没有数据时显示错误。
     */
    fun loadRecommended(instance: ServerInstance?, kind: ContentKind = _content.value.kind, force: Boolean = false) {
        if (!force && _content.value.recommended.isNotEmpty()) return
        _content.value = _content.value.copy(recommendedLoading = true, recommendedError = null)
        scope.launch(Dispatchers.IO) {
            try {
                if (force) ContentCatalog.clearRecommendCache()
                val list = ContentCatalog.recommended(instance, kind, serverOnly = _content.value.serverOnly)
                // 切分区时旧请求可能晚回来：只在"还是这个分区"时才写结果
                if (_content.value.kind != kind) return@launch
                _content.value = _content.value.copy(
                    recommended = list,
                    recommendedLoading = false,
                    recommendedError = if (list.isEmpty()) "没有可推荐的条目（网络或区域限制？）" else null,
                )
            } catch (e: Exception) {
                if (_content.value.kind != kind) return@launch
                _content.value = _content.value.copy(
                    recommendedLoading = false,
                    recommendedError = e.message ?: "推荐列表加载失败",
                )
            }
        }
    }

    /** 最近一次搜索的实例：翻页时复用，避免界面再传一遍 */
    private var lastInstance: ServerInstance? = null

    private companion object {
        const val PAGE_SIZE = 20
    }

    fun search(
        instance: ServerInstance?,
        query: String,
        sort: ContentCatalog.Sort = _content.value.sort,
        page: Int = 0,
    ) {
        lastInstance = instance
        val kind = _content.value.kind
        val requestId = ++searchRequestId
        _content.value = _content.value.copy(
            query = query,
            sort = sort,
            loading = true,
            error = null,
            searched = true,
            installError = null,
            installedMessage = null,
        )
        scope.launch(Dispatchers.IO) {
            try {
                val result = ContentCatalog.search(
                    kind = kind,
                    instance = instance,
                    query = query,
                    sort = sort,
                    offset = page * PAGE_SIZE,
                    limit = PAGE_SIZE,
                    includeAllVersions = _content.value.showAllVersions,
                    serverOnly = _content.value.serverOnly,
                )
                if (requestId == searchRequestId) {
                    _content.value = _content.value.copy(
                        hits = result.hits,
                        totalHits = result.total_hits,
                        page = page,
                        hasMore = result.hits.size >= PAGE_SIZE,
                        loading = false,
                    )
                }
            } catch (e: Exception) {
                if (requestId == searchRequestId) {
                    _content.value = _content.value.copy(
                        loading = false,
                        error = e.message ?: "搜索失败",
                        hits = emptyList(),
                        hasMore = false,
                    )
                }
            }
        }
    }

    /** 下一页（保持同一个实例/查询/排序） */
    fun nextPage() {
        val state = _content.value
        if (state.loading || !state.hasMore) return
        search(lastInstance, state.query, state.sort, state.page + 1)
    }

    /** 上一页 */
    fun prevPage() {
        val state = _content.value
        if (state.loading || state.page <= 0) return
        search(lastInstance, state.query, state.sort, state.page - 1)
    }

    /** 「仅服务端模组」开关：重新拉推荐与搜索（过滤客户端专属） */
    fun setServerOnly(serverOnly: Boolean) {
        if (_content.value.serverOnly == serverOnly) return
        _content.value = _content.value.copy(serverOnly = serverOnly)
        loadRecommended(lastInstance, _content.value.kind, force = true)
        if (_content.value.searched) {
            search(lastInstance, _content.value.query, _content.value.sort, 0)
        }
    }

    /** "全部版本"开关：忽略加载器/MC 版本过滤，从第 1 页重新搜 */
    fun setShowAllVersions(showAll: Boolean) {
        if (_content.value.showAllVersions == showAll) return
        _content.value = _content.value.copy(showAllVersions = showAll)
        val state = _content.value
        search(lastInstance, state.query, state.sort, 0)
    }

    // ── 项目详情（对齐 ZalithLauncher2 的下载页：点条目看介绍/标签/版本）──

    data class ProjectDetail(
        val visible: Boolean = false,
        val loading: Boolean = false,
        val project: ModrinthProject? = null,
        val error: String? = null,
    )

    private val _detail = MutableStateFlow(ProjectDetail())
    val detail: StateFlow<ProjectDetail> = _detail.asStateFlow()

    /** 打开详情：列表里的项目没有 Markdown 正文，这里补一次 `/project/{id}` */
    fun openProjectDetail(project: ModrinthProject) {
        _detail.value = ProjectDetail(visible = true, loading = true, project = project)
        scope.launch(Dispatchers.IO) {
            val full = runCatching { ModrinthApi.project(project.id) }
            _detail.value = _detail.value.copy(
                loading = false,
                project = full.getOrNull() ?: project,
                error = full.exceptionOrNull()?.message,
            )
        }
    }

    fun dismissProjectDetail() {
        _detail.value = ProjectDetail()
    }

    // ── 收藏 ──

    init {
        _content.value = _content.value.copy(favorites = prefs.favoriteProjects.value)
    }

    fun toggleFavorite(projectId: String) {
        prefs.toggleFavorite(projectId)
        val favorites = prefs.favoriteProjects.value
        _content.value = _content.value.copy(favorites = favorites)
        if (_content.value.showFavoritesOnly) loadFavoriteProjects()
    }

    fun setShowFavoritesOnly(show: Boolean) {
        _content.value = _content.value.copy(showFavoritesOnly = show)
        if (show) loadFavoriteProjects()
    }

    private fun loadFavoriteProjects() {
        val ids = prefs.favoriteProjects.value.toList()
        if (ids.isEmpty()) {
            _content.value = _content.value.copy(favoriteProjects = emptyList(), favoriteLoading = false)
            return
        }
        _content.value = _content.value.copy(favoriteLoading = true)
        scope.launch(Dispatchers.IO) {
            val projects = runCatching { ModrinthApi.projects(ids) }.getOrDefault(emptyList())
            _content.value = _content.value.copy(favoriteProjects = projects, favoriteLoading = false)
        }
    }

    // ── 版本面板（内嵌在资源中心页面上，不再弹对话框）──

    data class VersionsPanel(
        val project: ModrinthProject? = null,
        val loading: Boolean = false,
        val versions: List<ModrinthVersion> = emptyList(),
        val includeAll: Boolean = false,
        val error: String? = null,
    ) {
        val visible: Boolean get() = project != null
    }

    private val _versionsPanel = MutableStateFlow(VersionsPanel())
    val versionsPanel: StateFlow<VersionsPanel> = _versionsPanel.asStateFlow()

    private var panelInstance: ServerInstance? = null

    /** 点「版本」：同一个项目再点一次收起；否则加载并展开 */
    fun toggleVersionsPanel(instance: ServerInstance, project: ModrinthProject) {
        if (_versionsPanel.value.project?.id == project.id) {
            _versionsPanel.value = VersionsPanel()
            return
        }
        panelInstance = instance
        _versionsPanel.value = VersionsPanel(project = project, loading = true)
        loadPanelVersions(instance, project, includeAll = false)
    }

    fun toggleVersionsPanel(instance: ServerInstance, hit: ModrinthSearchHit) {
        toggleVersionsPanel(instance, projectOf(hit))
    }

    fun setPanelIncludeAll(includeAll: Boolean) {
        val panel = _versionsPanel.value
        val instance = panelInstance ?: return
        val project = panel.project ?: return
        _versionsPanel.value = panel.copy(includeAll = includeAll, loading = true, error = null)
        loadPanelVersions(instance, project, includeAll)
    }

    fun closeVersionsPanel() {
        _versionsPanel.value = VersionsPanel()
    }

    private fun loadPanelVersions(instance: ServerInstance, project: ModrinthProject, includeAll: Boolean) {
        val kind = _content.value.kind
        scope.launch(Dispatchers.IO) {
            try {
                val versions = if (kind.isAddon) {
                    val addonKind = if (kind == ContentKind.MOD) AddonKind.MOD else AddonKind.PLUGIN
                    AddonManager.versionsForPicker(instance, addonKind, project.id, includeAll)
                } else {
                    ContentCatalog.packVersions(project.id, instance.mcVersion, includeAll)
                }
                _versionsPanel.value = _versionsPanel.value.copy(
                    loading = false,
                    versions = versions,
                    includeAll = includeAll,
                    error = null,
                )
            } catch (e: Exception) {
                _versionsPanel.value = _versionsPanel.value.copy(
                    loading = false,
                    error = e.message ?: "版本列表加载失败",
                )
            }
        }
    }

    /** 从面板里直接装某个版本 */
    fun installPanelVersion(instance: ServerInstance, version: ModrinthVersion) {
        val project = _versionsPanel.value.project ?: return
        install(instance, project, version)
    }

    /** 搜索结果条目 → 项目对象（收藏 / 详情 / 版本面板共用） */
    fun projectOf(hit: ModrinthSearchHit): ModrinthProject = ModrinthProject(
        id = hit.project_id,
        slug = hit.slug,
        title = hit.title,
        description = hit.description,
        project_type = hit.project_type,
        icon_url = hit.icon_url,
        downloads = hit.downloads.toLong(),
    )

    fun installHit(instance: ServerInstance, hit: ModrinthSearchHit) {
        toggleVersionsPanel(instance, projectOf(hit))
    }

    /**
     * 安装一个项目。模组/插件复用 AddonManager（同名替换会直接覆盖 —— 这里已经明确是
     * "装这个项目"，与搜索页的行为一致）；资源包装完记下 [ContentUiState.lastPack]，
     * 让界面可以提供"设为服务器资源包"。
     */
    fun install(
        instance: ServerInstance,
        project: ModrinthProject,
        version: ModrinthVersion? = null,
    ) {
        if (_content.value.installing) return
        val kind = _content.value.kind
        _content.value = _content.value.copy(
            installing = true,
            installProgress = 0f,
            installMessage = "解析 ${project.title}…",
            installError = null,
            installedMessage = null,
            serverPackApplied = false,
        )
        scope.launch(Dispatchers.IO) {
            try {
                val installed = ContentCatalog.install(instance, kind, project, version = version) { p, m ->
                    _content.value = _content.value.copy(
                        installProgress = p.coerceIn(0f, 1f),
                        installMessage = m,
                    )
                }
                _content.value = _content.value.copy(
                    installing = false,
                    installProgress = 1f,
                    installedMessage = if (kind == ContentKind.RESOURCEPACK) {
                        "已安装 ${installed.file.name}（${installed.versionName}）"
                    } else {
                        "已安装 ${installed.file.name}（重启服务端生效）"
                    },
                    lastPack = if (kind == ContentKind.RESOURCEPACK) installed else null,
                )
            } catch (e: Exception) {
                _content.value = _content.value.copy(
                    installing = false,
                    installError = e.message ?: "安装失败",
                )
            }
        }
    }

    /** 把刚装好的资源包设为服务器资源包（写 server.properties） */
    fun applyAsServerResourcePack(instance: ServerInstance) {
        val pack = _content.value.lastPack ?: return
        scope.launch(Dispatchers.IO) {
            val ok = runCatching { ContentCatalog.applyServerResourcePack(instance, pack) }.getOrDefault(false)
            _content.value = _content.value.copy(
                serverPackApplied = ok,
                installedMessage = if (ok) {
                    "已设为服务器资源包：玩家进服会被要求下载（重启服务端生效）"
                } else {
                    null
                },
                installError = if (ok) null else "设置失败：资源包文件或下载地址缺失",
            )
        }
    }

    // ── 内容源自检（诊断 404 用）──

    data class SourceTest(
        val label: String,
        val ok: Boolean,
        val ms: Long,
        val detail: String = "",
    )

    private val _sourceTests = MutableStateFlow<List<SourceTest>>(emptyList())
    val sourceTests: StateFlow<List<SourceTest>> = _sourceTests.asStateFlow()

    /** 逐个请求各内容源的最小搜索接口，给出"哪个源通、耗时多少、失败原因" */
    fun testSources() {
        val sources = ContentSources.active()
        _sourceTests.value = sources.map { SourceTest(it.label, false, 0, "检测中…") }
        scope.launch(Dispatchers.IO) {
            _sourceTests.value = sources.map { source ->
                val started = System.currentTimeMillis()
                val result = runCatching {
                    val conn = java.net.URL(source.baseUrl + "/search?query=sodium&limit=1")
                        .openConnection() as java.net.HttpURLConnection
                    try {
                        conn.connectTimeout = 8000
                        conn.readTimeout = 10000
                        conn.setRequestProperty("User-Agent", "KazeSLauncher/0.11 (Android)")
                        val code = conn.responseCode
                        if (code != 200) throw RuntimeException("HTTP $code")
                        conn.inputStream.use { it.readBytes() }
                    } finally {
                        conn.disconnect()
                    }
                }
                SourceTest(
                    label = source.label,
                    ok = result.isSuccess,
                    ms = System.currentTimeMillis() - started,
                    detail = result.exceptionOrNull()?.message ?: "OK",
                )
            }
        }
    }

    fun clearSourceTests() {
        _sourceTests.value = emptyList()
    }
    fun clearInstallMessages() {
        _content.value = _content.value.copy(
            installError = null,
            installedMessage = null,
            lastPack = null,
            serverPackApplied = false,
        )
    }

    // ── 地图（世界存档）──

    data class WorldsUiState(
        val instanceId: String? = null,
        val loading: Boolean = false,
        val worlds: List<WorldInfo> = emptyList(),
        val busy: Boolean = false,
        val progress: Float = 0f,
        val message: String = "",
        val error: String? = null,
    )

    private val _worlds = MutableStateFlow(WorldsUiState())
    val worlds: StateFlow<WorldsUiState> = _worlds.asStateFlow()

    fun loadWorlds(instance: ServerInstance) {
        _worlds.value = _worlds.value.copy(instanceId = instance.id, loading = true, error = null)
        scope.launch(Dispatchers.IO) {
            val list = runCatching { WorldManager.worlds(instance) }.getOrElse {
                _worlds.value = _worlds.value.copy(loading = false, error = it.message ?: "读取世界列表失败")
                return@launch
            }
            _worlds.value = _worlds.value.copy(loading = false, worlds = list)
        }
    }

    fun importWorld(instance: ServerInstance, uri: Uri, displayName: String?) {
        runWorldTask(instance, "导入存档…") { temp ->
            val input = context.contentResolver.openInputStream(uri)
                ?: throw RuntimeException("打不开所选文件")
            input.use { ins -> temp.outputStream().use { outs -> ins.copyTo(outs, 128 * 1024) } }
            WorldManager.importZip(instance, temp, displayName.orEmpty())
        }
    }

    fun downloadWorld(instance: ServerInstance, url: String, nameHint: String) {
        _worlds.value = _worlds.value.copy(busy = true, progress = 0f, message = "下载存档…", error = null)
        scope.launch(Dispatchers.IO) {
            try {
                val world = WorldManager.downloadZip(instance, url.trim(), nameHint.trim()) { p, m ->
                    _worlds.value = _worlds.value.copy(progress = p.coerceIn(0f, 1f), message = m)
                }
                refreshWorlds(instance)
                _worlds.value = _worlds.value.copy(
                    busy = false,
                    progress = 1f,
                    message = "已下载「${world.displayName}」，可在列表里切换",
                )
            } catch (e: Exception) {
                _worlds.value = _worlds.value.copy(busy = false, error = e.message ?: "下载失败")
            }
        }
    }

    private fun runWorldTask(instance: ServerInstance, initial: String, block: (File) -> WorldInfo) {
        _worlds.value = _worlds.value.copy(busy = true, progress = 0f, message = initial, error = null)
        scope.launch(Dispatchers.IO) {
            val temp = File(instance.dir, ".kaze-world-import.zip")
            try {
                val world = block(temp)
                refreshWorlds(instance)
                _worlds.value = _worlds.value.copy(
                    busy = false,
                    progress = 1f,
                    message = "已导入「${world.displayName}」",
                )
            } catch (e: Exception) {
                _worlds.value = _worlds.value.copy(busy = false, error = e.message ?: "导入失败")
            } finally {
                runCatching { temp.delete() }
            }
        }
    }

    fun activateWorld(instance: ServerInstance, world: WorldInfo) {
        _worlds.value = _worlds.value.copy(busy = true, message = "切换世界…", error = null)
        scope.launch(Dispatchers.IO) {
            try {
                WorldManager.activate(instance, world)
                refreshWorlds(instance)
                _worlds.value = _worlds.value.copy(
                    busy = false,
                    message = "已切换到「${world.displayName}」（旧世界已备份为 world-backup-*）",
                )
            } catch (e: Exception) {
                _worlds.value = _worlds.value.copy(busy = false, error = e.message ?: "切换失败")
            }
        }
    }

    fun deleteWorld(instance: ServerInstance, world: WorldInfo) {
        scope.launch(Dispatchers.IO) {
            val ok = WorldManager.delete(instance, world)
            refreshWorlds(instance)
            _worlds.value = _worlds.value.copy(
                message = if (ok) "已删除「${world.displayName}」" else "当前世界不能删除（请先切换）",
            )
        }
    }

    private fun refreshWorlds(instance: ServerInstance) {
        val list = runCatching { WorldManager.worlds(instance) }.getOrDefault(emptyList())
        _worlds.value = _worlds.value.copy(worlds = list, loading = false)
    }

    // ── 基岩版 ──

    data class BedrockUiState(
        val release: BedrockRelease? = null,
        val loadingRelease: Boolean = false,
        val releaseError: String? = null,
        val packs: List<BedrockPack> = emptyList(),
        val packsLoading: Boolean = false,
        val busy: Boolean = false,
        val progress: Float = 0f,
        val message: String = "",
        val error: String? = null,
        /** 刚创建的基岩版实例名（界面提示用） */
        val createdServer: String? = null,
    )

    private val _bedrock = MutableStateFlow(BedrockUiState())
    val bedrock: StateFlow<BedrockUiState> = _bedrock.asStateFlow()

    fun loadBedrock(instance: ServerInstance?, forceRelease: Boolean = false) {
        if (instance != null) loadBedrockPacks(instance)
        if (_bedrock.value.release != null && !forceRelease) return
        _bedrock.value = _bedrock.value.copy(loadingRelease = true, releaseError = null)
        scope.launch(Dispatchers.IO) {
            try {
                val release = BedrockServer.latest()
                _bedrock.value = _bedrock.value.copy(release = release, loadingRelease = false)
            } catch (e: Exception) {
                _bedrock.value = _bedrock.value.copy(
                    loadingRelease = false,
                    releaseError = e.message ?: "获取基岩版服务端版本失败",
                )
            }
        }
    }

    fun loadBedrockPacks(instance: ServerInstance) {
        _bedrock.value = _bedrock.value.copy(packsLoading = true)
        scope.launch(Dispatchers.IO) {
            val packs = runCatching { BedrockPacks.packs(instance) }.getOrDefault(emptyList())
            _bedrock.value = _bedrock.value.copy(packs = packs, packsLoading = false)
        }
    }

    fun importBedrockPack(instance: ServerInstance, uri: Uri, displayName: String?) {
        _bedrock.value = _bedrock.value.copy(busy = true, message = "导入附加包…", error = null, createdServer = null)
        scope.launch(Dispatchers.IO) {
            val temp = File(instance.dir, ".kaze-addon-import.zip")
            try {
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw RuntimeException("打不开所选文件")
                input.use { ins -> temp.outputStream().use { outs -> ins.copyTo(outs, 128 * 1024) } }
                val report = BedrockPacks.import(instance, temp, displayName.orEmpty())
                val packs = BedrockPacks.packs(instance)
                _bedrock.value = _bedrock.value.copy(
                    busy = false,
                    packs = packs,
                    message = if (report.imported.isEmpty()) {
                        report.failed.firstOrNull()?.reason ?: "没有导入任何包"
                    } else {
                        "已导入 ${report.imported.size} 个附加包：${report.imported.joinToString("、") { it.name }}"
                    },
                    error = if (report.imported.isEmpty()) report.failed.firstOrNull()?.reason else null,
                )
            } catch (e: Exception) {
                _bedrock.value = _bedrock.value.copy(busy = false, error = e.message ?: "导入失败")
            } finally {
                runCatching { temp.delete() }
            }
        }
    }

    /** 启用/停用一个附加包：写世界目录的 world_*_packs.json */
    fun toggleBedrockPack(instance: ServerInstance, pack: BedrockPack) {
        _bedrock.value = _bedrock.value.copy(busy = true, error = null)
        scope.launch(Dispatchers.IO) {
            val current = BedrockPacks.packs(instance)
            val target = current.firstOrNull { it.uuid == pack.uuid && it.kind == pack.kind }
            val updated = current.map {
                if (it.uuid == target?.uuid && it.kind == target?.kind) it.copy(active = !it.active) else it
            }
            val ok = BedrockPacks.applyActive(instance, updated)
            val packs = BedrockPacks.packs(instance)
            _bedrock.value = _bedrock.value.copy(
                busy = false,
                packs = packs,
                message = if (ok) {
                    "已${if (target?.active != true) "启用" else "停用"}「${pack.name}」（重启服务端生效）"
                } else {
                    "写入失败：当前世界目录不存在（先启动一次服务端生成世界）"
                },
            )
        }
    }

    fun deleteBedrockPack(instance: ServerInstance, pack: BedrockPack) {
        scope.launch(Dispatchers.IO) {
            val ok = BedrockPacks.delete(pack)
            val packs = BedrockPacks.packs(instance)
            _bedrock.value = _bedrock.value.copy(packs = packs, message = if (ok) "已删除「${pack.name}」" else "删除失败")
        }
    }

    /**
     * 下载 PowerNukkitX 并创建一个基岩版实例。
     *
     * 实例用 `CUSTOM` 核心 + `bedrock = true`：启动路径与"导入 jar"完全一致，
     * 不需要为基岩版再写一套启动流程。
     */
    fun createBedrockServer(name: String, memoryMb: Int = 2048, javaMajor: Int = 21) {
        val release = _bedrock.value.release ?: run {
            _bedrock.value = _bedrock.value.copy(error = "还没有拿到基岩版服务端版本信息")
            return
        }
        if (_bedrock.value.busy) return
        _bedrock.value = _bedrock.value.copy(busy = true, progress = 0f, message = "准备下载…", error = null, createdServer = null)
        scope.launch(Dispatchers.IO) {
            try {
                val safeName = name.trim().ifBlank { "基岩服" }
                val dir = instanceStore.createInstanceDir(safeName)
                val instance = ServerInstance(
                    name = safeName,
                    coreType = CoreType.CUSTOM,
                    mcVersion = release.minecraftVersion.ifBlank { "基岩版" },
                    javaMajor = javaMajor,
                    memoryMb = memoryMb,
                    // 不传 nogui：PowerNukkitX 的参数与 Java 版服务端不同，多传反而可能起不来
                    nogui = false,
                    dir = dir,
                    coreDownloadUrl = release.jarUrl,
                    bedrock = true,
                )
                BedrockServer.download(instance, release) { p, m ->
                    _bedrock.value = _bedrock.value.copy(progress = p.coerceIn(0f, 1f), message = m)
                }
                // 基岩版默认端口 19132（UDP）；先写一份最小配置，首次启动后 PNX 会补齐其余键
                val props = ServerProperties.load(dir)
                if (props.isEmpty()) {
                    ServerProperties.save(
                        dir,
                        linkedMapOf(
                            "server-port" to BedrockServer.DEFAULT_PORT.toString(),
                            "motd" to safeName,
                            "online-mode" to "false",
                            "max-players" to "20",
                            "level-name" to "world",
                        ),
                    )
                }
                instanceStore.add(instance)
                onInstanceCreated(instance)
                _bedrock.value = _bedrock.value.copy(
                    busy = false,
                    progress = 1f,
                    message = "已创建「${safeName}」（PowerNukkitX ${release.version} · 基岩版 ${release.minecraftVersion}）",
                    createdServer = safeName,
                )
            } catch (e: Exception) {
                _bedrock.value = _bedrock.value.copy(busy = false, error = e.message ?: "创建基岩版服务端失败")
            }
        }
    }

    fun clearMessages() {
        _bedrock.value = _bedrock.value.copy(message = "", error = null)
        _worlds.value = _worlds.value.copy(message = "", error = null)
    }

    // ── 推荐理由（界面展示）──
    fun reasonOf(kind: ContentKind, slug: String): String = RecommendedProjects.reasonOf(kind, slug)
}
