package com.mcserver.launcher.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.mcserver.launcher.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "mc_server_prefs")

class PreferencesManager(private val context: Context) {

    companion object {
        private val KEY_THEME = stringPreferencesKey("theme_mode")
        private val KEY_SETUP_COMPLETED = booleanPreferencesKey("setup_completed")
        // 多服务器管理
        private val KEY_SERVER_LIST = stringPreferencesKey("server_list_v2")
        private val KEY_CURRENT_SERVER_ID = stringPreferencesKey("current_server_id")
        // 旧版单服务器配置键（保留用于迁移）
        private val KEY_SERVER_NAME = stringPreferencesKey("server_name")
        private val KEY_JAR_PATH = stringPreferencesKey("jar_path")
        private val KEY_JAVA_VERSION = intPreferencesKey("java_version")
        private val KEY_ALLOCATED_MEMORY = intPreferencesKey("allocated_memory_mb")
        private val KEY_MAX_RAM = intPreferencesKey("max_ram_mb")
        private val KEY_MIN_RAM = intPreferencesKey("min_ram_mb")
        private val KEY_PORT = intPreferencesKey("server_port")
        private val KEY_EXTRA_ARGS = stringPreferencesKey("extra_args")
        private val KEY_AUTO_RESTART = booleanPreferencesKey("auto_restart")
        private val KEY_NOGUI = booleanPreferencesKey("nogui")
        private val KEY_STARTUP_MODE = stringPreferencesKey("startup_mode")
        private val KEY_SH_SCRIPT_PATH = stringPreferencesKey("sh_script_path")
        private val KEY_SH_MAX_RAM = intPreferencesKey("sh_max_ram")
        private val KEY_SH_MIN_RAM = intPreferencesKey("sh_min_ram")
        private val KEY_MOTD = stringPreferencesKey("motd")
        private val KEY_MAX_PLAYERS = intPreferencesKey("max_players")
        private val KEY_GAMEMODE = stringPreferencesKey("gamemode")
        private val KEY_DIFFICULTY = stringPreferencesKey("difficulty")
        private val KEY_PVP = booleanPreferencesKey("pvp")
        private val KEY_ONLINE_MODE = booleanPreferencesKey("online_mode")
        private val KEY_WHITE_LIST = booleanPreferencesKey("white_list")
        private val KEY_SPAWN_PROTECTION = intPreferencesKey("spawn_protection")
        private val KEY_VIEW_DISTANCE = intPreferencesKey("view_distance")
        private val KEY_MAX_RESTARTS = intPreferencesKey("max_restarts")
        private val KEY_RESTART_COOLDOWN = intPreferencesKey("restart_cooldown")
        private val KEY_BACKUP_ON_STOP = booleanPreferencesKey("backup_on_stop")
        private val KEY_RCON_ENABLED = booleanPreferencesKey("rcon_enabled")
        private val KEY_RCON_PASSWORD = stringPreferencesKey("rcon_password")
        private val KEY_RCON_PORT = intPreferencesKey("rcon_port")
        // 外观自定义
        private val KEY_BG_TYPE = stringPreferencesKey("bg_type")
        private val KEY_BG_PATH = stringPreferencesKey("bg_path")
        private val KEY_BG_BLUR = floatPreferencesKey("bg_blur")
        private val KEY_BG_DARK_MASK = floatPreferencesKey("bg_dark_mask")
        private val KEY_ACCENT_COLOR = stringPreferencesKey("accent_color")
        private val KEY_CORNER_RADIUS = intPreferencesKey("corner_radius")
        private val KEY_LAYOUT_DENSITY = stringPreferencesKey("layout_density")
        private val KEY_FONT_SIZE = stringPreferencesKey("font_size")
        private val KEY_CURSEFORGE_API_KEY = stringPreferencesKey("curseforge_api_key")

        /** 将 ServerConfig 序列化为 JSONObject */
        fun configToJson(config: ServerConfig): JSONObject = JSONObject().apply {
            put("id", config.id)
            put("name", config.name)
            put("jarPath", config.jarPath)
            put("javaVersion", config.javaVersion)
            put("allocatedMemoryMB", config.allocatedMemoryMB)
            put("serverPort", config.serverPort)
            put("additionalArgs", config.additionalArgs)
            put("autoRestart", config.autoRestart)
            put("nogui", config.nogui)
            put("startupMode", config.startupMode.name)
            put("shScriptPath", config.shScriptPath)
            put("shMaxRamMB", config.shMaxRamMB)
            put("shMinRamMB", config.shMinRamMB)
            put("motd", config.motd)
            put("maxPlayers", config.maxPlayers)
            put("gamemode", config.gamemode)
            put("difficulty", config.difficulty)
            put("pvp", config.pvp)
            put("onlineMode", config.onlineMode)
            put("whiteList", config.whiteList)
            put("spawnProtection", config.spawnProtection)
            put("viewDistance", config.viewDistance)
            put("maxRestarts", config.maxRestarts)
            put("restartCooldownSec", config.restartCooldownSec)
            put("backupOnStop", config.backupOnStop)
            put("rconEnabled", config.rconEnabled)
            put("rconPassword", config.rconPassword)
            put("rconPort", config.rconPort)
        }

        /** 将 JSONObject 反序列化为 ServerConfig */
        fun jsonToConfig(obj: JSONObject): ServerConfig = ServerConfig(
            id = obj.optString("id", ""),
            name = obj.optString("name", "Minecraft Server"),
            jarPath = obj.optString("jarPath", ""),
            javaVersion = obj.optInt("javaVersion", 21),
            allocatedMemoryMB = obj.optInt("allocatedMemoryMB", 2048),
            serverPort = obj.optInt("serverPort", 25565),
            additionalArgs = obj.optString("additionalArgs", "-XX:+UseG1GC"),
            autoRestart = obj.optBoolean("autoRestart", false),
            nogui = obj.optBoolean("nogui", true),
            startupMode = try { StartupMode.valueOf(obj.optString("startupMode", "DIRECT_JAR")) } catch (_: Exception) { StartupMode.DIRECT_JAR },
            shScriptPath = obj.optString("shScriptPath", ""),
            shMaxRamMB = obj.optInt("shMaxRamMB", 4096),
            shMinRamMB = obj.optInt("shMinRamMB", 2048),
            motd = obj.optString("motd", "A Minecraft Server"),
            maxPlayers = obj.optInt("maxPlayers", 20),
            gamemode = obj.optString("gamemode", "survival"),
            difficulty = obj.optString("difficulty", "easy"),
            pvp = obj.optBoolean("pvp", true),
            onlineMode = obj.optBoolean("onlineMode", true),
            whiteList = obj.optBoolean("whiteList", false),
            spawnProtection = obj.optInt("spawnProtection", 16),
            viewDistance = obj.optInt("viewDistance", 10),
            maxRestarts = obj.optInt("maxRestarts", 3),
            restartCooldownSec = obj.optInt("restartCooldownSec", 5),
            backupOnStop = obj.optBoolean("backupOnStop", false),
            rconEnabled = obj.optBoolean("rconEnabled", true),
            rconPassword = obj.optString("rconPassword", ""),
            rconPort = obj.optInt("rconPort", 25575)
        )

        /** 从旧版单键配置迁移到多服务器列表 */
        fun migrateFromLegacy(prefs: Preferences): JSONArray {
            val arr = JSONArray()
            val legacy = JSONObject().apply {
                put("id", "default")
                put("name", prefs[KEY_SERVER_NAME] ?: "Minecraft Server")
                put("jarPath", prefs[KEY_JAR_PATH] ?: "")
                put("javaVersion", prefs[KEY_JAVA_VERSION] ?: 21)
                put("allocatedMemoryMB", prefs[KEY_ALLOCATED_MEMORY] ?: prefs[KEY_MAX_RAM] ?: 2048)
                put("serverPort", prefs[KEY_PORT] ?: 25565)
                put("additionalArgs", prefs[KEY_EXTRA_ARGS] ?: "-XX:+UseG1GC")
                put("autoRestart", prefs[KEY_AUTO_RESTART] ?: false)
                put("nogui", prefs[KEY_NOGUI] ?: true)
                put("startupMode", prefs[KEY_STARTUP_MODE] ?: "DIRECT_JAR")
                put("shScriptPath", prefs[KEY_SH_SCRIPT_PATH] ?: "")
                put("shMaxRamMB", prefs[KEY_SH_MAX_RAM] ?: 4096)
                put("shMinRamMB", prefs[KEY_SH_MIN_RAM] ?: 2048)
                put("motd", prefs[KEY_MOTD] ?: "A Minecraft Server")
                put("maxPlayers", prefs[KEY_MAX_PLAYERS] ?: 20)
                put("gamemode", prefs[KEY_GAMEMODE] ?: "survival")
                put("difficulty", prefs[KEY_DIFFICULTY] ?: "easy")
                put("pvp", prefs[KEY_PVP] ?: true)
                put("onlineMode", prefs[KEY_ONLINE_MODE] ?: true)
                put("whiteList", prefs[KEY_WHITE_LIST] ?: false)
                put("spawnProtection", prefs[KEY_SPAWN_PROTECTION] ?: 16)
                put("viewDistance", prefs[KEY_VIEW_DISTANCE] ?: 10)
                put("maxRestarts", prefs[KEY_MAX_RESTARTS] ?: 3)
                put("restartCooldownSec", prefs[KEY_RESTART_COOLDOWN] ?: 5)
                put("backupOnStop", prefs[KEY_BACKUP_ON_STOP] ?: false)
                put("rconEnabled", prefs[KEY_RCON_ENABLED] ?: true)
                put("rconPassword", prefs[KEY_RCON_PASSWORD] ?: "")
                put("rconPort", prefs[KEY_RCON_PORT] ?: 25575)
            }
            arr.put(legacy)
            return arr
        }
    }

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { prefs ->
        ThemeMode.fromKey(prefs[KEY_THEME] ?: "dark")
    }

