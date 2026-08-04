package com.mcserver.launcher

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.core.content.ContextCompat
import com.mcserver.launcher.data.SettingsStore
import com.mcserver.launcher.ui.MainApp
import com.mcserver.launcher.ui.screens.EnvSetupScreen
import com.mcserver.launcher.ui.theme.KazeTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()

        setContent {
            val dark by SettingsStore.themeDark.collectAsState()
            val setupCompleted by SettingsStore.setupCompleted.collectAsState()
            KazeTheme(dark = dark) {
                if (!setupCompleted) {
                    val scope = rememberCoroutineScope()
                    EnvSetupScreen(
                        onSetupComplete = {
                            scope.launch { SettingsStore.setSetupCompleted() }
                        }
                    )
                } else {
                    MainApp()
                }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
