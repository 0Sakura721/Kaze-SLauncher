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
 *
 * 排除项（非玩家数据，可再生，占体积大头）：
 *  - cache/（Paper/Purpur 预置的原版 jar ~50MB/核心）
 *  - logs/、crash-reports/
 *  - 运行日志 console-output*.log、下载残留 *.part
 */
object BackupManager {

    /** 打包时跳过的顶层目录名 */
    private val EXCLUDED_DIRS = setOf("cache", "logs", "crash-reports")

    /** 备份根目录（实例目录之外，避免被备份自身包含） */
    private fun backupParent(instance: ServerInstance): File =
        File(instance.dir.parentFile ?: instance.dir, "backups")

    /**
     * 该实例专属的备份目录：`backups/<实例目录名>/`
     *
     * 旧布局把所有实例的备份平铺在共享的 `backups/` 下、靠文件名前缀区分，后果是：
     *  - `deleteAllBackups` 直接 `deleteRecursively()` 该共享目录 → 删一个实例会清空**所有**实例的备份；
     *  - `list` 用 `startsWith(instance.name)` → `survival` 会串到 `survival2` 的备份，恢复时可能把
     *    别的实例的存档盖到当前实例上。
     * 目录名取实例目录名（已 sanitize，文件系统安全且实例之间唯一）。
     */
    private fun backupsDir(instance: ServerInstance): File =
        File(backupParent(instance), instance.dir.name)

    private fun backupsRoot(instance: ServerInstance): File =
        backupsDir(instance).apply { mkdirs() }

    private fun isExcluded(relPath: String): Boolean {
        val norm = relPath.replace('\\', '/')
        val top = norm.substringBefore('/')
        if (top in EXCLUDED_DIRS) return true
        val name = norm.substringAfterLast('/')
        return name.endsWith(".part") ||
            name == "console-output.log" || name == "console-output.old.log"
    }

    /**
     * 该实例的全部备份（新→旧）。
     * 同时兼容旧布局：平铺在 `backups/` 下的 `<实例名>_<时间戳>.zip` 依然可见、可恢复。
     * 前缀必须带 `_` 分隔符，否则 `survival` 会匹配到 `survival2` 的备份。
     */
    fun list(instance: ServerInstance): List<File> {
        val own = backupsRoot(instance)
            .listFiles { f: File -> f.isFile && f.name.endsWith(".zip") }
        val legacyPrefix = "${instance.name}_"
        val legacy = backupParent(instance)
            .listFiles { f: File ->
                f.isFile && f.name.endsWith(".zip") && f.name.startsWith(legacyPrefix)
            }
        return ((own ?: emptyArray()) + (legacy ?: emptyArray()))
            .distinct()
            .sortedByDescending { it.lastModified() }
    }

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
                        if (!isExcluded(rel)) {
                            zip.putNextEntry(ZipEntry(rel))
                            FileInputStream(f).use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
                }
            }
            walk(base)
        }
        return dest
    }

    /**
     * 恢复备份：解压到临时目录 → 旧目录整体改名保留 → 新目录换入 → 成功后才删旧目录。
     * 任一步失败回滚原名，绝不出现"实例数据全丢"的窗口
     * （旧实现 deleteRecursively→renameTo 两步间失败 = 数据清零）。
     */
    @Throws(Exception::class)
    fun restore(instance: ServerInstance, backupFile: File) {
        val ts = System.currentTimeMillis()
        val parent = instance.dir.parentFile ?: throw IllegalStateException("实例目录无父目录")
        val tmp = File(parent, "restore_tmp_$ts")
        val keepOld = File(parent, "restore_old_$ts")
        tmp.mkdirs()
        try {
            ZipInputStream(FileInputStream(backupFile)).use { zin ->
                var entry = zin.nextEntry
                while (entry != null) {
                    // Zip Slip 防护：导入的外部 zip 是不可信输入，
                    // entry.name 带 ../ 时拒绝写出 tmp 目录之外（canonical 前缀校验）
                    val target = File(tmp, entry.name)
                    if (!target.canonicalPath.startsWith(tmp.canonicalPath + File.separator)) {
                        throw SecurityException("备份内含非法路径：${entry.name}")
                    }
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
            // 换入三步：旧目录让位 → 新目录就位（失败即回滚）→ 确认后清理旧目录
            if (instance.dir.exists() && !instance.dir.renameTo(keepOld)) {
                throw IllegalStateException("无法移出当前实例目录（可能有进程占用）")
            }
            val movedIn = try {
                tmp.renameTo(instance.dir)
            } catch (e: Exception) {
                false
            }
            if (!movedIn && !instance.dir.exists()) {
                // 换入失败：旧目录原样滚回
                if (keepOld.exists()) keepOld.renameTo(instance.dir)
                throw IllegalStateException("恢复换入失败，已回滚（实例数据未受影响）")
            }
            if (keepOld.exists()) keepOld.deleteRecursively()
        } finally {
            if (tmp.exists()) tmp.deleteRecursively()
        }
    }

    fun delete(backupFile: File): Boolean = backupFile.delete()

    /**
     * 删除该实例的全部备份（实例被删除时调用）。
     *
     * 注意：旧实现是 `backupsRoot(instance).deleteRecursively()`，而 `backups/` 是**所有实例共享**的
     * 目录，于是删掉一个实例会连带清空其它实例的全部备份。现在只删除 `list(instance)` 命中的文件。
     */
    fun deleteAllBackups(instance: ServerInstance): Boolean {
        var allDeleted = true
        for (f in list(instance)) {
            if (!f.delete()) allDeleted = false
        }
        // 本实例专属目录空了就顺手删掉；非递归，目录非空时 delete 会失败——这正是期望行为
        runCatching { backupsDir(instance).delete() }
        return allDeleted
    }

    /** 导入外部备份 zip（复制到该实例的备份目录），返回目标文件 */
    @Throws(Exception::class)
    fun import(instance: ServerInstance, input: java.io.InputStream, fileName: String): File {
        // SAF 返回的 displayName 可能带路径分隔符，取最后一段，避免写到备份目录之外
        val raw = fileName.substringAfterLast('/').substringAfterLast('\\').trim()
        val base = raw.ifBlank { "imported_${System.currentTimeMillis()}" }
        val safeName = if (base.endsWith(".zip", ignoreCase = true)) base else "$base.zip"
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
