package com.kaze.newage

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.kaze.newage.ui.AppRoot
import com.kaze.newage.ui.theme.AppThemeMode
import com.kaze.newage.ui.theme.FgColorMode
import com.kaze.newage.ui.theme.GlassMode
import com.kaze.newage.ui.theme.NewAgeTheme

class MainActivity : ComponentActivity() {

    // Android 13+：前台服务通知需要运行时授权（拒绝仅隐藏通知，服务照常运行）
    private val notifPermLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        enableEdgeToEdge()
        setContent {
            val prefs = (applicationContext as NewAgeApp).container.uiPrefs
            val modeId by prefs.themeMode
            val modeValue by prefs.themeModeValue
            val darkStyle by prefs.darkStyle
            val colorSource by prefs.md3ColorSource
            val customColor by prefs.md3CustomColor
            val glassModeId by prefs.glassMode
            val glassIntensity by prefs.glassIntensity
            val fgColorModeId by prefs.fgColorMode

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
                glassMode = GlassMode.fromId(glassModeId),
                glassIntensity = glassIntensity,
                fgColorMode = FgColorMode.fromId(fgColorModeId),
            ) {
                // 系统栏图标明暗必须跟随**应用内**的主题选择，而不是系统主题。
                // enableEdgeToEdge() 的默认行为是按 isSystemInDarkTheme() 决定的：
                // 系统深色 + 应用手动切浅色时，状态栏图标是白色，画在浅色背景上完全看不见
                // （真机实测：顶部时钟/电量整条消失）。
                val view = LocalView.current
                SideEffect {
                    val window = (view.context as android.app.Activity).window
                    WindowCompat.getInsetsController(window, view).apply {
                        isAppearanceLightStatusBars = !darkTheme
                        isAppearanceLightNavigationBars = !darkTheme
                    }
                }
                AppRoot()
            }
        }
    }
}
