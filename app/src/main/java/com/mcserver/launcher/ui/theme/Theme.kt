package com.mcserver.launcher.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// ═══════════════════════════════════════════════════════════
//  Kaze SLauncher — 蓝青游戏风配色体系
//  主色:青蓝(#4FC3F7) + 辅色:薄荷青(#80CBC4)
//  强调:电光蓝 + 暗夜底色,营造科技仪表盘质感
// ═══════════════════════════════════════════════════════════

// ── 核心色板 ──
val KazeCyan = Color(0xFF4FC3F7)
val KazeTeal = Color(0xFF80CBC4)
val KazeDeepBlue = Color(0xFF0277BD)
val KazeDarkBg = Color(0xFF0E1518)
val KazeDarkSurface = Color(0xFF162023)
val KazeAmoledBg = Color(0xFF000000)

// ── 状态色 ──
val KazeSuccess = Color(0xFF4CAF50)
val KazeWarning = Color(0xFFFFA726)
val KazeError = Color(0xFFFF6E6E)

// ── 渐变刷(游戏风光效) ──
val PrimaryGradient = Brush.horizontalGradient(
    colors = listOf(Color(0xFF4FC3F7), Color(0xFF26C6DA))
)

val DashboardGradient = Brush.sweepGradient(
    colors = listOf(
        Color(0xFF4FC3F7),
        Color(0xFF26C6DA),
        Color(0xFF80CBC4),
        Color(0xFF4FC3F7)
    )
)

val CardGradient = Brush.linearGradient(
    colors = listOf(
        Color(0xFF1A2830).copy(alpha = 0.8f),
        Color(0xFF0E1518).copy(alpha = 0.95f)
    )
)

val SurfaceGlow = Brush.radialGradient(
    colors = listOf(
        Color(0xFF4FC3F7).copy(alpha = 0.15f),
        Color(0xFF4FC3F7).copy(alpha = 0.0f)
    )
)

val PulseGradient = Brush.sweepGradient(
    colors = listOf(
        Color(0xFF4FC3F7).copy(alpha = 0.4f),
        Color(0xFF26C6DA).copy(alpha = 0.2f),
        Color(0xFF4FC3F7).copy(alpha = 0.0f),
        Color(0xFF4FC3F7).copy(alpha = 0.4f)
    )
)

// ── 深色配色(蓝青游戏风) ──
private val DarkColors = darkColorScheme(
    primary = KazeCyan,
    onPrimary = Color(0xFF00344D),
    primaryContainer = Color(0xFF0A4A63),
    onPrimaryContainer = Color(0xFFBDEBFF),
    secondary = KazeTeal,
    background = KazeDarkBg,
    surface = KazeDarkSurface,
    surfaceVariant = Color(0xFF1E2A2E),
    onSurface = Color(0xFFE0E8EB),
    onSurfaceVariant = Color(0xFF9FB4BA),
    error = KazeError
)

// ── AMOLED:纯黑背景,极致省电 ──
private val AmoledColors = darkColorScheme(
    primary = KazeCyan,
    onPrimary = Color(0xFF00344D),
    primaryContainer = Color(0xFF0A4A63),
    onPrimaryContainer = Color(0xFFBDEBFF),
    secondary = KazeTeal,
    background = Color.Black,
    surface = Color(0xFF000000),
    surfaceVariant = Color(0xFF101518),
    onSurface = Color(0xFFE0E8EB),
    onSurfaceVariant = Color(0xFF9FB4BA),
    error = KazeError
)

// ── 浅色配色 ──
private val LightColors = lightColorScheme(
    primary = KazeDeepBlue,
    background = Color(0xFFF5FAFC),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE3EDF2),
    onSurface = Color(0xFF172124),
    onSurfaceVariant = Color(0xFF4F666D)
)

/**
 * @param mode 主题模式:ThemeMode.SYSTEM/LIGHT/DARK/AMOLED
 * @param systemDark 系统当前是否深色(跟随系统模式时使用)
 * @param darkAmoled 深色配色是否使用 AMOLED 纯黑(深色/跟随系统深色时生效)
 */
@Composable
fun KazeTheme(mode: String, systemDark: Boolean, darkAmoled: Boolean, content: @Composable () -> Unit) {
    val darkScheme = if (darkAmoled) AmoledColors else DarkColors
    val scheme = when (mode) {
        com.mcserver.launcher.data.ThemeMode.SYSTEM -> if (systemDark) darkScheme else LightColors
        com.mcserver.launcher.data.ThemeMode.LIGHT -> LightColors
        com.mcserver.launcher.data.ThemeMode.AMOLED -> AmoledColors
        else -> darkScheme // DARK
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
