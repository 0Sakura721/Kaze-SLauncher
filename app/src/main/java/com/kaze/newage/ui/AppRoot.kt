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
import androidx.compose.foundation.layout.statusBars
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
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
import com.kaze.newage.ui.components.BackdropLayer
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
import com.kaze.newage.ui.theme.glassBackdropBlur
import com.kaze.newage.ui.theme.liquidGlassLensSafe
import com.kaze.newage.ui.theme.blur.Backdrop
import com.kaze.newage.ui.theme.blur.blur
import com.kaze.newage.ui.theme.blur.drawBackdrop
import com.kaze.newage.ui.theme.blur.layerBackdrop
import com.kaze.newage.ui.theme.blur.rememberLayerBackdrop

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
        // 覆盖式布局（不用 Scaffold 的 bottomBar 预留位）：
        // 内容全屏滚动，可以"穿过"常驻底栏——滚动中的文字/卡片经 hazeSource 进入模糊源，
        // 被底栏的液态玻璃实时映射（模糊的字）
        Box(Modifier.fillMaxSize()) {
            // 内容层：Miuix miuix-blur 方案（Apache-2.0，已记 NOTICES）——
            // LayerBackdrop 录制内容，drawBackdrop 以 RuntimeShader 高斯模糊重绘
            //（vivo 上 RenderEffect.createBlurEffect 不渲染，此前 Haze/自研均无效；
            //   高斯 shader 与透镜同为 RuntimeShader，实测可用）
            val backdrop = rememberLayerBackdrop()
            Box(
                Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop)
            ) {
                BackdropLayer(prefs = uiPrefs, modifier = Modifier.fillMaxSize())
                NavHost(
                    navController = navController,
                    startDestination = Dest.Home.route,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()),
                    // 无底部留白：内容（含各页自带底部空白）可滚过常驻栏，滚动中充分透出
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
            // 常驻底栏：覆盖在内容之上（内容可滚动穿过，被液态玻璃模糊映射）
            if (showBottomBar) {
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                    LiquidGlassNavBar(
                        currentRoute = currentRoute,
                        backdrop = backdrop,
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
    backdrop: Backdrop,
    onNavigate: (Dest) -> Unit,
) {
    val isGlass = LocalAppTheme.current == AppThemeMode.GLASS
    val blurEnabled = LocalGlassBlurEnabled.current
    val density = LocalDensity.current
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val pillShape = CircleShape
    // 玻璃链只属于液态玻璃主题；M3 主题底栏保持原样式（不透明表面板，不参与模糊映射）
    val glassActive = isGlass && blurEnabled
    // 底栏背景：玻璃主题=极淡磨砂（自研 RenderEffect 模糊成型）；M3 主题=原样式不透明表面板
    val containerColor = if (isGlass) {
        // 0.12 极淡表面：折射与模糊主导"液态"观感，磨砂太厚会盖住折射弯曲
        MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.12f)
    } else {
        MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.95f)
    }

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
            // 层①：玻璃背景——Miuix miuix-blur：drawBackdrop 以 RuntimeShader 高斯模糊
            // 重绘内容层（自带坐标对齐/降采样），外再套 Kyant0 折射透镜
            Box(
                Modifier
                    .fillMaxSize()
                    .then(
                        if (glassActive) {
                            val glassP = com.kaze.newage.ui.theme.glassParams(LocalGlassMode.current)
                            val intensity = LocalGlassIntensity.current
                            val blurPx = with(density) { (glassP.blurRadius * intensity).toPx() }
                            Modifier
                                .drawBackdrop(
                                    backdrop = backdrop,
                                    shape = { pillShape },
                                    effects = {
                                        blur(blurPx)
                                    },
                                )
                                .liquidGlassLensSafe(
                                    refractionHeight = with(density) { 24.dp.toPx() },
                                    refractionAmount = with(density) { 24.dp.toPx() } *
                                        intensity * glassP.refractionScale,
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
