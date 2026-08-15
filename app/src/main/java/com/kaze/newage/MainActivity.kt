package com.kaze.newage

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import com.kaze.newage.ui.AppRoot
import com.kaze.newage.ui.theme.AppThemeMode
import com.kaze.newage.ui.theme.GlassMode
import com.kaze.newage.ui.theme.NewAgeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val prefs = (applicationContext as NewAgeApp).container.uiPrefs
            val modeId by prefs.themeMode
            val modeValue by prefs.themeModeValue
            val darkStyle by prefs.darkStyle
            val colorSource by prefs.md3ColorSource
            val customColor by prefs.md3CustomColor
            val paletteStyle by prefs.paletteStyle
            val glassModeId by prefs.glassMode

            // 主题模式（照搬 BiliPai AppThemeMode）：0=跟随系统 1=浅色 2=深色
            val darkTheme = when (modeValue) {
                1 -> false
                2 -> true
                else -> isSystemInDarkTheme()
            }
            NewAgeTheme(
                mode = AppThemeMode.fromId(modeId),
                darkTheme = darkTheme,
                amoledDark = darkStyle == 1,
                colorSource = colorSource,
                customColorHex = customColor,
                paletteStyle = paletteStyle,
                glassMode = GlassMode.fromId(glassModeId),
            ) {
                AppRoot()
            }
        }
    }
}
