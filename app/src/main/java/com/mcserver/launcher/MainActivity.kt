package com.mcserver.launcher

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.core.content.ContextCompat
import com.mcserver.launcher.data.SettingsStore
import com.mcserver.launcher.data.ThemeMode
import com.mcserver.launcher.ui.MainApp
import com.mcserver.launcher.ui.screens.EnvSetupScreen
import com.mcserver.launcher.ui.theme.KazeTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 让系统启动画面与窗口背景跟随应用内主题设置
        // (SYSTEM 模式不动,由 values-night 自动适配)
        when (SettingsStore.themeMode.value) {
            ThemeMode.DARK, ThemeMode.AMOLED ->
                setTheme(R.style.Theme_KazeSLauncher_Night)
            ThemeMode.LIGHT ->
                setTheme(R.style.Theme_KazeSLauncher)
        }
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()

        setContent {
            val themeMode by SettingsStore.themeMode.collectAsState()
            val systemDark = isSystemInDarkTheme()
            val darkAmoled by SettingsStore.darkAmoled.collectAsState()
            val setupCompleted by SettingsStore.setupCompleted.collectAsState()
            var splashDone by remember { mutableStateOf(false) }

            KazeTheme(mode = themeMode, systemDark = systemDark, darkAmoled = darkAmoled) {
                Box(Modifier.fillMaxSize()) {
                    // 主界面(闪屏结束后显示)
                    AnimatedVisibility(
                        visible = splashDone,
                        enter = fadeIn(),
                        modifier = Modifier.fillMaxSize()
                    ) {
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
                    // 启动闪屏:软件图标 + 名称,短暂展示后淡出
                    if (!splashDone) {
                        SplashScreen(
                            onFinished = { splashDone = true },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun SplashScreen(onFinished: () -> Unit, modifier: Modifier = Modifier) {
        var visible by remember { mutableStateOf(true) }
        val scale by animateFloatAsState(
            targetValue = if (visible) 1f else 1.15f,
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
            label = "splashScale"
        )
        LaunchedEffect(Unit) {
            delay(1100)
            visible = false
            delay(280)
            onFinished()
        }
        AnimatedVisibility(
            visible = visible,
            exit = fadeOut(animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy)),
            modifier = modifier
        ) {
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Image(
                        painter = painterResource(com.mcserver.launcher.R.drawable.ic_launcher_background),
                        contentDescription = null,
                        modifier = Modifier
                            .size(140.dp)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                            }
                    )
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
