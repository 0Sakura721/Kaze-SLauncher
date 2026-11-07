package com.kaze.newage.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.theme.Colors as MiuixColors
import top.yukonga.miuix.kmp.theme.MiuixTheme as MiuixThemeProvider
import top.yukonga.miuix.kmp.theme.darkColorScheme as miuixDarkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme as miuixLightColorScheme

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
 *
 * ## 0.8.6 的关键修正：把 Miuix 组件包进「同一套颜色」
 * 早期只把 M3 ColorScheme 换成 HyperOS 色，却**没有**给 Miuix 自己的组件提供
 * `MiuixTheme`。后果：`MiuixSwitch` 读的是 miuix 内置的浅色默认值
 * （primary #3482FF、unchecked track #E6E6E6、thumb 白色）——与本主题的色板无关，
 * 而且**深色模式下它仍然是浅色配色**（灰轨 + 白钮，贴在深底上几乎看不出来）。
 * 现在由 [NewAgeTheme] 用 [colorsLight] / [colorsDark] 构造一份 `Colors` 并提供
 * `MiuixTheme(colors = …)`，开关/滑条/勾选框/进度条全部落在同一套 HyperOS 令牌上。
 */
object MiuixTheme {

    /** HyperOS 主蓝（Miuix 默认强调色） */
    val Blue = Color(0xFF007AFF)

    /** 页面底色（背景层直接取用，避免每次重组都重建一份 ColorScheme） */
    val BackgroundLight = Color(0xFFF2F2F7)
    val BackgroundDark = Color(0xFF000000)

    // ── M3 ColorScheme 侧（给现有 M3 组件用）──
    private val LightBg = BackgroundLight                  // HyperOS 招牌的分组灰底
    private val LightSurface = Color(0xFFFFFFFF)   // 卡片白
    private val LightOnBg = Color(0xFF1C1C1E)
    private val LightOnSurfaceVariant = Color(0xFF6D6D72)
    private val LightOutline = Color(0xFFC7C7CC)
    private val LightContainer = Color(0xFFE5E5EA)

    private val DarkBg = BackgroundDark
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

    // ══════════════════════════════════════════════════════════
    // Miuix 组件侧（给它自己的 Switch / Slider / Checkbox / Progress 用）
    //
    // 这些参数不是「M3 角色的镜像」，而是 HyperOS 的真实令牌语义：
    //  - secondary / secondaryVariant 是**未选中的轨道/滑块底色**（浅灰、深灰）
    //  - onSecondary 是未选中态的钮色（浅色下白钮，深色下也是白钮）
    //  - disabled* 是四个禁用组合，必须与 base 色一起给，否则默认值会串回内置浅色板
    // ══════════════════════════════════════════════════════════

    /** Miuix 组件用的浅色令牌 */
    fun colorsLight(): MiuixColors = miuixLightColorScheme(
        primary = Blue,
        onPrimary = Color.White,
        primaryVariant = Blue,
        onPrimaryVariant = Color(0xFFAECDFF),
        error = Color(0xFFFA5151),
        onError = Color.White,
        errorContainer = Color(0xFFFFE5E5),
        onErrorContainer = Color(0xFF7A0E0E),
        disabledPrimary = Color(0xFFC2D9FF),
        disabledOnPrimary = Color(0xFFF3F8FF),
        disabledPrimaryButton = Color(0xFFC2D9FF),
        disabledOnPrimaryButton = Color.White,
        disabledPrimarySlider = Color(0xFFB8CFF5),
        primaryContainer = Color(0xFFD6E9FF),
        onPrimaryContainer = Color(0xFF00344D),
        secondary = Color(0xFFE5E5EA),
        onSecondary = Color.White,
        secondaryVariant = Color(0xFFF2F2F7),
        onSecondaryVariant = LightOnBg,
        disabledSecondary = Color(0xFFF2F2F7),
        disabledOnSecondary = Color.White,
        disabledSecondaryVariant = Color(0xFFE5E5EA),
        disabledOnSecondaryVariant = Color(0xFFB2B2B2),
        secondaryContainer = LightContainer,
        onSecondaryContainer = LightOnSurfaceVariant,
        secondaryContainerVariant = LightContainer,
        onSecondaryContainerVariant = LightOnSurfaceVariant,
        tertiaryContainer = Color(0xFFEAF2FF),
        onTertiaryContainer = Blue,
        tertiaryContainerVariant = Color(0xFFEAF2FF),
        background = LightBg,
        onBackground = LightOnBg,
        onBackgroundVariant = LightOnSurfaceVariant,
        surface = LightSurface,
        onSurface = LightOnBg,
        surfaceVariant = LightBg,
        onSurfaceSecondary = LightOnBg,
        onSurfaceVariantSummary = LightOnSurfaceVariant,
        onSurfaceVariantActions = Color(0xFF8E8E93),
        disabledOnSurface = Color(0xFFB2B2B2),
        surfaceContainer = LightSurface,
        onSurfaceContainer = LightOnBg,
        onSurfaceContainerVariant = LightOnSurfaceVariant,
        surfaceContainerHigh = Color(0xFFEBEBF0),
        onSurfaceContainerHigh = LightOnSurfaceVariant,
        surfaceContainerHighest = LightContainer,
        onSurfaceContainerHighest = LightOnBg,
        outline = LightOutline,
        dividerLine = Color(0xFFE2E2E7),
        windowDimming = Color.Black.copy(alpha = 0.3f),
        sliderKeyPoint = Color(0xFFD6E9FF),
        sliderKeyPointForeground = Blue,
    )

