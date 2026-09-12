package com.kaze.newage.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.kaze.newage.NewAgeApp
import com.kaze.newage.ui.screens.ConsoleScreen
import com.kaze.newage.ui.screens.HomeScreen
import com.kaze.newage.ui.screens.NewServerScreen
import com.kaze.newage.ui.screens.ServerScreen
import com.kaze.newage.ui.screens.SettingsScreen
import com.kaze.newage.ui.theme.AppThemeMode
import com.kaze.newage.ui.theme.NewAgeTheme
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
 * 与 `ScreenshotTest`（组件级）的分工：
 *  - `ScreenshotTest`：纯组件、无依赖，渲染快，用来盯单个控件的样式
 *  - 本类：整屏、走真实 ViewModel，用来检查布局/间距/空状态/文案
 *
 * 说明：这里的实例列表是空的（Robolectric 的存储是干净的），
 * 因此截到的是各页面的**空状态**。要看"有实例/运行中"的样子，
 * 需要往 `instanceStore` 里塞测试数据——见 [screen_server_with_instance]。
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
                content()
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

    // ── 服务端列表（空态）──
    @Test
    fun screen_server() = captureScreen("screen_server") {
        ServerScreen(vm(), onOpenInstance = {}, onNewServer = {})
    }

    // ── 控制台 ──
    @Test
    fun screen_console() = captureScreen("screen_console") {
        ConsoleScreen(vm())
    }

    // ── 设置 ──
    @Test
    fun screen_settings() = captureScreen("screen_settings") {
        SettingsScreen(vm())
    }

    // ── 新建服务端向导：第 1 步（选择核心类型）──
    // 不进第 2 步：那一步会在 LaunchedEffect 里拉版本清单（需要网络）
    @Test
    fun screen_new_server_step1() = captureScreen("screen_new_server_step1") {
        NewServerScreen(viewModel = vm(), onBack = {})
    }
}
