package com.kaze.newage.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kaze.newage.core.console.LineType

// ══════════════════════════════════════════════════════════════
// 主题系统：
//  - 主题模式：跟随系统 / 浅色 / 深色（AppThemeMode）
//  - 深色样式：普通黑 / AMOLED 纯黑（DarkThemeStyle）
//  - 主题样式：Material 3（壁纸动态取色 / 自定义二级色生成）
//  - 液态玻璃模式：清晰 CLEAR / 均衡 BALANCED / 磨砂 FROSTED
//    （参数按 progress 线性映射）
// ══════════════════════════════════════════════════════════════

/** 液态玻璃模式（三档） */
enum class GlassMode(val id: String, val label: String, val progress: Float) {
    CLEAR("clear", "清晰", 0.2f),
    BALANCED("balanced", "均衡", 0.5f),
    FROSTED("frosted", "磨砂", 0.85f),
    ;

    companion object {
        fun fromId(id: String?): GlassMode = entries.firstOrNull { it.id == id } ?: BALANCED
    }
}

/** 玻璃参数（按 progress lerp） */
data class GlassParams(
    val blurRadius: Dp,
    val surfaceAlpha: Float,
    val whiteOverlayAlpha: Float,
    /** 底栏透镜折射幅度系数：清晰→弱折射，磨砂→强折射 */
    val refractionScale: Float,
)

private fun lerpF(start: Float, stop: Float, fraction: Float): Float =
    start + (stop - start) * fraction

fun glassParams(mode: GlassMode): GlassParams {
    val p = mode.progress
    return GlassParams(
        // 常规参数 3→30 在真实壁纸上成立；无壁纸的渐变底需要更强的糊化才能看出玻璃
        blurRadius = lerpF(10f, 38f, p).dp,
        surfaceAlpha = lerpF(0.10f, 0.34f, p),
        whiteOverlayAlpha = lerpF(0.02f, 0.14f, p),
        refractionScale = lerpF(0.8f, 1.6f, p),
    )
}

/** 当前液态玻璃模式 */
val LocalGlassMode = staticCompositionLocalOf { GlassMode.BALANCED }

/** 玻璃强度（0.5..1.5）：glassSaturation 饱和度与透镜折射幅度按此缩放 */
val LocalGlassIntensity = staticCompositionLocalOf { 1f }

/** AMOLED 纯黑深色样式（背景层自绘渐变需感知，不能只靠 colorScheme） */
val LocalAmoledDark = staticCompositionLocalOf { false }

// ── M3 静态兜底（API<31 或取色失败时使用）──
private val M3Light = lightColorScheme(
    primary = Color(0xFF3563E9),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE3EAFF),
    onPrimaryContainer = Color(0xFF12359F),
    secondary = Color(0xFF54657E),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFF7A5CC4),
    background = Color(0xFFF5F7FA),
    onBackground = Color(0xFF18202E),
    surface = Color(0xFFF5F7FA),
    onSurface = Color(0xFF18202E),
    surfaceVariant = Color(0xFFEDF1F7),
    onSurfaceVariant = Color(0xFF68748A),
    outline = Color(0xFFB9C2D2),
    outlineVariant = Color(0xFFE4E9F1),
    surfaceContainer = Color(0xFFF5F7FA),
    surfaceContainerHigh = Color(0xFFEFF2F7),
    surfaceContainerLow = Color(0xFFFAFBFD),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceBright = Color(0xFFFFFFFF),
    error = Color(0xFFD64545),
    onError = Color(0xFFFFFFFF),
)

