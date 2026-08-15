package com.kaze.newage.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kaze.newage.ui.theme.AppThemeMode
import com.kaze.newage.ui.theme.LocalAppTheme
import com.kaze.newage.ui.theme.reducedMotion
import com.kaze.newage.ui.theme.statusPalette

/**
 * 服务状态基调（由 ServerState 映射而来）。
 */
enum class StatusTone { Running, Busy, Idle, Error }

/**
 * 状态球 —— 应用的签名元素：服务器的「生命体征」。
 * CLEAR=扁平圆环+实心点（克制）；GLASS=玻璃球体+镜面高光（呼吸）。
 */
@Composable
fun StatusOrb(
    tone: StatusTone,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
) {
    val palette = statusPalette()
    val color = when (tone) {
        StatusTone.Running -> palette.running
        StatusTone.Busy -> palette.busy
        StatusTone.Idle -> palette.idle
        StatusTone.Error -> palette.error
    }
    when (LocalAppTheme.current) {
        AppThemeMode.M3 -> ClearOrb(tone, color, modifier, size)
        AppThemeMode.GLASS -> GlassOrb(tone, color, modifier, size)
    }
}

/** CLEAR：扁平圆环 + 中心点，Busy 时脉动 */
@Composable
private fun ClearOrb(tone: StatusTone, color: Color, modifier: Modifier, size: Dp) {
    val still = reducedMotion()
    val transition = rememberInfiniteTransition(label = "orb")
    val pulse by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "orb-pulse",
    )
    val p = if (still) 0.65f else pulse
    Canvas(modifier) {
        val r = this.size.minDimension / 2f
        val stroke = 3.dp.toPx()
        drawCircle(color.copy(alpha = 0.3f), radius = r - stroke / 2f, style = Stroke(stroke))
        val innerR = when (tone) {
            StatusTone.Running -> r * 0.52f
            StatusTone.Busy -> r * (0.3f + 0.22f * p)
            StatusTone.Idle -> r * 0.26f
            StatusTone.Error -> r * 0.52f
        }
        val alpha = when (tone) {
            StatusTone.Busy -> p
            StatusTone.Idle -> 0.5f
            else -> 1f
        }
        drawCircle(color.copy(alpha = alpha), radius = innerR)
    }
}

/** GLASS：玻璃球体——白色镜面高光 + 彩色核心 + 柔和倒影感，Running 呼吸 / Busy 脉动 */
@Composable
private fun GlassOrb(tone: StatusTone, color: Color, modifier: Modifier, size: Dp) {
    val still = reducedMotion()
    val transition = rememberInfiniteTransition(label = "orb")
    val breath by transition.animateFloat(
        initialValue = 0.30f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(tween(2800), RepeatMode.Reverse),
        label = "orb-breath",
    )
    val pulse by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "orb-pulse",
    )
    val coreAlpha = when (tone) {
        StatusTone.Running -> 1f
        StatusTone.Busy -> if (still) 0.7f else pulse
        StatusTone.Idle -> 0.35f
        StatusTone.Error -> 1f
    }
    val rimAlpha = when (tone) {
        StatusTone.Running -> if (still) 0.42f else breath
        StatusTone.Busy -> if (still) 0.45f else pulse * 0.6f
        StatusTone.Idle -> 0.10f
        StatusTone.Error -> 0.45f
    }
    Canvas(modifier) {
        val c = Offset(this.size.width / 2f, this.size.height / 2f)
        val r = this.size.minDimension / 2f
        // 彩色柔光晕
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(color.copy(alpha = rimAlpha), Color.Transparent),
                center = c,
                radius = r * 1.25f,
            ),
            radius = r * 1.25f,
            center = c,
        )
        // 玻璃球体：白→透明的高光层 + 彩色核心层
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.White.copy(alpha = 0.85f), Color.White.copy(alpha = 0.05f)),
                center = Offset(c.x - r * 0.45f, c.y - r * 0.55f),
                radius = r * 1.6f,
            ),
            radius = r * 0.62f,
            center = c,
        )
        // 状态色核心
        drawCircle(color.copy(alpha = coreAlpha), radius = r * 0.40f, center = c)
        // 核心内高光点
        drawCircle(Color.White.copy(alpha = 0.9f * coreAlpha), radius = r * 0.13f, center = Offset(c.x - r * 0.15f, c.y - r * 0.18f))
        // 底部彩色倒影感
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(lerp(color, Color.Black, 0.25f).copy(alpha = 0.35f * coreAlpha), Color.Transparent),
                center = Offset(c.x + r * 0.3f, c.y + r * 0.5f),
                radius = r * 0.9f,
            ),
            radius = r * 0.62f,
            center = c,
        )
    }
}
