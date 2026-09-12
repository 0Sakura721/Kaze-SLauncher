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

    /** 存储文件放外部存储（真机内部 /data/user/0 的 FUSE 批量写会静默丢失） */
    private val storeFile: File = File(context.getExternalFilesDir(null), "instances.json")

    private val _instances = MutableStateFlow<List<ServerInstance>>(emptyList())
    val instances: StateFlow<List<ServerInstance>> = _instances.asStateFlow()

    init {
        rescan()
    }

    /** 重新加载：读 JSON + 扫描实例根目录（切换自定义目录后调用，可直接识别新目录里的既有服务端）。
     *  与 add/remove/rename 同锁：多个协程（下载完成/删实例/切目录）并发时防丢更新 */
    @Synchronized
    fun rescan() {
        _instances.value = load()
        save() // 目录扫描恢复出的实例回存 JSON
    }

    /**
     * 实例根目录：优先自定义目录（设置→环境→实例存储位置）；
     * 未设置时用 app 外部目录（用户可见）。目录不存在时自动创建。
     */
    fun instancesRoot(): File {
        val custom = prefs.instanceDirPath.value.takeIf { it.isNotBlank() }
            ?.let { File(it) }
            ?.takeIf { it.isDirectory }
        return (custom ?: File(context.getExternalFilesDir(null), "instances")).apply { mkdirs() }
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
        // 1) 正常读 JSON
        val fromJson = try {
            if (!storeFile.exists()) emptyList()
            else json.decodeFromString<List<StoredInstance>>(storeFile.readText()).map { it.toInstance() }
        } catch (_: Exception) { emptyList() }

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
            val dirs = root.listFiles()?.filter { it.isDirectory } ?: emptyList()
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

    private fun save() {
        try {
            storeFile.parentFile?.mkdirs()
            storeFile.writeText(
                Json.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(StoredInstance.serializer()),
                    _instances.value.map { StoredInstance.from(it) },
                )
            )
        } catch (_: Exception) { }
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
