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

    /** 文件名前缀：净化后的实例名（+ `_`），例：`生存_正式_` */
    private fun namePrefix(instance: ServerInstance): String =
        sanitizeName(instance.name).ifBlank { "backup" } + "_"

    /** 平铺布局里的文件名必须**以这个前缀开头**才算该实例的备份（见 [isLegacyBackupOf]） */
    private fun backupPrefix(instance: ServerInstance): String = namePrefix(instance)

    /** 实例标识：`<净化名>_<实例 id>_<时间戳>.zip` 里那段 id 就是它 */
    private fun idMarker(instance: ServerInstance): String = "_${instance.id}_"

    /** 文件名里那段 id 的位置；`-1` = 这个文件名没有 id 标记 */
    private fun idMarkerAt(fileName: String, instance: ServerInstance): Int =
        fileName.indexOf(idMarker(instance))

    /**
     * 平铺布局（旧版）里的这个文件是不是**该实例**的备份。
     *
     * 旧实现只比显示名前缀，两个真实后果：
     *  - **同名实例串台**：`<根>/a/survival` 与 `<根>/b/survival` 的备份都叫 `survival_<时间戳>.zip`，
     *    时间戳又只到秒，恢复时可能把另一个实例的存档盖到自己头上；
     *  - **改名即孤儿**：备份文件名里写的是当时的显示名，重命名实例（`InstanceStore.rename`）
     *    之后 `list()` 再也匹配不到，老备份在界面里直接消失 —— 文件还在磁盘上，用户却以为丢了。
     *
     * 所以判定分两路：
     *  1. 文件名里带 id 标记（`<名字>_<id>_<时间戳>.zip`，新版本写的）→ 只认 id，与名字无关；
     *  2. 老备份没有 id → 退化成"前缀 + 时间戳形状"，再额外要求**目录名也不冲突**：
     *     只有当目录里没有别的实例（同名实例）时才把它算作本实例的，宁可漏认也不串台。
     */
    private fun isLegacyBackupOf(instance: ServerInstance, f: File): Boolean {
        if (!f.isFile || !f.name.endsWith(".zip")) return false
        val named = namePrefix(instance)
        val marked = idMarkerAt(f.name, instance)
        if (marked >= 0) return true                       // id 命中：与显示名无关
        if (!f.name.startsWith(named)) return false
        // 老备份：`<名字>_<8位日期>-<6位时间>.zip`（SimpleDateFormat("yyyyMMdd-HHmmss")）
        if (!Regex("^\\d{8}-\\d{6}\\.zip$").matches(f.name.removePrefix(named))) return false
        // 同名实例（另一个实例的目录名 == 本实例的目录名是不可能的，但显示名可以相同）：
        // 目录名不同、名字相同的两个实例，老备份无法区分 → 谁都不要认，避免恢复错存档
        return !hasSiblingInstanceWithSameName(instance)
    }

    private fun hasSiblingInstanceWithSameName(instance: ServerInstance): Boolean =
        backupParent(instance).listFiles()
            ?.count { it.isDirectory && it.name == instance.dir.name } != 1

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
     * 同时兼容旧布局：平铺在 `backups/` 下的 `<实例名>_<时间戳>.zip` 依然可见、可恢复
     *（判定见 [isLegacyBackupOf]：新备份靠文件名里的实例 id 认，老备份靠"前缀 + 时间戳形状"，
     * 且只在没有同名实例时才认，宁可漏认也不串台）。
     */
    fun list(instance: ServerInstance): List<File> {
        val own = backupsRoot(instance)
            .listFiles { f: File -> f.isFile && f.name.endsWith(".zip") }
        val legacy = backupParent(instance)
            .listFiles { f: File -> isLegacyBackupOf(instance, f) }
        return ((own ?: emptyArray()) + (legacy ?: emptyArray()))
            .distinct()
            .sortedByDescending { it.lastModified() }
    }

    /**
     * 创建备份，返回备份文件。
     *
     * 文件名 = `<净化后的实例名>_<实例 id>_<时间戳>.zip`。
     * **必须带上实例 id**：显示名可以随时改（`InstanceStore.rename`），也可以两个实例起同一个
     * 名字，只靠名字前缀的话改名后老备份认不出来（界面上直接消失）、同名实例之间还会互相串
     *（恢复时把别人的存档盖到自己头上）。id 稳定且唯一，[list] 因此不依赖显示名。
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
    fun backup(instance: ServerInstance): File = backup(instance, prefix = "")

    /**
     * 创建备份，返回备份文件（[prefix] 供自动备份加 [AUTO_PREFIX] 用，手动传空串）。
     *
     * 文件名 = `<前缀><净化后的实例名>_<实例 id>_<时间戳>.zip`。
     * **必须带上实例 id**：显示名可以随时改（`InstanceStore.rename`），也可以两个实例起同一个
     * 名字，只靠名字前缀的话改名后老备份认不出来（界面上直接消失）、同名实例之间还会互相串
     *（恢复时把别人的存档盖到自己头上）。id 稳定且唯一，[list] 因此不依赖显示名。
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
    fun backup(instance: ServerInstance, prefix: String): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dir = backupsRoot(instance)
        // 名字必须净化：见 [sanitizeName]（带 `/` 的名字会让路径落进不存在的子目录 → 备份必然失败）
        val dest = File(dir, "$prefix${namePrefix(instance)}${instance.id}_$stamp.zip")
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

    /** 自动备份文件名前缀（与手动备份区分；清理只清这一系列，见 [pruneAutoBackups]） */
    const val AUTO_PREFIX = "auto_"

    /** 自动备份保留份数：超出后从最旧开始删 */
    const val AUTO_KEEP = 5

    /**
     * 自动备份的**完整判别前缀**：`auto_ + 该实例的名字前缀`。
     *
     * 不能只认裸 `auto_`：实例名本身就以 `auto_` 开头时（比如实例叫 `auto_survival`），
     * 它的**手动备份** `auto_survival_<id>_<时间>.zip` 同样以 `auto_` 开头——
     * 用裸前缀判别会把手动备份误认成自动备份，被 [pruneAutoBackups] 当冗余清掉。
     * 自动备份恒为 `auto_<名字>_<id>_…`，手动恒为 `<名字>_<id>_…`，同一实例专属目录内
     * 两者用完整前缀一定能区分开（目录按实例隔离，不存在跨实例撞名）。
     */
    private fun autoPrefix(instance: ServerInstance): String = AUTO_PREFIX + namePrefix(instance)

    /**
     * 停服自动备份（[ServerInstance.autoBackup] 开着时，进程退出收尾处调用）。
     *
     * 与手动备份的差异：
     *  - 文件名带 [AUTO_PREFIX]；[pruneAutoBackups] 只清这一系列，手动备份永不触碰；
     *  - 失败**静默**（返回 null）：自动路径没有用户盯着弹窗，失败只由调用方写一条控制台行，
     *    绝不能让备份把停止流程本身打断。
     */
    fun autoBackup(instance: ServerInstance): File? = try {
        val f = backup(instance, AUTO_PREFIX)
        pruneAutoBackups(instance)
        f
    } catch (_: Exception) {
        null
    }

    /** 该实例的自动备份（新→旧；只认 [autoPrefix] 完整前缀，手动备份——哪怕是 `auto_` 开头的实例名——不在列） */
    internal fun autoBackups(instance: ServerInstance): List<File> =
        backupsDir(instance)
            .listFiles { f: File -> f.isFile && f.name.startsWith(autoPrefix(instance)) && f.name.endsWith(".zip") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    /** 自动备份只留最近 [keep] 份（最旧的删除）；无 [AUTO_PREFIX] 的手动备份永不触碰 */
    fun pruneAutoBackups(instance: ServerInstance, keep: Int = AUTO_KEEP) {
        autoBackups(instance).drop(keep).forEach { it.delete() }
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
