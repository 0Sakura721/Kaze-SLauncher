package com.kaze.newage.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Miuix 主题（HyperOS 风格）的配色映射。
 *
 * ## 设计取舍
 * Miuix 组件库自带一套主题体系（MiuixTheme + MiuixColor），但它与 Material3 的
 * CompositionLocal **不互通** —— 直接整树换 MiuixTheme 会让现有 22 处
 * `LocalAppTheme` 分支之外的所有 M3 组件（卡片/列表/按钮/文本）失去颜色。
 *
 * 所以本主题采取「**视觉令牌映射**」：把 Miuix/HyperOS 的配色语言
 * （浅色=灰白底 + 蓝强调 + 大圆角；深色=深灰底 + 亮蓝）翻译成 M3 `ColorScheme`，
 * 让**现有组件不用改一行**就能呈现 Miuix 的观感。组件级差异（卡片描边、
 * 滑条形态等）由各消费点按 `LocalAppTheme.current == MIUIX` 分支处理（既有惯例）。
 *
 * 色值参考 Miuix 开源仓库的默认主题令牌（Apache-2.0，来源见 THIRD_PARTY_NOTICES）。
 */
object MiuixTheme {

    /** HyperOS 主蓝（Miuix 默认强调色） */
    val Blue = Color(0xFF007AFF)

    // 浅色
    private val LightBg = Color(0xFFF2F2F7)        // HyperOS 招牌的分组灰底
    private val LightSurface = Color(0xFFFFFFFF)   // 卡片白
    private val LightOnBg = Color(0xFF1C1C1E)
    private val LightOnSurfaceVariant = Color(0xFF6D6D72)
    private val LightOutline = Color(0xFFC7C7CC)
    private val LightContainer = Color(0xFFE5E5EA)

    // 深色
    private val DarkBg = Color(0xFF000000)
    private val DarkSurface = Color(0xFF1C1C1E)
    private val DarkOnBg = Color(0xFFF2F2F7)
    private val DarkOnSurfaceVariant = Color(0xFF9E9EA4)
    private val DarkOutline = Color(0xFF3A3A3C)
    private val DarkContainer = Color(0xFF2C2C2E)

    fun light(): ColorScheme = lightColorScheme(
        primary = Blue,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFD6E9FF),
        onPrimaryContainer = Color(0xFF00344D),
        secondary = Color(0xFF5A5A5F),
        onSecondary = Color.White,
        secondaryContainer = LightContainer,
        onSecondaryContainer = LightOnBg,
        tertiary = Color(0xFF34C759),          // HyperOS 绿（成功/在线）
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFD7F5DC),
        onTertiaryContainer = Color(0xFF0A3D14),
        background = LightBg,
        onBackground = LightOnBg,
        surface = LightSurface,
        onSurface = LightOnBg,
        surfaceVariant = LightContainer,
        onSurfaceVariant = LightOnSurfaceVariant,
        outline = LightOutline,
        outlineVariant = Color(0xFFE2E2E7),
        error = Color(0xFFFA5151),             // HyperOS 红
        onError = Color.White,
        errorContainer = Color(0xFFFFE5E5),
        onErrorContainer = Color(0xFF7A0E0E),
        inverseSurface = Color(0xFF2C2C2E),
        inverseOnSurface = Color(0xFFF2F2F7),
        inversePrimary = Color(0xFF6EB8FF),
        surfaceTint = Blue,
        surfaceBright = Color(0xFFF7F7FA),
        surfaceDim = Color(0xFFE2E2E8),
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = Color(0xFFF7F7FA),
        surfaceContainer = Color(0xFFF1F1F6),
        surfaceContainerHigh = Color(0xFFEBEBF0),
        surfaceContainerHighest = Color(0xFFE5E5EA),
    )

    fun dark(): ColorScheme = darkColorScheme(
        primary = Color(0xFF409CFF),
        onPrimary = Color.Black,
        primaryContainer = Color(0xFF004971),
        onPrimaryContainer = Color(0xFFBDDAFF),
        secondary = Color(0xFFB0B0B5),
        onSecondary = Color.Black,
        secondaryContainer = DarkContainer,
        onSecondaryContainer = DarkOnBg,
        tertiary = Color(0xFF30D158),
        onTertiary = Color.Black,
        tertiaryContainer = Color(0xFF0A3D14),
        onTertiaryContainer = Color(0xFFB8F0C2),
        background = DarkBg,
        onBackground = DarkOnBg,
        surface = DarkSurface,
        onSurface = DarkOnBg,
        surfaceVariant = DarkContainer,
        onSurfaceVariant = DarkOnSurfaceVariant,
        outline = DarkOutline,
        outlineVariant = Color(0xFF2C2C2E),
        error = Color(0xFFFF6961),
        onError = Color.Black,
        errorContainer = Color(0xFF5A0E0E),
        onErrorContainer = Color(0xFFFFD9D6),
        inverseSurface = Color(0xFFF2F2F7),
        inverseOnSurface = Color(0xFF1C1C1E),
        inversePrimary = Blue,
        surfaceTint = Color(0xFF409CFF),
        surfaceBright = Color(0xFF2C2C2E),
        surfaceDim = Color(0xFF0A0A0A),
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = Color(0xFF111114),
        surfaceContainer = Color(0xFF1C1C1E),
        surfaceContainerHigh = Color(0xFF262628),
        surfaceContainerHighest = Color(0xFF313133),
    )
}
