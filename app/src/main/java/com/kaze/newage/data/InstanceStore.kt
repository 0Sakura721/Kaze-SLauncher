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

/** 实例存储：内存 StateFlow + JSON 文件持久化 */
class InstanceStore(context: Context) {

    private val context: Context = context.applicationContext
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    /** 存储文件放外部存储（真机内部 /data/user/0 的 FUSE 批量写会静默丢失） */
    private val storeFile: File = File(context.getExternalFilesDir(null), "instances.json")

    private val _instances = MutableStateFlow<List<ServerInstance>>(emptyList())
    val instances: StateFlow<List<ServerInstance>> = _instances.asStateFlow()

    init {
        _instances.value = load()
        save() // 目录扫描恢复出的实例回存 JSON
    }

    /** 实例根目录（app 外部存储，用户可见） */
    fun instancesRoot(): File =
        File(context.getExternalFilesDir(null), "instances").apply { mkdirs() }

    fun createInstanceDir(name: String): File =
        File(instancesRoot(), sanitize(name)).apply { mkdirs() }

    fun add(instance: ServerInstance) {
        _instances.value = _instances.value + instance
        save()
    }

    fun remove(id: String) {
        _instances.value = _instances.value.filterNot { it.id == id }
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
            root.listFiles()?.filter { it.isDirectory }?.mapNotNull { dir ->
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
            } ?: emptyList()
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

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").ifBlank { "instance" }
}
