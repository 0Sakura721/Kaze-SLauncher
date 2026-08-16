package com.kaze.newage.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.kaze.newage.ui.components.AppBackground
import com.kaze.newage.ui.screens.AddonsScreen
import com.kaze.newage.ui.screens.ConsoleScreen
import com.kaze.newage.ui.screens.HomeScreen
import com.kaze.newage.ui.screens.InstanceDetailScreen
import com.kaze.newage.ui.screens.LogsScreen
import com.kaze.newage.ui.screens.NewServerScreen
import com.kaze.newage.ui.screens.ServerScreen
import com.kaze.newage.ui.screens.SettingsScreen
import com.kaze.newage.ui.theme.AppThemeMode
import com.kaze.newage.ui.theme.LocalAppTheme
import com.kaze.newage.ui.theme.LocalGlassBlurEnabled
import com.kaze.newage.ui.theme.LocalGlassIntensity
import com.kaze.newage.ui.theme.LocalGlassMode
import com.kaze.newage.ui.theme.LocalHazeState
import com.kaze.newage.ui.theme.glassHazeStyle
import com.kaze.newage.ui.theme.glassSaturation
import com.kaze.newage.ui.theme.liquidGlassLensSafe
import dev.chrisbanes.haze.hazeEffect

enum class Dest(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val iconSelected: ImageVector,
) {
    Home("home", "主页", Icons.Outlined.Home, Icons.Filled.Home),
    Server("server", "服务端", Icons.Outlined.Dns, Icons.Filled.Dns),
    Console("console", "控制台", Icons.Outlined.Terminal, Icons.Filled.Terminal),
    Settings("settings", "设置", Icons.Outlined.Settings, Icons.Filled.Settings),
}

@Composable
fun AppRoot(viewModel: AppViewModel = viewModel()) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val uiPrefs = viewModel.uiPrefs
    val showBottomBar = Dest.entries.any { it.route == currentRoute }

    AppBackground(prefs = uiPrefs) {
        Scaffold(
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            bottomBar = {
                if (showBottomBar) {
                    LiquidGlassNavBar(
                        currentRoute = currentRoute,
                        onNavigate = { dest ->
                            navController.navigate(dest.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
                }
            },
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = Dest.Home.route,
                // 底部额外留白：内容不被常驻底栏遮挡（底栏已透明，靠留白保证可读）
                modifier = Modifier.padding(padding).padding(bottom = 20.dp),
            ) {
                composable(Dest.Home.route) {
                    HomeScreen(viewModel, onNavigate = { navController.navigate(it) })
                }
                composable(Dest.Server.route) {
                    ServerScreen(
                        viewModel,
                        onOpenInstance = { inst -> navController.navigate("instance/${inst.id}") },
                        onNewServer = { navController.navigate("server/new") },
                    )
                }
                composable("server/new") {
                    NewServerScreen(
                        viewModel = viewModel,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(Dest.Console.route) { ConsoleScreen(viewModel) }
                composable(Dest.Settings.route) { SettingsScreen(viewModel) }
                composable("instance/{instanceId}") { entry ->
                    val id = entry.arguments?.getString("instanceId") ?: ""
                    InstanceDetailScreen(
                        viewModel = viewModel,
                        instanceId = id,
                        onBack = { navController.popBackStack() },
                        onOpenAddons = { kind ->
                            navController.navigate("instance/$id/addons/${kind.name.lowercase()}")
                        },
                        onOpenLogs = { navController.navigate("instance/$id/logs") },
                    )
                }
                composable("instance/{instanceId}/logs") { entry ->
                    val id = entry.arguments?.getString("instanceId") ?: ""
                    LogsScreen(
                        viewModel = viewModel,
                        instanceId = id,
                        onBack = { navController.popBackStack() },
                    )
                }
                composable("instance/{instanceId}/addons/{kind}") { entry ->
                    val id = entry.arguments?.getString("instanceId") ?: ""
                    val kindName = entry.arguments?.getString("kind") ?: ""
                    AddonsScreen(
                        viewModel = viewModel,
                        instanceId = id,
                        kind = if (kindName == "mod") com.kaze.newage.core.addons.AddonKind.MOD
                        else com.kaze.newage.core.addons.AddonKind.PLUGIN,
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }
    }
}

/**
 * 液态玻璃浮动胶囊底栏（照搬「现在的 BiliPai」= Miuix IosLiquidGlassNavigationBar 版式，
 * Miuix/AndroidLiquidGlass Apache-2.0，已记 NOTICES）：
 *  - 24dp 边距悬浮圆胶囊（64dp 高）+ 10dp 投影
 *  - 玻璃链：Haze 背景模糊 + vibrancy 饱和度 1.5 + Kyant0 圆角矩形折射透镜（24dp/24dp）
 *  - 容器：surfaceContainer alpha 0.4（模糊启用时）
 *  - 选中项：主色图标 + 标签
 */
@Composable
private fun LiquidGlassNavBar(
    currentRoute: String?,
    onNavigate: (Dest) -> Unit,
) {
    val isGlass = LocalAppTheme.current == AppThemeMode.GLASS
    val hazeState = LocalHazeState.current
    val blurEnabled = LocalGlassBlurEnabled.current
    val density = LocalDensity.current
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val pillShape = CircleShape
    val glassActive = isGlass && blurEnabled && hazeState != null
    // 底栏背景：完全透明（无白板）。玻璃激活时由「模糊+饱和度+透镜」构成玻璃本体，
    // 不需要半透明色板；非玻璃时图标直接悬浮在页面上，遮挡交给各页底部空白
    val containerColor = androidx.compose.ui.graphics.Color.Transparent

    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp)
            .padding(bottom = 8.dp + navInset),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .shadow(10.dp, pillShape, clip = false)
                .clip(pillShape)
        ) {
            // 层①：玻璃背景（BiliPai drawBackdrop 等效）——模糊+饱和度+透镜只作用于背景
            Box(
                Modifier
                    .fillMaxSize()
                    .then(
                        if (glassActive) {
                            val glassP = com.kaze.newage.ui.theme.glassParams(LocalGlassMode.current)
                            Modifier
                                .hazeEffect(state = hazeState!!, style = glassHazeStyle())
                                .glassSaturation()
                                .liquidGlassLensSafe(
                                    refractionHeight = with(density) { 24.dp.toPx() },
                                    refractionAmount = with(density) { 24.dp.toPx() } *
                                        LocalGlassIntensity.current * glassP.refractionScale,
                                )
                        } else Modifier
                    )
            )
            // 层②：表面色（BiliPai onDrawSurface 等效：container alpha 0.4）
            Box(Modifier.fillMaxSize().background(containerColor))
            // 层③：图标与标签（BiliPai tabsContent——始终在最上，不被折射）
            Row(
                Modifier.fillMaxSize().padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Dest.entries.forEach { dest ->
                    val selected = currentRoute == dest.route
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(20.dp))
                            .clickable { onNavigate(dest) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
                    ) {
                        Icon(
                            if (selected) dest.iconSelected else dest.icon,
                            contentDescription = dest.label,
                            modifier = Modifier.size(22.dp),
                            tint = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            dest.label,
                            fontSize = 11.sp,
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
