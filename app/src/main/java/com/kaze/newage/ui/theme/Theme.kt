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
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme

// ══════════════════════════════════════════════════════════════
// 主题系统（照搬 BiliPai 主题设置体系，GPL-3.0）：
//  - 主题模式：跟随系统 / 浅色 / 深色（AppThemeMode）
//  - 深色样式：普通黑 / AMOLED 纯黑（DarkThemeStyle）
//  - 主题样式：Material 3（动态取色/自定义种子色 + 取色风格）/ 液态玻璃
//  - 液态玻璃模式：清晰 CLEAR / 均衡 BALANCED / 磨砂 FROSTED
//    （参数照搬 BiliPai LiquidGlassTuning 的 progress 线性映射）
// ══════════════════════════════════════════════════════════════

/** 液态玻璃模式（照搬 BiliPai LiquidGlassMode 三档） */
enum class GlassMode(val id: String, val label: String, val progress: Float) {
    CLEAR("clear", "清晰", 0.2f),
    BALANCED("balanced", "均衡", 0.5f),
    FROSTED("frosted", "磨砂", 0.85f),
    ;

    companion object {
        fun fromId(id: String?): GlassMode = entries.firstOrNull { it.id == id } ?: BALANCED
    }
}

/** 玻璃参数（照搬 BiliPai LiquidGlassTuning 的 lerp 映射） */
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
        // BiliPai 原参数 3→30 在真实壁纸上成立；无壁纸的渐变底需要更强的糊化才能看出玻璃
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

/**
 * 主题入口。
 * @param darkTheme 是否深色（由「主题模式」与系统共同解析）
 * @param amoledDark AMOLED 纯黑深色样式（照搬 BiliPai DarkThemeStyle）
 * @param colorSource MD3 颜色来源：wallpaper / custom（照搬 BiliPai Md3ColorSource）
 * @param customColorHex 自定义种子色
 * @param paletteStyle 取色风格（materialkolor PaletteStyle）
 * @param glassMode 液态玻璃模式（clear/balanced/frosted）
 */
@Composable
fun NewAgeTheme(
    mode: AppThemeMode = AppThemeMode.M3,
    darkTheme: Boolean = isSystemInDarkTheme(),
    amoledDark: Boolean = false,
    colorSource: String = "wallpaper",
    customColorHex: String = "#007AFF",
    paletteStyle: String = "TonalSpot",
    glassMode: GlassMode = GlassMode.BALANCED,
    glassIntensity: Float = 1f,
    fgColorMode: FgColorMode = FgColorMode.AUTO,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val style = runCatching { PaletteStyle.valueOf(paletteStyle) }.getOrDefault(PaletteStyle.TonalSpot)
    val seed = if (colorSource == "custom") parseSeedColor(customColorHex) else null

    val scheme: ColorScheme = when (mode) {
        AppThemeMode.M3 -> {
            when {
                // materialkolor 2.x：seedColor 为 Compose Color；原生支持 isAmoled 纯黑
                seed != null -> dynamicColorScheme(
                    seedColor = Color(seed),
                    isDark = darkTheme,
                    isAmoled = amoledDark,
                    style = style,
                )
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                    if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
                else -> if (darkTheme) M3Dark else M3Light
            }
        }
        AppThemeMode.GLASS -> if (darkTheme) GlassDark else GlassLight
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
        MaterialTheme(colorScheme = fgAdjusted, content = content)
    }
}

// ───────────────────────────────────────────────
// 色板函数（改编自 ZalithLauncher2 ui/theme/Palette.kt，GPL-3.0）
// ───────────────────────────────────────────────

/** 应用整体背景色 */
@Composable
@ReadOnlyComposable
fun backgroundColor(): Color = MaterialTheme.colorScheme.surfaceContainer

@Composable
@ReadOnlyComposable
fun onBackgroundColor(): Color = MaterialTheme.colorScheme.onSurfaceVariant

/**
 * 卡片背景：M3=surfaceBright / GLASS=玻璃表面
 * （照搬 BiliPai：surfaceAlpha 随玻璃模式 progress 线性映射 0.12→0.42）
 */
