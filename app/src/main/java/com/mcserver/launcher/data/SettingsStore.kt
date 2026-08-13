package com.mcserver.launcher.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.kazeDataStore by preferencesDataStore(name = "kaze_settings")

/** 全局偏好（DataStore），键值定义 + Flow 暴露 */
object SettingsStore {

    private val KEY_STYLE = stringPreferencesKey("theme_style")
    private val KEY_MODE = intPreferencesKey("theme_mode")
    private val KEY_LANG = stringPreferencesKey("language")
    private val KEY_MEM = intPreferencesKey("memory_mb")
    private val KEY_AWAKE = booleanPreferencesKey("keep_awake")
    private val KEY_LOGLINES = intPreferencesKey("max_log_lines")

    lateinit var context: Context
        private set

    fun init(context: Context) {
        this.context = context.applicationContext
    }

    val themeStyle: Flow<String> get() = context.kazeDataStore.data.map { it[KEY_STYLE] ?: "piliplus" }
    val themeMode: Flow<Int> get() = context.kazeDataStore.data.map { it[KEY_MODE] ?: 0 }
    val language: Flow<String> get() = context.kazeDataStore.data.map { it[KEY_LANG] ?: "zh" }
    val memoryPresetMb: Flow<Int> get() = context.kazeDataStore.data.map { it[KEY_MEM] ?: 2048 }
    val keepAwake: Flow<Boolean> get() = context.kazeDataStore.data.map { it[KEY_AWAKE] ?: true }
    val maxLogLines: Flow<Int> get() = context.kazeDataStore.data.map { it[KEY_LOGLINES] ?: 2000 }

    suspend fun setThemeStyle(v: String) { context.kazeDataStore.edit { it[KEY_STYLE] = v } }
    suspend fun setThemeMode(v: Int) { context.kazeDataStore.edit { it[KEY_MODE] = v } }
    suspend fun setLanguage(v: String) { context.kazeDataStore.edit { it[KEY_LANG] = v } }
    suspend fun setMemoryPreset(v: Int) { context.kazeDataStore.edit { it[KEY_MEM] = v } }
    suspend fun setKeepAwake(v: Boolean) { context.kazeDataStore.edit { it[KEY_AWAKE] = v } }
    suspend fun setMaxLogLines(v: Int) { context.kazeDataStore.edit { it[KEY_LOGLINES] = v } }
}