private val M3Dark = darkColorScheme(
    primary = Color(0xFF7C9BFF),
    onPrimary = Color(0xFF0B2B7A),
    primaryContainer = Color(0xFF24376B),
    onPrimaryContainer = Color(0xFFD9E2FF),
    secondary = Color(0xFF9FB2D6),
    onSecondary = Color(0xFF1D2738),
    tertiary = Color(0xFFB7A0E8),
    background = Color(0xFF0E1117),
    onBackground = Color(0xFFE8ECF4),
    surface = Color(0xFF0E1117),
    onSurface = Color(0xFFE8ECF4),
    surfaceVariant = Color(0xFF20283A),
    onSurfaceVariant = Color(0xFF95A0B5),
    outline = Color(0xFF3C465A),
    outlineVariant = Color(0xFF242B3A),
    surfaceContainer = Color(0xFF0E1117),
    surfaceContainerHigh = Color(0xFF12161E),
    surfaceContainerLow = Color(0xFF10131B),
    surfaceContainerLowest = Color(0xFF0B0E14),
    surfaceBright = Color(0xFF1A202C),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF3B0606),
)

// ── SHELL · 岚（浅色/素纸）──
// 与 shell/Tokens.kt 的 PaperTokens 保持一致：纸底 + 黛蓝强调 + 朱红危险。
// 严格说 SHELL 走的是另一个 Activity，Compose 这边用不到；但一旦有人把「岚」
// 接进 Compose（或截图测试里切到 SHELL），配色也该是同一个人做的，不该是默认蓝。
private val ShellLight = lightColorScheme(
    primary = Color(0xFF2C4A63),
    onPrimary = Color(0xFFFDFEFF),
    primaryContainer = Color(0xFFE6EDF3),
    onPrimaryContainer = Color(0xFF1D3547),
    secondary = Color(0xFF54504A),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFF3B6E8F),
    background = Color(0xFFF4F1EC),
    onBackground = Color(0xFF191714),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF191714),
    surfaceVariant = Color(0xFFF8F5F0),
    onSurfaceVariant = Color(0xFF54504A),
    outline = Color(0xFFCBC1B2),
    outlineVariant = Color(0xFFE1DACF),
    surfaceContainer = Color(0xFFF4F1EC),
    surfaceContainerHigh = Color(0xFFF8F5F0),
    surfaceContainerLow = Color(0xFFF8F5F0),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceBright = Color(0xFFFFFFFF),
    error = Color(0xFFA83524),
    onError = Color(0xFFFFFFFF),
)

// ── SHELL · 岚（深色/墨夜）──
private val ShellDark = darkColorScheme(
    primary = Color(0xFFE7AE4C),
    onPrimary = Color(0xFF16181A),
    primaryContainer = Color(0xFF2B2317),
    onPrimaryContainer = Color(0xFFE7AE4C),
    secondary = Color(0xFFB2ACA2),
    onSecondary = Color(0xFF16181A),
    tertiary = Color(0xFF6FA8D6),
    background = Color(0xFF0E1114),
    onBackground = Color(0xFFF2EEE7),
    surface = Color(0xFF171B1F),
    onSurface = Color(0xFFF2EEE7),
    surfaceVariant = Color(0xFF1E2327),
    onSurfaceVariant = Color(0xFFB2ACA2),
    outline = Color(0xFF3E464C),
    outlineVariant = Color(0xFF2A3136),
    surfaceContainer = Color(0xFF111518),
    surfaceContainerHigh = Color(0xFF1E2327),
    surfaceContainerLow = Color(0xFF111518),
    surfaceContainerLowest = Color(0xFF06080A),
    surfaceBright = Color(0xFF2A3136),
    error = Color(0xFFE46A4F),
    onError = Color(0xFF16181A),
)

// ── GLASS · 液态玻璃（浅色）──
private val GlassLight = lightColorScheme(
    primary = Color(0xFF2B6BE4),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE9FF),
    onPrimaryContainer = Color(0xFF0A3A91),
    secondary = Color(0xFF5B6B8C),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFF8E6BD0),
    background = Color(0xFFD9E5F5),
    onBackground = Color(0xFF17202E),
    surface = Color(0xFFD9E5F5),
    onSurface = Color(0xFF17202E),
    surfaceVariant = Color(0xFFDDE6F2),
    onSurfaceVariant = Color(0xFF4E5C77),
    outline = Color(0xFFB4C2D8),
    outlineVariant = Color(0xFFCBD7E8),
    surfaceContainer = Color(0xFFD9E5F5),
    surfaceContainerHigh = Color(0xFFD2DFF0),
    surfaceContainerLow = Color(0xFFE3EDF9),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceBright = Color(0xFFFFFFFF),
    error = Color(0xFFD64545),
    onError = Color(0xFFFFFFFF),
)

