package com.kaze.newage.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
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
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.kaze.newage.ui.screens.AiScreen
import com.kaze.newage.ui.screens.AiSettingsScreen
import com.kaze.newage.ui.screens.ConsoleScreen
import com.kaze.newage.ui.screens.DiagnosticsScreen
import com.kaze.newage.ui.screens.HomeScreen
import com.kaze.newage.ui.screens.InstanceDetailScreen
import com.kaze.newage.ui.screens.LogsScreen
import com.kaze.newage.ui.screens.NewServerScreen
import com.kaze.newage.ui.screens.ServerScreen
import com.kaze.newage.ui.screens.SettingsScreen
import kotlinx.coroutines.launch
import com.kaze.newage.ui.theme.AppThemeMode
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn

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

    /**
     * 所有"切换主页面"都走这一条。
     *
     * 底栏一直用的是「popUpTo(起始页){saveState} + launchSingleTop + restoreState」，
     * 但首页那个控制台快捷入口原本是**裸 `navigate(route)`** —— 它推进去的条目和起始页
     * 之间没有 saveState 关系，之后底栏再切回主页就不生效了（实测：首页点控制台图标后
     * 切不回主页，语义树里内容仍是控制台）。混用两种跳转是根因，所以统一到这里。
     */
    val navigateTab: (String) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    // ── 启动自动检查更新（默认开；通道默认预览版，设置页可改）──
    // 状态全部交给 ViewModel：更新包几十 MB、下载要几分钟，挂在组合上的话
    // 离开设置页或转屏就会静默中止（见 AppUpdateState 的说明）。
    val appContext = LocalContext.current.applicationContext
    val updateState by viewModel.appUpdate.collectAsStateWithLifecycle()
    val updateInfo = (updateState as? AppUpdateState.Found)?.info
    val updateDownloading = (updateState as? AppUpdateState.Downloading)
    var updateChecked by rememberSaveable { mutableStateOf(false) }
    // 用户点过「以后再说」：本次启动不再弹。否则清状态时若还留着 Error，
    // 下一次任意重组都会把已经关掉的弹窗重新弹出来（过期的失败提示）
    var updateDialogDismissed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!updateChecked) {
            updateChecked = true
            viewModel.checkUpdateOnStart(uiPrefs.autoUpdate.value)
        }
    }

    AppBackground(prefs = uiPrefs) {
        // ── 首帧守卫：window insets 是异步下发的 ──
        // 首帧组合时 WindowInsets.statusBars 仍为 0，内容会先按"没有状态栏"布局
        // （标题与状态栏图标重叠），等 insets 到达后整屏下移一个状态栏高度。
        // 真机录屏实测：720p 下位移 76px，持续约 0.45s —— 观感就是"启动瞬间跳一下"。
        // 处理：insets 就绪前只画背景、不画前景（通常只差 1–2 帧，肉眼不可见）。
        // 兜底：真正没有状态栏的设备（全屏/车机）永远拿不到 inset，超时后照常渲染。
        val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        var insetsTimedOut by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            delay(300)
            insetsTimedOut = true
        }
        val contentVisible = statusBarTop > 0.dp || insetsTimedOut

        // 主内容采用全屏布局；底部导航使用标准 Material 3 surface，避免半透明胶囊与内容争夺注意力。
        Box(Modifier.fillMaxSize()) {
            BackdropLayer(prefs = uiPrefs, modifier = Modifier.fillMaxSize())
                NavHost(
                    navController = navController,
                    startDestination = Dest.Home.route,
                    modifier = Modifier
                        .fillMaxSize()
                        // insets 就绪前不画（布局照常，只是不可见）——见上方首帧守卫
                        .graphicsLayer { alpha = if (contentVisible) 1f else 0f }
                        .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()),
                    // 无底部留白：内容（含各页自带底部空白）可滚过常驻栏，滚动中充分透出
                ) {
                composable(Dest.Home.route) {
                    HomeScreen(
                        viewModel,
                        onNavigate = navigateTab,
                        // 首页大按钮在"没有实例"时显示的是「新建服务端」，
                        // 就必须真的进新建向导——此前跳到服务端列表页，用户还得再点一次「新建」
                        onNewServer = { navController.navigate("server/new") },
                    )
                }
                composable(Dest.Server.route) {
                    ServerScreen(
                        viewModel,
                        onOpenInstance = { inst -> navController.navigate("instance/${android.net.Uri.encode(inst.id)}") },
                        onNewServer = { navController.navigate("server/new") },
                        // 与 ConsoleScreen 那处同一条路由：AI 入口独立于实例是否存在
                        onOpenAi = { navController.navigate("ai") },
                    )
                }
                composable("server/new") {
                    NewServerScreen(
                        viewModel = viewModel,
                        // 只在向导仍处于栈顶时才回退。
                        // 向导的 onExit 会在**下载完成时**被调用，而下载是跨页面继续跑的：
                        // 用户完全可能点了「下载并创建」后按返回离开向导，去干别的。此时再无条件
                        // popBackStack() 就会把用户当前所在的页面弹掉（表现为"下载完自己退了一层"）。
                        onBack = {
                            if (navController.currentDestination?.route == "server/new") {
                                navController.popBackStack()
                            }
                        },
                    )
                }
                composable(Dest.Console.route) {
                    ConsoleScreen(viewModel, onOpenAi = { navController.navigate("ai") })
                }
                composable("ai") {
                    AiScreen(
                        viewModel = viewModel,
                        onBack = { navController.popBackStack() },
                        onOpenSettings = { navController.navigate("ai/settings") },
                    )
                }
                composable("ai/settings") {
                    AiSettingsScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
                }
                composable(Dest.Settings.route) {
                    SettingsScreen(
                        viewModel = viewModel,
                        onOpenDiagnostics = { navController.navigate("diagnostics") },
                    )
                }
                composable("diagnostics") {
                    DiagnosticsScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
                }
                composable("instance/{instanceId}") { entry ->
                    val id = entry.arguments?.getString("instanceId") ?: ""
                    InstanceDetailScreen(
                        viewModel = viewModel,
                        instanceId = id,
                        onBack = { navController.popBackStack() },
                        onOpenAddons = { kind ->
                            navController.navigate("instance/${android.net.Uri.encode(id)}/addons/${kind.name.lowercase()}")
                        },
                        onOpenLogs = { navController.navigate("instance/${android.net.Uri.encode(id)}/logs") },
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
            // 常驻底栏：Material 3 NavigationBar，完整表面与系统导航栏 inset 由组件处理
            if (showBottomBar) {
                // 渐隐的落点色：用主题背景色，深色下接近页面底色
                val scrimColor = com.kaze.newage.ui.theme.backgroundColor()
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        // 同上：底栏也依赖 navigationBars inset，首帧会跳，一起门控
                        .graphicsLayer { alpha = if (contentVisible) 1f else 0f }
                        // 底栏上方加一层向上渐隐：滚过来的内容"淡出"，
                        // 而不是被浮起的胶囊硬切一半（设置页的 chips 就出现过这种观感）。
                        // 用 drawBehind 绘制，不参与布局，底栏尺寸不变。
                        .drawBehind {
                            val fadePx = 44.dp.toPx()
                            drawRect(
                                brush = Brush.verticalGradient(
                                    0f to Color.Transparent,
                                    1f to scrimColor,
                                    startY = -fadePx,
                                    endY = size.height * 0.62f,
                                ),
                                topLeft = Offset(0f, -fadePx),
                                size = Size(size.width, size.height + fadePx),
                            )
                        }
                ) {
                    Material3NavBar(
                        currentRoute = currentRoute,
                        onNavigate = { dest -> navigateTab(dest.route) },
                    )
                }
            }
        }

        // ── 启动自动检查：发现新版本弹窗（下载阶段沿用同一个弹窗显示进度）──
        val releaseInfo = updateInfo ?: updateDownloading?.info
        if (releaseInfo != null && !updateDialogDismissed) {
            val busy = updateDownloading != null
            val cancelled = busy && viewModel.updateCancelRequestedFlag()
            AlertDialog(
                // 下载中不锁死弹窗：点击外部 = 请求取消（下载会中止，断点保留）
                onDismissRequest = {
                    if (busy) {
                        viewModel.cancelUpdateDownload()
                    } else {
                        updateDialogDismissed = true
                        viewModel.resetUpdateState()
                    }
                },
                title = { Text("发现新版本 ${releaseInfo.tag}") },
                text = {
                    Column {
                        // 发布日期：更新判定现在以它为准（版本号不再参与比较），亮出来让用户可核对
                        if (releaseInfo.publishedAtText.isNotBlank()) {
                            Text(
                                "发布于 ${releaseInfo.publishedAtText}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (releaseInfo.body.isNotBlank()) {
                            // 原来直接把 info.body 丢进 Text：用户看到的是**原始 Markdown**
                            // （## / > / ** / 表格竖线），而且 take(400) 把正文截断，后半截看不到。
                            // 现在转成可读纯文本，并给固定高度 + 可滚动 —— 全文都能翻。
                            Text(
                                remember(releaseInfo.tag, releaseInfo.body) {
                                    com.kaze.newage.core.update.ReleaseNotes
                                        .toPlainText(releaseInfo.body, dropTitle = releaseInfo.tag)
                                        .ifBlank { releaseInfo.body }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .heightIn(max = 340.dp)
                                    .verticalScroll(rememberScrollState()),
                            )
                        }
                        updateDownloading?.message?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                        // 下载失败时不能静默收弹窗（否则用户以为更新完成了）
                        (updateState as? AppUpdateState.Error)?.let {
                            Text(
                                it.msg,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                        if (busy) {
                            // 有进度就显示**确定**进度条，没有（探测/拼装/校验阶段）
                            // 才退回不确定的滚动条 —— 一直转圈会让人以为卡死了
                            val percent = updateDownloading?.progress ?: 0f
                            if (percent > 0f) {
                                LinearProgressIndicator(
                                    progress = { percent },
                                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                )
                            } else {
                                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = { viewModel.downloadAndInstallUpdate(releaseInfo) },
                        enabled = !busy,
                    ) {
                        Text(if (busy) "下载中…" else "下载并安装")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            // 下载中 = 取消（立刻给反馈：只把标志置真的话，用户点了取消、
                            // 界面几秒内毫无变化，观感就是"取消不了"，真机反馈过）
                            if (busy) {
                                viewModel.cancelUpdateDownload()
                            } else {
                                updateDialogDismissed = true
                                viewModel.resetUpdateState()
                            }
                        },
                        enabled = !cancelled,
                    ) {
                        Text(
                            when {
                                cancelled -> "正在取消…"
                                busy -> "取消下载"
                                else -> "以后再说"
                            },
                        )
                    }
                },
            )
        }
    }
}

/**
 * 应用主导航：采用 Material 3 NavigationBar，让选中态、触控反馈与动态配色跟随主题。
 * 标签始终可见，降低四个主要入口只靠图标识别的成本。
 */
@Composable
private fun Material3NavBar(
    currentRoute: String?,
    onNavigate: (Dest) -> Unit,
) {
    NavigationBar(
        modifier = Modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 2.dp,
        windowInsets = WindowInsets.navigationBars,
    ) {
        Dest.entries.forEach { dest ->
            val selected = currentRoute == dest.route
            NavigationBarItem(
                selected = selected,
                onClick = { onNavigate(dest) },
                icon = {
                    Icon(
                        if (selected) dest.iconSelected else dest.icon,
                        contentDescription = null,
                    )
                },
                label = { Text(dest.label) },
                alwaysShowLabel = true,
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                    indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}
