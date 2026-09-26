package com.kaze.newage.data

import android.content.Context
import com.kaze.newage.data.model.CoreType
import com.kaze.newage.data.model.ServerInstance
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** 实例持久化模型（序列化友好） */
@Serializable
data class StoredInstance(
    val id: String,
    val name: String,
    val coreType: String,
    val mcVersion: String,
    val javaMajor: Int,
    val memoryMb: Int,
    val nogui: Boolean,
    val autoRestart: Boolean,
    val maxRestarts: Int,
    val dirPath: String,
) {
    fun toInstance(): ServerInstance = ServerInstance(
        id = id,
        name = name,
        coreType = CoreType.entries.firstOrNull { it.name == coreType } ?: CoreType.CUSTOM,
        mcVersion = mcVersion,
        javaMajor = javaMajor,
        memoryMb = memoryMb,
        nogui = nogui,
        autoRestart = autoRestart,
        maxRestarts = maxRestarts,
        dir = File(dirPath),
    )

    companion object {
        fun from(instance: ServerInstance): StoredInstance = StoredInstance(
            id = instance.id,
            name = instance.name,
            coreType = instance.coreType.name,
            mcVersion = instance.mcVersion,
            javaMajor = instance.javaMajor,
            memoryMb = instance.memoryMb,
            nogui = instance.nogui,
            autoRestart = instance.autoRestart,
            maxRestarts = instance.maxRestarts,
            dirPath = instance.dir.absolutePath,
        )
    }
}

