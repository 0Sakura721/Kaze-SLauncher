package com.mcserver.launcher.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * CurseForge API 管理器。
 *
 * API Key 获取方式：CurseForge for Studios 控制台免费注册即可获得。
 * 此 Key 仅用于模组/插件搜索和下载，不涉及用户身份认证。
 */
class CurseForgeManager(private var apiKey: String) {

    companion object {
        @Volatile
        var instance: CurseForgeManager? = null
            private set

        fun initialize(apiKey: String) {
            if (instance == null) {
                synchronized(this) {
                    if (instance == null) {
                        instance = CurseForgeManager(apiKey)
                    }
                }
            } else {
                instance?.apiKey = apiKey
            }
        }
    }

    private val BASE_URL = "https://api.curseforge.com/v1"
    private val MINECRAFT_GAME_ID = 432

    /** 模组加载器类型 */
    enum class ModLoaderType(val id: Int) {
        ANY(0),
        FORGE(1),
        CAULDRON(2),
        LITE_LOADER(3),
        FABRIC(4),
        QUILT(5),
        NEO_FORGE(6)
    }

    /** 文件类型 */
    enum class FileType(val id: Int) {
        MOD(0),
        MODPACK(1),
        RESOURCE_PACK(2),
        WORLD(3),
        PLUGIN(5),
        DATA_PACK(6)
    }

    /** 搜索结果模组信息 */
    data class CfMod(
        val id: Int,
        val name: String,
        val summary: String,
        val slug: String,
        val downloadCount: Long,
        val categories: List<String>,
        val logoUrl: String?,
        val links: Map<String, String>
    )

    /** 模组文件 */
    data class CfFile(
        val id: Int,
        val fileName: String,
        val fileSize: Long,
        val downloadUrl: String?,
        val gameVersions: List<String>,
        val modLoaderType: ModLoaderType,
        val isServerPack: Boolean
    )

