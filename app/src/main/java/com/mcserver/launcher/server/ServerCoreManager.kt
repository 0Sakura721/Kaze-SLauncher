package com.mcserver.launcher.server

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL

/**
 * 服务器核心下载管理器。
 * 借鉴 Pterodactyl 和 MCSManager 的服务器模板设计，
 * 支持从 Paper / Purpur / Fabric / Vanilla / Spigot 等源下载 JAR。
 */
class ServerCoreManager {

    enum class CoreType(val displayName: String, val apiBase: String, val description: String) {
        PAPER("Paper", "https://api.papermc.io/v2", "高性能 Spigot 分支，推荐"),
        PURPUR("Purpur", "https://api.purpurmc.org/v2", "Paper 分支 + 更多配置选项"),
        FABRIC("Fabric", "https://meta.fabricmc.net/v2", "轻量 Mod 加载器"),
        VANILLA("Vanilla", "https://piston-meta.mojang.com", "官方原版"),
        SPIGOT("Spigot", "https://hub.spigotmc.org/versions", "经典 Bukkit 分支");

        companion object {
            fun fromKey(key: String): CoreType =
                entries.firstOrNull { it.name.equals(key, ignoreCase = true) } ?: PAPER
        }
    }

    data class CoreVersion(val id: String, val isStable: Boolean = true)
    data class CoreBuild(val id: String, val name: String = "", val fileName: String = "")

    /** 获取 Paper 可用的 MC 版本列表 */
    suspend fun fetchPaperVersions(): Result<List<CoreVersion>> = withContext(Dispatchers.IO) {
        try {
            val conn = URL("${CoreType.PAPER.apiBase}/projects/paper").openConnection() as HttpURLConnection
            conn.connectTimeout = 10000; conn.readTimeout = 10000
            conn.connect()
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            conn.disconnect()
            val arr = json.getJSONArray("versions")
            val list = (0 until arr.length()).map { CoreVersion(arr.getString(it)) }
            Result.success(list.sortedByDescending { it.id })
        } catch (e: Exception) { Result.failure(e) }
    }

    /** 获取 Paper 某版本的构建列表 */
    suspend fun fetchPaperBuilds(version: String): Result<List<CoreBuild>> = withContext(Dispatchers.IO) {
        try {
            val conn = URL("${CoreType.PAPER.apiBase}/projects/paper/versions/$version/builds")
                .openConnection() as HttpURLConnection
            conn.connectTimeout = 10000; conn.readTimeout = 10000
            conn.connect()
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            conn.disconnect()
            val builds = json.getJSONArray("builds")
            val list = (0 until builds.length()).map {
                val b = builds.getJSONObject(it)
                val buildId = b.getInt("build").toString()
                val fileName = b.optJSONObject("downloads")?.optJSONObject("application")?.optString("name")
                    ?: "paper-$version-$buildId.jar"
                CoreBuild(buildId, "Build #$buildId", fileName)
            }
            Result.success(list.reversed()) // 最新构建在前
        } catch (e: Exception) { Result.failure(e) }
    }

    /** 获取 Paper JAR 下载 URL */
    fun getPaperDownloadUrl(version: String, build: String, fileName: String): String {
        return "${CoreType.PAPER.apiBase}/projects/paper/versions/$version/builds/$build/downloads/$fileName"
    }

    /** 获取 Purpur 版本列表 */
    suspend fun fetchPurpurVersions(): Result<List<CoreVersion>> = withContext(Dispatchers.IO) {
        try {
            val conn = URL("${CoreType.PURPUR.apiBase}/purpur").openConnection() as HttpURLConnection
            conn.connectTimeout = 10000; conn.readTimeout = 10000
            conn.connect()
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            conn.disconnect()
            val arr = json.getJSONArray("versions")
            val list = (0 until arr.length()).map { CoreVersion(arr.getString(it)) }
            Result.success(list.sortedByDescending { it.id })
        } catch (e: Exception) { Result.failure(e) }
    }

    /** 获取 Purpur JAR 下载 URL */
    fun getPurpurDownloadUrl(version: String): String {
        return "${CoreType.PURPUR.apiBase}/purpur/$version/latest/download"
    }

    /** 获取 Fabric 加载器版本列表 */
    suspend fun fetchFabricVersions(): Result<List<CoreVersion>> = withContext(Dispatchers.IO) {
        try {
            val conn = URL("${CoreType.FABRIC.apiBase}/versions/game").openConnection() as HttpURLConnection
            conn.connectTimeout = 10000; conn.readTimeout = 10000
            conn.connect()
            val arr = JSONArray(conn.inputStream.bufferedReader().readText())
            conn.disconnect()
            val list = (0 until arr.length()).map {
                val obj = arr.getJSONObject(it)
                CoreVersion(obj.getString("version"), obj.optBoolean("stable", true))
            }
            Result.success(list)
        } catch (e: Exception) { Result.failure(e) }
    }

