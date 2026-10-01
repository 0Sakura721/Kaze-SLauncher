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
     * 备份文件名里可用的实例名。
     *
     * 实例名是**用户输入**，与实例目录名（`InstanceStore.sanitize` 出来的）是两回事：
     *  `BackupManager.backup` 用 `"${instance.name}_$stamp.zip"` 拼文件名，而 `list` 用
     * `startsWith("${instance.name}_")` 匹配。名字里带 `/`（或 `\`、`:` 等）时：
     *  - 路径被解析到**子目录**里，而那一级目录从没被创建过 → `FileOutputStream` 直接
     *    FileNotFoundException，备份永远失败；
     *  - Android 上 `:` 等字符在部分文件系统同样非法。
     * 顺带也挡住 `..` 前缀这类会指到备份目录之外的相对路径。
     */
    private fun sanitizeName(name: String): String =
        name.trim()
            .replace(Regex("[\\\\/:*?\"<>|\\s\\u0000-\\u001f]+"), "_")
            .trim('.')

    /** 新备份文件名前缀（`<净化后的实例名>_`） */
    private fun backupPrefix(instance: ServerInstance): String =
        sanitizeName(instance.name).ifBlank { "backup" } + "_"

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
     * 前缀必须带 `_` 分隔符，否则 `survival` 会匹配到 `survival2` 的备份；
     * 也必须与 [backup] **用同一个净化函数**，否则名字里带 `/` 的实例新建的备份（文件名里是 `_`）
     * 会被这里的前缀匹配漏掉 —— 备份建出来了却列不出来，等于没备份。
     */
    fun list(instance: ServerInstance): List<File> {
        val own = backupsRoot(instance)
            .listFiles { f: File -> f.isFile && f.name.endsWith(".zip") }
        val legacyPrefix = backupPrefix(instance)
        val legacy = backupParent(instance)
            .listFiles { f: File ->
                f.isFile && f.name.endsWith(".zip") && f.name.startsWith(legacyPrefix)
            }
        return ((own ?: emptyArray()) + (legacy ?: emptyArray()))
            .distinct()
            .sortedByDescending { it.lastModified() }
    }

    /**
     * 创建备份，返回备份文件。
     *
     * **先写同目录的 `.part`，全部写完后才 rename 成正式名**。
     * 直接写最终文件名的话，中途失败（世界 region 被占住读不了、空间不足、进程被杀）会留下
     * 一个"能列出、能解压、但少了几个 region"的半截 zip —— 恢复它就是把世界覆盖成残缺版本，
     * 比没有备份更危险，而 [list] 完全看不出它坏了。
     * 同目录 rename 在同一文件系统上是原子的，所以 [list] 只会看到两种状态：不存在、完整。
     *
     * `.part` 后缀也让中间态天然不被 [list] 命中（它只认 `.zip` 结尾）。
     */
    @Throws(Exception::class)
    fun backup(instance: ServerInstance): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dir = backupsRoot(instance)
        // 名字必须净化：见 [sanitizeName]（带 `/` 的名字会让路径落进不存在的子目录 → 备份必然失败）
        val dest = File(dir, "${backupPrefix(instance)}$stamp.zip")
        val tmp = File(dir, "${dest.name}.part")
        try {
            ZipOutputStream(FileOutputStream(tmp)).use { zip ->
                val base = instance.dir
                fun walk(d: File) {
                    d.listFiles()?.sortedBy { it.name }?.forEach { f ->
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
            if (!tmp.renameTo(dest)) {
                // 少数文件系统上 rename 会被拒；退回拷贝，成功了才删临时文件
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            // 失败绝不留下半截文件：既不能当备份用，又会被用户当成"备份成功了"
            tmp.delete()
            throw e
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
            if (!movedIn) {
                // 换入失败：**一律**回滚，并且绝不删 keepOld。
                //
                // 旧实现的条件是 `!movedIn && !instance.dir.exists()`：只要失败期间实例目录被
                // 重新创建（日志落盘的 parentFile.mkdirs()、或仍在运行的进程往目录里写文件），
                // 这个分支就会被跳过，紧接着的 `keepOld.deleteRecursively()` 会把旧数据删掉，
                // 而 finally 又删掉 tmp —— 两份数据同时消失，而界面还提示"已恢复"。
                if (instance.dir.exists()) {
                    // 目标位置被占：先挪开，再把旧目录放回去
                    val conflict = File(parent, "restore_conflict_$ts")
                    if (instance.dir.renameTo(conflict)) {
                        if (keepOld.exists()) keepOld.renameTo(instance.dir)
                        conflict.deleteRecursively()
                    } else {
                        // 挪不动就什么都不删，把旧数据留在原地并告知路径
                        throw IllegalStateException(
                            "恢复换入失败，且实例目录被占用；原数据保留在 ${keepOld.absolutePath}"
                        )
                    }
                } else if (keepOld.exists()) {
                    keepOld.renameTo(instance.dir)
                }
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
