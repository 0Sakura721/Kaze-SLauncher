package com.mcserver.launcher.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 应用设置(SharedPreferences) */
object SettingsStore {
    private const val PREFS = "kaze_settings"
    private lateinit var prefs: android.content.SharedPreferences

    private val _setupCompleted = MutableStateFlow(false)
    val setupCompleted: StateFlow<Boolean> = _setupCompleted.asStateFlow()

    private val _themeDark = MutableStateFlow(true)
    val themeDark: StateFlow<Boolean> = _themeDark.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _setupCompleted.value = prefs.getBoolean("setup_completed", false)
        _themeDark.value = prefs.getBoolean("theme_dark", true)
    }

    fun setSetupCompleted() {
        prefs.edit().putBoolean("setup_completed", true).apply()
        _setupCompleted.value = true
    }

    fun setThemeDark(dark: Boolean) {
        prefs.edit().putBoolean("theme_dark", dark).apply()
        _themeDark.value = dark
    }
}