    val setupCompleted: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_SETUP_COMPLETED] ?: false
    }

    // ── 外观偏好 ──
    val backgroundType: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_BG_TYPE] ?: "none"
    }
    val backgroundPath: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_BG_PATH] ?: ""
    }
    val backgroundBlur: Flow<Float> = context.dataStore.data.map { prefs ->
        prefs[KEY_BG_BLUR] ?: 0f
    }
    val backgroundDarkMask: Flow<Float> = context.dataStore.data.map { prefs ->
        prefs[KEY_BG_DARK_MASK] ?: 0.3f
    }
    val accentColor: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_ACCENT_COLOR] ?: "default"
    }
    val cornerRadius: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_CORNER_RADIUS] ?: 12
    }
    val layoutDensity: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_LAYOUT_DENSITY] ?: "normal"
    }
    val fontSize: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_FONT_SIZE] ?: "medium"
    }

    // ── 外观保存 ──
    suspend fun setBackgroundType(type: String) {
        context.dataStore.edit { prefs -> prefs[KEY_BG_TYPE] = type }
    }
    suspend fun setBackgroundPath(path: String) {
        context.dataStore.edit { prefs -> prefs[KEY_BG_PATH] = path }
    }
    suspend fun setBackgroundBlur(value: Float) {
        context.dataStore.edit { prefs -> prefs[KEY_BG_BLUR] = value }
    }
    suspend fun setBackgroundDarkMask(value: Float) {
        context.dataStore.edit { prefs -> prefs[KEY_BG_DARK_MASK] = value }
    }
    suspend fun setAccentColor(color: String) {
        context.dataStore.edit { prefs -> prefs[KEY_ACCENT_COLOR] = color }
    }
    suspend fun setCornerRadius(radius: Int) {
        context.dataStore.edit { prefs -> prefs[KEY_CORNER_RADIUS] = radius }
    }
    suspend fun setLayoutDensity(density: String) {
        context.dataStore.edit { prefs -> prefs[KEY_LAYOUT_DENSITY] = density }
    }
    suspend fun setFontSize(size: String) {
        context.dataStore.edit { prefs -> prefs[KEY_FONT_SIZE] = size }
    }

    // ── 多服务器管理 ──

    /** 服务器列表（自动从旧版迁移） */
    val serverList: Flow<List<ServerConfig>> = context.dataStore.data.map { prefs ->
        val listJson = prefs[KEY_SERVER_LIST]
        if (listJson.isNullOrBlank()) {
            // 首次使用多服务器功能：从旧版单键配置迁移
            val migrated = migrateFromLegacy(prefs)
            listOf(jsonToConfig(migrated.getJSONObject(0)))
        } else {
            try {
                val arr = JSONArray(listJson)
                (0 until arr.length()).map { jsonToConfig(arr.getJSONObject(it)) }
            } catch (_: Exception) {
                listOf(ServerConfig())
            }
        }
    }

    /** 当前选中的服务器 ID */
    val currentServerId: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_CURRENT_SERVER_ID] ?: "default"
    }

    /** 获取当前激活的服务器配置 */
    val serverConfig: Flow<ServerConfig> = context.dataStore.data.map { prefs ->
        val listJson = prefs[KEY_SERVER_LIST]
        val currentId = prefs[KEY_CURRENT_SERVER_ID] ?: "default"
        if (listJson.isNullOrBlank()) {
            val migrated = migrateFromLegacy(prefs)
            jsonToConfig(migrated.getJSONObject(0))
        } else {
            try {
                val arr = JSONArray(listJson)
                (0 until arr.length())
                    .map { jsonToConfig(arr.getJSONObject(it)) }
                    .firstOrNull { it.id == currentId }
                    ?: jsonToConfig(arr.getJSONObject(0))
            } catch (_: Exception) {
                ServerConfig()
            }
        }
    }

    /** 切换当前服务器 */
    suspend fun switchServer(serverId: String) {
        context.dataStore.edit { prefs -> prefs[KEY_CURRENT_SERVER_ID] = serverId }
    }

    /** 创建新服务器实例 */
    suspend fun createServer(config: ServerConfig) {
        context.dataStore.edit { prefs ->
            val list = try {
                val json = prefs[KEY_SERVER_LIST]
                if (json.isNullOrBlank()) migrateFromLegacy(prefs) else JSONArray(json)
            } catch (_: Exception) { JSONArray() }
            list.put(configToJson(config))
            prefs[KEY_SERVER_LIST] = list.toString()
            prefs[KEY_CURRENT_SERVER_ID] = config.id
        }
    }

    /** 删除服务器实例 */
    suspend fun deleteServer(serverId: String) {
        context.dataStore.edit { prefs ->
            val list = try {
                val json = prefs[KEY_SERVER_LIST]
                if (json.isNullOrBlank()) return@edit
                JSONArray(json)
            } catch (_: Exception) { return@edit }
            val filtered = JSONArray()
            var removed = false
            for (i in 0 until list.length()) {
                val obj = list.getJSONObject(i)
                if (obj.optString("id") == serverId) {
                    removed = true
                    continue
                }
                filtered.put(obj)
            }
            if (removed) {
                prefs[KEY_SERVER_LIST] = filtered.toString()
                // 如果删的是当前选中的，切换到第一个
                if (prefs[KEY_CURRENT_SERVER_ID] == serverId && filtered.length() > 0) {
                    prefs[KEY_CURRENT_SERVER_ID] = filtered.getJSONObject(0).optString("id", "default")
                }
            }
        }
    }

    /** 保存指定服务器的配置（不切换当前服务器） */
    suspend fun saveServerConfig(config: ServerConfig) {
        context.dataStore.edit { prefs ->
            val list = try {
                val json = prefs[KEY_SERVER_LIST]
                if (json.isNullOrBlank()) migrateFromLegacy(prefs) else JSONArray(json)
            } catch (_: Exception) { JSONArray() }
            var updated = false
            for (i in 0 until list.length()) {
                if (list.getJSONObject(i).optString("id") == config.id) {
                    list.put(i, configToJson(config))
                    updated = true
                    break
                }
            }
            if (!updated) {
                list.put(configToJson(config))
            }
            prefs[KEY_SERVER_LIST] = list.toString()
            // 兼容旧版：同时保存到单键（便于回滚）
            prefs[KEY_SERVER_NAME] = config.name
            prefs[KEY_JAR_PATH] = config.jarPath
            prefs[KEY_JAVA_VERSION] = config.javaVersion
            prefs[KEY_ALLOCATED_MEMORY] = config.allocatedMemoryMB
            prefs[KEY_PORT] = config.serverPort
            prefs[KEY_EXTRA_ARGS] = config.additionalArgs
            prefs[KEY_AUTO_RESTART] = config.autoRestart
            prefs[KEY_NOGUI] = config.nogui
            prefs[KEY_STARTUP_MODE] = config.startupMode.name
            prefs[KEY_SH_SCRIPT_PATH] = config.shScriptPath
            prefs[KEY_SH_MAX_RAM] = config.shMaxRamMB
            prefs[KEY_SH_MIN_RAM] = config.shMinRamMB
            prefs[KEY_MOTD] = config.motd
            prefs[KEY_MAX_PLAYERS] = config.maxPlayers
            prefs[KEY_GAMEMODE] = config.gamemode
            prefs[KEY_DIFFICULTY] = config.difficulty
            prefs[KEY_PVP] = config.pvp
            prefs[KEY_ONLINE_MODE] = config.onlineMode
            prefs[KEY_WHITE_LIST] = config.whiteList
            prefs[KEY_SPAWN_PROTECTION] = config.spawnProtection
            prefs[KEY_VIEW_DISTANCE] = config.viewDistance
            prefs[KEY_MAX_RESTARTS] = config.maxRestarts
            prefs[KEY_RESTART_COOLDOWN] = config.restartCooldownSec
            prefs[KEY_BACKUP_ON_STOP] = config.backupOnStop
            prefs[KEY_RCON_ENABLED] = config.rconEnabled
            prefs[KEY_RCON_PASSWORD] = config.rconPassword
            prefs[KEY_RCON_PORT] = config.rconPort
        }
    }

    suspend fun setTheme(mode: ThemeMode) {
        context.dataStore.edit { prefs -> prefs[KEY_THEME] = mode.key }
    }

    suspend fun setSetupCompleted() {
        context.dataStore.edit { prefs -> prefs[KEY_SETUP_COMPLETED] = true }
    }

    suspend fun setJarPath(path: String) {
        context.dataStore.edit { prefs -> prefs[KEY_JAR_PATH] = path }
    }

    val curseforgeApiKey: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_CURSEFORGE_API_KEY] ?: ""
    }

    suspend fun setCurseforgeApiKey(apiKey: String) {
        context.dataStore.edit { prefs -> prefs[KEY_CURSEFORGE_API_KEY] = apiKey }
    }
}
