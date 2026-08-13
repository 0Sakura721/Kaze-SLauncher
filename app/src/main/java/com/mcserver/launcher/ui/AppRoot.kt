package com.mcserver.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.mcserver.launcher.core.engine.ServerEngine
import com.mcserver.launcher.ui.design.GlassCard
import com.mcserver.launcher.ui.design.GlassTray
import com.mcserver.launcher.ui.design.RunButton
import com.mcserver.launcher.ui.screens.ConsoleScreen
import com.mcserver.launcher.ui.screens.ContentScreen
import com.mcserver.launcher.ui.screens.DownloadScreen
import com.mcserver.launcher.ui.screens.HomeScreen
import com.mcserver.launcher.ui.screens.InstanceScreen
import com.mcserver.launcher.ui.screens.SettingsScreen
import com.mcserver.launcher.ui.theme.GlassBackground
import com.mcserver.launcher.ui.theme.LocalKazeTokens

@Composable
fun AppRoot(vm: AppViewModel) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
val route = backStack?.destination?.route ?: "home"
        val mainRoutes = listOf("home", "console", "content", "download", "settings")
    val tokens = LocalKazeTokens.current
    val engineState by ServerEngine.state.collectAsState()

    // Toast
    val toast by vm.toast
    var showToast by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(toast) {
        if (toast != null) {
            showToast = toast
            kotlinx.coroutines.delay(2200)
            showToast = null
            vm.clearToast()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(tokens.background)
    ) {
        GlassBackground(tokens)

        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                if (route in mainRoutes) {
                    Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        GlassTray(
                            items = listOf(
                                "home" to Icons.Filled.Home,
                                "console" to Icons.Filled.Terminal,
                                "content" to Icons.Filled.Menu,
                                "download" to Icons.Filled.Download,
                                "settings" to Icons.Filled.Settings,
                            ),
                            selected = route,
                            onSelect = { key ->
                                if (key != route) {
                                    navController.navigate(key) {
                                        popUpTo("home") { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            centerAction = {
                                RunButton(state = engineState, onClick = vm::toggleRun, size = 54.dp)
                            },
                        )
                    }
                }
            },
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = "home",
                modifier = Modifier.padding(bottom = padding.calculateBottomPadding()),
            ) {
                composable("home") { HomeScreen(vm, onOpenInstances = { navController.navigate("instance") }, onOpenSettings = { navController.navigate("settings") }) }
                composable("console") { ConsoleScreen(vm) }
                composable("content") { ContentScreen(vm) }
                composable("download") { DownloadScreen(vm, onOpenInstance = { navController.navigate("instance") }) }
                composable("settings") { SettingsScreen(vm) }
                composable("instance") { InstanceScreen(vm, onBack = { navController.popBackStack() }) }
            }
        }

        // 浮动 Toast
        if (showToast != null) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 56.dp)
            ) {
                GlassCard {
                    Text(
                        showToast.orEmpty(),
                        color = tokens.onSurface,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                    )
                }
            }
        }
    }
}