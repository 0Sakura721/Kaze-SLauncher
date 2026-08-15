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

    /** 搜索项目 */
    fun search(query: String, kind: AddonKind, limit: Int = 20): List<ModrinthSearchHit> {
        val facets = when (kind) {
            AddonKind.PLUGIN -> """[["project_type:plugin"],["categories:paper"]]"""
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
