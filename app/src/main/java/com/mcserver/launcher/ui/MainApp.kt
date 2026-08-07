package com.mcserver.launcher.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.mcserver.launcher.ui.screens.DownloadScreen
import com.mcserver.launcher.ui.screens.HomeScreen
import com.mcserver.launcher.ui.screens.SettingsScreen
import com.mcserver.launcher.ui.components.PageTransition
import com.mcserver.launcher.ui.theme.KazeCyan

private enum class Tab(val label: String, val icon: ImageVector) {
    HOME("首页", Icons.Filled.Home),
    DOWNLOADS("下载", Icons.Filled.Download),
    SETTINGS("设置", Icons.Filled.Settings)
}

/** 主界面:首页(仪表盘)/下载中心/设置 */
@androidx.compose.foundation.ExperimentalFoundationApi
@Composable
fun MainApp() {
    var tab by remember { mutableStateOf(Tab.HOME) }
    val downloadsBadge = com.mcserver.launcher.core.download.DownloadCenter.activeCount

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp
            ) {
                Tab.entries.forEach { t ->
                    val selected = tab == t
                    val iconTint by animateColorAsState(
                        targetValue = if (selected) KazeCyan else MaterialTheme.colorScheme.onSurfaceVariant,
                        animationSpec = tween(durationMillis = 300),
                        label = "navTint"
                    )
                    NavigationBarItem(
                        selected = selected,
                        onClick = { tab = t },
                        icon = {
                            if (t == Tab.DOWNLOADS && downloadsBadge > 0) {
                                BadgedBox(badge = { Badge { Text("$downloadsBadge") } }) {
                                    Icon(t.icon, null, tint = iconTint)
                                }
                            } else {
                                Icon(t.icon, null, tint = iconTint)
                            }
                        },
                        label = { Text(t.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = KazeCyan,
                            selectedTextColor = KazeCyan,
                            indicatorColor = KazeCyan.copy(alpha = 0.12f)
                        )
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
