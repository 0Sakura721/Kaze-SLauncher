package com.kaze.newage.core.addons

import com.kaze.newage.core.content.ContentSources
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Modrinth 搜索结果条目 */
@Serializable
data class ModrinthSearchHit(
    val project_id: String = "",
    val title: String = "",
    val description: String = "",
    val project_type: String = "",
    val slug: String = "",
    val icon_url: String = "",
    val downloads: Int = 0,
    /** 搜索接口会带回分类与支持的游戏版本：界面据此显示「64x」「优化」之类的标签 */
    val categories: List<String> = emptyList(),
    val versions: List<String> = emptyList(),
    val latest_version: String = "",
    val author: String = "",
)

@Serializable
data class ModrinthSearchResult(
    val hits: List<ModrinthSearchHit> = emptyList(),
    val offset: Int = 0,
    val limit: Int = 0,
    val total_hits: Int = 0,
)

/**
 * 项目详情（`/v2/project/{id}` 与 `/v2/projects?ids=[...]` 的返回）。
 *
 * 推荐列表用批量接口一次拿十几个项目的图标/标题/下载量，
 * 不走搜索接口（搜索按关键词查，拿不到"我指定的那几个项目"）。
 */
@Serializable
data class ModrinthProject(
    val id: String = "",
    val slug: String = "",
    val title: String = "",
    val description: String = "",
    val project_type: String = "",
    val icon_url: String = "",
    val downloads: Long = 0,
    val followers: Long = 0,
    val categories: List<String> = emptyList(),
    val game_versions: List<String> = emptyList(),
    val loaders: List<String> = emptyList(),
    /** required / optional / unsupported —— 用来判断"这个项目的服务端到底需不需要" */
    val client_side: String = "",
    val server_side: String = "",
    /** 项目详情的 Markdown 正文（`/project/{id}` 才返回，列表接口为空） */
    val body: String = "",
) {
    val webUrl: String get() = "https://modrinth.com/${project_type.ifBlank { "mod" }}/$slug"

    /**
     * 客户端专属：装到**服务端**会直接崩或毫无作用。
     *
     * 两条独立证据（任一成立即判定）：
     *  - `server_side = unsupported`：作者明确不支持服务端；
     *  - `client_side = required`：必须装在客户端才能工作（Sodium/Iris/ModMenu 之类）。
     */
    val clientOnly: Boolean
        get() = server_side.equals("unsupported", ignoreCase = true) ||
            client_side.equals("required", ignoreCase = true)
}

@Serializable
data class ModrinthFile(
    val url: String = "",
    val filename: String = "",
    val primary: Boolean = false,
)

@Serializable
data class ModrinthVersion(
    val id: String = "",
    /** 所属项目 id —— `/version_file/{hash}` 回查时用它知道 jar 属于哪个项目 */
    val project_id: String = "",
    val version_number: String = "",
    val game_versions: List<String> = emptyList(),
    val loaders: List<String> = emptyList(),
    val files: List<ModrinthFile> = emptyList(),
)

/** 附加组件类型：插件（Paper 系）或模组（Fabric/Forge 系） */
enum class AddonKind(val dirName: String, val modrinthType: String) {
    PLUGIN("plugins", "plugin"),
    MOD("mods", "mod"),
}

/**
 * Modrinth v2 API 客户端（开放 API，无需密钥）。
 * 搜索与版本列表；下载走 CDN（交给 Downloader）。
 * 文档：https://docs.modrinth.com
 */
object ModrinthApi {

    /** 当前生效的源（成功请求过的那个）；界面用它显示"正在用哪个源" */
    @Volatile
    private var activeBase: String = ContentSources.OFFICIAL_URL

    val base: String get() = activeBase

    fun activeSourceLabel(): String = ContentSources.labelOf(activeBase)
    private const val USER_AGENT = "KazeSLauncher/3.0 (com.kaze.newage; server launcher)"
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 搜索项目。
     *
     * facet 只按 **project_type** 过滤，不写死 `categories:paper`。
     *
     * 原来插件搜索固定带 `["categories:paper"]`：Modrinth 的 categories facet 要求项目**显式**
     * 勾了该分类，而 Spigot / Purpur / 通用 Bukkit 插件基本不会勾 paper —— 于是 Spigot、Purpur
     * 实例搜「EssentialsX」之类能跑得很好的插件，结果为空，用户以为"没有这个插件"。
     * 按加载器筛选发生在 [versions]（安装时按实例核心取 paper/spigot/purpur/fabric/forge/neoforge），
     * 不支持当前核心的项目会在那一步给出明确提示，不会装错。
     */
    fun search(query: String, kind: AddonKind, limit: Int = 20): List<ModrinthSearchHit> {
        val facets = when (kind) {
            AddonKind.PLUGIN -> """[["project_type:plugin"]]"""
            AddonKind.MOD -> """[["project_type:mod"]]"""
        }
        val url = "search?query=${enc(query)}&limit=$limit&index=relevance&facets=${enc(facets)}"
        val body = get(url)
        return json.decodeFromString<ModrinthSearchResult>(body).hits
    }

