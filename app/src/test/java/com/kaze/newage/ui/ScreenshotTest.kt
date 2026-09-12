package com.kaze.newage.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.kaze.newage.ui.components.CheckChip
import com.kaze.newage.ui.components.StatusOrb
import com.kaze.newage.ui.components.StatusTone
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
 * Roborazzi 截图测试：在纯 JVM 上渲染 Compose 并输出 PNG，
 * 不需要模拟器、不需要安装 APK。
 *
 *   ./gradlew :app:recordRoborazziArm64Debug     # 渲染并写出 PNG
 *   输出目录：app/build/screenshots/
 *
 * 新增截图：加一个 @Test，调用 capture("<文件名>") { ...你的 Composable... }。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class ScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun capture(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        composeRule.setContent {
            NewAgeTheme(mode = AppThemeMode.M3, darkTheme = dark, colorSource = "custom") {
                // 同 ScreenScreenshotTest：不铺主题背景层的话，深色模式会截成
                // "浅色文字 + 宿主默认白底"，看着像主题坏了
                Box(Modifier.fillMaxSize()) {
                    ThemeBackdrop(Modifier.matchParentSize())
                    content()
                }
            }
        }
        composeRule.onRoot().captureRoboImage("build/screenshots/$name.png")
    }

    @Test
    fun statusOrbs_light() {
        capture("status_orbs_light") {
            Column(
                Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                StatusTone.entries.forEach { StatusOrb(tone = it) }
            }
        }
    }

    @Test
    fun components_dark() {
        capture("components_dark", dark = true) {
            Column(
                Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                StatusOrb(tone = StatusTone.Running, size = 72.dp)
                CheckChip(selected = true, label = "已选中", onClick = {})
                CheckChip(selected = false, label = "未选中", onClick = {})
                Text("Kaze Launcher UI")
            }
        }
    }
}
