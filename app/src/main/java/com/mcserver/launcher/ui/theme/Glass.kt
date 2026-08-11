package com.mcserver.launcher.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/**
 * 玻璃拟态基础设施
 * Compose 原生不支持 backdrop-filter，玻璃质感采用「半透明卡片 + 顶部高光描边 + 内发光」
 * 叠加在 AuroraBackground 极光环境光之上，v7a 老机会在 GlassCard 层级自动降级（见 KazeGlass）。
 */

/** 是否降低动效（v7a 老机 / 用户设置）。动画组件读取后停用无限循环动画。 */
val LocalReduceMotion = compositionLocalOf { false }

/** 冷蓝绿极光三色 */
val Aurora1 = Color(0xFF0EA5E9)  // 天蓝
val Aurora2 = Color(0xFF2DD4BF)  // 青绿
val Aurora3 = Color(0xFF14B8A6)  // 蓝绿

/**
 * 极光背景层：深色底 + 三团冷蓝绿径向光晕，作为玻璃磨砂的环境光来源。
 * 各光团定位在角落外，径向渐变天然融入背景，无需知道屏幕尺寸。
 */
@Composable
fun AuroraBackground(
    modifier: Modifier = Modifier,
    amoled: Boolean = false
) {
    val base = if (amoled) Color(0xFF05070D) else Color(0xFF0B0F1A)
    Box(modifier.fillMaxSize().background(base)) {
        Box(
            Modifier.size(380.dp).align(Alignment.TopStart).offset((-100).dp, (-80).dp)
                .background(Brush.radialGradient(colors = listOf(Aurora1.copy(alpha = 0.42f), Color.Transparent)))
        )
        Box(
            Modifier.size(320.dp).align(Alignment.TopEnd).offset(80.dp, (-50).dp)
                .background(Brush.radialGradient(colors = listOf(Aurora2.copy(alpha = 0.30f), Color.Transparent)))
        )
        Box(
            Modifier.size(440.dp).align(Alignment.BottomCenter).offset(0.dp, 140.dp)
                .background(Brush.radialGradient(colors = listOf(Aurora3.copy(alpha = 0.34f), Color.Transparent)))
        )
    }
}

private fun topHighlight(isDark: Boolean): Brush = Brush.verticalGradient(
    colors = listOf(
        Color.White.copy(alpha = if (isDark) KazeGlass.highlightAlpha else 0.5f),
        Color.Transparent
    )
)

@Composable
private fun isDarkTheme(): Boolean =
    MaterialTheme.colorScheme.background.luminance() < 0.5f

/** 玻璃容器底层（无点击交互），内部 content 处于 BoxScope，可直接 align */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
    content: @Composable BoxScope.() -> Unit
) {
    val isDark = isDarkTheme()
    val fill = Color.White.copy(alpha = if (isDark) KazeGlass.cardAlphaDark else KazeGlass.cardAlphaLight)
    val border = Color.White.copy(alpha = if (isDark) KazeGlass.cardBorderAlphaDark else KazeGlass.cardBorderAlphaLight)
    Surface(
        modifier = modifier,
        shape = shape,
        color = fill,
        border = BorderStroke(1.dp, border),
        shadowElevation = if (isDark) 0.dp else 6.dp,
        content = {
            Box {
                Box(Modifier.fillMaxWidth().height(32.dp).background(topHighlight(isDark)))
                content()
            }
        }
    )
}

/** 玻璃对话框容器 */
@Composable
fun GlassDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(22.dp),
    content: @Composable BoxScope.() -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        GlassSurface(modifier, shape, content)
    }
}
