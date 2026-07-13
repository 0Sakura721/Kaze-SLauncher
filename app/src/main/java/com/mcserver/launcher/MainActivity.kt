package com.mcserver.launcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.mcserver.launcher.data.PreferencesManager
import com.mcserver.launcher.data.ServerConfig
import com.mcserver.launcher.ui.navigation.Screen
import com.mcserver.launcher.ui.navigation.bottomNavItems
import com.mcserver.launcher.ui.screens.*
import com.mcserver.launcher.ui.theme.McServerTheme
import com.mcserver.launcher.ui.theme.ThemeMode
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val prefsManager = PreferencesManager(this)

        setContent {
            val themeMode by prefsManager.themeMode.collectAsState(initial = ThemeMode.DARK)
            val config by prefsManager.serverConfig.collectAsState(initial = ServerConfig())

            McServerTheme(themeMode = themeMode) {
                MainApp(
                    themeMode = themeMode,
                    config = config,
                    prefsManager = prefsManager
                )
            }
        }
    }
}

@Composable
fun MainApp(
    themeMode: ThemeMode,
    config: ServerConfig,
    prefsManager: PreferencesManager
) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    Scaffold(
        bottomBar = {
            NavigationBar {
                bottomNavItems.forEach { screen ->
                    val selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true
                    NavigationBarItem(
                        icon = {
                            Icon(
                                imageVector = screen.icon,
                                contentDescription = screen.label
                            )
                        },
                        label = { Text(screen.label) },
                        selected = selected,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Home.route) {
                HomeScreen(
                    config = config,
                    onNavigateToConfig = {
                        navController.navigate(Screen.ServerConfig.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onNavigateToConsole = {
                        navController.navigate(Screen.Console.route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
            composable(Screen.Console.route) {
                ConsoleScreen()
            }
            composable(Screen.ServerConfig.route) {
                ServerConfigScreen(
                    config = config,
                    onConfigSave = { newConfig ->
                        scope.launch {
                            prefsManager.saveServerConfig(newConfig)
                        }
                    }
                )
            }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    currentTheme = themeMode,
                    onThemeChange = { mode ->
                        scope.launch {
                            prefsManager.setTheme(mode)
                        }
                    }
                )
            }
        }
    }
}
