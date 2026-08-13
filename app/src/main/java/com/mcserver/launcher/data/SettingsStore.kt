package com.mcserver.launcher.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 主题模式 */
object ThemeMode {
    const val SYSTEM = "system"   // 跟随系统
    const val LIGHT = "light"     // 明亮
    const val DARK = "dark"       // 暗色
    const val AMOLED = "amoled"   // 纯黑省电

    val labels = mapOf(
        SYSTEM to "跟随系统",
        LIGHT to "明亮",
        DARK to "暗色",
        AMOLED to "AMOLED 省电"
    )
    val descriptions = mapOf(
        SYSTEM to "自动跟随系统深色模式",
        LIGHT to "白色主题，适合白天使用",
        DARK to "深色主题，护眼舒适",
        AMOLED to "纯黑背景，极致省电"
    )

    /** 根据模式 + 系统深色状态计算是否使用深色配色 */
    fun isDark(mode: String, systemDark: Boolean): Boolean = when (mode) {
        SYSTEM -> systemDark
        LIGHT -> false
        else -> true // dark / amoled
    }

    /** 是否使用纯黑背景(AMOLED) */
    fun isAmoled(mode: String): Boolean = mode == AMOLED
}

/** 应用设置(SharedPreferences) */
object SettingsStore {
    private const val PREFS = "kaze_settings"
    private lateinit var prefs: android.content.SharedPreferences

    private val _setupCompleted = MutableStateFlow(false)
    val setupCompleted: StateFlow<Boolean> = _setupCompleted.asStateFlow()

    private val _themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    /** 深色模式下是否使用 AMOLED 纯黑配色 */
    private val _darkAmoled = MutableStateFlow(true)
    val darkAmoled: StateFlow<Boolean> = _darkAmoled.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _setupCompleted.value = prefs.getBoolean("setup_completed", false)
        _darkAmoled.value = prefs.getBoolean("dark_amoled", true)
        // 兼容旧版 theme_dark 布尔值
        _themeMode.value = prefs.getString("theme_mode", null) ?: run {
            if (prefs.contains("theme_dark")) {
                if (prefs.getBoolean("theme_dark", true)) ThemeMode.DARK else ThemeMode.LIGHT
            } else {
                ThemeMode.SYSTEM
            }
        }
    }

    fun setSetupCompleted() {
        prefs.edit().putBoolean("setup_completed", true).apply()
        _setupCompleted.value = true
    }

    fun setThemeMode(mode: String) {
        prefs.edit().putString("theme_mode", mode).apply()
        _themeMode.value = mode
    }

    fun setDarkAmoled(amoled: Boolean) {
        prefs.edit().putBoolean("dark_amoled", amoled).apply()
        _darkAmoled.value = amoled
    }
}
