package com.kaze.newage.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.kaze.newage.NewAgeApp
import com.kaze.newage.data.model.CoreType
import com.kaze.newage.data.model.ServerInstance
import com.kaze.newage.ui.screens.ConsoleScreen
import com.kaze.newage.ui.screens.HomeScreen
import com.kaze.newage.ui.screens.NewServerScreen
import com.kaze.newage.ui.screens.ServerScreen
import com.kaze.newage.ui.screens.SettingsScreen
import com.kaze.newage.ui.theme.AppThemeMode
import com.kaze.newage.ui.theme.NewAgeTheme
import com.kaze.newage.ui.theme.ThemeBackdrop
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * **屏幕级**截图测试：用 Robolectric 实例化真实的 [NewAgeApp]（因此 `application.container`
 * 与 [AppViewModel] 都是真的），把整屏 Compose 渲染成 PNG。
 *
 *   ./gradlew :app:recordRoborazziArm64Debug
 *   输出：app/build/screenshots/screen_*.png
 *
 * 与 `ScreenshotTest` / `ExpressiveComponentsTest`（组件级）的分工：
 *  - 组件级：纯组件、无依赖，渲染快，用来盯单个控件的样式
 *  - 本类：整屏、走真实 ViewModel，用来检查布局/间距/空状态/文案
 *
 * 说明：这里的实例列表是空的（Robolectric 的存储是干净的），
 * 因此截到的是各页面的**空状态**——新设计的空状态同样是设计的一部分（要有引导，不能是白屏）。
 * 要看「有实例 / 运行中」的样子，需要往 `instanceStore` 里塞测试数据。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class ScreenScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun captureScreen(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        composeRule.setContent {
            NewAgeTheme(mode = AppThemeMode.M3, darkTheme = dark, colorSource = "custom") {
                // 必须自己铺上主题背景层。真机上这一层由 AppRoot 的 AppBackground 负责，
                // 测试里直接渲染屏幕 Composable 的话背景是宿主给的默认白底——
                // 深色模式下内容用浅色文字、背景却是白的，截出来像"主题坏了"，
                // 实际只是少画了一层。见 ThemeBackdrop（M3=纵向渐变，GLASS=光斑）。
                Box(Modifier.fillMaxSize()) {
                    ThemeBackdrop(Modifier.matchParentSize())
                    content()
                }
            }
        }
        composeRule.onRoot().captureRoboImage("build/screenshots/$name.png")
    }

    /** 真实 Application → 真实 AppContainer → 真实 ViewModel */
    private fun vm(): AppViewModel =
        AppViewModel(ApplicationProvider.getApplicationContext<NewAgeApp>())

    // ── 首页 ──
    @Test
    fun screen_home() = captureScreen("screen_home") {
        HomeScreen(vm(), onNavigate = {}, onNewServer = {})
    }

    @Test
    fun screen_home_dark() = captureScreen("screen_home_dark", dark = true) {
        HomeScreen(vm(), onNavigate = {}, onNewServer = {})
    }

    // ── 有实例时的首页：主行动按钮组 / 运行时长卡 / 次要动作行只有这一屏才看得到 ──
    @Test
    fun screen_home_with_instance() {
        val viewModel = vm()
        seedInstance(viewModel)
        captureScreen("screen_home_with_instance") {
            HomeScreen(viewModel, onNavigate = {}, onNewServer = {})
        }
    }

    // ── 服务端列表（空态）──
    @Test
    fun screen_server() = captureScreen("screen_server") {
        ServerScreen(vm(), onOpenInstance = {}, onNewServer = {})
    }

    @Test
    fun screen_server_dark() = captureScreen("screen_server_dark", dark = true) {
        ServerScreen(vm(), onOpenInstance = {}, onNewServer = {})
    }

    // ── 服务端列表（两个实例）：相连列表、分类着色、选中环只有这一屏才看得到 ──
    @Test
    fun screen_server_with_instances() {
        val viewModel = vm()
        seedInstance(viewModel, name = "生存服", core = CoreType.PAPER, mc = "1.21.4", java = 21, memory = 4096)
        seedInstance(viewModel, name = "模组服", core = CoreType.FABRIC, mc = "1.21.1", java = 21, memory = 6144)
        captureScreen("screen_server_with_instances") {
            ServerScreen(viewModel, onOpenInstance = {}, onNewServer = {})
        }
    }

    // ── 控制台 ──
    @Test
    fun screen_console() = captureScreen("screen_console") {
        ConsoleScreen(vm())
    }

    @Test
    fun screen_console_dark() = captureScreen("screen_console_dark", dark = true) {
        ConsoleScreen(vm())
    }

    // ── 设置 ──
    @Test
    fun screen_settings() = captureScreen("screen_settings") {
        SettingsScreen(vm())
    }

    @Test
    fun screen_settings_dark() = captureScreen("screen_settings_dark", dark = true) {
        SettingsScreen(vm())
    }

    // ── 新建服务端向导：第 1 步（选择核心类型）──
    // 不进第 2 步：那一步会在 LaunchedEffect 里拉版本清单（需要网络）
    @Test
    fun screen_new_server_step1() = captureScreen("screen_new_server_step1") {
        NewServerScreen(viewModel = vm(), onBack = {})
    }

    @Test
    fun screen_new_server_step1_dark() = captureScreen("screen_new_server_step1_dark", dark = true) {
        NewServerScreen(viewModel = vm(), onBack = {})
    }

    /**
     * 往真实的 InstanceStore 里塞一个实例，并把它设为当前实例。
     *
     * 只建目录与登记条目，不写 jar：截图要看的是「有实例」时的版式
     * （主行动按钮组、运行时长卡、相连列表、选中环），不是启动流程。
     */
    private fun seedInstance(
        viewModel: AppViewModel,
        name: String = "生存服",
        core: CoreType = CoreType.PAPER,
        mc: String = "1.21.4",
        java: Int = 21,
        memory: Int = 4096,
    ) {
        val dir = viewModel.instanceStore.createInstanceDir(name)
        val instance = ServerInstance(
            name = name,
            coreType = core,
            mcVersion = mc,
            javaMajor = java,
            memoryMb = memory,
            dir = dir,
        )
        viewModel.instanceStore.add(instance)
        viewModel.selectInstance(instance)
    }
}
