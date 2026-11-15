package com.kaze.newage.core.content

import com.kaze.newage.core.addons.AddonKind
import com.kaze.newage.core.addons.AddonManager
import com.kaze.newage.core.addons.ModrinthVersion
import com.kaze.newage.core.addons.ModrinthApi
import com.kaze.newage.core.addons.ModrinthProject
import com.kaze.newage.core.addons.ModrinthSearchResult
import com.kaze.newage.core.server.ServerProperties
import com.kaze.newage.data.model.CoreType
import com.kaze.newage.data.model.ServerInstance
import com.kaze.newage.util.Downloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 内容中心的数据层：搜索、推荐、下载安装。
 *
 * 三类内容共用一套入口，差异收敛在 [ContentKind] 上：
 *  - 模组/插件复用手上已有的 [AddonManager.install]（含同名替换、来源台账、ZIP 魔数校验）；
 *  - 资源包单独实现，因为它是 `.zip` 而不是 `.jar`，而且装完还有一步"设为服务器资源包"
 *    （写 `server.properties` 的 `resource-pack` / `resource-pack-sha1`，
 *    这样进服的玩家会被要求下载，不需要每人手动装 —— 这才是服务器端装资源包的意义）。
 */
object ContentCatalog {

    /** 排序方式（映射到 Modrinth 的 index 参数） */
    enum class Sort(val index: String, val label: String) {
        Relevance("relevance", "相关度"),
        Downloads("downloads", "下载最多"),
        Newest("newest", "最新发布"),
    }

    data class Installed(
        val file: File,
        val versionName: String = "",
        /** 上游 CDN 地址（资源包"设为服务器资源包"要用它下发） */
        val downloadUrl: String = "",
    )

    private data class Cached(val at: Long, val items: List<ModrinthProject>)

    private val recommendedCache = ConcurrentHashMap<String, Cached>()

    /** 推荐缓存时长：10 分钟内重复进页面不再请求网络 */
    private const val RECOMMENDED_TTL_MS = 10 * 60 * 1000L

    // ── 搜索 ──

    /**
     * 搜索内容。[instance] 用于收紧过滤：
     *  - 模组/插件按当前核心的加载器过滤（Fabric 实例不会搜出 Forge 模组）；
     *  - 所有类型都按实例的 MC 版本过滤（资源包也分版本）。
     * 传 null 表示"不按实例过滤"（全局搜索）。
     */
    fun search(
        kind: ContentKind,
        instance: ServerInstance?,
        query: String,
        sort: Sort = Sort.Relevance,
        offset: Int = 0,
        limit: Int = 20,
        /** true = 不按实例的加载器/MC 版本过滤（"全部版本"开关） */
        includeAllVersions: Boolean = false,
        /** 手动指定的加载器（筛选器）；null = 跟随实例 */
        loaderOverride: String? = null,
        /** 手动指定的 MC 版本（筛选器）；null = 跟随实例 */
        gameVersionOverride: String? = null,
        /** 只保留**服务端可用**的项目（模组默认开启，过滤客户端专属） */
        serverOnly: Boolean = true,
    ): ModrinthSearchResult {
        val loaders = when {
            includeAllVersions -> emptyList()
            !loaderOverride.isNullOrBlank() -> listOf(loaderOverride)
            else -> instance?.let { loadersFor(it, kind) }.orEmpty()
        }
        val gameVersion = when {
            includeAllVersions -> null
            !gameVersionOverride.isNullOrBlank() -> gameVersionOverride
            else -> instance?.mcVersion?.takeIf { it.isNotBlank() && !it.startsWith("bedrock", true) }
        }
        val result = ModrinthApi.searchContent(
            query = query,
            projectType = kind.modrinthType,
            loaders = loaders,
            gameVersion = gameVersion,
            sort = sort.index,
            limit = limit,
            offset = offset,
        )
        // 搜索接口没有 server_side facet：批量补一次项目详情，再剔掉客户端专属。
        // 失败就不过滤（宁可能看到客户端模组，也不让搜索整页空掉）。
        if (!serverOnly || !kind.isAddon || result.hits.isEmpty()) return result
        val clientOnlyIds = runCatching {
            ModrinthApi.projects(result.hits.map { it.project_id })
                .filter { it.clientOnly }
                .map { it.id }
                .toSet()
        }.getOrNull() ?: return result
        return result.copy(hits = result.hits.filter { it.project_id !in clientOnlyIds })
    }

