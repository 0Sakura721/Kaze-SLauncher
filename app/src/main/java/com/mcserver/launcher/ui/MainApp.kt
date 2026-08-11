package com.mcserver.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.mcserver.launcher.core.download.DownloadCenter
import com.mcserver.launcher.ui.components.LocalUiMessenger
import com.mcserver.launcher.ui.components.PageTransition
import com.mcserver.launcher.ui.components.rememberUiMessenger
import com.mcserver.launcher.ui.screens.DownloadScreen
import com.mcserver.launcher.ui.screens.HomeScreen
import com.mcserver.launcher.ui.screens.SettingsScreen
import com.mcserver.launcher.ui.theme.AuroraBackground
import com.mcserver.launcher.ui.theme.GlassSurface

enum class MainTab(val label: String, val icon: ImageVector) {
    HOME("首页", Icons.Filled.Home),
    DOWNLOADS("下载", Icons.Filled.Download),
    SETTINGS("设置", Icons.Filled.Settings)
}

/** 主界面:首页(仪表盘)/下载中心/设置。极光背景 + 玻璃悬浮导航栏。 */
@androidx.compose.foundation.ExperimentalFoundationApi
@Composable
fun MainApp() {
    val messenger = rememberUiMessenger()
    var tab by remember { mutableStateOf(MainTab.HOME) }
    val downloadsBadge = DownloadCenter.activeCount

    Box(Modifier.fillMaxSize()) {
        AuroraBackground(Modifier.fillMaxSize())
        CompositionLocalProvider(LocalUiMessenger provides messenger) {
            Scaffold(
                containerColor = Color.Transparent,
                bottomBar = {
                    GlassNavBar(
                        current = tab,
                        onSelect = { tab = it },
                        badge = downloadsBadge
                    )
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
}

/** 玻璃悬浮药丸导航栏 */
@Composable
private fun GlassNavBar(
    current: MainTab,
    onSelect: (MainTab) -> Unit,
    badge: Int
) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp)) {
        GlassSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                MainTab.entries.forEach { t ->
                    Box(Modifier.weight(1f)) {
                        GlassNavItem(
                            tab = t,
                            selected = current == t,
                            onClick = { onSelect(t) },
                            badge = badge
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GlassNavItem(
    tab: MainTab,
    selected: Boolean,
    onClick: () -> Unit,
    badge: Int
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .background(if (selected) scheme.primary.copy(alpha = 0.18f) else Color.Transparent)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (tab == MainTab.DOWNLOADS && badge > 0) {
                BadgedBox(badge = {
                    Badge { Text(if (badge > 99) "99+" else "$badge") }
                }) {
                    Icon(
                        tab.icon,
                        contentDescription = tab.label,
                        tint = if (selected) scheme.primary else scheme.onSurfaceVariant
                    )
                }
            } else {
                Icon(
                    tab.icon,
                    contentDescription = tab.label,
                    tint = if (selected) scheme.primary else scheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(
                tab.label,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) scheme.primary else scheme.onSurfaceVariant
            )
        }
    }
}
