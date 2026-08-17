package com.kaze.newage.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import dev.chrisbanes.haze.HazeState
/**
 * 主题模式（照搬 BiliPai 主题设置思路，GPL-3.0）：
 *  - M3：Material 3 动态取色（Material You，跟随系统深浅色）
 *  - GLASS：液态玻璃（Haze 原生背景模糊 + 折射，跟随系统深浅色）
 */
enum class AppThemeMode(val id: String, val label: String, val desc: String) {
    M3("m3", "Material 3", "动态取色（Material You）"),
    GLASS("glass", "液态玻璃", "原生背景模糊 + 折射"),
    ;

    companion object {
        fun fromId(id: String?): AppThemeMode = when (id) {
            "glass" -> GLASS
            "aurora" -> GLASS // 旧版「极光」迁移
            "clear" -> M3    // 旧版「简洁面板」迁移为 Material 3
            else -> M3
        }
    }
}

/** 当前主题模式（供组件/色板函数按主题差异化渲染） */
val LocalAppTheme = staticCompositionLocalOf { AppThemeMode.M3 }

/**
 * 实际生效的深色状态（由 NewAgeTheme 提供，与「跟随系统/强制浅色/强制深色」解析结果一致）。
 * 背景层/条目色须读它，不能读 isSystemInDarkTheme()。
 */
val LocalDarkTheme = staticCompositionLocalOf { false }

/**
 * Haze 状态（安卓原生背景模糊）。由 AppBackground 提供；
 * 玻璃表面经 hazeEffect 采样其背后内容做真实模糊（API 31+ 生效，低版本自动降级为半透明）。
 */
val LocalHazeState = staticCompositionLocalOf<HazeState?> { null }

/**
 * 原生模糊是否启用（设置「原生模糊」开关；个别 GPU 渲染异常时可关闭回退半透明玻璃）。
 */
val LocalGlassBlurEnabled = staticCompositionLocalOf { true }

/**
 * 图标与文字颜色模式（设置「背景图 → 图标与文字颜色」）：
 *  - AUTO：跟随主题深浅色（默认）
 *  - LIGHT：强制白色（深色背景图）
 *  - DARK：强制黑色（浅色背景图）
 */
enum class FgColorMode(val id: String, val label: String) {
    AUTO("auto", "跟随主题"),
    LIGHT("light", "白色"),
    DARK("dark", "黑色"),
    ;

    companion object {
        fun fromId(id: String?): FgColorMode = entries.firstOrNull { it.id == id } ?: AUTO
    }
}

/** 当前图标与文字颜色模式（由 NewAgeTheme 提供） */
val LocalFgColorMode = staticCompositionLocalOf { FgColorMode.AUTO }
