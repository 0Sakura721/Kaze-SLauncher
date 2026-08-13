package com.mcserver.launcher.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** 当前风格令牌（全 UI 通过它取色，实现全局换肤） */
val LocalKazeTokens = staticCompositionLocalOf<StyleTokens> {
    Styles.forKey(StyleKeys.LIQUID, false)
}

private val KazeTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        letterSpacing = 0.5.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 13.sp,
        lineHeight = 19.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
    ),
)

@Composable
fun KazeTheme(
    styleKey: String,
    themeMode: Int, // 0=系统 1=浅色 2=深色
    customSeed: Int = 0, // 用户自定义主色（ARGB，0=风格默认）
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val isDark = when (themeMode) {
        1 -> false
        2 -> true
        else -> systemDark
    }
    val tokens = Styles.forKey(styleKey, isDark, customSeed)

    val colorScheme = if (isDark) {
        darkColorScheme(
            primary = tokens.primary,
            secondary = tokens.secondary,
            tertiary = tokens.accent,
            background = tokens.background,
            surface = tokens.surface,
            surfaceVariant = tokens.surfaceVariant,
            onPrimary = Color.White,
            onSecondary = Color.White,
            onBackground = tokens.onBackground,
            onSurface = tokens.onSurface,
            outline = tokens.outline,
        )
    } else {
        lightColorScheme(
            primary = tokens.primary,
            secondary = tokens.secondary,
            tertiary = tokens.accent,
            background = tokens.background,
            surface = tokens.surface,
            surfaceVariant = tokens.surfaceVariant,
            onPrimary = Color.White,
            onSecondary = Color.White,
            onBackground = tokens.onBackground,
            onSurface = tokens.onSurface,
            outline = tokens.outline,
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(tokens.cornerSmall),
            small = RoundedCornerShape(tokens.cornerSmall),
            medium = RoundedCornerShape(tokens.cornerMedium),
            large = RoundedCornerShape(tokens.cornerLarge),
            extraLarge = RoundedCornerShape(tokens.cornerLarge + androidx.compose.ui.unit.Dp(8f)),
        ),
        typography = KazeTypography,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(LocalKazeTokens provides tokens) {
            content()
        }
    }
}