    /**
     * 该实例下搜索时应该带上的加载器。
     *
     * 插件一侧刻意列多个（paper/spigot/bukkit/purpur）：Modrinth 上大量插件只标了 bukkit，
     * 只按 paper 过滤会把它们全部漏掉（这是旧版"搜不到 EssentialsX"的根因）。
     */
    fun loadersFor(instance: ServerInstance, kind: ContentKind): List<String> {
        if (!kind.loaderAware || instance.bedrock) return emptyList()
        return when (kind) {
            ContentKind.MOD -> when (instance.coreType) {
                CoreType.FORGE -> listOf("forge")
                CoreType.NEOFORGE -> listOf("neoforge")
                // 认不出核心类型：不加加载器过滤（宁可多给结果，也不能一个都不给）
                CoreType.CUSTOM -> emptyList()
                else -> listOf("fabric", "quilt")
            }
            ContentKind.PLUGIN -> when (instance.coreType) {
                CoreType.SPIGOT -> listOf("spigot", "bukkit")
                CoreType.PURPUR -> listOf("purpur", "paper", "spigot", "bukkit")
                else -> listOf("paper", "spigot", "bukkit", "purpur")
            }
            ContentKind.RESOURCEPACK -> emptyList()
        }
    }

    // ── 推荐 ──

    /**
     * 推荐列表（带缓存）。
     *
     * 与搜索不同，推荐是**项目级**数据（图标、下载量、简介都来自 `/v2/projects`），
     * 再按实例的加载器与 MC 版本过滤一遍 —— 否则在 1.20.1 的 Forge 服上会推出一堆
     * 只支持 1.21 的 Fabric 模组，点安装必失败。
     */
    fun recommended(
        instance: ServerInstance?,
        kind: ContentKind,
        serverOnly: Boolean = true,
    ): List<ModrinthProject> {
        val key = "${kind.name}|${instance?.coreType?.name.orEmpty()}|${instance?.mcVersion.orEmpty()}|$serverOnly"
        val now = System.currentTimeMillis()
        recommendedCache[key]?.takeIf { now - it.at < RECOMMENDED_TTL_MS }?.let { return it.items }
        val slugs = RecommendedProjects.slugs(kind)
        // 批量接口失败（镜像未收录 / 404 / 网络）时**逐个回退**：只要有一个项目能取到，
        // 推荐区就不该整块空着。逐个取时跳过已下架的项目。
        var all = runCatching { ModrinthApi.projects(slugs) }.getOrElse { emptyList() }
        if (all.isEmpty()) {
            all = slugs.mapNotNull { slug ->
                runCatching { ModrinthApi.project(slug) }.getOrNull()
            }
        }
        // 推荐表里有 Sodium/Iris/ModMenu 这类客户端模组：给服务端推荐时必须剔掉
        val serverSafe = if (serverOnly && kind.isAddon) all.filter { !it.clientOnly } else all
        val filtered = serverSafe.filter { matches(it, instance, kind) }
        // 过滤后为空时**退回全量**：宁可显示"可能不兼容"的推荐，也不要让整页空着
        // （实例版本/加载器千奇百怪，严格的过滤反而会制造"资源中心没有内容"的错觉）。
        val result = if (filtered.isEmpty()) serverSafe else filtered
        // 只缓存非空结果：一次网络失败若缓存了空列表，10 分钟内都会显示"没有可用条目"
        if (result.isNotEmpty()) recommendedCache[key] = Cached(now, result)
        return result
    }

    /** 清缓存（切实例/测试用） */
    fun clearRecommendCache() = recommendedCache.clear()

    private fun matches(project: ModrinthProject, instance: ServerInstance?, kind: ContentKind): Boolean {
        if (instance == null) return true
        if (kind.loaderAware) {
            val loaders = loadersFor(instance, kind)
            if (loaders.isNotEmpty() && project.loaders.isNotEmpty() &&
                project.loaders.none { it.lowercase(Locale.US) in loaders }
            ) {
                return false
            }
        }
        val gv = instance.mcVersion
        if (gv.isNotBlank() && project.game_versions.isNotEmpty() && gv !in project.game_versions) return false
        return true
    }

    // ── 安装 ──

    /**
     * 安装一个项目；模组/插件走 [AddonManager]，资源包走 [installResourcePack]。
     * 阻塞式网络 + 磁盘操作，调用方放 IO 线程（本函数内部已 withContext(IO)）。
     */
    suspend fun install(
        instance: ServerInstance,
        kind: ContentKind,
        project: ModrinthProject,
        /** 指定要装的版本（版本选择对话框）；null = 自动挑最新兼容版本 */
        version: ModrinthVersion? = null,
        onProgress: (Float, String) -> Unit = { _, _ -> },
    ): Installed = withContext(Dispatchers.IO) {
        if (kind.isAddon) {
            val addonKind = if (kind == ContentKind.MOD) AddonKind.MOD else AddonKind.PLUGIN
            val file = if (version != null) {
                AddonManager.installVersion(instance, addonKind, project.id, version, onProgress)
            } else {
                AddonManager.install(
                    instance = instance,
                    kind = addonKind,
                    projectId = project.id,
                    gameVersion = instance.mcVersion.takeIf { it.isNotBlank() },
                    onProgress = onProgress,
                )
            }
            Installed(file)
        } else if (version != null) {
            installResourcePackVersion(instance, project, version, onProgress)
        } else {
            installResourcePack(instance, project, onProgress)
        }
    }