/** 实例存储：内存 StateFlow + JSON 文件持久化；实例目录支持自定义（SAF 选择） */
class InstanceStore(
    context: Context,
    private val prefs: com.kaze.newage.data.prefs.SettingsPrefs,
) {

    private val context: Context = context.applicationContext
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    /**
     * 实例库落在**内部** `filesDir`。
     *
     * 原来用 `getExternalFilesDir(null)`：
     *  1. 它可能返回 **null**（外部存储未挂载），`File(null, "instances.json")` 会退化成
     *     相对进程 CWD 的路径 —— 写不进去，而 [save] 又把异常吞了，用户看到实例"建好了"、
     *     重启后全没了；
     *  2. Android 7–10 上外部目录任何持有 WRITE_EXTERNAL_STORAGE 的应用都能改写，
     *     实例库被改等于数据丢失/被指向别的 jar。
     * 旧位置的文件会在 [migrateLegacyStore] 里一次性迁过来。
     */
    private val storeFile: File = File(context.filesDir, "instances.json")
    private val backupFile: File = File(context.filesDir, "instances.json.bak")

    /** 旧版把实例库放在这里（外部私有目录），仅用于一次性迁移 */
    private val legacyStoreFile: File? =
        runCatching { context.getExternalFilesDir(null)?.let { File(it, "instances.json") } }.getOrNull()

    /**
     * 实例库最近一次读写出的问题。这类失败必须让用户看见：否则实例"建好了"却会在重启后消失。
     *
     * 读取告警与保存错误**分开**两个流：合成一条的话，损坏提示会在紧随其后的那次
     * `save()` 成功时被抹掉，而那次 save 写的恰恰是"恢复出来的子集"，用户就看不到
     * 自己丢了东西。
     */
    private val _loadWarning = MutableStateFlow<String?>(null)
    val loadWarning: StateFlow<String?> = _loadWarning.asStateFlow()

    private val _saveError = MutableStateFlow<String?>(null)
    val saveError: StateFlow<String?> = _saveError.asStateFlow()

    private val _instances = MutableStateFlow<List<ServerInstance>>(emptyList())
    val instances: StateFlow<List<ServerInstance>> = _instances.asStateFlow()

    init {
        migrateLegacyStore()
        rescan()
    }

    /** 把旧版放在外部私有目录的实例库迁到内部（只做一次，且不覆盖已有的内部文件） */
    private fun migrateLegacyStore() {
        runCatching {
            val legacy = legacyStoreFile ?: return
            if (storeFile.isFile || !legacy.isFile) return
            legacy.copyTo(storeFile, overwrite = false)
        }
    }

    /** 重新加载：读 JSON + 扫描实例根目录（切换自定义目录后调用，可直接识别新目录里的既有服务端）。
     *  与 add/remove/rename 同锁：多个协程（下载完成/删实例/切目录）并发时防丢更新 */
    @Synchronized
    fun rescan() {
        _instances.value = load()
        // 迁移必须放在 load() 之后：要按已加载的实例列表同步它们记录的目录
        migrateInstancesToSharedRoot()
        save() // 目录扫描恢复出的实例回存 JSON
    }

    /**
     * 共享存储里的默认实例根：`<手机根目录>/KazeS`，例如 `/sdcard/KazeS`。
     *
     * 放这里是让**存档、服务端 jar、运行日志、备份**都在用户能直接看到的地方 ——
     * 传存档、手动备份、用文件管理器翻世界文件夹，都不需要 root，也不用记
     * `Android/data/...` 这种路径。
     */
    fun sharedRoot(): File? = runCatching {
        @Suppress("DEPRECATION")
        val base = android.os.Environment.getExternalStorageDirectory() ?: return null
        File(base, "KazeS")
    }.getOrNull()

    /**
     * 目录是否**真的可写**。
     *
     * 只看 `isDirectory` 不够：没有共享存储权限（Android 11+ 的「所有文件访问」或
     * 11 以下的 WRITE_EXTERNAL_STORAGE）时目录可能建得出来却写不进去，那样实例会
     * 建到一半才失败。这里实地写一个探针文件。
     */
    private fun ensureWritable(dir: File): Boolean = runCatching {
        if (!dir.isDirectory && !dir.mkdirs()) return false
        val probe = File(dir, ".kaze-write-probe")
        probe.writeText("ok")
        probe.delete()
        true
    }.getOrDefault(false)

    /**
     * 把旧默认根（app 外部私有目录 `Android/data/<pkg>/files/instances`）里已有的实例
     * 搬到共享根。同一分区内是 rename，几 GB 也是瞬间完成；搬不动就跳过（实例记录里的
     * 路径是绝对的，留在原处照常能用，只是不在 KazeS 里）。
     *
     * 搬完必须同步实例记录里的 dirPath，否则重启后指向已经不存在的旧目录。
     */
    private fun migrateInstancesToSharedRoot() {
        if (prefs.instanceDirPath.value.isNotBlank()) return // 用户自己指定了目录，不动
        val target = sharedRoot() ?: return
        if (!ensureWritable(target)) return
        runCatching {
            val old = File(context.getExternalFilesDir(null), "instances")
            if (!old.isDirectory) return
            val oldCanon = runCatching { old.canonicalPath }.getOrElse { old.absolutePath }
            val newCanon = runCatching { target.canonicalPath }.getOrElse { target.absolutePath }
            if (oldCanon == newCanon || newCanon.startsWith(oldCanon)) return
            val dirs = old.listFiles()?.filter { it.isDirectory } ?: return
            val moved = mutableMapOf<String, String>()
            dirs.forEach { src ->
                val dst = File(target, src.name)
                if (dst.exists()) return@forEach
                if (src.renameTo(dst)) moved[src.absolutePath] = dst.absolutePath
            }
            if (moved.isNotEmpty()) {
                _instances.value = _instances.value.map { inst ->
                    moved[inst.dir.absolutePath]?.let { inst.copy(dir = File(it)) } ?: inst
                }
            }
        }
    }

    /**
     * 实例根目录，按优先级：
     *  1. 用户在「设置 → 存储」里指定的目录；
     *  2. **共享存储的 `KazeS/`**（`/sdcard/KazeS`）—— 默认值，用户能直接用文件管理器看到；
     *  3. app 外部私有目录 `Android/data/<pkg>/files/instances` —— 没拿到共享存储权限时的兜底。
     *
     * 第 2 步必须**实地探测可写**才能采用：Android 11+ 需要「所有文件访问」、
     * 11 以下需要 WRITE_EXTERNAL_STORAGE，没授权时目录可能建得出来却写不进去。
     */
    fun instancesRoot(): File {
        val custom = prefs.instanceDirPath.value.takeIf { it.isNotBlank() }
            ?.let { File(it) }
            ?.takeIf { it.isDirectory }
        if (custom != null) return custom.apply { mkdirs() }

        sharedRoot()?.let { if (ensureWritable(it)) return it }
        return File(context.getExternalFilesDir(null), "instances").apply { mkdirs() }
    }

    /**
     * 取一个可用的实例目录（不与已有非空目录冲突）。
     *
     * 两道防线：
     *  1. [sanitize] 去掉首尾点号，使 "." / ".." 不可能成为目录名；
     *  2. 这里再用 canonicalPath 断言结果确实落在实例根之内。
     *
     * 为什么必须防：`File(instancesRoot(), "..")` 会被系统解析到实例根的**父目录**
     * （默认 = 应用外部私有目录 `files/`，里面装着全部实例、世界存档与 instances.json），
     * 而删除实例时会 `deleteRecursively()`——一个名为 ".." 的实例被删除就等于清空全部数据；
     * 名为 "." 则直接指向实例根本身，同样一次删光。
     *
     * 同名不再复用：向导的默认名是「核心-版本」，用户不改名连续建两次会落到同一目录，
     * 第二条实例的 core jar 覆盖第一条的、新建时的 propsOverride 还会改写第一条的
     * server.properties（端口/正版验证/游戏模式被换掉），而两条记录指向同一目录互相踩。
     */
    fun createInstanceDir(name: String): File {
        val root = instancesRoot()
        val canonicalRoot = runCatching { root.canonicalFile }.getOrElse { root.absoluteFile }
        val base = sanitize(name)
        var dir = File(canonicalRoot, base)
        var n = 2
        // 已存在且非空才让位；空目录可以复用（下载失败后重试的场景）
        while (dir.exists() && !dir.listFiles().isNullOrEmpty()) {
            dir = File(canonicalRoot, "$base ($n)")
            n++
        }
        val canonicalDir = runCatching { dir.canonicalFile }.getOrElse { dir.absoluteFile }
        check(
            canonicalDir.path == canonicalRoot.path ||
                canonicalDir.path.startsWith(canonicalRoot.path + File.separator)
        ) { "实例名不合法：$name" }
        canonicalDir.mkdirs()
        return canonicalDir
    }

    @Synchronized
    fun add(instance: ServerInstance) {
        _instances.value = _instances.value + instance
        save()
    }

    @Synchronized
    fun remove(id: String) {
        _instances.value = _instances.value.filterNot { it.id == id }
        save()
    }

    /** 重命名实例（只改软件里的显示名，不影响目录/server.properties/MOTD） */
    @Synchronized
    fun rename(id: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        _instances.value = _instances.value.map {
            if (it.id == id) it.copy(name = trimmed) else it
        }
        save()
    }

    fun get(id: String): ServerInstance? = _instances.value.firstOrNull { it.id == id }

    private fun load(): List<ServerInstance> {
        // 1) 正常读 JSON。解析失败**绝不能**当成"没有实例"：
        //    旧实现直接 emptyList()，紧接着 rescan() 把空列表写回去 —— 一旦文件半截
        //    （writeText 先截断再写，进程被杀/掉电/FUSE 短写都会留下半截），用户的
        //    全部实例就永久消失了。现在先把坏文件留档，再退回上一次的备份。
        val text = runCatching { storeFile.takeIf { it.isFile }?.readText() }.getOrNull()
        _loadWarning.value = null
        val fromJson = if (text == null) {
            emptyList()
        } else {
            runCatching { json.decodeFromString<List<StoredInstance>>(text).map { it.toInstance() } }
                .getOrElse { e ->
                    val archived = runCatching {
                        val dst = File(storeFile.parentFile, storeFile.name + ".corrupt-" + System.currentTimeMillis())
                        storeFile.renameTo(dst)
                    }.getOrDefault(false)
                    _loadWarning.value = buildString {
                        append("实例列表读取失败：").append(e.message ?: e.javaClass.simpleName)
                        if (archived) append("（原文件已留档为 instances.json.corrupt-…）")
                    }
                    runCatching {
                        backupFile.takeIf { it.isFile }?.readText()?.let { b ->
                            json.decodeFromString<List<StoredInstance>>(b).map { it.toInstance() }
                        }
                    }.getOrNull() ?: emptyList()
                }
        }

        // 2) 目录扫描恢复：JSON 丢失（内部存储不可靠）但实例目录还在时重建记录
        val recovered = recoverFromDirs()

        // 合并：JSON 优先，目录里多出的实例补回来
        val merged = fromJson + recovered.filter { r -> fromJson.none { it.id == r.id || it.dir == r.dir } }

        // 3) 迁移：新版 MC（26.x 起，如 26.1.1）需要 Java 25；旧档 javaMajor 21 直接启动会报
        // UnsupportedClassVersionError（实测 bundler class file 69.0）
        return merged.map { inst ->
            val m = Regex("""(\d+)\.(\d+)""").find(inst.mcVersion)
            if (m != null && m.groupValues[1].toInt() >= 26 && inst.javaMajor < 25) {
                inst.copy(javaMajor = 25)
            } else inst
        }
    }

    /** 从实例目录重建：vanilla-X.Y.Z.jar / paper-X.jar 等文件名推断元数据 */
    private fun recoverFromDirs(): List<ServerInstance> {
        val root = instancesRoot()
        return try {
            // 跳过隐藏目录与备份/恢复临时目录：BackupManager 的 restore_tmp_*/restore_old_*
            // 里也有 .jar，被扫到就会在实例列表里冒出指向临时目录的"幻影实例"，
            // 而且 rescan() 会把它写进 JSON 长期留存。
            val dirs = root.listFiles()
                ?.filter { it.isDirectory && !it.name.startsWith(".") && !it.name.startsWith("restore_") }
                ?: emptyList()
            dirs.mapNotNull { dir ->
                val jar = dir.listFiles()?.firstOrNull { it.isFile && it.name.endsWith(".jar") && !it.name.contains("installer", true) }
                    ?: return@mapNotNull null
                val name = jar.name.removeSuffix(".jar")
                val parts = name.split("-")
                val core = when {
                    parts.firstOrNull()?.equals("vanilla", true) == true -> CoreType.VANILLA
                    parts.firstOrNull()?.equals("paper", true) == true -> CoreType.PAPER
                    else -> CoreType.CUSTOM
                }
                val mcVersion = parts.getOrNull(1)?.takeIf { it.firstOrNull()?.isDigit() == true } ?: ""
                ServerInstance(
                    id = dir.name,
                    name = dir.name,
                    coreType = core,
                    mcVersion = mcVersion,
                    javaMajor = com.kaze.newage.data.model.JavaVersionInference.infer(mcVersion),
                    memoryMb = 2048,
                    nogui = true,
                    autoRestart = false,
                    maxRestarts = 3,
                    dir = dir,
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    /**
     * 原子写实例库：先写 `instances.json.tmp` 并 `fd.sync()`，再 rename 覆盖正式文件，
     * 写成功前把上一份留成 `instances.json.bak`。
     *
     * 原来的 `writeText` 是"先截断再写"：进程被杀 / 掉电 / FUSE 短写都会留下半截 JSON，
     * 而 [load] 解析失败会静默返回空列表、紧接着 [rescan] 又写回去 —— 实例全没了。
     * rename 在同一文件系统上是原子的，所以正式文件要么是旧的完整内容、要么是新的完整内容。
     *
     * 失败不再静默：[saveError] 会带上原因，界面据此提示。
     */
    private fun save(): Boolean = try {
        storeFile.parentFile?.mkdirs()
        val text = Json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(StoredInstance.serializer()),
            _instances.value.map { StoredInstance.from(it) },
        )
        val tmp = File(storeFile.parentFile, storeFile.name + ".tmp")
        java.io.FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        if (storeFile.isFile) runCatching { storeFile.copyTo(backupFile, overwrite = true) }
        if (!tmp.renameTo(storeFile)) {
            // 少数文件系统上 rename 可能失败，退回覆盖写（至少 tmp 已落盘）
            runCatching { tmp.copyTo(storeFile, overwrite = true) }
            tmp.delete()
        }
        _saveError.value = null
        true
    } catch (e: Exception) {
        _saveError.value = "实例列表保存失败：${e.message ?: e.javaClass.simpleName}"
        false
    }

    /**
     * 目录名净化。
     *
     * 除了替换文件系统非法字符，还必须 `trim('.')`：`.` 与 `..` 不含任何会被替换的字符，
     * 原样传给 `File(root, name)` 会被解析成 root 自身 / root 的父目录（见 [createInstanceDir]）；
     * 顺带也避免生成 `.hidden` 这类隐藏目录。
     */
    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
            .trim()
            .trim('.')
            .ifBlank { "instance" }
}
