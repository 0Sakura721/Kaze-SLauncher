package com.mcserver.launcher.core.content

import com.mcserver.launcher.util.KLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** 内容分类（对标 Fold Craft Launcher） */
enum class ContentKind(val label: String, val modrinthTypes: List<String>) {
    GAME("游戏", listOf("modpack")),
    MOD("模组", listOf("mod", "plugin")),
    RESOURCE("资源包", listOf("resourcepack", "shader")),
    WORLD("世界", listOf("datapack")),
}

/** 版本类型 */
enum class VersionKind(val label: String) {
    ALL("全部"),
    RELEASE("正式版"),
    SNAPSHOT("测试版"),
    OLD_BETA("远古版"),
    APRIL("愚人节"),
}

data class ContentItem(
    val slug: String,
    val title: String,
    val author: String,
    val downloads: Int,
    val description: String,
    val projectType: String,
)

/**
 * Modrinth 内容源（免费、无需 key）。
 * 搜索用 facets 过滤项目类型与游戏版本；版本类型映射 Mojang 版本标签。
 */
object ModrinthApi {

    private const val BASE = "https://api.modrinth.com/v2"
    private const val UA = "Kaze-SLauncher/2.0 (android)"

    /** 愚人节版本标签（Mojang 官方彩蛋版本） */
    private val APRIL_VERSIONS = listOf(
        "1.RV-Pre1", "3D Shareware v1.34", "20w14infinite",
        "22w13oneBlockAtATime", "23w13a_or_b", "24w14potato",
    )

    suspend fun latestRelease(): String? = mojangManifest()?.optJSONObject("latest")?.optString("release").takeIf { it.isNotBlank() }

    suspend fun latestSnapshot(): String? = mojangManifest()?.optJSONObject("latest")?.optString("snapshot").takeIf { it.isNotBlank() }

    private suspend fun mojangManifest(): JSONObject? = withContext(Dispatchers.IO) {
        try {
            val conn = URL("https://launchermeta.mojang.com/mc/game/version_manifest_v2.json").openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", UA)
            if (conn.responseCode == 200) {
                val obj = JSONObject(conn.inputStream.bufferedReader().readText())
                conn.disconnect()
                obj
            } else {
                conn.disconnect()
                null
            }
        } catch (e: Exception) {
            KLog.w("Mojang manifest 请求失败: ${e.message}")
            null
        }
    }

    /** 搜索内容 */
    suspend fun search(
        kind: ContentKind,
        versionKind: VersionKind,
        query: String,
        offset: Int = 0,
        limit: Int = 20,
    ): List<ContentItem> = withContext(Dispatchers.IO) {
        try {
            val facets = mutableListOf<JSONArray>()
            facets.add(JSONArray(kind.modrinthTypes.map { "project_type:$it" }))
            val versions = versionFacets(versionKind)
            if (versions.isNotEmpty()) {
                facets.add(JSONArray(versions.map { "versions:$it" }))
            }
            val facetsJson = JSONArray(facets).toString()
            val url = BASE + "/search?query=" + URLEncoder.encode(query, "UTF-8") +
                "&index=downloads&limit=$limit&offset=$offset" +
                "&facets=" + URLEncoder.encode(facetsJson, "UTF-8")
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", UA)
            if (conn.responseCode != 200) {
                conn.disconnect()
                return@withContext emptyList()
            }
            val hits = JSONObject(conn.inputStream.bufferedReader().readText()).optJSONArray("hits") ?: JSONArray()
            conn.disconnect()
            (0 until hits.length()).map { i ->
                val h = hits.getJSONObject(i)
                ContentItem(
                    slug = h.optString("slug"),
                    title = h.optString("title"),
                    author = h.optString("author"),
                    downloads = h.optInt("downloads"),
                    description = h.optString("description"),
                    projectType = h.optString("project_type"),
                )
            }
        } catch (e: Exception) {
            KLog.w("Modrinth 搜索失败: ${e.message}")
            emptyList()
        }
    }

    private suspend fun versionFacets(vk: VersionKind): List<String> = when (vk) {
        VersionKind.ALL -> emptyList()
        VersionKind.RELEASE -> listOfNotNull(latestRelease() ?: "1.21.8")
        VersionKind.SNAPSHOT -> listOfNotNull(latestSnapshot())
        VersionKind.OLD_BETA -> listOf("b1.7.3", "a1.2.6")
        VersionKind.APRIL -> APRIL_VERSIONS
    }

    /** 解析可下载文件（url, filename）；无可用文件返回 null */
    suspend fun resolveDownload(item: ContentItem): Pair<String, String>? = withContext(Dispatchers.IO) {
        try {
            val url = "$BASE/project/${item.slug}/version"
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", UA)
            if (conn.responseCode != 200) {
                conn.disconnect()
                return@withContext null
            }
            val arr = JSONArray(conn.inputStream.bufferedReader().readText())
            conn.disconnect()
            if (arr.length() == 0) return@withContext null
            val ver = arr.getJSONObject(0)
            val files = ver.optJSONArray("files") ?: JSONArray()
            var chosen: JSONObject? = null
            for (i in 0 until files.length()) {
                val f = files.getJSONObject(i)
                if (f.optBoolean("primary")) {
                    chosen = f
                    break
                }
                if (chosen == null) chosen = f
            }
            val u = chosen?.optString("url")
            val name = chosen?.optString("filename")
            if (u.isNullOrBlank() || name.isNullOrBlank()) null else u to name
        } catch (e: Exception) {
            KLog.w("Modrinth 版本解析失败: ${e.message}")
            null
        }
    }

    /** 整合包索引（mrpack 内依赖清单），供解压安装使用 */
    data class PackFile(val path: String, val url: String)

    suspend fun resolvePackFiles(packIndexJson: JSONObject): List<PackFile> = withContext(Dispatchers.IO) {
        try {
            val files = packIndexJson.optJSONArray("files") ?: JSONArray()
            (0 until files.length()).mapNotNull { i ->
                val f = files.getJSONObject(i)
                val path = f.optString("path")
                val downloads = f.optJSONArray("downloads")
                val u = downloads?.optString(0)
                if (path.isNotBlank() && !u.isNullOrBlank()) PackFile(path, u) else null
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}