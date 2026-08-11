package com.mcserver.launcher.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ═══════════════════════════════════════════════════════════════
//  Kaze 玻璃拟态 · 冷蓝绿极光
// ═══════════════════════════════════════════════════════════════

// ── 主色 ──
// 主色冷化为冷蓝绿（保留 Kaze 蓝品牌联想，贴合极光主题）
val KazeBlue = Color(0xFF0EA5E9)
val KazeBlueLight = Color(0xFF38BDF8)
val KazeBlueDark = Color(0xFF0369A1)

// ── 语义色 ──
val KazeSuccess = Color(0xFF22C55E)
val KazeWarning = Color(0xFFF59E0B)
val KazeError = Color(0xFFEF4444)
val KazeInfo = Color(0xFF3B82F6)

// ── 中性色(浅) ──
val LightBg = Color(0xFFF8FAFC)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFF1F5F9)
val LightOutline = Color(0xFFE2E8F0)

// ── 中性色(深) ──
val DarkBg = Color(0xFF0F172A)
val DarkSurface = Color(0xFF1E293B)
val DarkSurfaceVariant = Color(0xFF334155)
val DarkOutline = Color(0xFF475569)

// ═══════════════════════════════════════════════════════════════
//  旧名称别名(保持编译兼容,值映射到新色系)
// ═══════════════════════════════════════════════════════════════
val SakuraPink = KazeBlue
val SakuraHot = KazeBlueDark
val SakuraSoft = KazeBlueLight
val Amethyst = KazeBlue
val AmethystDeep = KazeBlueDark
val Lavender = KazeBlueLight
val SkyLite = KazeBlueLight
val MintLite = KazeSuccess
val Peach = KazeWarning
val LemonLite = KazeWarning
val InkBlack = DarkBg
val InkDeep = DarkSurface
val InkSoft = DarkSurfaceVariant
val InkMuted = DarkOutline
val Cream = LightBg
val Milk = LightSurface
val Fog = LightSurfaceVariant
val Mist = LightOutline

// ═══════════════════════════════════════════════════════════════
//  渐变别名(统一改为纯色,保持 API 兼容)
// ═══════════════════════════════════════════════════════════════
val PrimaryGradient = Brush.linearGradient(listOf(Aurora1, Aurora2))
val PrimaryGradientH = Brush.horizontalGradient(listOf(Aurora1, Aurora2))
val GlassHighlight = Brush.linearGradient(
    0f to Color.White.copy(alpha = 0.06f),
    1f to Color.Transparent
)
val CardHeroGradient = Brush.linearGradient(
    listOf(KazeBlue.copy(alpha = 0.08f), Color.Transparent)
)
val PulseGradient = Brush.sweepGradient(listOf(KazeBlue.copy(alpha = 0.3f), Color.Transparent))

val BgAuroraDark = listOf(DarkBg to Brush.verticalGradient(listOf(DarkBg, DarkBg)))
val BgAuroraLight = listOf(LightBg to Brush.verticalGradient(listOf(LightBg, LightBg)))

// ═══════════════════════════════════════════════════════════════
//  ColorScheme
// ═══════════════════════════════════════════════════════════════
@Immutable
data class GlassPalette(
    val cardAlpha: Float,
    val cardBorder: Color,
    val cardGlow: Color,
    val tintAccent: Brush,
    val overlayStrong: Color,
    val overlaySoft: Color
)

val LocalGlassPalette = staticCompositionLocalOf<GlassPalette> {
    error("no GlassPalette provided, wrap in KazeTheme")
}

private val GlassDark = GlassPalette(
    cardAlpha = 0.08f,
    cardBorder = Color.White.copy(alpha = 0.16f),
    cardGlow = Aurora1.copy(alpha = 0.20f),
    tintAccent = PrimaryGradient,
    overlayStrong = Color.Black.copy(alpha = 0.72f),
    overlaySoft = DarkSurface.copy(alpha = 0.4f)
)

