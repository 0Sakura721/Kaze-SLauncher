package com.kaze.newage.core.server

import com.kaze.newage.data.model.ServerInstance
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 实例备份管理：全量 zip（世界 + 配置 + 插件/模组目录）。
 * 备份存放于实例目录外的 backups/ 下（避免被自身包含）。
 */
object BackupManager {

    private fun backupsRoot(instance: ServerInstance): File =
        File(instance.dir.parentFile ?: instance.dir, "backups").apply { mkdirs() }

    /** 该实例的全部备份（新→旧） */
    fun list(instance: ServerInstance): List<File> =
        backupsRoot(instance).listFiles { f ->
            f.isFile && f.name.endsWith(".zip") && f.name.startsWith(instance.name)
        }?.sortedByDescending { it.lastModified() } ?: emptyList()

    /** 创建备份，返回备份文件 */
    @Throws(Exception::class)
    fun backup(instance: ServerInstance): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dest = File(backupsRoot(instance), "${instance.name}_$stamp.zip")
        ZipOutputStream(FileOutputStream(dest)).use { zip ->
            val base = instance.dir
            fun walk(dir: File) {
                dir.listFiles()?.sortedBy { it.name }?.forEach { f ->
                    if (f.isDirectory) walk(f) else {
                        val rel = f.relativeTo(base).path.replace('\\', '/')
                        zip.putNextEntry(ZipEntry(rel))
                        FileInputStream(f).use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }
            walk(base)
        }
        return dest
    }

    /** 恢复备份：清空实例目录（保留 server.jar 外的内容可被备份覆盖）后解压 */
    @Throws(Exception::class)
    fun restore(instance: ServerInstance, backupFile: File) {
        // 解压到临时目录，再整体替换（避免中途失败损坏实例）
        val tmp = File(instance.dir.parentFile, "restore_tmp_${System.currentTimeMillis()}")
        tmp.mkdirs()
        try {
            ZipInputStream(FileInputStream(backupFile)).use { zin ->
                var entry = zin.nextEntry
                while (entry != null) {
                    val target = File(tmp, entry.name)
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { zin.copyTo(it) }
                    }
                    zin.closeEntry()
                    entry = zin.nextEntry
                }
            }
            instance.dir.deleteRecursively()
            tmp.renameTo(instance.dir)
        } finally {
            if (tmp.exists()) tmp.deleteRecursively()
        }
    }

    fun delete(backupFile: File): Boolean = backupFile.delete()

    /** 导入外部备份 zip（复制到备份目录），返回目标文件 */
    @Throws(Exception::class)
    fun import(instance: ServerInstance, input: java.io.InputStream, fileName: String): File {
        val safeName = fileName.ifBlank { "imported_${System.currentTimeMillis()}.zip" }
            .let { if (it.endsWith(".zip")) it else "$it.zip" }
        val dest = File(backupsRoot(instance), safeName)
        input.use { ins -> dest.outputStream().use { outs -> ins.copyTo(outs) } }
        return dest
    }

    /** 导出备份到输出流（SAF CreateDocument 场景） */
    @Throws(Exception::class)
    fun export(backupFile: File, out: java.io.OutputStream) {
        backupFile.inputStream().use { ins -> ins.copyTo(out) }
    }
}
