package com.kaze.newage.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.kaze.newage.ui.components.M3ECard
import com.kaze.newage.ui.components.M3ECheckbox
import com.kaze.newage.ui.components.M3EConnectedList
import com.kaze.newage.ui.components.M3EDivider
import com.kaze.newage.ui.components.M3ELinearProgress
import com.kaze.newage.ui.components.M3EListItem
import com.kaze.newage.ui.components.M3EScreenColumn
import com.kaze.newage.ui.components.M3ESegmentedRow
import com.kaze.newage.ui.components.M3ESlider
import com.kaze.newage.ui.components.M3EStatusChip
import com.kaze.newage.ui.components.M3ESwitch
import com.kaze.newage.ui.components.themedIndication
import com.kaze.newage.ui.theme.AppThemeMode
import com.kaze.newage.ui.theme.LocalAppTheme
import com.kaze.newage.ui.theme.M3Spacing
import com.kaze.newage.ui.theme.MiuixTheme
import com.kaze.newage.ui.theme.NewAgeTheme
import com.kaze.newage.ui.theme.ThemeBackdrop
import com.kaze.newage.ui.theme.miuixTypography
import com.kaze.newage.ui.theme.statusPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * MIUIX（HyperOS）主题的组件级截图与不变量测试。
 *
 *   ./gradlew :app:recordRoborazziArm64Debug
 *   输出：app/build/screenshots/miuix_*.png
 *
 * ## 这些测试防的是什么
 * 0.8.0 的 Miuix 主题有三个**只在真机上才看得出来**的缺陷，全部属于
 * 「编译通过、单测全绿、界面照样坏」：
 *  1. 从未提供 `MiuixTheme(colors = …)` → Miuix 的 Switch 在深色主题下用内置**浅色**令牌
 *     （灰轨白钮贴在黑底上几乎不可见）；
 *  2. 背景层复用了 M3 的纵向渐变 → HyperOS 的分组灰底被抹成淡蓝渐变，
 *     「白卡 vs 灰底」的分层失效；
 *  3. 列表仍是「每行一张小卡」→ HyperOS 的设置页是「一个分组一张卡 + 行间分隔线」。
 *
 * 所以这里既有**截图**（人工核对版式），也有**硬断言**（把上面几条钉成不变量）。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-xhdpi")
class MiuixThemeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun capture(
        name: String,
        dark: Boolean = false,
        amoled: Boolean = false,
        content: @Composable () -> Unit,
    ) {
        composeRule.setContent {
            NewAgeTheme(
                mode = AppThemeMode.MIUIX,
                darkTheme = dark,
                amoledDark = amoled,
                colorSource = "custom",
            ) {
                Box(Modifier.fillMaxSize()) {
                    ThemeBackdrop(Modifier.matchParentSize())
                    content()
                }
            }
        }
        composeRule.onRoot().captureRoboImage("build/screenshots/$name.png")
    }

    // ══════════════════════════════════════════════════════════
    // 截图：组件总览（浅色 / 深色 / AMOLED）
    // ══════════════════════════════════════════════════════════

    @Test
    fun miuix_components_light() = capture("miuix_components_light") { MiuixGallery() }

    @Test
    fun miuix_components_dark() = capture("miuix_components_dark", dark = true) { MiuixGallery() }

    @Test
    fun miuix_components_amoled() =
        capture("miuix_components_amoled", dark = true, amoled = true) { MiuixGallery() }

    /**
     * 组件画廊：把这套主题真正会碰到的东西一次画全。
     * 顺序刻意和真机设置页一致（分组标题 → 分组卡 → 行 → 脚注）。
     */
    @Composable
    private fun MiuixGallery() {
        var sw1 by remember { mutableStateOf(true) }
        var sw2 by remember { mutableStateOf(false) }
        var cb by remember { mutableStateOf(true) }
        var slider by remember { mutableStateOf(4096f) }
        var tab by remember { mutableStateOf(0) }
        val palette = statusPalette()

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            M3EScreenColumn(Modifier.padding(vertical = 16.dp)) {
                // ① 分组标题（卡外灰字）+ 分组卡 + 行间分隔线
                M3EConnectedList(count = 3) { i, shape ->
                        when (i) {
                            0 -> M3EListItem(
                                headline = "开关（开）",
                                supporting = "整行点击负责切换，开关本体不绑定回调",
                                leadingIcon = Icons.Filled.Settings,
                                iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                                shape = shape,
                                onClick = { sw1 = !sw1 },
                                trailing = { M3ESwitch(checked = sw1, onCheckedChange = null) },
                            )
                            1 -> M3EListItem(
                                headline = "开关（关）",
                                leadingIcon = Icons.Filled.Storage,
                                iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                                shape = shape,
                                onClick = { sw2 = !sw2 },
                                trailing = { M3ESwitch(checked = sw2, onCheckedChange = null) },
                            )
                            else -> M3EListItem(
                                headline = "高亮行（当前实例）",
                                supporting = "primaryContainer 底 + onPrimaryContainer 字",
                                highlighted = true,
                                leadingIcon = Icons.Filled.PlayArrow,
                                iconContainer = MaterialTheme.colorScheme.primaryContainer,
                                shape = shape,
                                onClick = {},
                                trailing = { M3EStatusChip(text = "运行中", color = palette.running) },
                            )
                        }
                    }

                // ② 勾选框与滑块（这两件在 0.8.0 里还是 Material 组件）
                M3EConnectedList(count = 2) { i, shape ->
                        if (i == 0) {
                            M3EListItem(
                                headline = "同意 EULA",
                                leadingIcon = Icons.Filled.CheckCircle,
                                iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                                shape = shape,
                                onClick = { cb = !cb },
                                trailing = { M3ECheckbox(checked = cb, onCheckedChange = null) },
                            )
                        } else {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                                Text(
                                    "内存分配 %.1f GB".format(slider / 1024f),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                M3ESlider(
                                    value = slider,
                                    onValueChange = { slider = it },
                                    valueRange = 512f..8192f,
                                    steps = 29,
                                )
                            }
                        }
                    }

                // ③ 分段选择（HyperOS = 一个胶囊轨道 + 一块白色滑块）
                M3ESegmentedRow(
                    options = listOf(0, 1, 2),
                    selected = tab,
                    label = { listOf("全部", "已安装", "可更新")[it] },
                    onSelect = { tab = it },
                )

                // ④ 进度条：确定 / 不确定
                M3ECard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        M3ELinearProgress(progress = 0.42f, modifier = Modifier.fillMaxWidth())
                        M3ELinearProgress(progress = null, modifier = Modifier.fillMaxWidth())
                        M3EDivider()
                        Text(
                            "水波纹换成 HyperOS 的矩形按压高亮",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                // ⑤ 普通卡片（Miuix 下应与分组卡同圆角、无投影）
                M3ECard(title = "普通卡片", supporting = "16dp 圆角 + 0.5dp 描边 + 无投影") {
                    Text("HyperOS 不使用海拔分层，靠底色对比。", style = MaterialTheme.typography.bodyMedium)
                }
            }
            Box(Modifier.fillMaxWidth().height(M3Spacing.bottomBarSpace))
        }
    }

    // ══════════════════════════════════════════════════════════
    // 不变量（截图之外的硬断言）
    // ══════════════════════════════════════════════════════════

    /**
     * 深色令牌必须是「深底 + 亮钮 / 深轨」，不能退回 miuix 的浅色默认值。
     *
     * 这正是 0.8.0 的真实缺陷：`MiuixSwitch` 的 checkedTrackColor 默认取
     * `MiuixTheme.colorScheme.primary`，而当时根本没有 MiuixTheme 提供者，
     * 于是拿到 `lightColorScheme()` 的 #3482FF + 未选中 #E6E6E6 —— 深色下
     * 灰轨白钮贴在黑底上，等于看不见开关。
     */
    @Test
    fun miuix_dark_tokens_are_dark() {
        val dark = MiuixTheme.colorsDark()
        val light = MiuixTheme.colorsLight()
        assertEquals(0xFF2C2C2E.toInt(), dark.secondary.toArgb())          // 未选中轨道（深）
        assertEquals(0xFFE5E5EA.toInt(), light.secondary.toArgb())         // 未选中轨道（浅）
        assertEquals(0xFF1C1C1E.toInt(), dark.surface.toArgb())            // 深色卡片
        assertEquals(0xFF000000.toInt(), MiuixTheme.colorsDark(pureBlack = true).surface.toArgb())
        assertEquals(0xFF007AFF.toInt(), light.primary.toArgb())           // HyperOS 蓝
    }

    /**
     * 反证「必须提供 MiuixTheme」这个前提：miuix 的内置默认板是浅色的、且与本项目不同源。
     * 若上游将来改了默认值，这条会红，提示重新评估该前提。
     */
    @Test
    fun miuix_component_builtin_defaults_differ() {
        val builtin = top.yukonga.miuix.kmp.theme.lightColorScheme()
        assertEquals(0xFF3482FF.toInt(), builtin.primary.toArgb())
        assertTrue(
            "本项目的 HyperOS 主色必须与 miuix 内置默认不同，否则 '令牌映射' 就没有意义",
            builtin.primary.toArgb() != MiuixTheme.colorsLight().primary.toArgb(),
        )
    }

    /**
     * MIUIX 下 M3 排版必须换成 HyperOS 标尺（正文 16/14/13/11），
     * 否则「Miuix 开关旁的 M3 文字」会比 Miuix 自带文字大一号。
     */
    @Test
    fun miuix_typography_matches_hyperos_scale() {
        val typo = miuixTypography()
        assertEquals(16f, typo.bodyLarge.fontSize.value, 0.01f)
        assertEquals(14f, typo.bodyMedium.fontSize.value, 0.01f)
        assertEquals(13f, typo.bodySmall.fontSize.value, 0.01f)
        assertEquals(11f, typo.labelSmall.fontSize.value, 0.01f)
        assertEquals(20f, typo.headlineSmall.fontSize.value, 0.01f)
    }

    /** 组件层必须真的走主题分支：MIUIX 上下文取到的应是 HyperOS 语义色 */
    @Test
    fun miuix_context_provides_theme_and_palette() {
        var observedMode: AppThemeMode? = null
        var running = 0
        var error = 0
        composeRule.setContent {
            NewAgeTheme(mode = AppThemeMode.MIUIX, darkTheme = false, colorSource = "custom") {
                observedMode = LocalAppTheme.current
                val p = statusPalette()
                running = p.running.toArgb()
                error = p.error.toArgb()
                // 顺带把按压反馈取出来：MIUIX 下应拿到 MiuixIndication（类名不同即通过）
                val indication = themedIndication()
                assertTrue(
                    "MIUIX 的按压反馈应是 Miuix 的矩形高亮",
                    indication::class.java.name.contains("MiuixIndication"),
                )
            }
        }
        composeRule.waitForIdle()
        assertEquals(AppThemeMode.MIUIX, observedMode)
        assertEquals(0xFF34C759.toInt(), running)   // HyperOS 绿
        assertEquals(0xFFFA5151.toInt(), error)     // HyperOS 红
    }
}