private val GlassLight = GlassPalette(
    cardAlpha = 0.60f,
    cardBorder = Color.White.copy(alpha = 0.8f),
    cardGlow = Aurora1.copy(alpha = 0.12f),
    tintAccent = PrimaryGradient,
    overlayStrong = Color.White.copy(alpha = 0.82f),
    overlaySoft = LightSurfaceVariant.copy(alpha = 0.5f)
)

private val DarkColors = darkColorScheme(
    primary = KazeBlue,
    onPrimary = Color.White,
    primaryContainer = KazeBlueDark.copy(alpha = 0.3f),
    onPrimaryContainer = Color.White,
    secondary = KazeBlueLight,
    onSecondary = Color.White,
    secondaryContainer = DarkSurfaceVariant,
    onSecondaryContainer = Color.White,
    tertiary = KazeBlueLight,
    tertiaryContainer = DarkSurfaceVariant,
    background = DarkBg,
    onBackground = Color(0xFFE2E8F0),
    surface = DarkSurface,
    onSurface = Color(0xFFE2E8F0),
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = Color(0xFF94A3B8),
    surfaceTint = KazeBlue,
    outline = DarkOutline,
    outlineVariant = Color(0xFF334155),
    error = KazeError,
    onError = Color.White,
    scrim = Color.Black.copy(alpha = 0.5f)
)

private val AmoledColors = darkColorScheme(
    primary = KazeBlue,
    onPrimary = Color.White,
    primaryContainer = KazeBlueDark.copy(alpha = 0.35f),
    onPrimaryContainer = Color.White,
    secondary = KazeBlueLight,
    background = Color.Black,
    surface = Color(0xFF0A0F1A),
    surfaceVariant = Color(0xFF1A1F2E),
    onSurface = Color(0xFFE2E8F0),
    onSurfaceVariant = Color(0xFF94A3B8),
    surfaceTint = KazeBlue,
    outline = Color(0xFF334155),
    outlineVariant = Color(0xFF1E293B),
    error = KazeError
)

private val LightColors = lightColorScheme(
    primary = KazeBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDBEAFE),
    onPrimaryContainer = KazeBlueDark,
    secondary = KazeBlueLight,
    background = LightBg,
    onBackground = Color(0xFF0F172A),
    surface = LightSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurface = Color(0xFF1E293B),
    onSurfaceVariant = Color(0xFF64748B),
    outline = LightOutline,
    outlineVariant = Color(0xFFE2E8F0),
    error = KazeError
)

// ═══════════════════════════════════════════════════════════════
//  Typography
// ═══════════════════════════════════════════════════════════════
private val CleanTypography = Typography(
    displayLarge = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    displayMedium = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold),
    displaySmall = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold),
    headlineLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium)
)

// ═══════════════════════════════════════════════════════════════
//  主题入口
// ═══════════════════════════════════════════════════════════════
@Composable
fun KazeTheme(
    mode: String,
    systemDark: Boolean,
    darkAmoled: Boolean,
    reduceMotion: Boolean = false,
    content: @Composable () -> Unit
) {
    val isDark = when (mode) {
        com.mcserver.launcher.data.ThemeMode.LIGHT -> false
        com.mcserver.launcher.data.ThemeMode.SYSTEM -> systemDark
        else -> true
    }
    val useAmoled = darkAmoled && isDark && mode != com.mcserver.launcher.data.ThemeMode.LIGHT

    val scheme = when {
        useAmoled -> AmoledColors
        isDark -> DarkColors
        else -> LightColors
    }
    val glass = if (isDark) GlassDark else GlassLight

    CompositionLocalProvider(
        LocalGlassPalette provides glass,
        LocalReduceMotion provides reduceMotion
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = CleanTypography,
            shapes = KazeShapes,
            content = content
        )
    }
}

val glassPalette: GlassPalette
    @Composable get() = LocalGlassPalette.current