    // ── 搜索 ──
    suspend fun searchMods(
        query: String,
        gameVersion: String? = null,
        modLoaderType: ModLoaderType = ModLoaderType.ANY,
        fileType: FileType = FileType.MOD,
        pageSize: Int = 25,
        index: Int = 0
    ): Result<List<CfMod>> = withContext(Dispatchers.IO) {
        try {
            val params = mutableListOf(
                "gameId=$MINECRAFT_GAME_ID",
                "classId=${fileType.id}",
                "searchFilter=${URLEncoder.encode(query, "UTF-8")}",
                "sortField=2",      // 按下载量排序
                "sortOrder=desc",
                "pageSize=$pageSize",
                "index=$index"
            )
            if (gameVersion != null) params.add("gameVersion=${URLEncoder.encode(gameVersion, "UTF-8")}")
            if (modLoaderType != ModLoaderType.ANY) params.add("modLoaderType=${modLoaderType.id}")

            val urlStr = "$BASE_URL/mods/search?${params.joinToString("&")}"
            val json = apiGet(urlStr)
            val data = json.getJSONArray("data")
            val mods = (0 until data.length()).map { i ->
                val mod = data.getJSONObject(i)
                val categories = mutableListOf<String>()
                val catsJson = mod.optJSONArray("categories")
                if (catsJson != null) {
                    for (j in 0 until catsJson.length()) {
                        catsJson.getJSONObject(j).optString("name")?.let { categories.add(it) }
                    }
                }
                val links = mutableMapOf<String, String>()
                mod.optJSONObject("links")?.let { linksObj ->
                    linksObj.keys().forEach { key -> links[key] = linksObj.optString(key, "") }
                }
                CfMod(
                    id = mod.getInt("id"),
                    name = mod.getString("name"),
                    summary = mod.optString("summary", ""),
                    slug = mod.optString("slug", ""),
                    downloadCount = mod.optLong("downloadCount", 0),
                    categories = categories,
                    logoUrl = mod.optJSONObject("logo")?.optString("thumbnailUrl"),
                    links = links
                )
            }
            Result.success(mods)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── 获取模组文件列表 ──
    suspend fun getModFiles(
        modId: Int,
        gameVersion: String? = null,
        modLoaderType: ModLoaderType = ModLoaderType.ANY
    ): Result<List<CfFile>> = withContext(Dispatchers.IO) {
        try {
            val params = mutableListOf("pageSize=50")
            if (gameVersion != null) params.add("gameVersion=${URLEncoder.encode(gameVersion, "UTF-8")}")
            if (modLoaderType != ModLoaderType.ANY) params.add("modLoaderType=${modLoaderType.id}")

            val urlStr = "$BASE_URL/mods/$modId/files?${params.joinToString("&")}"
            val json = apiGet(urlStr)
            val data = json.getJSONArray("data")
            val files = (0 until data.length()).mapNotNull { i ->
                val file = data.getJSONObject(i)
                val gameVersions = mutableListOf<String>()
                val gvArr = file.optJSONArray("gameVersions")
                if (gvArr != null) {
                    for (j in 0 until gvArr.length()) gvArr.getString(j)?.let { gameVersions.add(it) }
                }
                // 优先选择服务端包（isServerPack）
                CfFile(
                    id = file.getInt("id"),
                    fileName = file.getString("fileName"),
                    fileSize = file.optLong("fileLength", 0),
                    downloadUrl = file.optString("downloadUrl", null),
                    gameVersions = gameVersions,
                    modLoaderType = ModLoaderType.entries
                        .find { it.id == file.optInt("modLoaderType", 0) } ?: ModLoaderType.ANY,
                    isServerPack = file.optBoolean("isServerPack", false)
                )
            }
            Result.success(files)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── 获取 Minecraft 版本列表 ──
    suspend fun getMinecraftVersions(): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val urlStr = "$BASE_URL/minecraft/version"
            val json = apiGet(urlStr)
            val data = json.getJSONArray("data")
            val versions = (0 until data.length()).map { i ->
                data.getJSONObject(i).getString("versionString")
            }
            Result.success(versions)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── HTTP GET ──
    private fun apiGet(urlStr: String): JSONObject {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("x-api-key", apiKey)
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("User-Agent", "MCServerLauncher/1.0")
        conn.connectTimeout = 10000
        conn.readTimeout = 15000

        val code = conn.responseCode
        if (code != HttpURLConnection.HTTP_OK) {
            val errText = conn.errorStream?.bufferedReader()?.readText() ?: ""
            throw RuntimeException("CurseForge API HTTP $code: $errText")
        }

        val body = conn.inputStream.bufferedReader().readText()
        conn.disconnect()
        return JSONObject(body)
    }

    // ── 下载模组文件 ──
    suspend fun downloadModFile(
        downloadUrl: String,
        destPath: String,
        onProgress: (Float, Long, Long, Long) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val url = URL(downloadUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "MCServerLauncher/1.0")
            conn.connectTimeout = 10000
            conn.readTimeout = 60000

            var currentConn = conn
            var redirects = 0
            while (redirects < 5) {
                val code = currentConn.responseCode
                if (code == HttpURLConnection.HTTP_MOVED_TEMP ||
                    code == HttpURLConnection.HTTP_MOVED_PERM ||
                    code == 307 || code == 308
                ) {
                    val newUrl = currentConn.getHeaderField("Location") ?: break
                    currentConn.disconnect()
                    currentConn = URL(newUrl).openConnection() as HttpURLConnection
                    currentConn.setRequestProperty("User-Agent", "MCServerLauncher/1.0")
                    redirects++
                    continue
                }
                break
            }

            val total = currentConn.contentLengthLong
            val buffer = ByteArray(8192)
            var downloaded = 0L
            val startTime = System.currentTimeMillis()

            currentConn.inputStream.use { input ->
                java.io.FileOutputStream(destPath).use { output ->
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        val elapsed = (System.currentTimeMillis() - startTime).coerceAtLeast(1)
                        val progress = if (total > 0) downloaded.toFloat() / total else 0f
                        val speed = downloaded * 1000 / elapsed
                        onProgress(progress, downloaded, total, speed)
                    }
                }
            }
            currentConn.disconnect()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}