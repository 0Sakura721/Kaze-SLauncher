package com.mcserver.launcher.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 青蓝游戏风配色(沿用原项目风格)
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

private val LightColors = lightColorScheme(
    primary = Color(0xFF0277BD),
    background = Color(0xFFF5FAFC),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE3EDF2),
    onSurface = Color(0xFF172124),
    onSurfaceVariant = Color(0xFF4F666D)
)

@Composable
fun KazeTheme(dark: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content
    )
}