    /**
     * Miuix 组件用的深色令牌。
     * @param pureBlack AMOLED 纯黑：surface 与 background 一起压成 #000（HyperOS 的纯黑模式）
     */
    fun colorsDark(pureBlack: Boolean = false): MiuixColors {
        val surface = if (pureBlack) Color.Black else DarkSurface
        val surfaceHigh = if (pureBlack) Color(0xFF0E0E10) else Color(0xFF2C2C2E)
        val surfaceHighest = if (pureBlack) Color(0xFF161618) else Color(0xFF313133)
        val divider = if (pureBlack) Color(0xFF1C1C1E) else Color(0xFF2C2C2E)
        return miuixDarkColorScheme(
            primary = Color(0xFF409CFF),
            onPrimary = Color.White,
            primaryVariant = Color(0xFF0073DD),
            onPrimaryVariant = Color(0xFF99C7F1),
            error = Color(0xFFFF6961),
            onError = Color(0xFF410002),
            errorContainer = Color(0xFF5A0E0E),
            onErrorContainer = Color(0xFFFFD9D6),
            disabledPrimary = Color(0xFF253E64),
            disabledOnPrimary = Color(0xFF677993),
            disabledPrimaryButton = Color(0xFF253E64),
            disabledOnPrimaryButton = Color(0xFF677893),
            disabledPrimarySlider = Color(0xFF44587C),
            primaryContainer = Color(0xFF004971),
            onPrimaryContainer = Color(0xFFBDDAFF),
            secondary = Color(0xFF2C2C2E),
            onSecondary = Color(0xFFF2F2F7),
            secondaryVariant = surfaceHigh,
            onSecondaryVariant = Color(0xFFEBEBF0),
            disabledSecondary = surfaceHigh,
            disabledOnSecondary = Color(0xFF7C7C7C),
            disabledSecondaryVariant = Color(0xFF3A3A3C),
            disabledOnSecondaryVariant = Color(0xFF707170),
            secondaryContainer = surfaceHigh,
            onSecondaryContainer = DarkOnSurfaceVariant,
            secondaryContainerVariant = surfaceHigh,
            onSecondaryContainerVariant = Color(0xFF959595),
            tertiaryContainer = Color(0xFF0F2A45),
            onTertiaryContainer = Color(0xFF409CFF),
            tertiaryContainerVariant = surfaceHigh,
            background = Color.Black,
            onBackground = DarkOnBg,
            onBackgroundVariant = DarkOnSurfaceVariant,
            surface = surface,
            onSurface = DarkOnBg,
            surfaceVariant = Color.Black,
            onSurfaceSecondary = Color(0xFFE5E5EA),
            onSurfaceVariantSummary = DarkOnSurfaceVariant,
            onSurfaceVariantActions = Color(0xFF8E8E93),
            disabledOnSurface = Color(0xFF666666),
            surfaceContainer = surface,
            onSurfaceContainer = DarkOnBg,
            onSurfaceContainerVariant = DarkOnSurfaceVariant,
            surfaceContainerHigh = surfaceHigh,
            onSurfaceContainerHigh = Color(0xFF9E9EA4),
            surfaceContainerHighest = surfaceHighest,
            onSurfaceContainerHighest = Color(0xFFE5E5EA),
            outline = DarkOutline,
            dividerLine = divider,
            windowDimming = Color.Black.copy(alpha = 0.6f),
            sliderKeyPoint = surfaceHigh,
            sliderKeyPointForeground = Color(0xFF6EB8FF),
        )
    }

