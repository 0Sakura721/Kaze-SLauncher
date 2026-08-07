package com.mcserver.launcher.core.server

import com.mcserver.launcher.KazeApp
import com.mcserver.launcher.data.ServerInstance
import com.mcserver.launcher.util.Logger
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 世界备份:停止服务器时把世界目录打包为 zip 存到 backups/。
 * 纯 Kotlin ZipOutputStream 实现,零依赖。
 */
object BackupManager {

    val backupsDir: File get() = File(KazeApp.instance.filesDir, "backups").apply { mkdirs() }

    /** 某实例的全部备份列表(按时间倒序) */
    fun backupsFor(instanceId: String): List<File> =
        File(backupsDir, instanceId).listFiles()?.filter { it.name.endsWith(".zip") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

    /** 备份世界目录为 zip(自动查找 world / world_nether / world_the_end 等目录) */
    fun backupWorld(instance: ServerInstance): File? {
        return try {
            val dir = instance.dir(InstanceStore.instancesDir)
            val worldDirs = dir.listFiles()?.filter {
                it.isDirectory && (it.name == "world" || it.name.startsWith("world_"))
            } ?: return null
            if (worldDirs.isEmpty()) return null

            val targetDir = File(backupsDir, instance.id).apply { mkdirs() }
            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
            val zipFile = File(targetDir, "world_$timestamp.zip")
            ZipOutputStream(zipFile.outputStream().buffered()).use { zip ->
                worldDirs.forEach { world ->
                    addToZip(zip, world, world.name)
                }
            }
            // 只保留最近 10 份
            backupsFor(instance.id).drop(10).forEach { it.delete() }
            Logger.i("备份完成: ${zipFile.name} (${zipFile.length() / 1024} KB)")
            zipFile
        } catch (e: Exception) {
            Logger.w("备份失败", e)
            null
        }
    }

    private fun addToZip(zip: ZipOutputStream, dir: File, basePath: String) {
        dir.listFiles()?.forEach { f ->
            val entryPath = "$basePath/${f.name}"
            if (f.isDirectory) {
                zip.putNextEntry(ZipEntry("$entryPath/"))
                zip.closeEntry()
                addToZip(zip, f, entryPath)
            } else {
                zip.putNextEntry(ZipEntry(entryPath))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    /** 还原备份:解压 zip 到实例目录(覆盖世界) */
    fun restoreBackup(instance: ServerInstance, zipFile: File): Boolean {
        return try {
            val dir = instance.dir(InstanceStore.instancesDir)
            val worldDirs = dir.listFiles()?.filter {
                it.isDirectory && (it.name == "world" || it.name.startsWith("world_"))
            } ?: emptyList()
            // 旧世界改名保留(防误删)
            worldDirs.forEach { w ->
                val renamed = File(dir, "${w.name}_old_${System.currentTimeMillis()}")
                w.renameTo(renamed)
            }
            java.util.zip.ZipInputStream(zipFile.inputStream().buffered()).use { zin ->
                var entry = zin.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val dest = File(dir, entry.name)
                        dest.parentFile?.mkdirs()
                        dest.outputStream().use { out -> zin.copyTo(out) }
                    }
                    zin.closeEntry()
                    entry = zin.nextEntry
                }
            }
            Logger.i("备份还原完成: ${zipFile.name}")
            true
        } catch (e: Exception) {
            Logger.w("还原失败", e)
            false
        }
    }

    /** 删除备份文件 */
    fun deleteBackup(instanceId: String, zipName: String): Boolean {
        val f = File(File(backupsDir, instanceId), zipName)
        return if (f.exists()) f.delete() else false
    }
}