// ── GLASS · 液态玻璃（深色）──
private val GlassDark = darkColorScheme(
    primary = Color(0xFF7CA8FF),
    onPrimary = Color(0xFF0A2A6B),
    primaryContainer = Color(0xFF1E3A70),
    onPrimaryContainer = Color(0xFFD8E4FF),
    secondary = Color(0xFFA3B4D8),
    onSecondary = Color(0xFF1A2436),
    tertiary = Color(0xFFB9A2E8),
    onTertiary = Color(0xFF281A45),
    background = Color(0xFF070D1C),
    onBackground = Color(0xFFE6ECF7),
    surface = Color(0xFF070D1C),
    onSurface = Color(0xFFE6ECF7),
    surfaceVariant = Color(0xFF15203A),
    onSurfaceVariant = Color(0xFF97A5C2),
    outline = Color(0xFF33405E),
    outlineVariant = Color(0xFF24304A),
    surfaceContainer = Color(0xFF070D1C),
    surfaceContainerHigh = Color(0xFF0A1122),
    surfaceContainerLow = Color(0xFF080F20),
    surfaceContainerLowest = Color(0xFF050A17),
    surfaceBright = Color(0xFF121C33),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF3B0606),
)

/** 解析自定义种子色 hex（非法回退 null → 壁纸动态） */
fun parseSeedColor(hex: String?): Int? {
    val raw = hex?.trim()?.removePrefix("#") ?: return null
    if (raw.length != 6 || !raw.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return null
    return raw.toLong(16).toInt() or 0xFF000000.toInt()
}

// ───────────────────────────────────────────────
// 自定义二级色 → 完整 ColorScheme（亮/暗变换 + 自动对比色的通用算法）：
// 亮/暗变换 + 自动对比文字色，不依赖取色风格算法
// ───────────────────────────────────────────────

private fun lightenColor(color: Color, factor: Float): Color {
    val r = color.red + (1f - color.red) * factor
    val g = color.green + (1f - color.green) * factor
    val b = color.blue + (1f - color.blue) * factor
    return Color(r, g, b, color.alpha)
}

private fun darkenColor(color: Color, factor: Float): Color {
    val r = color.red * (1f - factor)
    val g = color.green * (1f - factor)
    val b = color.blue * (1f - factor)
    return Color(r, g, b, color.alpha)
}

private fun isColorLight(color: Color): Boolean {
    val luminance = 0.299f * color.red + 0.587f * color.green + 0.114f * color.blue
    return luminance > 0.5f
}

private fun contrastingText(color: Color): Color =
    if (isColorLight(color)) Color(0xFF1A1C20) else Color.White

/** 从二级色种子生成全套 ColorScheme（亮暗变换 + 自动对比色） */
private fun seedColorScheme(seed: Int, isDark: Boolean): ColorScheme {
    val primary = Color(seed)
    // 深色下主色提亮（可读性），浅色保持原型
    val effectivePrimary = if (isDark) lightenColor(primary, 0.25f) else primary
    val secondary = if (isDark) lightenColor(primary, 0.45f) else lightenColor(primary, 0.35f)
    val primaryContainer = if (isDark) darkenColor(primary, 0.3f) else lightenColor(primary, 0.7f)
    val secondaryContainer = if (isDark) darkenColor(primary, 0.2f) else lightenColor(primary, 0.75f)
    val base = if (isDark) M3Dark else M3Light
    return base.copy(
        primary = effectivePrimary,
        onPrimary = contrastingText(effectivePrimary),
        primaryContainer = primaryContainer,
        onPrimaryContainer = if (isDark) Color.White else contrastingText(primaryContainer),
        secondary = secondary,
        onSecondary = contrastingText(secondary),
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = contrastingText(secondaryContainer),
        tertiary = if (isDark) lightenColor(primary, 0.6f) else lightenColor(primary, 0.5f),
        onTertiary = Color.White,
    )
}

/**
 * 主题入口。
 * @param darkTheme 是否深色（由「主题模式」与系统共同解析）
 * @param amoledDark AMOLED 纯黑深色样式
 * @param colorSource MD3 颜色来源：wallpaper / custom
 * @param customColorHex 自定义主色（hex）
 * @param glassMode 液态玻璃模式（clear/balanced/frosted）
 */
@Composable
fun NewAgeTheme(
    mode: AppThemeMode = AppThemeMode.M3,
    darkTheme: Boolean = isSystemInDarkTheme(),
    amoledDark: Boolean = false,
    colorSource: String = "wallpaper",
    customColorHex: String = "#00FFFF",
    glassMode: GlassMode = GlassMode.BALANCED,
    glassIntensity: Float = 1f,
    fgColorMode: FgColorMode = FgColorMode.AUTO,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val seed = if (colorSource == "custom") parseSeedColor(customColorHex) else null

    val scheme: ColorScheme = when (mode) {
        AppThemeMode.M3 -> {
            when {
                // 自定义二级色：亮/暗变换 + 自动对比色生成，不依赖取色风格
                seed != null -> seedColorScheme(seed, darkTheme)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                    if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
                else -> if (darkTheme) M3Dark else M3Light
            }
        }
        AppThemeMode.GLASS -> if (darkTheme) GlassDark else GlassLight
        AppThemeMode.SHELL -> if (darkTheme) ShellDark else ShellLight
    }

    // AMOLED 纯黑：深色时将背景/表面系压到纯黑
    val amoledAdjusted = if (darkTheme && amoledDark) {
        scheme.copy(
            background = Color.Black,
            onBackground = scheme.onBackground,
            surface = Color.Black,
            onSurface = scheme.onSurface,
            surfaceVariant = Color(0xFF0F1114),
            surfaceContainer = Color.Black,
            surfaceContainerHigh = Color(0xFF0A0B0E),
            surfaceContainerLow = Color(0xFF050608),
            surfaceContainerLowest = Color.Black,
            surfaceContainerHighest = Color(0xFF101216),
            // surfaceBright 也必须覆盖：M3 的卡片底色 cardColor() 用的正是它，
            // 漏掉的话「AMOLED 纯黑」下卡片仍是原来的亮灰，看着一点都不纯黑
            surfaceBright = Color(0xFF14161A),
        )
    } else scheme

    // 深色可读性保险：中性文字角色不依赖动态色板，统一使用验证过的亮色系；
    // 主/次/第三色仍保留动态取色。（历史根因：M3 1.3 MaterialTheme 不提供
    // LocalContentColor，默认文字色=Color.Black，深色下整段文字与黑底融为一体。）
    val effectiveScheme = if (darkTheme) {
        amoledAdjusted.copy(
            onSurface = Color(0xFFE8ECF4),
            onBackground = Color(0xFFE8ECF4),
            onSurfaceVariant = Color(0xFFAEB8CC),
            outline = Color(0xFF5E6A86),
        )
    } else amoledAdjusted

    // 图标与文字颜色模式：覆盖中性前景角色（背景/卡片上的文字与图标色），
    // 动态色底（chip/按钮容器）上的文字保持原有对比，不随模式翻转。
    val fgAdjusted = when (fgColorMode) {
        FgColorMode.LIGHT -> effectiveScheme.copy(
            onSurface = Color(0xFFFFFFFF),
            onBackground = Color(0xFFFFFFFF),
            onSurfaceVariant = Color(0xFFD6DCE6),
            outline = Color(0xFF8A93A6),
            outlineVariant = Color(0xFF5E6A86),
        )
        FgColorMode.DARK -> effectiveScheme.copy(
            onSurface = Color(0xFF1A1C20),
            onBackground = Color(0xFF1A1C20),
            onSurfaceVariant = Color(0xFF4A505C),
            outline = Color(0xFF6E7684),
            outlineVariant = Color(0xFFB8BEC9),
        )
        FgColorMode.AUTO -> effectiveScheme
    }

    CompositionLocalProvider(
        LocalAppTheme provides mode,
        LocalDarkTheme provides darkTheme,
        LocalGlassMode provides glassMode,
        LocalGlassIntensity provides glassIntensity,
        LocalAmoledDark provides (darkTheme && amoledDark),
        LocalFgColorMode provides fgColorMode,
        // M3 1.3.x 的 MaterialTheme 不再提供 LocalContentColor（默认值=Color.Black），
        // 未显式指定颜色的 Text 在深色下会整段渲染成纯黑——这里按 scheme 显式补上
        LocalContentColor provides fgAdjusted.onBackground,
    ) {
        // 排版走 M3 Expressive 的强调字阶：字号/行高与 M3 基线完全一致，
        // 只有字重与字距变（titleMedium 及以下加粗到 Bold）。
        // 这是「四轴」里的 typography 一轴，材料见 m3e-canvas 生成的 prompt。
        MaterialTheme(
            colorScheme = fgAdjusted,
            typography = expressiveTypography(),
            content = content,
        )
    }
}

// ───────────────────────────────────────────────
// 色板函数（改编自 ZalithLauncher2 ui/theme/Palette.kt，GPL-3.0）
//
// 说明：原来这一组还包含 cardColor / cardShape / cardTitleColor / itemColor /
// serverItemBorderColor 等「卡片体系」助手，它们服务于已删除的
// BackgroundCard/CardTitleLayout（旧设计的大卡框架）。界面重构后所有屏幕都改用
// ui/components/M3EComponents.kt 的 Expressive 组件，颜色一律从
// MaterialTheme.colorScheme 的角色取，助手层只剩背景色与状态色。
// ───────────────────────────────────────────────

/** 应用整体背景色 */
@Composable
@ReadOnlyComposable
fun backgroundColor(): Color = MaterialTheme.colorScheme.surfaceContainer

@Composable
@ReadOnlyComposable
fun onBackgroundColor(): Color = MaterialTheme.colorScheme.onSurfaceVariant

/**
 * 玻璃表面的 Haze 样式（在常规参数基础上强化：
 * 白色雾化 tint + 蓝色 Plus 增色 + 细噪点，让模糊后的色斑更有玻璃质感）。
 */
@Composable
fun glassHazeStyle(): dev.chrisbanes.haze.HazeStyle {
    val p = glassParams(LocalGlassMode.current)
    val white = dev.chrisbanes.haze.HazeTint(Color.White.copy(alpha = p.whiteOverlayAlpha))
    return dev.chrisbanes.haze.HazeStyle(
        blurRadius = p.blurRadius,
        noiseFactor = 0.05f,
        fallbackTint = white,
        tints = listOf(
            white,
            dev.chrisbanes.haze.HazeTint(Color(0xFF2B4BFF).copy(alpha = 0.06f), BlendMode.Plus),
        ),
    )
}

/**
 * 底栏专用 Haze 样式（blur 3→30dp）：
 * 文字透过时明显模糊但仍可辨形态，边缘折射弯曲可见；强度滑杆缩放模糊。
 */
@Composable
fun glassNavBarHazeStyle(): dev.chrisbanes.haze.HazeStyle {
    val mode = LocalGlassMode.current
    val intensity = LocalGlassIntensity.current
    val white = dev.chrisbanes.haze.HazeTint(Color.White.copy(alpha = lerpF(0.04f, 0.10f, mode.progress)))
    return dev.chrisbanes.haze.HazeStyle(
        blurRadius = (lerpF(3f, 30f, mode.progress) * intensity).dp,
        noiseFactor = 0.05f,
        fallbackTint = white,
        tints = listOf(
            white,
            dev.chrisbanes.haze.HazeTint(Color(0xFF2B4BFF).copy(alpha = 0.08f), BlendMode.Plus),
        ),
    )
}

// ───────────────────────────────────────────────
// 状态色与日志着色
// ───────────────────────────────────────────────

data class StatusPalette(
    val running: Color,
    val busy: Color,
    val idle: Color,
    val error: Color,
)

@Composable
@ReadOnlyComposable
fun statusPalette(): StatusPalette = when (LocalAppTheme.current) {
    AppThemeMode.M3 -> if (LocalDarkTheme.current) StatusPalette(
        running = Color(0xFF34D399),
        busy = Color(0xFFFFC24B),
        idle = Color(0xFFA9B3C8), // 提亮：深色下"未启动/已停止"等状态字可见
        error = Color(0xFFFF6B6B),
    ) else StatusPalette(
        running = Color(0xFF23A268),
        busy = Color(0xFFC9821A),
        idle = Color(0xFF98A2B3),
        error = Color(0xFFD64545),
    )
    AppThemeMode.GLASS -> if (LocalDarkTheme.current) StatusPalette(
        running = Color(0xFF34D399),
        busy = Color(0xFFFFC24B),
        idle = Color(0xFFA9B3C8), // 提亮：深色下状态字可见
        error = Color(0xFFFF6B6B),
    ) else StatusPalette(
        running = Color(0xFF1FA36B),
        busy = Color(0xFFC9821A),
        idle = Color(0xFF8A93A6),
        error = Color(0xFFD64545),
    )
    // 与 PaperTokens/InkTokens 的 good/warn/danger/ink3 对齐
    AppThemeMode.SHELL -> if (LocalDarkTheme.current) StatusPalette(
        running = Color(0xFF62C08D),
        busy = Color(0xFFD9A54B),
        idle = Color(0xFF79838B),
        error = Color(0xFFE46A4F),
    ) else StatusPalette(
        running = Color(0xFF2F6B48),
        busy = Color(0xFF9A6A14),
        idle = Color(0xFF8A8277),
        error = Color(0xFFA83524),
    )
}

/** 控制台日志面板背景 */
@Composable
@ReadOnlyComposable
fun consoleBackgroundColor(): Color = when (LocalAppTheme.current) {
    AppThemeMode.M3 -> Color(0xFF10141C)
    AppThemeMode.GLASS -> if (LocalDarkTheme.current) Color(0xFF060B16) else Color(0xFF111A2C)
    // 岚的日志面板：纸底用浅暖白，墨夜用最深的那张贴面色
    AppThemeMode.SHELL -> if (LocalDarkTheme.current) Color(0xFF111518) else Color(0xFFF8F5F0)
}

/** 控制台日志行颜色 */
@Composable
@ReadOnlyComposable
fun consoleLineColor(type: LineType): Color = when (LocalAppTheme.current) {
    AppThemeMode.M3 -> when (type) {
        LineType.Error -> Color(0xFFFF7A7A)
        LineType.Warn -> Color(0xFFFFD166)
        LineType.Command -> Color(0xFF7CB8FF)
        LineType.System -> Color(0xFFAEB8CC) // 提亮：深色终端上系统行可见
        LineType.Info -> Color(0xFFDDE3EC)
    }
    AppThemeMode.GLASS -> when (type) {
        LineType.Error -> Color(0xFFFF7A7A)
        LineType.Warn -> Color(0xFFFFD166)
        LineType.Command -> Color(0xFF8AB9FF)
        LineType.System -> if (LocalDarkTheme.current) Color(0xFFA9B3C8) else Color(0xFF93A1B8)
        LineType.Info -> if (LocalDarkTheme.current) Color(0xFFC9D4E8) else Color(0xFFE2E9F4)
    }
    // 与 shell/ShellHost.lineColor 同源：浅底用深墨，深底用亮墨
    AppThemeMode.SHELL -> when (type) {
        LineType.Error -> Color(0xFFA83524)
        LineType.Warn -> Color(0xFF9A6A14)
        LineType.Command -> Color(0xFF2C4A63)
        LineType.System -> Color(0xFF8A8277)
        LineType.Info -> Color(0xFF54504A)
    }
}
