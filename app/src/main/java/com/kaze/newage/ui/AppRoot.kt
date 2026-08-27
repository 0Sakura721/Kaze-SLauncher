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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.kaze.newage.core.update.UpdateChecker
import com.kaze.newage.core.update.UpdateInstaller
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
import kotlinx.coroutines.launch
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

    // ── 启动自动检查更新（默认开；通道默认预览版，设置页可改）──
    val appContext = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var updateInfo by remember { mutableStateOf<UpdateChecker.ReleaseInfo?>(null) }
    var updateProgress by remember { mutableStateOf<String?>(null) }
    var updateBusy by remember { mutableStateOf(false) }
    var updateChecked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (uiPrefs.autoUpdate.value && !updateChecked) {
            updateChecked = true
            val channel = uiPrefs.updateChannel.value
            val current = runCatching {
                appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: ""
            }.getOrDefault("")
            try {
                val info = UpdateChecker.check(channel)
                if (info != null && UpdateChecker.isNewer(info.tag, current)) {
                    updateInfo = info
                }
            } catch (_: Exception) { /* 静默：启动检查失败不影响使用 */ }
        }
    }
    var updateCancelRequested by remember { mutableStateOf(false) }
    fun startUpdateDownload(info: UpdateChecker.ReleaseInfo) {
        if (updateBusy) return
        updateBusy = true
        updateCancelRequested = false
        updateProgress = "准备下载…"
        scope.launch {
            val file = UpdateInstaller.download(
                context = appContext,
                info = info,
                onProgress = { done, total, _ ->
                    updateProgress = if (total > 0) "下载中 $done MB / $total MB" else "下载中 $done MB…"
                },
                shouldCancel = { updateCancelRequested },
            )
            updateBusy = false
            if (file != null) {
                updateInfo = null
                UpdateInstaller.install(appContext, file)
            } else if (updateCancelRequested) {
                // 用户主动取消：静默收起弹窗（下载已在 Downloader 内中止，断点保留）
                updateInfo = null
                updateProgress = null
            } else {
                updateProgress = "下载失败，请稍后在设置中重试"
            }
        }
    }

    AppBackground(prefs = uiPrefs) {
        // 覆盖式布局（不用 Scaffold 的 bottomBar 预留位）：
        // 内容全屏滚动，可以"穿过"常驻底栏——滚动中的文字/卡片经 hazeSource 进入模糊源，
        // 被底栏的液态玻璃实时映射（模糊的字）
        Box(Modifier.fillMaxSize()) {
            // 内容层录制进 GraphicsLayer：软件模糊从该层栅格化+降采样放大
            //（vivo Android 16：RenderEffect blur 不渲染——Haze、自研 renderEffect、
            //   Miuix 高斯 shader 均无效；软件降采样是真机唯一已验证生效的路径）
            val contentLayer = rememberGraphicsLayer()
            Box(
                Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        contentLayer.record {
                            this@drawWithContent.drawContent()
                        }
                        drawLayer(contentLayer)
                    }
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
                        contentLayer = contentLayer,
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

        // ── 启动自动检查：发现新版本弹窗 ──
        updateInfo?.let { info ->
            AlertDialog(
                // 下载中不锁死弹窗：点击外部 = 请求取消（下载会中止，断点保留）
                onDismissRequest = {
                    if (updateBusy) updateCancelRequested = true else updateInfo = null
                },
                title = { Text("发现新版本 ${info.tag}") },
                text = {
                    Column {
                        if (info.body.isNotBlank()) {
                            Text(
                                info.body.take(400),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        updateProgress?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                        if (updateBusy) {
                            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = { startUpdateDownload(info) },
                        enabled = !updateBusy,
                    ) {
                        Text(if (updateBusy) "下载中…" else "下载并安装")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            if (updateBusy) updateCancelRequested = true else updateInfo = null
                        },
                    ) {
                        Text(if (updateBusy) "取消下载" else "以后再说")
                    }
                },
            )
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
    contentLayer: androidx.compose.ui.graphics.layer.GraphicsLayer,
    onNavigate: (Dest) -> Unit,
) {
    val isGlass = LocalAppTheme.current == AppThemeMode.GLASS
    val blurEnabled = LocalGlassBlurEnabled.current
    val density = LocalDensity.current
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val pillShape = CircleShape
    // 玻璃链只属于液态玻璃主题；M3 主题底栏保持原样式（不透明表面板，不参与模糊映射）
    val glassActive = isGlass && blurEnabled
    // 底栏背景：玻璃主题=极淡磨砂（自研 RenderEffect 模糊成型）；M3 主题=50% 透光表面
    // （普通黑/AMOLED 纯黑两种深色样式统一起效：滚动内容可从底栏透出）
    val containerColor = if (isGlass) {
        // 0.12 极淡表面：折射与模糊主导"液态"观感，磨砂太厚会盖住折射弯曲
        MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.12f)
    } else {
        MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.5f)
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
            // 层①：玻璃背景——软件模糊（vivo 唯一生效路径）+ Kyant0 折射透镜在外
            Box(
                Modifier
                    .fillMaxSize()
                    .then(
                        if (glassActive) {
                            val glassP = com.kaze.newage.ui.theme.glassParams(LocalGlassMode.current)
                            val intensity = LocalGlassIntensity.current
                            Modifier
                                .glassBackdropBlur(
                                    contentLayer = contentLayer,
                                    // 更精细的源（0.4），真高斯交给 StackBlur 半径
                                    sampleScale = 0.4f,
                                    // 玻璃强度 → 高斯半径（越大越糊）
                                    blurRadiusPx = (2f + 6f * (intensity - 0.5f)).coerceIn(1f, 9f),
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
