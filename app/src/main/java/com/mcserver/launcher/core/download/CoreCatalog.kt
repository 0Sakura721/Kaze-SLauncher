package com.mcserver.launcher.core.download

import com.mcserver.launcher.core.instance.CoreType
import com.mcserver.launcher.util.KLog
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 核心解析结果 */
data class CoreInfo(
    val downloadUrl: String?,
    val fileName: String,
)

/**
 * 核心市场目录：查询各平台可用版本并解析下载地址。
 * - Paper / Purpur：官方 API（含构建号）
 * - Vanilla：Mojang version manifest
 * - Fabric：meta.fabricmc.net（loader + installer）
 * - Spigot / Forge：需自备 jar（resolve 返回 null，UI 提示手动导入）
 */
object CoreCatalog {

    private const val UA = "Kaze-SLauncher/2.0"

    /** 列出某类型可用的 MC 版本（新→旧） */
    fun listMcVersions(type: CoreType): List<String> = try {
        when (type) {
            CoreType.PAPER -> getJson("https://api.papermc.io/v2/projects/paper")
                ?.optJSONArray("versions")
                ?.let { arr -> jsonArrayToStrings(arr).reversed() }
                ?: emptyList()

            CoreType.VANILLA -> getJson("https://launchermeta.mojang.com/mc/game/version_manifest_v2.json")
                ?.optJSONArray("versions")
                ?.let { arr ->
                    (0 until arr.length()).map { arr.getJSONObject(it).optString("id") }.reversed()
                }
                ?: emptyList()

            CoreType.FABRIC -> getJsonArray("https://meta.fabricmc.net/v2/versions/game")
                ?.let { arr ->
                    (0 until arr.length()).map { arr.getJSONObject(it).optString("version") }
                }
                ?: emptyList()

            CoreType.PURPUR -> getJson("https://api.purpurmc.org/v2/purpur")
                ?.optJSONObject("versions")
                ?.keys()
                ?.toList()
                ?: emptyList()

            else -> emptyList()
        }
    } catch (e: Exception) {
        KLog.w("加载版本列表失败(${type.name}): ${e.message}")
        emptyList()
    }

    /** 解析某版本核心的直链；无法自动获取（Spigot/Forge 等）返回 null */
    fun resolve(type: CoreType, version: String): CoreInfo? = try {
        when (type) {
            CoreType.PAPER -> {
                val builds = getJson("https://api.papermc.io/v2/projects/paper/versions/$version/builds")
                val last = builds?.optJSONArray("builds")?.let { arr ->
                    if (arr.length() == 0) null else arr.getJSONObject(arr.length() - 1)
                }
                val build = last?.optInt("build")
                val app = last?.optJSONObject("downloads")?.optJSONObject("application")
                if (build != null && app != null) {
                    CoreInfo(
                        "https://api.papermc.io/v2/projects/paper/versions/$version/builds/$build/downloads/${app.optString("name")}",
                        app.optString("name"),
                    )
                } else null
            }

            CoreType.VANILLA -> {
                val manifest = getJson("https://launchermeta.mojang.com/mc/game/version_manifest_v2.json")
                val ver = manifest?.optJSONArray("versions")?.let { arr ->
                    (0 until arr.length()).map { arr.getJSONObject(it) }
                        .firstOrNull { it.optString("id") == version }
                }
                val url = ver?.optString("url")
                if (url != null) {
                    val server = getJson(url)?.optJSONObject("downloads")?.optJSONObject("server")
                    val jar = server?.optString("url")
                    if (jar != null) CoreInfo(jar, "server-$version.jar") else null
                } else null
            }

            CoreType.FABRIC -> {
                val loaders = getJsonArray("https://meta.fabricmc.net/v2/versions/loader/$version")
                val loader = loaders?.optJSONObject(0)?.optJSONObject("loader")?.optString("version")
                if (loader != null) {
                    // 使用 fabric-installer 生成的 server launcher（0.16.x 为常见稳定版）
                    CoreInfo(
                        "https://meta.fabricmc.net/v2/versions/loader/$version/$loader/0.16.9/server/jar",
                        "fabric-server-mc.$version-loader-$loader.jar",
                    )
                } else null
            }

            CoreType.PURPUR -> {
                val builds = getJson("https://api.purpurmc.org/v2/purpur/$version")
                val build = builds?.optJSONObject("builds")?.keys()?.lastOrNull()
                if (build != null) {
                    CoreInfo(
                        "https://api.purpurmc.org/v2/purpur/$version/$build/download",
                        "purpur-$version-$build.jar",
                    )
                } else null
            }

            else -> null // SPIGOT / FORGE 需自备 jar
        }
    } catch (e: Exception) {
        KLog.w("解析下载地址失败(${type.name} $version): ${e.message}")
        null
    }

    private fun jsonArrayToStrings(arr: JSONArray): List<String> =
        (0 until arr.length()).map { arr.getString(it) }

    private fun getJson(url: String): JSONObject? = try {
        val conn = open(url)
        if (conn != null) {
            try {
                JSONObject(conn.inputStream.bufferedReader().readText())
            } finally {
                conn.disconnect()
            }
        } else null
    } catch (e: Exception) {
        KLog.w("JSON 请求失败: $url ${e.message}")
        null
    }

    private fun getJsonArray(url: String): JSONArray? = try {
        val conn = open(url)
        if (conn != null) {
            try {
                JSONArray(conn.inputStream.bufferedReader().readText())
            } finally {
                conn.disconnect()
            }
        } else null
    } catch (e: Exception) {
        KLog.w("JSON 请求失败: $url ${e.message}")
        null
    }

    private fun open(url: String): HttpURLConnection? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Accept", "application/json")
        if (conn.responseCode == 200) conn else {
            conn.disconnect()
            null
        }
    } catch (e: Exception) {
        KLog.w("网络请求失败: $url ${e.message}")
        null
    }
}
