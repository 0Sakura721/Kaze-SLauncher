package com.mcserver.launcher.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private val Context.kazeDataStore by preferencesDataStore(name = "kaze_settings")

/** 全局偏好（DataStore），键值定义 + Flow 暴露 */
object SettingsStore {

    /** 内存自动档标记：-1 表示按设备内存自动推荐 */
    const val MEM_AUTO = -1

    private val KEY_STYLE = stringPreferencesKey("theme_style")
    private val KEY_MODE = intPreferencesKey("theme_mode")
    private val KEY_COLOR = intPreferencesKey("theme_custom_color")
    private val KEY_LANG = stringPreferencesKey("language")
    private val KEY_MEM = intPreferencesKey("memory_mb")
    private val KEY_AWAKE = booleanPreferencesKey("keep_awake")
    private val KEY_LOGLINES = intPreferencesKey("max_log_lines")

    lateinit var context: Context
        private set

    /** 同步缓存（引擎/服务无需挂起即可读内存预设） */
    @Volatile
    var memoryPresetMbSync: Int = 2048
        private set

    fun init(context: Context) {
        this.context = context.applicationContext
        memoryPresetMbSync = runBlocking {
            context.kazeDataStore.data.map { it[KEY_MEM] ?: 2048 }.first()
        }
    }

    val themeStyle: Flow<String> get() = context.kazeDataStore.data.map { it[KEY_STYLE] ?: "liquid" }
    val themeMode: Flow<Int> get() = context.kazeDataStore.data.map { it[KEY_MODE] ?: 0 }
    val customColor: Flow<Int> get() = context.kazeDataStore.data.map { it[KEY_COLOR] ?: 0 }
    val language: Flow<String> get() = context.kazeDataStore.data.map { it[KEY_LANG] ?: "zh" }
    val memoryPresetMb: Flow<Int> get() = context.kazeDataStore.data.map { it[KEY_MEM] ?: 2048 }
    val keepAwake: Flow<Boolean> get() = context.kazeDataStore.data.map { it[KEY_AWAKE] ?: true }
    val maxLogLines: Flow<Int> get() = context.kazeDataStore.data.map { it[KEY_LOGLINES] ?: 2000 }

    suspend fun setThemeStyle(v: String) { context.kazeDataStore.edit { it[KEY_STYLE] = v } }
    suspend fun setThemeMode(v: Int) { context.kazeDataStore.edit { it[KEY_MODE] = v } }
    suspend fun setCustomColor(v: Int) { context.kazeDataStore.edit { it[KEY_COLOR] = v } }
    suspend fun setLanguage(v: String) { context.kazeDataStore.edit { it[KEY_LANG] = v } }
    suspend fun setMemoryPreset(v: Int) {
        context.kazeDataStore.edit { it[KEY_MEM] = v }
        memoryPresetMbSync = v
    }
    suspend fun setKeepAwake(v: Boolean) { context.kazeDataStore.edit { it[KEY_AWAKE] = v } }
    suspend fun setMaxLogLines(v: Int) { context.kazeDataStore.edit { it[KEY_LOGLINES] = v } }

    /** 解析实际生效内存：自动档按设备总内存推荐（约一半，向上取整到 256MB 倍数） */
    fun resolveMemoryMb(): Int {
        if (memoryPresetMbSync > 0) return memoryPresetMbSync
        val totalMb = try {
            val am = context.getSystemService(android.app.ActivityManager::class.java)
            val mi = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            (mi.totalMem / 1024 / 1024).toInt()
        } catch (_: Exception) {
            4096
        }
        val recommended = (totalMb / 2).coerceIn(512, 8192)
        return (recommended + 255) / 256 * 256
    }
}