package com.mcserver.launcher.core.download

import com.mcserver.launcher.data.CoreType
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 版本条目 */
data class CoreVersion(val id: String, val isStable: Boolean = true)

/** 构建条目 */
data class CoreBuild(val id: String, val name: String, val fileName: String? = null)

/** 下载结果:URL + 建议文件名 */
data class CoreDownload(val url: String, val fileName: String)

/**
 * 核心源:统一获取各类型服务端的版本/构建/下载链接。
 * 全部返回失败时由 UI 提示网络问题。
 */
object CoreSources {

    private fun httpGet(urlStr: String): String {
        var conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        var redirects = 0
        while (redirects < 5 && conn.responseCode in listOf(301, 302, 303, 307, 308)) {
            val loc = conn.getHeaderField("Location") ?: break
            conn.disconnect()
            conn = URL(loc).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            redirects++
        }
        if (conn.responseCode != HttpURLConnection.HTTP_OK) throw RuntimeException("HTTP ${conn.responseCode}")
        val text = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        return text
    }

    // ── Vanilla ──
    suspend fun fetchVanillaVersions(): Result<List<CoreVersion>> = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject(httpGet("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"))
            val arr = json.getJSONArray("versions")
            val list = (0 until arr.length()).map { CoreVersion(arr.getJSONObject(it).getString("id")) }
            Result.success(list.filter { !it.id.contains("pre") && !it.id.contains("snapshot") && !it.id.contains("rc") })
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun getVanillaDownload(mcVersion: String): Result<CoreDownload> = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject(httpGet("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"))
            val arr = json.getJSONArray("versions")
            for (i in 0 until arr.length()) {
                val v = arr.getJSONObject(i)
                if (v.getString("id") == mcVersion) {
                    val vJson = JSONObject(httpGet(v.getString("url")))
                    val jar = vJson.getJSONObject("downloads").getJSONObject("server")
                    return@withContext Result.success(CoreDownload(jar.getString("url"), "vanilla-$mcVersion.jar"))
                }
            }
            Result.failure(RuntimeException("未找到版本 $mcVersion"))
        } catch (e: Exception) { Result.failure(e) }
    }

    // ── Paper ──
    suspend fun fetchPaperVersions(): Result<List<CoreVersion>> = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject(httpGet("https://api.papermc.io/v2/projects/paper"))
            val arr = json.getJSONArray("versions")
            Result.success((0 until arr.length()).map { CoreVersion(arr.getString(it)) })
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun fetchPaperBuilds(version: String): Result<List<CoreBuild>> = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject(httpGet("https://api.papermc.io/v2/projects/paper/versions/$version/builds"))
            val arr = json.getJSONArray("builds")
            val list = (0 until arr.length()).map {
                val b = arr.getJSONObject(it)
                val name = b.optJSONObject("downloads")?.optJSONObject("application")?.optString("name")
                    ?: "paper-$version-${b.getInt("build")}.jar"
                CoreBuild(b.getInt("build").toString(), "build #${b.getInt("build")}", name)
            }
            Result.success(list.reversed())
        } catch (e: Exception) { Result.failure(e) }
    }

    fun getPaperDownload(version: String, buildId: String, fileName: String): CoreDownload =
        CoreDownload("https://api.papermc.io/v2/projects/paper/versions/$version/builds/$buildId/downloads/$fileName", fileName)

    // ── Purpur ──
    suspend fun fetchPurpurVersions(): Result<List<CoreVersion>> = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject(httpGet("https://api.purpurmc.org/v2/purpur"))
            val arr = json.getJSONArray("versions")
            Result.success((0 until arr.length()).map { CoreVersion(arr.getString(it)) })
        } catch (e: Exception) { Result.failure(e) }
    }

    fun getPurpurDownload(version: String): CoreDownload =
        CoreDownload("https://api.purpurmc.org/v2/purpur/$version/latest/download", "purpur-$version.jar")

    // ── Fabric ──
    suspend fun fetchFabricVersions(): Result<List<CoreVersion>> = withContext(Dispatchers.IO) {
        try {
            val arr = JSONArray(httpGet("https://meta.fabricmc.net/v2/versions/game"))
            Result.success((0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                CoreVersion(o.getString("version"), o.optBoolean("stable", true))
            }.reversed())
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun getFabricDownload(mcVersion: String): Result<CoreDownload> = withContext(Dispatchers.IO) {
        try {
            val loaderArr = JSONArray(httpGet("https://meta.fabricmc.net/v2/versions/loader/$mcVersion"))
            val loader = loaderArr.getJSONObject(0).getJSONObject("loader").getString("version")
            val installerArr = JSONArray(httpGet("https://meta.fabricmc.net/v2/versions/installer"))
            val installer = installerArr.getJSONObject(0).getString("version")
            Result.success(
                CoreDownload(
                    "https://meta.fabricmc.net/v2/versions/loader/$mcVersion/$loader/$installer/server/jar",
                    "fabric-server-mc.$mcVersion-loader.$loader-launcher.$installer.jar"
                )
            )
        } catch (e: Exception) { Result.failure(e) }
    }

    // ── Forge(需从 maven 索引解析) ──
    suspend fun fetchForgeVersions(): Result<List<CoreVersion>> = withContext(Dispatchers.IO) {
        try {
            val html = httpGet("https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml")
            val versions = Regex("<version>([^<]+)</version>").findAll(html).map { it.groupValues[1] }.toList()
            val mcVersions = versions.mapNotNull { v ->
                val m = Regex("^(\\d+\\.\\d+(\\.\\d+)?)-\\d+\\.\\d+\\.\\d+$").find(v) ?: return@mapNotNull null
                m.groupValues[1]
            }.distinct().reversed()
            Result.success(mcVersions.map { CoreVersion(it) })
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun getForgeLatestBuild(mcVersion: String): Result<CoreDownload> = withContext(Dispatchers.IO) {
        try {
            val html = httpGet("https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml")
            val versions = Regex("<version>([^<]+)</version>").findAll(html).map { it.groupValues[1] }.toList()
            val match = versions.lastOrNull { it.startsWith("$mcVersion-") } ?: return@withContext Result.failure(RuntimeException("Forge 不支持 $mcVersion"))
            Result.success(CoreDownload("https://maven.minecraftforge.net/net/minecraftforge/forge/$match/forge-$match-installer.jar", "forge-$match-installer.jar"))
        } catch (e: Exception) { Result.failure(e) }
    }

    // ── NeoForge ──
    suspend fun fetchNeoForgeVersions(): Result<List<CoreVersion>> = withContext(Dispatchers.IO) {
        try {
            val html = httpGet("https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml")
            val versions = Regex("<version>([^<]+)</version>").findAll(html).map { it.groupValues[1] }.toList()
            val mcVersions = versions.mapNotNull { v ->
                val m = Regex("^(\\d+\\.\\d+(\\.\\d+)?)-\\d+\\.\\d+\\.\\d+$").find(v) ?: return@mapNotNull null
                m.groupValues[1]
            }.distinct().reversed()
            Result.success(mcVersions.map { CoreVersion(it) })
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun getNeoForgeLatestBuild(mcVersion: String): Result<CoreDownload> = withContext(Dispatchers.IO) {
        try {
            val html = httpGet("https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml")
            val versions = Regex("<version>([^<]+)</version>").findAll(html).map { it.groupValues[1] }.toList()
            val match = versions.lastOrNull { it.startsWith("$mcVersion-") } ?: return@withContext Result.failure(RuntimeException("NeoForge 不支持 $mcVersion"))
            Result.success(CoreDownload("https://maven.neoforged.net/releases/net/neoforged/neoforge/$match/neoforge-$match-installer.jar", "neoforge-$match-installer.jar"))
        } catch (e: Exception) { Result.failure(e) }
    }

    // ── Spigot(官方构建直链) ──
    suspend fun fetchSpigotVersions(): Result<List<CoreVersion>> = withContext(Dispatchers.IO) {
        try {
            val html = httpGet("https://hub.spigotmc.org/versions/")
            val versions = Regex("href=\"([^\"]+\\.json)\"").findAll(html)
                .map { it.groupValues[1].removeSuffix(".json") }
                .filter { it.matches(Regex("\\d+\\.\\d+(\\.\\d+)?")) }
                .toList().reversed()
            Result.success(versions.map { CoreVersion(it) })
        } catch (e: Exception) { Result.failure(e) }
    }

    fun getSpigotDownload(version: String): CoreDownload =
        CoreDownload("https://download.getbukkit.org/spigot/spigot-$version.jar", "spigot-$version.jar")

    // ── 统一入口 ──
    suspend fun fetchVersions(type: CoreType): Result<List<CoreVersion>> = when (type) {
        CoreType.VANILLA -> fetchVanillaVersions()
        CoreType.PAPER -> fetchPaperVersions()
        CoreType.PURPUR -> fetchPurpurVersions()
        CoreType.SPIGOT -> fetchSpigotVersions()
        CoreType.FABRIC -> fetchFabricVersions()
        CoreType.FORGE -> fetchForgeVersions()
        CoreType.NEOFORGE -> fetchNeoForgeVersions()
    }

    /** 获取最终下载链接 */
    suspend fun resolveDownload(type: CoreType, mcVersion: String, buildId: String = "", fileName: String? = null): Result<CoreDownload> =
        when (type) {
            CoreType.VANILLA -> getVanillaDownload(mcVersion)
            CoreType.PAPER -> {
                if (buildId.isBlank()) Result.failure(RuntimeException("Paper 需要选择构建"))
                else Result.success(getPaperDownload(mcVersion, buildId, fileName ?: "paper-$mcVersion-$buildId.jar"))
            }
            CoreType.PURPUR -> Result.success(getPurpurDownload(mcVersion))
            CoreType.SPIGOT -> Result.success(getSpigotDownload(mcVersion))
            CoreType.FABRIC -> getFabricDownload(mcVersion)
            CoreType.FORGE -> getForgeLatestBuild(mcVersion)
            CoreType.NEOFORGE -> getNeoForgeLatestBuild(mcVersion)
        }
}
