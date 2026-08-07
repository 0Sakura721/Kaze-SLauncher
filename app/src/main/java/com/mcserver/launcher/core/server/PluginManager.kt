package com.mcserver.launcher.core.server

import com.mcserver.launcher.data.CoreType
import com.mcserver.launcher.data.ServerInstance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 已安装的插件/模组条目 */
data class InstalledAddon(
    val name: String,
    val file: File,
    val enabled: Boolean = true
)

/**
 * 插件/模组管理(实例维度)。
 * 按核心类型自动选择目录:插件核心(Paper/Spigot/Purpur/Vanilla)→ plugins/,模组核心(Fabric/Forge/NeoForge)→ mods/。
 * 本地导入优先,在线搜索为备选。
 */
object PluginManager {

    /** 插件/模组目录(不存在则创建) */
    fun addonDir(instance: ServerInstance): File {
        val dir = instance.dir(InstanceStore.instancesDir)
        val isModded = instance.coreType == CoreType.FABRIC ||
            instance.coreType == CoreType.FORGE ||
            instance.coreType == CoreType.NEOFORGE
        val sub = if (isModded) "mods" else "plugins"
        return File(dir, sub).apply { mkdirs() }
    }

    /** 列出已安装的插件/模组(含禁用文件 .jar.disabled) */
    suspend fun list(instance: ServerInstance): List<InstalledAddon> = withContext(Dispatchers.IO) {
        addonDir(instance).listFiles()
            ?.filter { it.isFile && (it.name.endsWith(".jar") || it.name.endsWith(".jar.disabled")) }
            ?.sortedBy { it.name.lowercase() }
            ?.map {
                val enabled = !it.name.endsWith(".disabled")
                InstalledAddon(it.name.removeSuffix(".disabled").removeSuffix(".jar"), it, enabled)
            } ?: emptyList()
    }

    /** 删除插件/模组 */
    suspend fun delete(instance: ServerInstance, fileName: String): Boolean = withContext(Dispatchers.IO) {
        File(addonDir(instance), fileName).delete()
    }

    /** 启用/禁用(重命名 .jar → .jar.disabled) */
    suspend fun toggleEnabled(instance: ServerInstance, fileName: String): Boolean = withContext(Dispatchers.IO) {
        val file = File(addonDir(instance), fileName)
        if (!file.exists()) return@withContext false
        if (fileName.endsWith(".disabled")) {
            val target = File(addonDir(instance), fileName.removeSuffix(".disabled"))
            file.renameTo(target)
        } else {
            val target = File(addonDir(instance), fileName + ".disabled")
            file.renameTo(target)
        }
    }

    /** 目录显示名 */
    fun dirLabel(instance: ServerInstance): String =
        if (instance.coreType == CoreType.FABRIC || instance.coreType == CoreType.FORGE || instance.coreType == CoreType.NEOFORGE) "mods" else "plugins"
}
