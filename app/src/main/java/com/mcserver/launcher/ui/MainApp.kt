package com.mcserver.launcher.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import com.mcserver.launcher.core.download.DownloadCenter
import com.mcserver.launcher.ui.components.LocalUiMessenger
import com.mcserver.launcher.ui.components.PageTransition
import com.mcserver.launcher.ui.components.rememberUiMessenger
import com.mcserver.launcher.ui.screens.DownloadScreen
import com.mcserver.launcher.ui.screens.HomeScreen
import com.mcserver.launcher.ui.screens.SettingsScreen

enum class MainTab(val label: String, val icon: ImageVector) {
    HOME("首页", Icons.Filled.Home),
    DOWNLOADS("下载", Icons.Filled.Download),
    SETTINGS("设置", Icons.Filled.Settings)
}

/** 主界面:首页(仪表盘)/下载中心/设置 */
@androidx.compose.foundation.ExperimentalFoundationApi
@Composable
fun MainApp() {
    val messenger = rememberUiMessenger()
    var tab by remember { mutableStateOf(MainTab.HOME) }
    val downloadsBadge = DownloadCenter.activeCount

    CompositionLocalProvider(LocalUiMessenger provides messenger) {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    MainTab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = {
                                if (t == MainTab.DOWNLOADS && downloadsBadge > 0) {
                                    BadgedBox(badge = {
                                        Badge { Text(if (downloadsBadge > 99) "99+" else "$downloadsBadge") }
                                    }) {
                                        Icon(t.icon, contentDescription = t.label)
                                    }
                                } else {
                                    Icon(t.icon, contentDescription = t.label)
                                }
                            },
                            label = { Text(t.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    }
                }
            }
        ) { innerPadding ->
            Box(Modifier.fillMaxSize().padding(innerPadding)) {
                PageTransition(tab, Modifier.fillMaxSize()) { t ->
                    when (t) {
                        MainTab.HOME -> HomeScreen(onGotoSettings = { tab = MainTab.SETTINGS })
                        MainTab.DOWNLOADS -> DownloadScreen()
                        MainTab.SETTINGS -> SettingsScreen()
                    }
                }
            }
        }
    }
}