    /** 项目版本列表（按加载器过滤） */
    fun versions(projectId: String, loader: String, gameVersion: String? = null): List<ModrinthVersion> {
        val gamePart = gameVersion?.let { """&game_versions=${enc("[\"$it\"]")}""" } ?: ""
        val url = "project/$projectId/version?loaders=${enc("[\"$loader\"]")}$gamePart"
        val body = try {
            getFresh(url)
        } catch (e: Exception) {
            if (e.message?.contains("HTTP 400") == true) getFresh("project/$projectId/version") else throw e
        }
        return json.decodeFromString<List<ModrinthVersion>>(body)
    }

    /**
     * 按文件哈希反查版本（Modrinth `/version_file/{hash}`）。
     *
     * 用途：识别**台账里没有**的存量插件 —— 只要这个 jar 是从 Modrinth 下的，就能反查
     * 出项目与版本，于是老用户不重装也能获得更新检查。
     *
     * 注意：查不到时 Modrinth 返回 404，这里让它抛出去，由调用方当作"识别不了"处理
     * （不要吞成空对象，否则会被误认为"识别成功但项目为空"）。
     */
    fun versionByHash(hash: String, algorithm: String = "sha512"): ModrinthVersion {
        if (hash.isBlank()) throw RuntimeException("哈希为空")
        val body = get("version_file/$hash?algorithm=$algorithm")
        return json.decodeFromString<ModrinthVersion>(body)
    }

    /**
     * 内容中心用的通用搜索：按项目类型 + 加载器 + 游戏版本过滤。
     *
     * 与 [search] 的区别是 [sort] 可切换（相关度 / 下载最多 / 最新）且能翻页，
     * 资源包/光影这类"没有加载器"的类型走同一条路径（[loaders] 传空即可）。
     *
     * facets 的语义要小心：**同一个内层数组是"或"、数组之间是"且"**。
     * 所以多个加载器要塞进同一个数组（Fabric 或 NeoForge 都行），
     * 而"版本"必须另起一个数组（否则就变成"版本或加载器"了）。
     */
    fun searchContent(
        query: String,
        projectType: String,
        loaders: List<String> = emptyList(),
        gameVersion: String? = null,
        sort: String = "relevance",
        limit: Int = 20,
        offset: Int = 0,
    ): ModrinthSearchResult {
        val groups = mutableListOf<List<String>>()
        groups += listOf("project_type:$projectType")
        if (loaders.isNotEmpty()) groups += loaders.map { "categories:$it" }
        gameVersion?.takeIf { it.isNotBlank() }?.let { groups += listOf("versions:$it") }
        val facetsJson = groups.joinToString(",", prefix = "[", postfix = "]") { group ->
            group.joinToString(",", prefix = "[", postfix = "]") { "\"$it\"" }
        }
        val url = "search?query=${enc(query)}&limit=$limit&offset=$offset" +
            "&index=$sort&facets=${enc(facetsJson)}"
        val body = try {
            get(url)
        } catch (e: Exception) {
            // 某些镜像/版本会对 facets 组合返回 400；退化掉过滤条件再搜一次，
            // 宁可结果不准，也不让整个搜索页只显示一条 400。
            if (e.message?.contains("HTTP 400") == true) {
                get("search?query=${enc(query)}&limit=$limit&offset=$offset&index=$sort")
            } else {
                throw e
            }
        }
        return json.decodeFromString<ModrinthSearchResult>(body)
    }

    /** 单个项目详情（接受项目 id 或 slug） */
    fun project(idOrSlug: String): ModrinthProject {
        if (idOrSlug.isBlank()) throw RuntimeException("项目 id 为空")
        return json.decodeFromString<ModrinthProject>(get("project/${enc(idOrSlug)}"))
    }

    /**
     * 批量取项目（推荐列表用）：一次请求拿十几个项目的图标与标题。
     *
     * `/v2/projects?ids=[...]` **同时接受 id 与 slug**（实测），所以推荐表可以按 slug 维护，
     * 比记住一长串项目 id 可读得多。查不到的项目会被服务端直接略过，不会让整批失败。
     */
    fun projects(idsOrSlugs: List<String>): List<ModrinthProject> {
        val cleaned = idsOrSlugs.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (cleaned.isEmpty()) return emptyList()
        val arr = cleaned.joinToString(",", prefix = "[", postfix = "]") { "\"${it.replace("\"", "")}\"" }
        return json.decodeFromString<List<ModrinthProject>>(get("projects?ids=${enc(arr)}"))
    }

    /**
     * 资源包 / 光影的版本列表：这两类没有"加载器"概念，只按游戏版本过滤，
     * 传 loaders 会被服务端当成"没有匹配"（这正是之前拿不到资源包版本的原因）。
     */
    fun versionsForPack(projectId: String, gameVersion: String?): List<ModrinthVersion> {
        val gv = gameVersion?.takeIf { it.isNotBlank() }
        val url = if (gv == null) {
            "project/$projectId/version"
        } else {
            "project/$projectId/version?game_versions=${enc("[\"$gv\"]")}"
        }
        val body = try {
            getFresh(url)
        } catch (e: Exception) {
            if (e.message?.contains("HTTP 400") == true) getFresh("project/$projectId/version") else throw e
        }
        return json.decodeFromString<List<ModrinthVersion>>(body)
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /**
     * 带**源回退**的 GET：按 [ContentSources.active] 的顺序尝试，第一个成功即返回。
     *
     * 这是"资源中心显示没有可用条目"的根治点：以前写死官方域名，网络不通就整页空；
     * 现在官方超时后会自动换 MCIM 镜像，并把成功的那一个记为 [activeBase]（界面可显示）。
     */
    /**
     * 版本/下载地址优先走**官方源**：镜像的版本列表可能滞后（实测 MCIM 返回的
     * Sodium 还是 0.5.11，官方已经是 0.8.x），旧版本的文件地址在 CDN 上可能 404。
     * 官方失败再退回 [get] 的多源轮询。
     */
    private fun getFresh(path: String): String {
        val clean = if (path.startsWith("/")) path else "/$path"
        return try {
            httpGet(ContentSources.join(ContentSources.OFFICIAL_URL, clean))
        } catch (e: Exception) {
            get(clean)
        }
    }
    private fun get(pathOrUrl: String): String {
        // 关键：baseUrl 以 `/v2` 结尾，path 必须以 `/` 开头。
        // 之前调用点写成 "project/..."（丢了开头的斜杠）时拼出来是 `.../v2project/...`，
        // Modrinth 一律 404 —— 这正是"资源中心一直 HTTP 404"的根因。
        val rawPath = if (pathOrUrl.startsWith("http")) ContentSources.pathOf(pathOrUrl) else pathOrUrl
        val path = if (rawPath.startsWith("/")) rawPath else "/$rawPath"
        val sources = ContentSources.active()
        var lastError: Exception? = null
        for (source in sources) {
            try {
                val body = httpGet(ContentSources.join(source.baseUrl, path))
                if (activeBase != source.baseUrl) activeBase = source.baseUrl
                return body
            } catch (e: Exception) {
                lastError = e
            }
        }
        val reason = lastError?.message ?: "网络错误"
        val hint = if (reason.contains("404")) {
            "（接口或项目不存在；镜像可能未收录该接口，可在右上角切换内容源）"
        } else {
            ""
        }
        throw RuntimeException(
            "所有内容源都不可用（${sources.joinToString(" / ") { it.label }}）：$reason$hint",
            lastError,
        )
    }

    private fun httpGet(urlStr: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 12000
            conn.readTimeout = 20000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            val code = conn.responseCode
            if (code != 200) {
                // 带上服务端返回的正文：Modrinth 的 400 会说清楚哪个参数不合法，
                // 只报 "HTTP 400" 根本没法排查问题。
                val detail = runCatching {
                    conn.errorStream?.bufferedReader()?.use { it.readText().take(200) }
                }.getOrNull()?.replace("\n", " ")?.trim()
                throw RuntimeException(
                    "HTTP $code" + (detail?.takeIf { it.isNotBlank() }?.let { "：$it" } ?: ""),
                )
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
