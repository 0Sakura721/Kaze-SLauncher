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

    private val storeFile: File = File(context.filesDir, "instances.json")

    private val _instances = MutableStateFlow(load())
    val instances: StateFlow<List<ServerInstance>> = _instances.asStateFlow()

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

    private fun load(): List<ServerInstance> = try {
        if (!storeFile.exists()) emptyList()
        else json.decodeFromString<List<StoredInstance>>(storeFile.readText()).map { it.toInstance() }
    } catch (_: Exception) { emptyList() }

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