    /**
     * 资源包的可选版本：默认按实例 MC 版本过滤；`includeAll=true` 时不带版本过滤，
     * 让用户能装"没有标这个 MC 版本"的包（很多包其实通用，只是没打标签）。
     */
    fun packVersions(
        projectId: String,
        gameVersion: String?,
        includeAll: Boolean = false,
    ): List<ModrinthVersion> {
        val gv = gameVersion?.takeIf { it.isNotBlank() }
        return if (includeAll || gv == null) {
            ModrinthApi.versionsForPack(projectId, null)
        } else {
            ModrinthApi.versionsForPack(projectId, gv)
        }
    }

    /**
     * 下载资源包到 `<实例>/resourcepacks/`。
     *
     * 版本解析**不能**带 loaders（资源包没有加载器），否则接口返回空列表 ——
     * 这正是 [ModrinthApi.versionsForPack] 存在的原因。
     */
    fun installResourcePack(
        instance: ServerInstance,
        project: ModrinthProject,
        onProgress: (Float, String) -> Unit = { _, _ -> },
    ): Installed {
        val gv = instance.mcVersion.takeIf { it.isNotBlank() }
        val versions = ModrinthApi.versionsForPack(project.id, gv).ifEmpty {
            if (gv == null) emptyList() else ModrinthApi.versionsForPack(project.id, null)
        }
        val version = versions.firstOrNull()
            ?: throw RuntimeException("「${project.title}」没有可下载的版本（可能已下架）")
        return installResourcePackVersion(instance, project, version, onProgress)
    }

    /** 安装资源包的**指定版本**（版本选择对话框用） */
    fun installResourcePackVersion(
        instance: ServerInstance,
        project: ModrinthProject,
        version: ModrinthVersion,
        onProgress: (Float, String) -> Unit = { _, _ -> },
    ): Installed {
        val file = version.files.firstOrNull { it.primary } ?: version.files.firstOrNull()
            ?: throw RuntimeException("「${project.title}」的版本里没有文件")
        val dir = File(instance.dir, ContentKind.RESOURCEPACK.dirName).apply { mkdirs() }
        val name = safeFileName(file.filename, "${project.slug}.zip")
        val dest = File(dir, name)
        val part = File(dir, ".$name.part")
        onProgress(0f, "下载 ${file.filename}（${version.version_number}）…")
        Downloader.download(
            file.url,
            part,
            onProgress = { done, total ->
                val p = if (total > 0) done.toFloat() / total else 0f
                onProgress(p, "下载中 ${human(done)} / ${human(total)}")
            },
            validate = { Downloader.isZip(it) },
        )
        if (dest.exists()) dest.delete()
        if (!part.renameTo(dest)) {
            part.copyTo(dest, overwrite = true)
            part.delete()
        }
        onProgress(1f, "已安装 $name")
        return Installed(dest, version.version_number, file.url)
    }

    /**
     * 把资源包设为**服务器资源包**：写 `server.properties` 的三个键。
     *
     * 用上游 CDN 地址（而不是本地文件）下发：客户端必须能访问该地址，
     * 而 Modrinth 的 CDN 是公网可用的 —— 省掉了"在手机上再起一个文件服务"的麻烦。
     * `resource-pack-sha1` 必须与客户端拿到的字节一致，否则客户端会校验失败并回退原版。
     */
    fun applyServerResourcePack(instance: ServerInstance, pack: Installed, require: Boolean = false): Boolean {
        if (!pack.file.isFile || pack.downloadUrl.isBlank()) return false
        val sha1 = Downloader.sha1Of(pack.file) ?: return false
        val props = ServerProperties.load(instance.dir)
        props["resource-pack"] = pack.downloadUrl
        props["resource-pack-sha1"] = sha1
        props["require-resource-pack"] = if (require) "true" else "false"
        ServerProperties.save(instance.dir, props)
        return true
    }

    /** 取消服务器资源包（恢复原版） */
    fun clearServerResourcePack(instance: ServerInstance) {
        val props = ServerProperties.load(instance.dir)
        props["resource-pack"] = ""
        props["resource-pack-sha1"] = ""
        props.remove("require-resource-pack")
        ServerProperties.save(instance.dir, props)
    }

    /** 该实例已下载的资源包（按修改时间倒序） */
    fun installedPacks(instance: ServerInstance): List<File> =
        File(instance.dir, ContentKind.RESOURCEPACK.dirName)
            .listFiles { f -> f.isFile && f.name.endsWith(".zip", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

    // ── 工具 ──

    private fun safeFileName(raw: String, fallback: String): String {
        val base = File(raw.ifBlank { fallback }).name
            .filterNot { it == '/' || it == '\\' || it == '\u0000' || it.isISOControl() }
            .trim()
        val name = base.ifBlank { fallback }
        return if (name.endsWith(".zip", ignoreCase = true)) name else "$name.zip"
    }

    internal fun human(bytes: Long): String = when {
        bytes < 0 -> "?"
        bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.2f GB", bytes / 1024f / 1024f / 1024f)
        bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024f / 1024f)
        bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024f)
        else -> "$bytes B"
    }
}
