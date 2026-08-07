package com.mcserver.launcher.core.server

import android.content.Context
import com.mcserver.launcher.data.CoreType
import com.mcserver.launcher.data.InstanceConfig
import com.mcserver.launcher.data.ServerInstance
import com.mcserver.launcher.util.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 多实例管理:每个实例独立目录/配置/状态(版本隔离)。
 */
object InstanceStore {

    private const val TAG = "InstanceStore"
    private lateinit var appContext: Context

    /** 实例根目录(App 外部存储,与 rootfs 绑定共享) */
    val instancesDir: File get() = File(appContext.getExternalFilesDir(null), "instances").apply { mkdirs() }

    private val prefsFile: File get() = File(appContext.filesDir, "instances.json")

    private val _instances = MutableStateFlow<List<ServerInstance>>(emptyList())
    val instances: StateFlow<List<ServerInstance>> = _instances.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        load()
    }

    fun instance(id: String): ServerInstance? = _instances.value.firstOrNull { it.id == id }

    fun create(name: String, coreType: CoreType, mcVersion: String, buildId: String = "", config: InstanceConfig = InstanceConfig()): ServerInstance {
        val instance = ServerInstance(
            id = UUID.randomUUID().toString().take(8),
            name = name.ifBlank { "$mcVersion ${coreType.displayName}" },
            coreType = coreType,
            mcVersion = mcVersion,
            buildId = buildId,
            config = config
        )
        instance.dir(instancesDir).mkdirs()
        _instances.value = _instances.value + instance
        save()
        return instance
    }

    fun update(instance: ServerInstance) {
        _instances.value = _instances.value.map { if (it.id == instance.id) instance else it }
        save()
    }

    fun delete(id: String) {
        instance(id)?.let { it.dir(instancesDir).deleteRecursively() }
        _instances.value = _instances.value.filterNot { it.id == id }
        save()
    }

    private fun save() {
        try {
            val arr = JSONArray()
            _instances.value.forEach { inst ->
                arr.put(
                    JSONObject().apply {
                        put("id", inst.id)
                        put("name", inst.name)
                        put("coreType", inst.coreType.name)
                        put("mcVersion", inst.mcVersion)
                        put("buildId", inst.buildId)
                        put("createdAt", inst.createdAt)
                        put("config", JSONObject().apply {
                            put("serverPort", inst.config.serverPort)
                            put("maxPlayers", inst.config.maxPlayers)
                            put("gamemode", inst.config.gamemode)
                            put("difficulty", inst.config.difficulty)
                            put("pvp", inst.config.pvp)
                            put("onlineMode", inst.config.onlineMode)
                            put("whiteList", inst.config.whiteList)
                            put("motd", inst.config.motd)
                            put("maxRamMB", inst.config.maxRamMB)
                            put("viewDistance", inst.config.viewDistance)
                            put("spawnProtection", inst.config.spawnProtection)
                            put("nogui", inst.config.nogui)
                            put("levelName", inst.config.levelName)
                            put("levelSeed", inst.config.levelSeed)
                            put("levelType", inst.config.levelType)
                            put("hardcore", inst.config.hardcore)
                            put("allowNether", inst.config.allowNether)
                            put("allowFlight", inst.config.allowFlight)
                            put("spawnMonsters", inst.config.spawnMonsters)
                            put("spawnAnimals", inst.config.spawnAnimals)
                            put("maxWorldSize", inst.config.maxWorldSize)
                            put("jvmArgs", inst.config.jvmArgs)
                            put("rconEnabled", inst.config.rconEnabled)
                            put("rconPassword", inst.config.rconPassword)
                            put("rconPort", inst.config.rconPort)
                            put("autoRestart", inst.config.autoRestart)
                            put("maxRestarts", inst.config.maxRestarts)
                            put("backupOnStop", inst.config.backupOnStop)
                        })
                    }
                )
            }
            // 原子写:先写临时文件再 rename,避免进程被杀时配置损坏
            val tmp = File(prefsFile.parentFile, prefsFile.name + ".tmp")
            tmp.writeText(arr.toString(2))
            if (prefsFile.exists()) prefsFile.delete()
            tmp.renameTo(prefsFile)
        } catch (e: Exception) {
            Logger.e("save instances failed", e)
        }
    }

    private fun load() {
        try {
            if (!prefsFile.exists()) return
            val arr = JSONArray(prefsFile.readText())
            val list = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val c = o.optJSONObject("config") ?: JSONObject()
                ServerInstance(
                    id = o.getString("id"),
                    name = o.optString("name", "服务器"),
                    coreType = CoreType.fromKey(o.optString("coreType", "VANILLA")) ?: CoreType.VANILLA,
                    mcVersion = o.optString("mcVersion", ""),
                    buildId = o.optString("buildId", ""),
                    createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                    config = InstanceConfig(
                        serverPort = c.optInt("serverPort", 25565),
                        maxPlayers = c.optInt("maxPlayers", 20),
                        gamemode = c.optString("gamemode", "survival"),
                        difficulty = c.optString("difficulty", "normal"),
                        pvp = c.optBoolean("pvp", true),
                        onlineMode = c.optBoolean("onlineMode", false),
                        whiteList = c.optBoolean("whiteList", false),
                        motd = c.optString("motd", "A Minecraft Server"),
                        maxRamMB = c.optInt("maxRamMB", 2048),
                        viewDistance = c.optInt("viewDistance", 10),
                        spawnProtection = c.optInt("spawnProtection", 16),
                        nogui = c.optBoolean("nogui", true),
                        levelName = c.optString("levelName", "world"),
                        levelSeed = c.optString("levelSeed", ""),
                        levelType = c.optString("levelType", "default"),
                        hardcore = c.optBoolean("hardcore", false),
                        allowNether = c.optBoolean("allowNether", true),
                        allowFlight = c.optBoolean("allowFlight", false),
                        spawnMonsters = c.optBoolean("spawnMonsters", true),
                        spawnAnimals = c.optBoolean("spawnAnimals", true),
                        maxWorldSize = c.optInt("maxWorldSize", 29999984),
                        jvmArgs = c.optString("jvmArgs", ""),
                        rconEnabled = c.optBoolean("rconEnabled", true),
                        rconPassword = c.optString("rconPassword", ""),
                        rconPort = c.optInt("rconPort", 25575),
                        autoRestart = c.optBoolean("autoRestart", true),
                        maxRestarts = c.optInt("maxRestarts", 3),
                        backupOnStop = c.optBoolean("backupOnStop", true)
                    )
                )
            }
            _instances.value = list
        } catch (e: Exception) {
            Logger.e("load instances failed", e)
        }
    }
}
