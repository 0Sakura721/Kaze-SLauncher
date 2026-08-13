package com.mcserver.launcher

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import com.mcserver.launcher.data.SettingsStore
import com.mcserver.launcher.ui.AppRoot
import com.mcserver.launcher.ui.AppViewModel
import com.mcserver.launcher.ui.theme.KazeTheme
import com.mcserver.launcher.ui.theme.StyleKeys

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 通知权限（前台服务必需，Android 13+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }

        setContent {
            val styleKey by SettingsStore.themeStyle.collectAsState(initial = StyleKeys.LIQUID)
            val themeMode by SettingsStore.themeMode.collectAsState(initial = 0)
            val customColor by SettingsStore.customColor.collectAsState(initial = 0)
            KazeTheme(styleKey = styleKey, themeMode = themeMode, customSeed = customColor) {
                AppRoot(vm)
            }
        }
    }
}