    /**
     * 把当前 M3 色板 + 深色状态包成 Miuix 的 `MiuixTheme`。
     *
     * 只在 MIUIX 主题下调用：它同时提供 `LocalColors`、`LocalTextStyles` 与
     * `LocalIndication`（HyperOS 的矩形按压高亮，不是水波纹）。
     *
     * **必须 remember**：`colorsLight()/colorsDark()` 每次都 new 出 50 多个 Color 的
     * `Colors`，而 CompositionLocal 用 `equals` 比较 —— `Colors` 没实现 equals，
     * 身份不等即视为"值变了"，于是每次重组都会让所有读 `LocalColors` 的 Miuix 组件
     * 失效（MIUIX 下等于全屏重排）。
     */
    @Composable
    fun Provide(dark: Boolean, pureBlack: Boolean = false, content: @Composable () -> Unit) {
        val colors = remember(dark, pureBlack) {
            if (dark) colorsDark(pureBlack) else colorsLight()
        }
        MiuixThemeProvider(
            colors = colors,
            content = content,
        )
    }
}

/**
 * HyperOS 的版式度量（Miuix 组件的真实 token）。
 *
 * 数值来自 miuix 0.7.2 源码（Apache-2.0）：
 *  - `CardDefaults.CornerRadius = 16.dp`、`InsideMargin = 0.dp`
 *  - `BasicComponent` 行高 `heightIn(min = 56.dp)`
 *  - `SliderDefaults.MinHeight = 30.dp`
 *  - `ProgressIndicatorDefaults.DefaultLinearProgressIndicatorHeight = 6.dp`
 *  - `Switch` 本体 `50 × 28.5 dp`
 *
 * 这些值集中在常量里，组件层按 `LocalAppTheme.current == MIUIX` 取用，
 * 避免每个调用点各写一个「差不多」的圆角。
 */
object MiuixSpec {
    /** 卡片 / 分组容器的圆角（HyperOS 连续圆角的标准值） */
    val cardCorner = 16.dp
    /** 设置行、列表行最小高度 */
    val rowMinHeight = 56.dp
    /** 行内左右边距 */
    val rowPadding = 16.dp
    /** 行间分隔线左侧缩进（与文字起始对齐） */
    val dividerInset = 16.dp
    /** 图标容器（圆角方块）尺寸 */
    val iconBox = 32.dp
    /** 图标容器圆角 */
    val iconBoxCorner = 8.dp
    /** 分段选择器轨道高度 */
    val segmentedHeight = 32.dp
}

/**
 * HyperOS 的排印标尺（Miuix TextStyles 的字号，单位 sp）。
 *
 * 与 M3 Expressive 的「强调字阶」是**两套语言**：
 *  - Expressive：字号不变、titleMedium 及以下一律 Bold；
 *  - HyperOS：字号整体小一档（正文 17/16/14/13/11），字重克制（标题仅 Medium），
 *    行高更紧。
 *
 * Miuix 组件自己用 `LocalTextStyles`（默认值就是这套），而屏幕上的 M3 `Text`
 * 走 `MaterialTheme.typography` —— 若不把两者统一，同一个页面里
 * 「Miuix 开关旁边的 M3 文字」会比 Miuix 自带的文字明显更大更粗。
 * 所以 MIUIX 主题下把 M3 Typography 也换成这套标尺。
 */
fun miuixTypography(): Typography {
    val base = Typography()
    fun style(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) =
        TextStyle(
            fontSize = size.sp,
            lineHeight = line.sp,
            fontWeight = weight,
        )
    return base.copy(
        displayLarge = style(32, 38, FontWeight.Medium),
        displayMedium = style(28, 34, FontWeight.Medium),
        displaySmall = style(24, 30, FontWeight.Medium),
        headlineLarge = style(28, 34, FontWeight.SemiBold),
        headlineMedium = style(24, 30, FontWeight.SemiBold),
        headlineSmall = style(20, 26, FontWeight.SemiBold),
        titleLarge = style(18, 24, FontWeight.Medium),
        titleMedium = style(16, 22, FontWeight.Medium),
        titleSmall = style(14, 20, FontWeight.Medium),
        bodyLarge = style(16, 24),
        bodyMedium = style(14, 21),
        bodySmall = style(13, 19),
        labelLarge = style(15, 20, FontWeight.Medium),
        labelMedium = style(13, 18, FontWeight.Medium),
        labelSmall = style(11, 16),
    )
}
