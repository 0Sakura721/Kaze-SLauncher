package com.mcserver.launcher.server

import com.mcserver.launcher.McApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文件管理器 — 浏览服务器目录结构、查看崩溃报告、管理世界文件夹。
 */
object FileManager {

    private val serverDir: File get() = TermuxManager.serverDir(McApplication.instance)

    data class FileEntry(
        val name: String,
        val path: String,
        val isDirectory: Boolean,
        val size: Long,
        val lastModified: Long,
        val isJar: Boolean = false,
        val isLog: Boolean = false,
        val isConfig: Boolean = false
    )

    /** 获取服务器根目录文件列表 */
    suspend fun listServerRoot(): List<FileEntry> = withContext(Dispatchers.IO) {
        listDirectory(serverDir)
    }

    /** 获取指定目录文件列表 */
    suspend fun listDirectory(dir: File): List<FileEntry> = withContext(Dispatchers.IO) {
        if (!dir.exists() || !dir.isDirectory) return@withContext emptyList()
        dir.listFiles()?.map { f ->
            val ext = f.extension.lowercase()
            FileEntry(
                name = f.name,
                path = f.absolutePath,
                isDirectory = f.isDirectory,
                size = f.length(),
                lastModified = f.lastModified(),
                isJar = ext == "jar",
                isLog = ext == "log" || ext == "gz" || f.name.startsWith("crash-"),
                isConfig = ext in listOf("yml", "yaml", "json", "properties", "txt", "toml")
            )
        }?.sortedWith(compareByDescending<FileEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
            ?: emptyList()
    }

    /** 获取世界文件夹列表 */
    suspend fun listWorlds(): List<FileEntry> = withContext(Dispatchers.IO) {
        serverDir.listFiles()?.filter { f ->
            f.isDirectory && File(f, "level.dat").exists()
        }?.map { f ->
            var totalSize = 0L
            f.walkTopDown().forEach { if (it.isFile) totalSize += it.length() }
            FileEntry(
                name = f.name,
                path = f.absolutePath,
                isDirectory = true,
                size = totalSize,
                lastModified = f.lastModified()
            )
        }?.sortedByDescending { it.lastModified } ?: emptyList()
    }

    /** 获取崩溃报告列表 */
    suspend fun listCrashReports(): List<FileEntry> = withContext(Dispatchers.IO) {
        val crashDir = File(serverDir, "crash-reports")
        if (!crashDir.exists()) return@withContext emptyList()
        crashDir.listFiles()?.filter { it.isFile && it.extension == "txt" }?.map { f ->
            FileEntry(
                name = f.name,
                path = f.absolutePath,
                isDirectory = false,
                size = f.length(),
                lastModified = f.lastModified(),
                isLog = true
            )
        }?.sortedByDescending { it.lastModified } ?: emptyList()
    }

    /** 读取崩溃报告内容（最多 200 行） */
    suspend fun readCrashReport(file: File): String = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext "文件不存在"
        file.readLines().take(200).joinToString("\n")
    }

    /** 读取配置文件内容 */
    suspend fun readFileContent(file: File): String = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext "文件不存在"
        if (file.length() > 2 * 1024 * 1024) return@withContext "文件过大（>2MB），无法预览"
        file.readText()
    }

    /** 写入配置文件 */
    suspend fun writeFileContent(file: File, content: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            file.writeText(content)
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    /** 删除文件或目录 */
    suspend fun deleteFile(file: File): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (file.isDirectory) file.deleteRecursively() else file.delete()
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    /** 获取文件/目录大小描述 */
    fun formatFileSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0; if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0; if (mb < 1024) return "%.1f MB".format(mb)
        return "%.2f GB".format(mb / 1024.0)
    }

    /** 格式化时间 */
    fun formatTime(timestamp: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
    }

    /** 获取总磁盘使用情况 */
    data class DiskUsage(val totalFiles: Int, val totalSize: Long)

    suspend fun getDiskUsage(): DiskUsage = withContext(Dispatchers.IO) {
        var files = 0
        var size = 0L
        serverDir.walkTopDown().forEach {
            if (it.isFile && it.name != "cmdpipe") {
                files++
                size += it.length()
            }
        }
        DiskUsage(files, size)
    }
}
