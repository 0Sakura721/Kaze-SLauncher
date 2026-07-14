package com.mcserver.launcher.server

import com.mcserver.launcher.McApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 世界管理器 — 管理 Minecraft 服务器世界目录。
 * 借鉴 Pterodactyl 和 MCSManager 的世界管理功能。
 *
 * 功能：
 * - 列出所有世界（检测 level.dat）
 * - 获取世界大小和最后修改时间
 * - 删除世界
 * - 导出世界（打包为 zip）
 * - 切换默认世界（通过 server.properties 的 level-name）
 */
object WorldManager {

    private val serverDir: File get() = TermuxManager.serverDir(McApplication.instance)

    data class WorldInfo(
        val name: String,
        val path: String,
        val sizeBytes: Long,
        val lastModified: Long,
        val dimensionCount: Int = 0,  // 子维度数量
        val isDefault: Boolean = false  // 是否是默认世界
    )

    /**
     * 列出服务器目录中所有世界。
     * 通过检测目录中是否存在 level.dat 来识别世界。
     */
    suspend fun listWorlds(): List<WorldInfo> = withContext(Dispatchers.IO) {
        if (!serverDir.exists()) return@withContext emptyList()

        val defaultWorld = getDefaultWorldName()

        serverDir.listFiles()
            ?.filter { it.isDirectory && File(it, "level.dat").exists() }
            ?.map { dir ->
                var totalSize = 0L
                var dimensionCount = 0
                dir.walkTopDown().forEach { file ->
                    if (file.isFile) totalSize += file.length()
                    if (file.isDirectory && file != dir) dimensionCount++
                }
                WorldInfo(
                    name = dir.name,
                    path = dir.absolutePath,
                    sizeBytes = totalSize,
                    lastModified = dir.lastModified(),
                    dimensionCount = dimensionCount,
                    isDefault = dir.name == defaultWorld
                )
            }
            ?.sortedByDescending { it.lastModified } ?: emptyList()
    }

    /**
     * 获取 server.properties 中配置的默认世界名
     */
    private fun getDefaultWorldName(): String {
        return try {
            val props = File(serverDir, "server.properties")
            if (!props.exists()) return "world"
            props.readLines()
                .firstOrNull { it.trim().startsWith("level-name=") }
                ?.substringAfter("=")
                ?.trim() ?: "world"
        } catch (_: Exception) { "world" }
    }

    /**
     * 设置默认世界名（修改 server.properties 中的 level-name）
     */
    suspend fun setDefaultWorld(worldName: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val props = File(serverDir, "server.properties")
            if (!props.exists()) return@withContext Result.failure(Exception("server.properties 不存在"))

            val lines = props.readLines().toMutableList()
            var found = false
            for (i in lines.indices) {
                if (lines[i].trim().startsWith("level-name=")) {
                    lines[i] = "level-name=$worldName"
                    found = true
                    break
                }
            }
            if (!found) {
                lines.add("level-name=$worldName")
            }
            props.writeText(lines.joinToString("\n") + "\n")
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    /**
     * 删除指定世界目录。
     * 不会删除当前默认世界。
     */
    suspend fun deleteWorld(worldName: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val defaultWorld = getDefaultWorldName()
            if (worldName == defaultWorld) {
                return@withContext Result.failure(Exception("不能删除当前默认世界，请先切换到其他世界"))
            }

            val worldDir = File(serverDir, worldName)
            if (!worldDir.exists()) return@withContext Result.failure(Exception("世界目录不存在"))
            if (!File(worldDir, "level.dat").exists()) return@withContext Result.failure(Exception("不是有效的世界目录"))

            worldDir.deleteRecursively()
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    /**
     * 导出世界为 ZIP 文件到下载目录。
     * @return 导出的文件路径
     */
    suspend fun exportWorld(worldName: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val worldDir = File(serverDir, worldName)
            if (!worldDir.exists() || !File(worldDir, "level.dat").exists()) {
                return@withContext Result.failure(Exception("世界不存在或已损坏"))
            }

            val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            )
            downloadsDir.mkdirs()

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val zipFile = File(downloadsDir, "${worldName}_$timestamp.zip")

            ZipOutputStream(zipFile.outputStream().buffered()).use { zos ->
                worldDir.walkTopDown().forEach { file ->
                    if (file.isFile) {
                        val relativePath = file.relativeTo(worldDir).path
                        zos.putNextEntry(ZipEntry("$worldName/$relativePath"))
                        file.inputStream().buffered().use { input ->
                            input.copyTo(zos)
                        }
                        zos.closeEntry()
                    }
                }
            }

            Result.success(zipFile.absolutePath)
        } catch (e: Exception) { Result.failure(e) }
    }

    /**
     * 获取世界大小格式化字符串
     */
    fun formatWorldSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f MB".format(mb)
        val gb = mb / 1024.0
        return "%.2f GB".format(gb)
    }

    /**
     * 格式化最后修改时间
     */
    fun formatLastModified(timestamp: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
    }
}
