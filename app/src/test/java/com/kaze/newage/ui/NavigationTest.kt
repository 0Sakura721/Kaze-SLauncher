package com.kaze.newage.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.kaze.newage.NewAgeApp
import com.kaze.newage.data.model.CoreType
import com.kaze.newage.data.model.ServerInstance
import com.kaze.newage.ui.theme.AppThemeMode
import com.kaze.newage.ui.theme.NewAgeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 底部导航与页面间跳转的**交互**测试（Robolectric + Compose Test，不需要设备）。
 *
 * 用的是真实 `AppRoot`（真 NavHost、真底栏），所以栈的行为与真机一致。
 * 起因：首页「启动服务端」旁的控制台快捷按钮绕过了底栏的跳转语义
 * （裸 `navigate()`，没有 popUpTo/launchSingleTop/restoreState），
 * 之后底栏切不回主页。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class NavigationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun vm(): AppViewModel =
        AppViewModel(ApplicationProvider.getApplicationContext<NewAgeApp>())

    /**
     * 首页那一排按钮（大按钮 + 控制台快捷入口）**只在有当前实例时渲染**，
     * 空状态是单独的一个「新建服务端」。所以测试必须先放一个实例进去。
     */
    private fun vmWithInstance(): AppViewModel {
        val v = vm()
        val dir = v.instanceStore.createInstanceDir("导航测试服")
        v.instanceStore.add(
            ServerInstance(
                name = "导航测试服",
                coreType = CoreType.VANILLA,
                mcVersion = "1.21.4",
                dir = dir,
            )
        )
        return v
    }

    private fun setApp() {
        composeRule.setContent {
            NewAgeTheme(mode = AppThemeMode.M3, darkTheme = true, colorSource = "custom") {
                AppRoot(viewModel = vmWithInstance())
            }
        }
        composeRule.waitForIdle()
    }

    /** 底栏 tab（contentDescription 就是 Dest.label） */
    private fun clickTab(label: String) {
        composeRule.onAllNodesWithContentDescription(label).onFirst().performClick()
        composeRule.waitForIdle()
    }

    /** 首页那个控制台快捷按钮 */
    private fun clickHomeConsoleShortcut() {
        composeRule.onNodeWithContentDescription("打开控制台").performClick()
        composeRule.waitForIdle()
    }

    private fun assertOnConsole() {
        // 控制台页的输入框占位文案（其它页面没有）
        composeRule.onNodeWithText("服务端运行后可输入命令").assertIsDisplayed()
    }


    private fun assertOnSettings() {
        // 「主题样式」只属于设置页（"设置"两个字页头与底栏都有，不能拿来判定）
        composeRule.onNodeWithText("主题样式").assertIsDisplayed()
    }

    private fun assertOnHome() {
        composeRule.onNodeWithContentDescription("打开控制台").assertIsDisplayed()
    }

    @Test
    fun `首页控制台快捷入口切过去后能切回主页`() {
        setApp()
        assertOnHome()

        clickHomeConsoleShortcut()
        assertOnConsole()

        clickTab("主页")
        assertOnHome()
    }

    @Test
    fun `反复用快捷入口与底栏切换都回得来`() {
        setApp()
        repeat(3) {
            clickHomeConsoleShortcut()
            assertOnConsole()
            clickTab("主页")
            assertOnHome()
        }
    }

    @Test
    fun `快捷入口之后再走底栏切其它页也正常`() {
        setApp()
        clickHomeConsoleShortcut()
        assertOnConsole()
        clickTab("设置")
        assertOnSettings()
        clickTab("主页")
        assertOnHome()
    }
}
