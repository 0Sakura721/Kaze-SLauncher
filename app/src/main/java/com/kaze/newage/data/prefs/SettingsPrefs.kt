package com.kaze.newage.data.prefs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import java.io.File

/**
 * UI 设置存储（SharedPreferences）：背景图、毛玻璃参数。
 * 借鉴 ZalithLauncher2 的背景设置体系（GPL-3.0），简化实现。
 */
class SettingsPrefs(context: Context) {

    private val prefs = context.getSharedPreferences("kaze_ui_settings", Context.MODE_PRIVATE)
    private val bgFile = File(context.filesDir, "background.png")

    /** 是否启用背景图 */
    val bgEnabled = mutableStateOf(prefs.getBoolean("bg_enabled", false))

    /** 主题模式：m3（Material 3 动态取色，默认）/ glass（安卓原生液态玻璃） */
    val themeMode = mutableStateOf(prefs.getString("theme_mode", "clear") ?: "clear")

    /** 深色模式（旧键，迁移到 themeModeValue 后弃用） */
    val forceDark = mutableStateOf<Boolean?>(
        if (prefs.contains("force_dark")) prefs.getBoolean("force_dark", false) else null
    )

    /** 主题模式（照搬 BiliPai AppThemeMode）：0=跟随系统 1=浅色 2=深色 */
    val themeModeValue = mutableStateOf(
        when {
            prefs.contains("theme_mode_value") -> prefs.getInt("theme_mode_value", 0)
            prefs.contains("force_dark") -> if (prefs.getBoolean("force_dark", false)) 2 else 1
            else -> 0
        }
    )

    /** 深色样式（照搬 BiliPai DarkThemeStyle）：0=普通黑 1=AMOLED纯黑 */
    val darkStyle = mutableStateOf(prefs.getInt("dark_style", 0))

    /** MD3 颜色来源（照搬 BiliPai Md3ColorSource）：wallpaper=跟随系统壁纸 / custom=自定义颜色 */
    val md3ColorSource = mutableStateOf(prefs.getString("md3_color_source", "wallpaper") ?: "wallpaper")

    /** MD3 自定义种子色（hex，如 #007AFF） */
    val md3CustomColor = mutableStateOf(prefs.getString("md3_custom_color", "#007AFF") ?: "#007AFF")

    /** 取色风格（materialkolor PaletteStyle 名） */
    val paletteStyle = mutableStateOf(prefs.getString("palette_style", "TonalSpot") ?: "TonalSpot")

    /** 液态玻璃模式（照搬 BiliPai LiquidGlassMode）：clear/balanced/frosted */
    val glassMode = mutableStateOf(prefs.getString("glass_mode", "balanced") ?: "balanced")

    /** Linux 环境存放到外部存储（内部空间不足时；切换后需重新部署） */
    val envExternal = mutableStateOf(prefs.getBoolean("env_external", false))

    /** 原生模糊（Haze/RenderEffect，Android 12+；个别 GPU 异常时可关闭回退半透明玻璃） */
    val glassBlur = mutableStateOf(prefs.getBoolean("glass_blur", true))

    /** 是否已请求过电池优化白名单（只自动弹一次） */
    val batteryPrompted = mutableStateOf(prefs.getBoolean("battery_prompted", false))

    /** 背景模糊强度（0..25） */
    val bgBlur = mutableFloatStateOf(prefs.getFloat("bg_blur", 12f))

    /** 背景不透明度（0..100，影响背景图整体显示） */
    val bgOpacity = mutableFloatStateOf(prefs.getFloat("bg_opacity", 25f))

    val hasBackgroundImage: Boolean get() = bgFile.exists()

    fun backgroundImagePath(): String? = bgFile.takeIf { it.exists() }?.absolutePath

    /** 保存选中的背景图（压缩到屏幕尺寸，避免大图内存问题） */
    fun saveBackgroundImage(sourcePath: String) {
        val src = BitmapFactory.decodeFile(sourcePath) ?: return
        val maxDim = 1600
        val scale = minOf(1f, maxDim.toFloat() / maxOf(src.width, src.height))
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true)
        } else src
        bgFile.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        if (scaled != src) scaled.recycle()
        src.recycle()
    }

    fun clearBackgroundImage() {
        bgFile.delete()
    }

    fun setBgEnabled(v: Boolean) {
        bgEnabled.value = v
        prefs.edit().putBoolean("bg_enabled", v).apply()
    }

    fun setThemeMode(v: String) {
        themeMode.value = v
        prefs.edit().putString("theme_mode", v).apply()
    }

    fun setForceDark(v: Boolean?) {
        forceDark.value = v
        if (v == null) prefs.edit().remove("force_dark").apply()
        else prefs.edit().putBoolean("force_dark", v).apply()
    }

    fun setThemeModeValue(v: Int) {
        themeModeValue.value = v
        prefs.edit().putInt("theme_mode_value", v).apply()
    }

    fun setDarkStyle(v: Int) {
        darkStyle.value = v
        prefs.edit().putInt("dark_style", v).apply()
    }

    fun setMd3ColorSource(v: String) {
        md3ColorSource.value = v
        prefs.edit().putString("md3_color_source", v).apply()
    }

    fun setMd3CustomColor(v: String) {
        md3CustomColor.value = v
        prefs.edit().putString("md3_custom_color", v).apply()
    }

    fun setPaletteStyle(v: String) {
        paletteStyle.value = v
        prefs.edit().putString("palette_style", v).apply()
    }

    fun setGlassMode(v: String) {
        glassMode.value = v
        prefs.edit().putString("glass_mode", v).apply()
    }

    fun setEnvExternal(v: Boolean) {
        envExternal.value = v
        prefs.edit().putBoolean("env_external", v).apply()
    }

    fun setGlassBlur(v: Boolean) {
        glassBlur.value = v
        prefs.edit().putBoolean("glass_blur", v).apply()
    }

    fun setBatteryPrompted(v: Boolean) {
        batteryPrompted.value = v
        prefs.edit().putBoolean("battery_prompted", v).apply()
    }

    fun setBgBlur(v: Float) {
        bgBlur.floatValue = v
        prefs.edit().putFloat("bg_blur", v).apply()
    }

    fun setBgOpacity(v: Float) {
        bgOpacity.floatValue = v
        prefs.edit().putFloat("bg_opacity", v).apply()
    }
}
