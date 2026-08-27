package com.kaze.newage.core.download

import com.kaze.newage.data.model.CoreType
import com.kaze.newage.data.model.GameVersion
import com.kaze.newage.data.model.VersionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

/** 构建条目 */
data class CoreBuild(val id: String, val name: String, val fileName: String? = null, val url: String = "")

/** 下载结果：URL + 建议文件名 */
data class CoreDownload(val url: String, val fileName: String)

/**
 * 核心源：统一获取各类型服务端的版本/构建/下载链接。
 * 移植自 v2（自有代码），JSON 解析用 kotlinx.serialization；
 * 版本分类体系照搬 FCL/HMCL（VersionType：RELEASE/SNAPSHOT/OLD_BETA/OLD_ALPHA）。
 */
object CoreSources {

    private val json = Json { ignoreUnknownKeys = true }

    /** 版本号 → 数字段序列（26.2 → [26,2]；1.21.11 → [1,21,11]） */
    private fun versionKey(v: String): List<Long> =
        Regex("\\d+").findAll(v).map { it.value.toLong() }.toList()

    /** 语义比较：a > b → 正（按数字段逐位比，不依赖 API 返回顺序） */
    private fun compareVersions(a: String, b: String): Int {
        val ka = versionKey(a)
        val kb = versionKey(b)
        val n = maxOf(ka.size, kb.size)
        for (i in 0 until n) {
            val x = ka.getOrNull(i) ?: 0
            val y = kb.getOrNull(i) ?: 0
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    private fun httpGet(urlStr: String): String {
        var current = urlStr
        var redirects = 0
        while (true) {
            val conn = URL(current).openConnection() as HttpURLConnection
            // 自动跟随开启时 3xx 由底层处理；手动循环主要为兼容相对 Location
            val code = conn.responseCode
            if (code in listOf(301, 302, 303, 307, 308)) {
                val loc = conn.getHeaderField("Location") ?: throw RuntimeException("重定向无 Location")
                conn.disconnect()
                if (++redirects > 5) throw RuntimeException("重定向过多")
                // 相对 Location（/path 或相对路径）必须基于当前 URL 解析，直接 new URL(loc) 会抛异常
                current = if (loc.startsWith("http")) loc else URL(URL(current), loc).toString()
                continue
            }
            if (code != HttpURLConnection.HTTP_OK) { conn.disconnect(); throw RuntimeException("HTTP $code") }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            return text
        }
    }

    // ── Vanilla（manifest type 字段：release/snapshot/old_beta/old_alpha） ──
    private fun parseManifestType(raw: String?): VersionType = when (raw) {
        "release" -> VersionType.RELEASE
        "snapshot" -> VersionType.SNAPSHOT
        "old_beta" -> VersionType.OLD_BETA
        "old_alpha" -> VersionType.OLD_ALPHA
        else -> VersionType.RELEASE
    }

    suspend fun fetchVanillaVersions(): Result<List<GameVersion>> = withContext(Dispatchers.IO) {
        try {
            val root = json.parseToJsonElement(httpGet("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json")).jsonObject
            val arr = root["versions"]!!.jsonArray
            Result.success(arr.mapNotNull {
                val o = it.jsonObject
                GameVersion(
                    id = o["id"]!!.jsonPrimitive.content,
                    type = parseManifestType(o["type"]?.jsonPrimitive?.content),
                )
            })
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun getVanillaDownload(mcVersion: String): Result<CoreDownload> = withContext(Dispatchers.IO) {
        try {
            val root = json.parseToJsonElement(httpGet("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json")).jsonObject
            val versions = root["versions"]!!.jsonArray
            val entry = versions.firstOrNull { it.jsonObject["id"]?.jsonPrimitive?.content == mcVersion }
                ?: return@withContext Result.failure(RuntimeException("未找到版本 $mcVersion"))
            val vJson = json.parseToJsonElement(httpGet(entry.jsonObject["url"]!!.jsonPrimitive.content)).jsonObject
            val jar = vJson["downloads"]!!.jsonObject["server"]!!.jsonObject
            Result.success(CoreDownload(jar["url"]!!.jsonPrimitive.content, "vanilla-$mcVersion.jar"))
        } catch (e: Exception) { Result.failure(e) }
    }

    // ── Paper（v3 API：releaseChannel = release/experimental） ──
    private const val PAPER_API = "https://fill.papermc.io/v3/projects/paper"

    suspend fun fetchPaperVersions(): Result<List<GameVersion>> = withContext(Dispatchers.IO) {
        try {
            val root = json.parseToJsonElement(httpGet("$PAPER_API/versions")).jsonObject
            val arr = root["versions"]!!.jsonArray
            val list = arr.mapNotNull { el ->
                val v = el.jsonObject["version"]?.jsonObject ?: return@mapNotNull null
                GameVersion(
                    id = v["id"]!!.jsonPrimitive.content,
                    type = if (v["releaseChannel"]?.jsonPrimitive?.content == "experimental") VersionType.SNAPSHOT else VersionType.RELEASE,
                )
            }
            Result.success(list)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun fetchPaperBuilds(version: String): Result<List<CoreBuild>> = withContext(Dispatchers.IO) {
        try {
            val text = httpGet("$PAPER_API/versions/$version/builds")
            val arr: JsonArray = if (text.trimStart().startsWith("[")) {
                json.parseToJsonElement(text) as JsonArray
            } else {
                json.parseToJsonElement(text).jsonObject["builds"]!!.jsonArray
            }
            val list = arr.mapNotNull { el ->
                val o = el.jsonObject
                val buildId = o["id"]!!.jsonPrimitive.content
                val dl = o["downloads"]?.jsonObject?.get("server:default")
                CoreBuild(
                    id = buildId,
                    name = "build #$buildId",
                    fileName = (dl as? JsonObject)?.get("name")?.jsonPrimitive?.content ?: "paper-$version-$buildId.jar",
                    url = (dl as? JsonObject)?.get("url")?.jsonPrimitive?.content ?: "",
                )
            }
            // build 按 id 数值降序（不依赖 API 返回顺序——fill API 顺序变化会导致拿到旧 build）
            Result.success(list.sortedByDescending { it.id.toLongOrNull() ?: 0L })
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun getPaperDownload(version: String, buildId: String): Result<CoreDownload> = withContext(Dispatchers.IO) {
        try {
            val builds = fetchPaperBuilds(version).getOrNull()
                ?: return@withContext Result.failure(RuntimeException("获取构建列表失败"))
            val build = (
                if (buildId.isBlank() || buildId == "latest") builds.firstOrNull()
                else builds.firstOrNull { it.id == buildId }
                ) ?: return@withContext Result.failure(RuntimeException("构建 $buildId 不存在"))
            if (build.url.isBlank()) return@withContext Result.failure(RuntimeException("该构建没有可下载文件"))
            Result.success(CoreDownload(build.url, build.fileName ?: "paper-$version-$buildId.jar"))
        } catch (e: Exception) { Result.failure(e) }
    }

    // ── Purpur ──
    suspend fun fetchPurpurVersions(): Result<List<GameVersion>> = withContext(Dispatchers.IO) {
        try {
            val root = json.parseToJsonElement(httpGet("https://api.purpurmc.org/v2/purpur")).jsonObject
            val arr = root["versions"]!!.jsonArray
            Result.success(arr.map { GameVersion(it.jsonPrimitive.content) })
        } catch (e: Exception) { Result.failure(e) }
    }

    fun getPurpurDownload(version: String): CoreDownload =
        CoreDownload("https://api.purpurmc.org/v2/purpur/$version/latest/download", "purpur-$version.jar")

    // ── Fabric（stable 字段 → 正式/快照） ──
    suspend fun fetchFabricVersions(): Result<List<GameVersion>> = withContext(Dispatchers.IO) {
        try {
            val arr = json.parseToJsonElement(httpGet("https://meta.fabricmc.net/v2/versions/game")) as JsonArray
            Result.success(arr.mapNotNull {
                val o = it.jsonObject
                val stable = o["stable"]?.jsonPrimitive?.booleanOrNull ?: true
                GameVersion(
                    id = o["version"]!!.jsonPrimitive.content,
                    type = if (stable) VersionType.RELEASE else VersionType.SNAPSHOT,
                    stable = stable,
                )
            }.reversed())
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun getFabricDownload(mcVersion: String): Result<CoreDownload> = withContext(Dispatchers.IO) {
        try {
            val loaderArr = json.parseToJsonElement(httpGet("https://meta.fabricmc.net/v2/versions/loader/$mcVersion")) as JsonArray
            val loader = loaderArr.first().jsonObject["loader"]!!.jsonObject["version"]!!.jsonPrimitive.content
            val installerArr = json.parseToJsonElement(httpGet("https://meta.fabricmc.net/v2/versions/installer")) as JsonArray
            val installer = installerArr.first().jsonObject["version"]!!.jsonPrimitive.content
            Result.success(
                CoreDownload(
                    "https://meta.fabricmc.net/v2/versions/loader/$mcVersion/$loader/$installer/server/jar",
                    "fabric-server-mc.$mcVersion-loader.$loader-launcher.$installer.jar"
                )
            )
        } catch (e: Exception) { Result.failure(e) }
    }

    // ── Forge（maven 索引解析） ──
    suspend fun fetchForgeVersions(): Result<List<GameVersion>> = withContext(Dispatchers.IO) {
        try {
            val html = httpGet("https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml")
            val versions = Regex("<version>([^<]+)</version>").findAll(html).map { it.groupValues[1] }.toList()
            // build 段放宽为任意位数：老版本如 1.12.2-14.23.5.2860 是四段，原三段正则会漏
            val mcVersions = versions.mapNotNull { v ->
                Regex("^(\\d+\\.\\d+(?:\\.\\d+)?)-[\\d.]+$").find(v)?.groupValues?.get(1)
            }.distinct()
            Result.success(mcVersions.map { GameVersion(it) })
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun getForgeLatestBuild(mcVersion: String): Result<CoreDownload> = withContext(Dispatchers.IO) {
        try {
            val html = httpGet("https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml")
            val versions = Regex("<version>([^<]+)</version>").findAll(html).map { it.groupValues[1] }.toList()
            // 不依赖 XML 行序：按 build 号语义取最大（中间可能夹 RC/回移植行）
            val match = versions.filter { it.startsWith("$mcVersion-") }
                .maxWithOrNull(Comparator { x, y -> compareVersions(x.substringAfter('-'), y.substringAfter('-')) })
                ?: return@withContext Result.failure(RuntimeException("Forge 不支持 $mcVersion"))
            Result.success(CoreDownload(
                "https://maven.minecraftforge.net/net/minecraftforge/forge/$match/forge-$match-installer.jar",
                "forge-$match-installer.jar"))
        } catch (e: Exception) { Result.failure(e) }
    }

    // ── NeoForge ──
    // 版本 id 两代格式：
    //  - 现代（1.20.2 起）：无前缀统一 id「20.4.236 / 21.1.77」，major.minor 即 MC
    //    「1.major.minor」去掉前导 1.——旧正则要求 `-x.y.z` 前缀，对现代 id 全不命中
    //    → 列表恒空、"NeoForge 不支持 x.y"；
    //  - 极早期 fork 产物：`1.20.1-x.y.z` 带 MC 前缀，兼容保留。
    private fun neoForgeMcFromUnified(id: String): String? {
        val m = Regex("^(\\d+)\\.(\\d+)\\.\\d+").find(id) ?: return null
        return "1.${m.groupValues[1]}.${m.groupValues[2]}"
    }

    suspend fun fetchNeoForgeVersions(): Result<List<GameVersion>> = withContext(Dispatchers.IO) {
        try {
            val html = httpGet("https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml")
            val versions = Regex("<version>([^<]+)</version>").findAll(html).map { it.groupValues[1] }.toList()
            val mcVersions = versions.flatMap { v ->
                if ('-' in v) {
                    listOfNotNull(Regex("^(\\d+\\.\\d+(?:\\.\\d+)?)-").find(v)?.groupValues?.get(1))
                } else {
                    listOfNotNull(neoForgeMcFromUnified(v))
                }
            }.distinct()
            Result.success(mcVersions.map { GameVersion(it) })
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun getNeoForgeLatestBuild(mcVersion: String): Result<CoreDownload> = withContext(Dispatchers.IO) {
        try {
            val html = httpGet("https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml")
            val versions = Regex("<version>([^<]+)</version>").findAll(html).map { it.groupValues[1] }.toList()
            // 前缀双路匹配："1.21.1-" 命中极早期带前缀 id；现代 id 去 "1." 化成 "21.1."
            val unifiedBase = if (Regex("^1\\.\\d+\\.\\d+$").matches(mcVersion)) mcVersion.removePrefix("1.")
            else mcVersion
            val match = versions.filter { v ->
                v.startsWith("$mcVersion-") ||
                    Regex("^${Regex.escape(unifiedBase)}\\.\\d+").containsMatchIn(v)
            }.maxWithOrNull(Comparator { x, y ->
                compareVersions(x.substringAfterLast('-'), y.substringAfterLast('-'))
            })
                ?: return@withContext Result.failure(RuntimeException("NeoForge 不支持 $mcVersion"))
            Result.success(CoreDownload(
                "https://maven.neoforged.net/releases/net/neoforged/neoforge/$match/neoforge-$match-installer.jar",
                "neoforge-$match-installer.jar"))
        } catch (e: Exception) { Result.failure(e) }
    }

    // ── Spigot（官方构建直链） ──
    suspend fun fetchSpigotVersions(): Result<List<GameVersion>> = withContext(Dispatchers.IO) {
        try {
            val html = httpGet("https://hub.spigotmc.org/versions/")
            val versions = Regex("href=\"([^\"]+\\.json)\"").findAll(html)
                .map { it.groupValues[1].removeSuffix(".json") }
                .filter { it.matches(Regex("\\d+\\.\\d+(\\.\\d+)?")) }
                .toList().reversed()
            Result.success(versions.map { GameVersion(it) })
        } catch (e: Exception) { Result.failure(e) }
    }

    fun getSpigotDownload(version: String): CoreDownload =
        CoreDownload("https://download.getbukkit.org/spigot/spigot-$version.jar", "spigot-$version.jar")

    // ── 统一入口 ──
    /** 各源版本列表统一处理：按版本号语义降序（最新在前，不依赖 API 返回顺序）+ 去重 */
    suspend fun fetchVersions(type: CoreType): Result<List<GameVersion>> {
        val r = when (type) {
            CoreType.VANILLA -> fetchVanillaVersions()
            CoreType.PAPER -> fetchPaperVersions()
            CoreType.PURPUR -> fetchPurpurVersions()
            CoreType.SPIGOT -> fetchSpigotVersions()
            CoreType.FABRIC -> fetchFabricVersions()
            CoreType.FORGE -> fetchForgeVersions()
            CoreType.NEOFORGE -> fetchNeoForgeVersions()
            CoreType.CUSTOM -> Result.failure(RuntimeException("自定义导入无下载源"))
        }
        return r.map { list ->
            list.distinctBy { it.id }
                .sortedWith { a, b -> compareVersions(b.id, a.id) }
        }
    }

    /** 获取最终下载链接 */
    suspend fun resolveDownload(type: CoreType, mcVersion: String, buildId: String = ""): Result<CoreDownload> =
        when (type) {
            CoreType.VANILLA -> getVanillaDownload(mcVersion)
            CoreType.PAPER -> getPaperDownload(mcVersion, buildId)
            CoreType.PURPUR -> Result.success(getPurpurDownload(mcVersion))
            CoreType.SPIGOT -> Result.success(getSpigotDownload(mcVersion))
            CoreType.FABRIC -> getFabricDownload(mcVersion)
            CoreType.FORGE -> getForgeLatestBuild(mcVersion)
            CoreType.NEOFORGE -> getNeoForgeLatestBuild(mcVersion)
            CoreType.CUSTOM -> Result.failure(RuntimeException("自定义导入无下载源"))
        }
}
