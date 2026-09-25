package com.kaze.newage.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.kaze.newage.ui.components.ExpressiveLoadingGlyph
import com.kaze.newage.ui.components.ExpressiveLoadingIndicator
import com.kaze.newage.ui.components.ExpressiveLoadingRing
import com.kaze.newage.ui.components.LOADING_SHAPE_NAMES
import com.kaze.newage.ui.components.M3ECard
import com.kaze.newage.ui.components.M3ECardVariant
import com.kaze.newage.ui.components.M3EConnectedList
import com.kaze.newage.ui.components.M3EListItem
import com.kaze.newage.ui.components.M3EMetric
import com.kaze.newage.ui.components.M3EScreenColumn
import com.kaze.newage.ui.components.M3ESegmentedRow
import com.kaze.newage.ui.components.M3EStatusChip
import com.kaze.newage.ui.components.WavyLinearProgress
import com.kaze.newage.ui.components.morphedShape
import com.kaze.newage.ui.theme.AppThemeMode
import com.kaze.newage.ui.theme.M3Spacing
import com.kaze.newage.ui.theme.NewAgeTheme
import com.kaze.newage.ui.theme.ThemeBackdrop
import com.kaze.newage.ui.theme.statusPalette
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * M3 Expressive 组件层的截图：确认新设计体系的每个零件真的画出来了。
 *
 *   ./gradlew :app:recordRoborazziArm64Debug
 *   输出：app/build/screenshots/m3e_*.png
 *
 * 这些图是**人工核对**用的，不做像素基线比对：形状变化加载指示器是无限动画，
 * 不同帧本来就长得不一样，拿它做基线只会带来假失败。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class ExpressiveComponentsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun capture(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        composeRule.setContent {
            NewAgeTheme(mode = AppThemeMode.M3, darkTheme = dark, colorSource = "custom") {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth()) {
                    ThemeBackdrop(Modifier.matchParentSize())
                    content()
                }
            }
        }
        composeRule.onRoot().captureRoboImage("build/screenshots/$name.png")
    }

    /** 官方 7 个形状：从形状几何直接取静止造型，不依赖动画时序 */
    @Test
    fun m3e_loading_shapes() = capture("m3e_loading_shapes") {
        M3EScreenColumn(Modifier.padding(vertical = 24.dp)) {
            Text("M3 Expressive 加载指示器的 7 个形状", style = MaterialTheme.typography.titleMedium)
            LOADING_SHAPE_NAMES.forEachIndexed { i, name ->
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    ExpressiveLoadingGlyph(size = 48.dp, shapeIndex = i)
                    Text("$i  $name", style = MaterialTheme.typography.bodyMedium)
                    // 顺带验证 morphedShape 的中间帧不外溢
                    ExpressiveLoadingGlyph(size = 32.dp, shapeIndex = i, color = MaterialTheme.colorScheme.tertiary)
                }
            }
        }
    }

    /** 动画中的三个形态：指示器 / 细环 / 静止造型 */
    @Test
    fun m3e_loading_variants() = capture("m3e_loading_variants") {
        M3EScreenColumn(Modifier.padding(vertical = 24.dp)) {
            Text("形状变化加载指示器 · 三种用法", style = MaterialTheme.typography.titleMedium)
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                ExpressiveLoadingIndicator(size = 48.dp)
                ExpressiveLoadingIndicator(size = 48.dp, speed = 1.8f, color = statusPalette().busy)
                ExpressiveLoadingRing(size = 32.dp)
                ExpressiveLoadingRing(size = 24.dp, strokeWidth = 1.5.dp)
            }
            Text(
                "morphedShape(2.5f) 的中间帧 —— 形状之间是逐点插值，不是淡入淡出",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "顶点数 ${morphedShape(2.5f).size}",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }

    /** 卡片 / 列表项 / 状态胶囊 / 指标 / 分段选择 / 波浪进度条 */
    @Test
    fun m3e_components_light() = capture("m3e_components_light") {
        M3EScreenColumn(Modifier.padding(vertical = 24.dp)) {
            M3ECard(
                variant = M3ECardVariant.Elevated,
                title = "生存服",
                supporting = "Paper 1.21.4 · Java 21 · 4096 MB · 端口 25565",
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    M3EMetric("02:14:37", "已运行")
                    M3EMetric("3", "在线")
                    M3EMetric("", "内存")
                }
            }
            M3ECard(variant = M3ECardVariant.Outlined, title = "描边卡片", supporting = "surface + 1dp outlineVariant")
            M3ECard(variant = M3ECardVariant.Filled, title = "填充卡片", supporting = "surfaceContainerHighest")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                M3EStatusChip("运行中", statusPalette().running)
                M3EStatusChip("启动中", statusPalette().busy)
                M3EStatusChip("已停止", statusPalette().idle)
                M3EStatusChip("启动失败", statusPalette().error)
            }
            M3EConnectedList(count = 3) { i, shape ->
                M3EListItem(
                    headline = listOf("主题模式", "颜色来源", "深色样式")[i],
                    supporting = listOf("跟随系统", "壁纸动态取色", "普通黑")[i],
                    leadingIcon = Icons.Filled.Settings,
                    iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                    shape = shape,
                    onClick = {},
                )
            }
            M3ESegmentedRow(
                options = listOf("全部", "官方", "性能", "模组"),
                selected = "全部",
                label = { it },
                onSelect = {},
            )
            WavyLinearProgress(progress = 0.45f, modifier = Modifier.fillMaxWidth())
            WavyLinearProgress(progress = null, modifier = Modifier.fillMaxWidth())
            WavyLinearProgress(progress = 0.73f, modifier = Modifier.fillMaxWidth(), wavy = false)
            androidx.compose.foundation.layout.Spacer(Modifier.padding(M3Spacing.betweenParts))
        }
    }

    /** 深色下再核一遍：新组件层不能出现「黑底黑字」 */
    @Test
    fun m3e_components_dark() = capture("m3e_components_dark", dark = true) {
        M3EScreenColumn(Modifier.padding(vertical = 24.dp)) {
            M3ECard(
                variant = M3ECardVariant.Elevated,
                title = "运行状态",
                supporting = "已运行 02:14:37 · 在线 3 人 · 内存 1.8 / 4.0 GB",
            ) {
                WavyLinearProgress(progress = 0.45f, modifier = Modifier.fillMaxWidth())
            }
            M3EConnectedList(count = 2) { i, shape ->
                M3EListItem(
                    headline = listOf("自动备份", "查看启动日志")[i],
                    supporting = listOf("每 30 分钟 · 共 4 份", "每次启动的完整输出与退出码")[i],
                    leadingIcon = Icons.Filled.Settings,
                    iconContainer = MaterialTheme.colorScheme.secondaryContainer,
                    shape = shape,
                    onClick = {},
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                M3EStatusChip("运行中", statusPalette().running)
                M3EStatusChip("已停止", statusPalette().idle)
            }
            ExpressiveLoadingIndicator(size = 48.dp)
        }
    }
}