@Composable
@ReadOnlyComposable
fun cardColor(): Color = when (LocalAppTheme.current) {
    AppThemeMode.M3 -> MaterialTheme.colorScheme.surfaceBright
    AppThemeMode.GLASS -> Color.White.copy(alpha = glassParams(LocalGlassMode.current).surfaceAlpha)
}

@Composable
@ReadOnlyComposable
fun onCardColor(): Color = MaterialTheme.colorScheme.onSurface

/** 卡片描边：M3=发丝灰 / GLASS=高光白边 */
@Composable
@ReadOnlyComposable
fun cardBorderColor(): Color = when (LocalAppTheme.current) {
    AppThemeMode.M3 -> MaterialTheme.colorScheme.outlineVariant
    AppThemeMode.GLASS -> if (LocalDarkTheme.current) Color.White.copy(alpha = 0.20f)
    else Color.White.copy(alpha = 0.85f)
}

/** 服务器实例项边框：主题对应且**可见**（M3=outline 灰框；玻璃深色=白框；玻璃浅色=深蓝灰框；选中=主色） */
@Composable
@ReadOnlyComposable
fun serverItemBorderColor(selected: Boolean = false): Color = when (LocalAppTheme.current) {
    AppThemeMode.M3 ->
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    AppThemeMode.GLASS -> when {
        selected -> MaterialTheme.colorScheme.primary
        LocalDarkTheme.current -> Color.White.copy(alpha = 0.35f)
        else -> Color(0xFF233049).copy(alpha = 0.30f)
    }
}

/** 卡片圆角：M3=14 / GLASS=24 */
@Composable
@ReadOnlyComposable
fun cardShape(): RoundedCornerShape = when (LocalAppTheme.current) {
    AppThemeMode.M3 -> RoundedCornerShape(14.dp)
    AppThemeMode.GLASS -> RoundedCornerShape(24.dp)
}

/** 卡片顶部标题栏背景 */
@Composable
@ReadOnlyComposable
fun cardTitleColor(): Color = when (LocalAppTheme.current) {
    AppThemeMode.M3 -> MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.85f)
    AppThemeMode.GLASS -> Color.White.copy(alpha = 0.10f)
}

/** 卡片内条目背景 */
@Composable
@ReadOnlyComposable
fun itemColor(): Color = when (LocalAppTheme.current) {
    AppThemeMode.M3 -> if (LocalDarkTheme.current) MaterialTheme.colorScheme.surfaceVariant
    else MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
    AppThemeMode.GLASS -> Color.White.copy(alpha = 0.12f)
}

@Composable
@ReadOnlyComposable
fun onItemColor(): Color = MaterialTheme.colorScheme.onSurface

/**
 * 玻璃表面的 Haze 样式（BiliPai LiquidGlassTuning 参数基础上强化：
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
 * 底栏专用 Haze 样式（照搬 BiliPai LiquidGlassTuning：blur 3→30dp）：
 * 文字透过时明显模糊但仍可辨形态，边缘折射弯曲可见；强度滑杆缩放模糊。
 */
@Composable
fun glassNavBarHazeStyle(): dev.chrisbanes.haze.HazeStyle {
    val mode = LocalGlassMode.current
    val intensity = LocalGlassIntensity.current
    val white = dev.chrisbanes.haze.HazeTint(Color.White.copy(alpha = lerpF(0.04f, 0.10f, mode.progress)))
    return dev.chrisbanes.haze.HazeStyle(
        blurRadius = 100.dp, // 极端值诊断：验证 Haze blur 是否在真机执行，验证后回调
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
}

/** 控制台日志面板背景 */
@Composable
@ReadOnlyComposable
fun consoleBackgroundColor(): Color = when (LocalAppTheme.current) {
    AppThemeMode.M3 -> Color(0xFF10141C)
    AppThemeMode.GLASS -> if (LocalDarkTheme.current) Color(0xFF060B16) else Color(0xFF111A2C)
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
}
