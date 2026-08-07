package com.mcserver.launcher.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 按压反馈:按下时轻微缩小,松手回弹(弹簧动画)。
 * 与 ripple 叠加,让按钮/卡片"按得下去"的手感更明显。
 */
@Composable
fun Modifier.pressScale(interactionSource: MutableInteractionSource): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "pressScale"
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * 便捷创建按压效果:(Modifier, MutableInteractionSource)
 * 与 Material 组件的 interactionSource 参数配合:
 *   val (press, source) = pressSource()
 *   Button(onClick = ..., interactionSource = source, modifier = press) { ... }
 */
@Composable
fun pressSource(): Pair<Modifier, MutableInteractionSource> {
    val source = remember { MutableInteractionSource() }
    return Modifier.pressScale(source) to source
}

/**
 * 页面切换过渡:横向滑动 + 淡入淡出(用于 Tab 切换与详情页导航)。
 * 返回 AnimatedContent 的目标状态。
 */
@Composable
fun <T> PageTransition(
    targetState: T,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit
) {
    AnimatedContent(
        targetState = targetState,
        modifier = modifier,
        transitionSpec = {
            val direction = if (targetState.hashCode() > initialState.hashCode()) 1 else -1
            (slideInHorizontally(initialOffsetX = { it / 8 * direction }) + fadeIn())
                .togetherWith(slideOutHorizontally(targetOffsetX = { -it / 8 * direction }) + fadeOut())
        },
        label = "pageTransition"
    ) { state -> content(state) }
}

// ═══════════════════════════════════════════════════════════
//  游戏风可视化组件:弧形仪表盘 / 资源环 / 脉冲光圈
// ═══════════════════════════════════════════════════════════

/**
 * 弧形仪表盘:半圆弧进度条,用于首页顶部展示服务器状态。
 * @param progress 0f..1f
 * @param gradient 渐变色刷
 * @param backgroundColor 底层轨道色
 * @param strokeWidth 弧线宽度
 */
@Composable
fun ArcDashboard(
    progress: Float,
    modifier: Modifier = Modifier,
    gradient: Brush = Brush.sweepGradient(
        listOf(Color(0xFF4FC3F7), Color(0xFF26C6DA), Color(0xFF80CBC4), Color(0xFF4FC3F7))
    ),
    backgroundColor: Color = Color(0xFF1E2A2E),
    strokeWidth: Dp = 12.dp
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
        label = "arcProgress"
    )
    Canvas(modifier) {
        val sw = strokeWidth.toPx()
        val diameter = minOf(size.width, size.height * 2) - sw
        val topLeft = Offset((size.width - diameter) / 2f, size.height - diameter / 2f)
        val arcSize = Size(diameter, diameter)

        // 底层轨道(半圆)
        drawArc(
            color = backgroundColor,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = sw, cap = StrokeCap.Round)
        )
        // 进度弧(半圆,带渐变)
        if (animatedProgress > 0.001f) {
            drawArc(
                brush = gradient,
                startAngle = 180f,
                sweepAngle = 180f * animatedProgress,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = sw, cap = StrokeCap.Round)
            )
        }
    }
}

/**
 * 资源环:圆形进度环,用于展示 CPU/内存等指标。
 * @param progress 0f..1f
 * @param color 进度色
 * @param trackColor 底层轨道色
 * @param strokeWidth 环宽
 */
@Composable
fun ResourceRing(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = Color(0xFF4FC3F7),
    trackColor: Color = Color(0xFF1E2A2E),
    strokeWidth: Dp = 6.dp
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 800),
        label = "ringProgress"
    )
    Canvas(modifier) {
        val sw = strokeWidth.toPx()
        val diameter = minOf(size.width, size.height) - sw
        val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
        val arcSize = Size(diameter, diameter)

        drawArc(
            color = trackColor,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = sw, cap = StrokeCap.Round)
        )
        if (animatedProgress > 0.001f) {
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 360f * animatedProgress,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = sw, cap = StrokeCap.Round)
            )
        }
    }
}

/**
 * 脉冲光圈:呼吸式扩散动画,用于运行中服务器的状态指示。
 * @param color 光圈色
 * @param maxRadius 最大扩散半径比例(相对于组件尺寸)
 */
@Composable
fun PulseGlow(
    modifier: Modifier = Modifier,
    color: Color = Color(0xFF4FC3F7),
    active: Boolean = true
) {
    if (!active) return
    val transition = rememberInfiniteTransition(label = "pulse")
    val pulse by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )
    Canvas(modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val maxR = minOf(size.width, size.height) / 2f
        // 外层光圈
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(color.copy(alpha = pulse * 0.4f), color.copy(alpha = 0f)),
                center = center,
                radius = maxR
            ),
            center = center,
            radius = maxR
        )
    }
}