    /** 获取 Fabric 最新安装器 URL（含加载器版本） */
    suspend fun getFabricDownloadUrl(mcVersion: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val loaderConn = URL("${CoreType.FABRIC.apiBase}/versions/loader/$mcVersion")
                .openConnection() as HttpURLConnection
            loaderConn.connectTimeout = 10000; loaderConn.readTimeout = 10000
            loaderConn.connect()
            val loaderJson = JSONArray(loaderConn.inputStream.bufferedReader().readText())
            loaderConn.disconnect()
            val loaderVersion = loaderJson.getJSONObject(0).getJSONObject("loader").getString("version")

            val installerConn = URL("${CoreType.FABRIC.apiBase}/versions/installer")
                .openConnection() as HttpURLConnection
            installerConn.connectTimeout = 10000; installerConn.readTimeout = 10000
            installerConn.connect()
            val installerJson = JSONArray(installerConn.inputStream.bufferedReader().readText())
            installerConn.disconnect()
            val installerVersion = installerJson.getJSONObject(0).getString("version")

            val url = "https://meta.fabricmc.net/v2/versions/loader/$mcVersion/$loaderVersion/$installerVersion/server/jar"
            Result.success(url)
        } catch (e: Exception) { Result.failure(e) }
    }

    /** 获取 Vanilla 版本列表 */
    suspend fun fetchVanillaVersions(): Result<List<CoreVersion>> = withContext(Dispatchers.IO) {
        try {
            val conn = URL("${CoreType.VANILLA.apiBase}/mc/game/version_manifest_v2.json")
                .openConnection() as HttpURLConnection
            conn.connectTimeout = 10000; conn.readTimeout = 10000
            conn.connect()
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            conn.disconnect()
            val arr = json.getJSONArray("versions")
            val list = (0 until arr.length()).map {
                val obj = arr.getJSONObject(it)
                CoreVersion(obj.getString("id"), obj.optString("type") == "release")
            }
            Result.success(list)
        } catch (e: Exception) { Result.failure(e) }
    }

    /** 获取 Vanilla server.jar 下载 URL */
    suspend fun getVanillaDownloadUrl(version: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val manifestConn = URL("${CoreType.VANILLA.apiBase}/mc/game/version_manifest_v2.json")
                .openConnection() as HttpURLConnection
            manifestConn.connectTimeout = 10000; manifestConn.readTimeout = 10000
            manifestConn.connect()
            val manifest = JSONObject(manifestConn.inputStream.bufferedReader().readText())
            manifestConn.disconnect()

            val versions = manifest.getJSONArray("versions")
            var versionUrl = ""
            for (i in 0 until versions.length()) {
                val v = versions.getJSONObject(i)
                if (v.getString("id") == version) {
                    versionUrl = v.getString("url")
                    break
                }
            }
            if (versionUrl.isEmpty()) return@withContext Result.failure(Exception("版本不存在"))

            val detailConn = URL(versionUrl).openConnection() as HttpURLConnection
            detailConn.connectTimeout = 10000; detailConn.readTimeout = 10000
            detailConn.connect()
            val detail = JSONObject(detailConn.inputStream.bufferedReader().readText())
            detailConn.disconnect()

            val serverUrl = detail.getJSONObject("downloads").getJSONObject("server").getString("url")
            Result.success(serverUrl)
        } catch (e: Exception) { Result.failure(e) }
    }

    /**
     * 下载 JAR 文件到指定目录。
     * 返回下载后的文件路径，支持进度回调。
     */
    suspend fun downloadJar(
        url: String,
        targetDir: File,
        fileName: String,
        onProgress: (Float, Long, Long) -> Unit = { _, _, _ -> }
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            targetDir.mkdirs()
            val targetFile = File(targetDir, fileName)
            val tempFile = File(targetDir, "$fileName.part")

            var downloaded = if (tempFile.exists()) tempFile.length() else 0L
            val fos = FileOutputStream(tempFile, downloaded > 0)

            var connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 30000; connection.readTimeout = 300000
            if (downloaded > 0) {
                connection.setRequestProperty("Range", "bytes=$downloaded-")
                connection.connect()
                if (connection.responseCode != 206) {
                    // 服务器不支持断点续传，重新下载
                    downloaded = 0
                    tempFile.delete()
                }
            } else {
                connection.connect()
            }

            val totalSize = if (downloaded > 0) {
                connection.getHeaderField("Content-Range")?.let {
                    val total = it.substringAfter("/").toLongOrNull()
                    total
                } ?: (connection.contentLength + downloaded)
            } else {
                connection.contentLength.toLong().let { if (it <= 0) -1L else it }
            }

            val input = connection.inputStream
            val buffer = ByteArray(16384)
            var bytesRead: Int
            var lastReportTime = System.currentTimeMillis()
            var lastReportBytes = downloaded

            while (input.read(buffer).also { bytesRead = it } != -1) {
                fos.write(buffer, 0, bytesRead)
                downloaded += bytesRead

                val now = System.currentTimeMillis()
                if (now - lastReportTime >= 200) {
                    val speed = if (now > lastReportTime) ((downloaded - lastReportBytes) * 1000L / (now - lastReportTime)) else 0L
                    lastReportTime = now
                    lastReportBytes = downloaded

                    val progress = if (totalSize > 0) (downloaded.toFloat() / totalSize).coerceIn(0f, 1f) else -1f
                    onProgress(progress, downloaded, if (totalSize > 0) totalSize else downloaded * 2)
                }
            }

            fos.close()
            input.close()
            connection.disconnect()

            // 下载完成，重命名
            if (targetFile.exists()) targetFile.delete()
            tempFile.renameTo(targetFile)
            Result.success(targetFile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
