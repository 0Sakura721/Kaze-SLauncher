package com.mcserver.launcher.core.download

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Modrinth 搜索结果 */
data class ModrinthHit(
    val projectId: String,
    val slug: String,
    val title: String,
    val description: String,
    val versionType: String
)

/**
 * Modrinth 模组/插件搜索(在线备选;默认引导本地导入)。
 * API 免费无需 key: https://docs.modrinth.com/api
 */
object ModrinthApi {

    private fun httpGet(urlStr: String): String {
        var conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.setRequestProperty("User-Agent", "KazeSLauncher/2.0 (Android; Minecraft Server Launcher)")
        if (conn.responseCode != HttpURLConnection.HTTP_OK) throw RuntimeException("HTTP ${conn.responseCode}")
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        return text
    }

    /**
     * 搜索插件/模组。
     * @param query 关键字
     * @param mcVersion MC 版本(如 1.21.1)
     * @param loader fabric/forge/neoforge/paper/spigot(插件端用 paper)
     */
    suspend fun search(query: String, mcVersion: String, loader: String): Result<List<ModrinthHit>> =
        withContext(Dispatchers.IO) {
            try {
                val facets = JSONArray()
                facets.put(JSONArray().put("versions:$mcVersion"))
                facets.put(JSONArray().put("categories:$loader"))
                val url = "https://api.modrinth.com/v2/search?query=${java.net.URLEncoder.encode(query, "UTF-8")}" +
                    "&facets=${java.net.URLEncoder.encode(facets.toString(), "UTF-8")}&limit=20"
                val json = JSONObject(httpGet(url))
                val hits = json.getJSONArray("hits")
                Result.success((0 until hits.length()).map {
                    val o = hits.getJSONObject(it)
                    ModrinthHit(
                        projectId = o.getString("project_id"),
                        slug = o.optString("slug", ""),
                        title = o.optString("title", ""),
                        description = o.optString("description", ""),
                        versionType = o.optString("project_type", "")
                    )
                })
            } catch (e: Exception) { Result.failure(e) }
        }

    /** 获取项目指定 MC 版本+加载器的第一个可用版本下载链接 */
    suspend fun resolveDownload(projectId: String, mcVersion: String, loader: String): Result<CoreDownload> =
        withContext(Dispatchers.IO) {
            try {
                val url = "https://api.modrinth.com/v2/project/$projectId/version?game_versions=%5B%22$mcVersion%22%5D" +
                    "&loaders=%5B%22$loader%22%5D"
                val arr = JSONArray(httpGet(url))
                if (arr.length() == 0) return@withContext Result.failure(RuntimeException("该版本没有可用文件"))
                val first = arr.getJSONObject(0)
                val files = first.getJSONArray("files")
                val file = files.getJSONObject(0)
                Result.success(CoreDownload(file.getString("url"), file.getString("filename")))
            } catch (e: Exception) { Result.failure(e) }
        }
}
