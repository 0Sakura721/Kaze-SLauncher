package com.kaze.newage.core.addons

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
)

@Serializable
data class ModrinthSearchResult(
    val hits: List<ModrinthSearchHit> = emptyList(),
)

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

    private const val BASE = "https://api.modrinth.com/v2"
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
        val url = "$BASE/search?query=${enc(query)}&limit=$limit&index=relevance&facets=${enc(facets)}"
        val body = get(url)
        return json.decodeFromString<ModrinthSearchResult>(body).hits
    }

    /** 项目版本列表（按加载器过滤） */
    fun versions(projectId: String, loader: String, gameVersion: String? = null): List<ModrinthVersion> {
        val gamePart = gameVersion?.let { """&game_versions=${enc("[\"$it\"]")}""" } ?: ""
        val url = "$BASE/project/$projectId/version?loaders=${enc("[\"$loader\"]")}$gamePart"
        val body = get(url)
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
        val body = get("$BASE/version_file/$hash?algorithm=$algorithm")
        return json.decodeFromString<ModrinthVersion>(body)
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun get(urlStr: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            val code = conn.responseCode
            if (code != 200) throw RuntimeException("Modrinth HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
