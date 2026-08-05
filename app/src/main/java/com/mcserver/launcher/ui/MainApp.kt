package com.mcserver.launcher.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.mcserver.launcher.ui.screens.DownloadScreen
import com.mcserver.launcher.ui.screens.HomeScreen
import com.mcserver.launcher.ui.screens.SettingsScreen
import com.mcserver.launcher.ui.components.PageTransition

private enum class Tab(val label: String, val icon: ImageVector) {
    HOME("首页", Icons.Filled.Home),
    DOWNLOADS("下载", Icons.Filled.Download),
    SETTINGS("设置", Icons.Filled.Settings)
}

/** 主界面:首页(实例)/下载中心/设置 */
@Composable
fun MainApp() {
    var tab by remember { mutableStateOf(Tab.HOME) }
    val downloadsBadge = com.mcserver.launcher.core.download.DownloadCenter.activeCount

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = {
                            if (t == Tab.DOWNLOADS && downloadsBadge > 0) {
                                BadgedBox(badge = { Badge { Text("$downloadsBadge") } }) {
                                    Icon(t.icon, null)
                                }
                            } else {
                                Icon(t.icon, null)
                            }
                        },
                        label = { Text(t.label) }
                    )
                }
            }
        }
    ) { padding ->
        PageTransition(tab, Modifier.padding(padding)) { t ->
            when (t) {
                Tab.HOME -> HomeScreen()
                Tab.DOWNLOADS -> DownloadScreen()
                Tab.SETTINGS -> SettingsScreen()
            }
        }
    }
}
