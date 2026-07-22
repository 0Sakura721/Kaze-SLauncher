package com.mcserver.launcher.server

import com.mcserver.launcher.McApplication
import com.mcserver.launcher.utils.L
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 服务器存档/文件备份管理（借鉴 MCSManager 的备份思路）。
 *
 * 备份策略：
 *  - 完整复制整个服务器目录（JAR、server.properties、world/、插件等）到带时间戳的子目录；
 *  - 支持列出历史备份、按名恢复（恢复前会先备份当前状态，便于回滚）；
 *  - 备份目录统一放在服务器目录下的 `backups/`。
 */
object BackupManager {

    private const val TAG = "BackupManager"
    private val serverDir: File get() = ProotServerManager.serverDir(McApplication.instance)
    private val backupsRoot: File get() = File(serverDir, "backups")

    private val _backups = MutableStateFlow<List<BackupEntry>>(emptyList())
    val backups: StateFlow<List<BackupEntry>> = _backups.asStateFlow()

    data class BackupEntry(
        val name: String,
        val dir: File,
        val createdAt: Long,
        val sizeMB: Long
    )

    private fun validateBackupName(name: String) {
        require(name.isNotBlank()) { "备份名称不能为空" }
        require(!name.contains("/") && !name.contains("\\") && !name.contains("..") && !name.contains(":")) {
            "备份名称包含非法字符: $name"
        }
    }

    private fun validatePathInsideRoot(file: File, root: File): Boolean {
        return try {
            file.canonicalFile.startsWith(root.canonicalFile)
        } catch (e: Exception) {
            L.w(TAG, "validatePathInsideRoot failed", e)
            false
        }
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    /** 刷新备份列表（按时间倒序） */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        val list = mutableListOf<BackupEntry>()
        if (backupsRoot.exists()) {
            backupsRoot.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
                var size = 0L
                dir.walkTopDown().forEach { if (it.isFile) size += it.length() }
                val created = dir.name.substringAfter("backup_").let { ts ->
                    runCatching { SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(ts)?.time }
                        .getOrNull() ?: dir.lastModified()
                }
                list.add(BackupEntry(dir.name, dir, created, size / (1024 * 1024)))
            }
        }
        _backups.value = list.sortedByDescending { it.createdAt }
    }

    /**
     * 创建一次备份。返回生成的备份目录名，失败时抛出异常。
     * 跳过正在写入的临时文件与 logs 目录（日志通常不纳入备份）。
     */
    suspend fun createBackup(label: String = ""): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (!serverDir.exists()) return@withContext Result.failure(Exception("服务器目录不存在"))
            // 必须存在一个 JAR 才算有效服务器目录
            val hasJar = serverDir.listFiles()?.any { it.extension == "jar" } ?: false
            if (!hasJar) return@withContext Result.failure(Exception("未找到服务器 JAR，无法备份"))

            if (label.isNotBlank()) {
                validateBackupName(label)
            }

            backupsRoot.mkdirs()
            val name = "backup_${timestamp()}${if (label.isNotBlank()) "_$label" else ""}"
            val target = File(backupsRoot, name)
            if (!validatePathInsideRoot(target, backupsRoot)) {
                return@withContext Result.failure(SecurityException("备份路径越界: $name"))
            }
            copyDirectory(serverDir, target, excludeNames = setOf("backups", "server.log", "cmdpipe", "mcserver.pid"))
            refresh()
            Result.success(name)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 恢复指定备份到服务器目录（恢复前自动做一次当前状态备份，便于回滚） */
    suspend fun restoreBackup(name: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            validateBackupName(name)
            val src = File(backupsRoot, name)
            if (!validatePathInsideRoot(src, backupsRoot)) {
                return@withContext Result.failure(SecurityException("备份路径越界: $name"))
            }
            if (!src.exists() || !src.isDirectory)
                return@withContext Result.failure(Exception("备份不存在：$name"))

            // 回滚点：把当前状态先存一份
            createBackup("before_restore").onFailure { /* 忽略回滚点失败，继续恢复 */ }

            // 清空现有世界与配置（保留 backups 目录）
            serverDir.listFiles()?.forEach { f ->
                if (f.name != "backups") f.deleteRecursively()
            }
            copyDirectory(src, serverDir, excludeNames = setOf("backups"))
            refresh()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 删除一个备份 */
    suspend fun deleteBackup(name: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            validateBackupName(name)
            val dir = File(backupsRoot, name)
            if (!validatePathInsideRoot(dir, backupsRoot)) {
                return@withContext Result.failure(SecurityException("备份路径越界: $name"))
            }
            if (dir.exists()) dir.deleteRecursively()
            refresh()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun copyDirectory(src: File, dst: File, excludeNames: Set<String>) {
        if (src.name in excludeNames) return
        if (src.isDirectory) {
            dst.mkdirs()
            src.listFiles()?.forEach { child ->
                copyDirectory(child, File(dst, child.name), excludeNames)
            }
        } else {
            dst.parentFile?.mkdirs()
            src.copyTo(dst, overwrite = true)
        }
    }
}
