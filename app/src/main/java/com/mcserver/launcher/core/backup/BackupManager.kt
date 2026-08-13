package com.mcserver.launcher.core.backup

import com.mcserver.launcher.data.AppPaths
import com.mcserver.launcher.util.KLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class BackupEntry(val file: File, val sizeMb: Long, val time: Long)

/** 实例备份：zip 流式打包 / 列表 / 恢复（带 pre-restore 安全备份）/ 删除 */
object BackupManager {

    private val fmt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault())
    private val skipDirs = setOf("logs", ".cache")
    private val skipFiles = setOf("session.lock")

    suspend fun backup(instanceId: String, instanceName: String): Result<File> =
        withContext(Dispatchers.IO) {
            try {
                val dir = AppPaths.instanceDir(instanceId)
                val name = "$instanceName-${fmt.format(Date())}.zip"
                val out = File(AppPaths.backupsDir, name)
                ZipOutputStream(FileOutputStream(out)).use { zip ->
                    zipDir(zip, dir, "")
                }
                KLog.i("备份完成: ${out.name} (${out.length() / 1024 / 1024}MB)")
                Result.success(out)
            } catch (e: Exception) {
                KLog.e("备份失败", e)
                Result.failure(e)
            }
        }

    private fun zipDir(zip: ZipOutputStream, dir: File, prefix: String) {
        dir.listFiles()?.sortedBy { it.name }?.forEach { f ->
            val rel = if (prefix.isEmpty()) f.name else "$prefix/${f.name}"
            when {
                f.isDirectory -> {
                    if (f.name in skipDirs) return@forEach
                    zip.putNextEntry(ZipEntry("$rel/"))
                    zip.closeEntry()
                    zipDir(zip, f, rel)
                }

                f.name in skipFiles || f.name.endsWith(".tmp") || f.name.endsWith(".part") ->
                    return@forEach

                else -> {
                    zip.putNextEntry(ZipEntry(rel))
                    FileInputStream(f).use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    fun list(): List<BackupEntry> = AppPaths.backupsDir.listFiles()
        ?.filter { it.extension.equals("zip", true) }
        ?.map { BackupEntry(it, it.length() / 1024 / 1024, it.lastModified()) }
        ?.sortedByDescending { it.time }
        ?: emptyList()

    /** 恢复：先安全备份现有目录（pre-restore），再解压覆盖 */
    suspend fun restore(instanceId: String, backupFile: File): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val dir = AppPaths.instanceDir(instanceId)
                val hasContent = dir.exists() && dir.listFiles()?.isNotEmpty() == true
                if (hasContent) {
                    val pre = File(AppPaths.backupsDir, "pre-restore-${System.currentTimeMillis()}")
                    if (!dir.renameTo(pre)) {
                        return@withContext Result.failure(Exception("无法移动现有目录，恢复中止"))
                    }
                }
                dir.mkdirs()
                unzip(backupFile, dir)
                KLog.i("恢复完成: ${backupFile.name}")
                Result.success(Unit)
            } catch (e: Exception) {
                KLog.e("恢复失败", e)
                Result.failure(e)
            }
        }

    private fun unzip(zipFile: File, destDir: File) {
        ZipInputStream(FileInputStream(zipFile)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val out = File(destDir, entry.name)
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { zis.copyTo(it) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    fun delete(file: File): Boolean = try {
        file.delete()
    } catch (e: Exception) {
        false
    }
}