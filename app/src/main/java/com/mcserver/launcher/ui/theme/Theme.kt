package com.mcserver.launcher.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 蓝青游戏风配色(沿用原项目风格)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF4FC3F7),
    onPrimary = Color(0xFF00344D),
    primaryContainer = Color(0xFF0A4A63),
    onPrimaryContainer = Color(0xFFBDEBFF),
    secondary = Color(0xFF80CBC4),
    background = Color(0xFF0E1518),
    surface = Color(0xFF162023),
    surfaceVariant = Color(0xFF1E2A2E),
    onSurface = Color(0xFFE0E8EB),
    onSurfaceVariant = Color(0xFF9FB4BA),
    error = Color(0xFFFF6E6E)
)

// AMOLED:纯黑背景,极致省电
private val AmoledColors = darkColorScheme(
    primary = Color(0xFF4FC3F7),
    onPrimary = Color(0xFF00344D),
    primaryContainer = Color(0xFF0A4A63),
    onPrimaryContainer = Color(0xFFBDEBFF),
    secondary = Color(0xFF80CBC4),
    background = Color.Black,
    surface = Color(0xFF000000),
    surfaceVariant = Color(0xFF101518),
    onSurface = Color(0xFFE0E8EB),
    onSurfaceVariant = Color(0xFF9FB4BA),
    error = Color(0xFFFF6E6E)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0277BD),
    background = Color(0xFFF5FAFC),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE3EDF2),
    onSurface = Color(0xFF172124),
    onSurfaceVariant = Color(0xFF4F666D)
)

/**
 * @param mode 主题模式:ThemeMode.SYSTEM/LIGHT/DARK/AMOLED
 * @param systemDark 系统当前是否深色(跟随系统模式时使用)
 */
@Composable
fun KazeTheme(mode: String, systemDark: Boolean, content: @Composable () -> Unit) {
    val scheme = when {
        com.mcserver.launcher.data.ThemeMode.isAmoled(mode) -> AmoledColors
        com.mcserver.launcher.data.ThemeMode.isDark(mode, systemDark) -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
