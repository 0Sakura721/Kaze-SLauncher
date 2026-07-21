package com.mcserver.launcher

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
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
import com.mcserver.launcher.ui.components.ErrorBoundary
import com.mcserver.launcher.ui.navigation.Screen
import com.mcserver.launcher.ui.navigation.bottomNavItems
import com.mcserver.launcher.ui.screens.*
import com.mcserver.launcher.ui.theme.McServerTheme
import com.mcserver.launcher.ui.theme.ThemeMode
import com.mcserver.launcher.utils.PermissionHelper
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var notificationLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 绑定权限助手
        PermissionHelper.bind(this)
        notificationLauncher = PermissionHelper.createNotificationLauncher(this)

        // Android 13+ 启动时尝试请求通知权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            PermissionHelper.shouldRequestNotificationPermission(this)
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val prefsManager = PreferencesManager(this)

        setContent {
            val themeMode by prefsManager.themeMode.collectAsState(initial = ThemeMode.DARK)
            val config by prefsManager.serverConfig.collectAsState(initial = ServerConfig())
            val setupCompleted by prefsManager.setupCompleted.collectAsState(initial = false)

            McServerTheme(themeMode = themeMode) {
                ErrorBoundary {
                    if (!setupCompleted) {
                        val setupScope = rememberCoroutineScope()
                        EnvSetupScreen(
                            onSetupComplete = {
                                setupScope.launch {
                                    prefsManager.setSetupCompleted()
                                }
                            }
                        )
                    } else {
                        MainApp(
                            themeMode = themeMode,
                            config = config,
                            prefsManager = prefsManager,
                            onRequestNotificationPermission = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        PermissionHelper.refreshNotificationStatus(this)
    }
}

@Composable
fun MainApp(
    themeMode: ThemeMode,
    config: ServerConfig,
    prefsManager: PreferencesManager,
    onRequestNotificationPermission: () -> Unit = {}
) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    // 判断是否在管理子页面（显示返回按钮而非底部导航）
    val isSubScreen = currentDestination?.route in listOf(
        Screen.ServerConfig.route, Screen.Plugins.route,
        Screen.Players.route, Screen.Files.route,
        Screen.Backups.route, Screen.CoreDownload.route,
        Screen.ResourcePacks.route, Screen.Schedules.route,
        Screen.Worlds.route, Screen.Diagnostics.route,
        Screen.Modrinth.route, Screen.CrashReports.route,
        Screen.Appearance.route, Screen.Terminal.route,
        Screen.ServerList.route
    )

    Scaffold(
        bottomBar = {
            if (!isSubScreen) {
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
                            launchSingleTop = true
                        }
                    },
                    onNavigateToConsole = {
                        navController.navigate(Screen.Console.route) {
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onNavigateToManagement = {
                        navController.navigate(Screen.Management.route) {
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onNavigateToServerList = {
                        navController.navigate(Screen.ServerList.route) {
                            launchSingleTop = true
                        }
                    },
                    onNavigateToTerminal = {
                        navController.navigate(Screen.Terminal.route) {
                            launchSingleTop = true
                        }
                    }
                )
            }
            composable(Screen.Console.route) {
                ConsoleScreen()
            }
            composable(Screen.Management.route) {
                ManagementScreen(
                    config = config,
                    onNavigate = { screen ->
                        navController.navigate(screen.route) {
                            launchSingleTop = true
                        }
                    }
                )
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
            composable(Screen.CoreDownload.route) {
                ServerCoreDownloadScreen(
                    config = config,
                    onJarDownloaded = { path ->
                        scope.launch {
                            prefsManager.saveServerConfig(config.copy(jarPath = path))
                        }
                    }
                )
            }
            composable(Screen.Modrinth.route) {
                ModrinthScreen()
            }
            composable(Screen.Plugins.route) {
                PluginsScreen()
            }
            composable(Screen.Players.route) {
                PlayersScreen()
            }
            composable(Screen.Files.route) {
                FilesScreen()
            }
            composable(Screen.Backups.route) {
                BackupsScreen(
                    config = config,
                    onConfigSave = { newConfig ->
                        scope.launch {
                            prefsManager.saveServerConfig(newConfig)
                        }
                    }
                )
            }
            composable(Screen.ResourcePacks.route) {
                ResourcePacksScreen()
            }
            composable(Screen.Schedules.route) {
                SchedulesScreen()
            }
            composable(Screen.Worlds.route) {
                WorldsScreen()
            }
            composable(Screen.Diagnostics.route) {
                DiagnosticsScreen(
                    config = config,
                    onNavigateToCrashReports = {
                        navController.navigate(Screen.CrashReports.route) {
                            launchSingleTop = true
                        }
                    }
                )
            }
            composable(Screen.CrashReports.route) {
                CrashReportsScreen()
            }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    currentTheme = themeMode,
                    onThemeChange = { mode ->
                        scope.launch {
                            prefsManager.setTheme(mode)
                        }
                    },
                    onNavigateToAppearance = {
                        navController.navigate(Screen.Appearance.route) {
                            launchSingleTop = true
                        }
                    },
                    prefsManager = prefsManager
                )
            }
            composable(Screen.Appearance.route) {
                AppearanceScreen(
                    prefsManager = prefsManager,
                    currentTheme = themeMode,
                    onThemeChange = { mode ->
                        scope.launch {
                            prefsManager.setTheme(mode)
                        }
                    }
                )
            }
            composable(Screen.Terminal.route) {
                TerminalScreen()
            }
            composable(Screen.ServerList.route) {
                ServerListScreen(
                    onBack = { navController.popBackStack() },
                    onServerSelected = {
                        navController.popBackStack()
                    }
                )
            }
        }
    }
}
