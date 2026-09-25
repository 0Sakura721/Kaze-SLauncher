package com.kaze.newage.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kaze.newage.ui.theme.M3Motion
import com.kaze.newage.ui.theme.reducedMotion
import com.kaze.newage.ui.theme.uiIsResumed

/*
 * ══════════════════════════════════════════════════════════════════════════════
 * M3 Expressive 波浪形进度条（wavy linear progress indicator）
 *
 * 官方参数（LinearProgressIndicatorTokens）：
 *  - 轨道 / 指示器厚度 4dp，圆头端点
 *  - 容器高度 10dp（经典版是 4dp）
 *  - 波幅 3dp（= 容器高 10 − 厚度 4 的一半）
 *  - 波长：确定态 40dp、不定态 20dp
 *  - 波浪相位速度：1 秒移动一个波长
 *
 * 波形不是 sin 曲线，而是每半个波长一个锚点、控制点在锚点中点的二次贝塞尔
 * ——官方就是这么画的，用 sin 会得到不同的观感。
 * ══════════════════════════════════════════════════════════════════════════════
 */

private val TRACK_THICKNESS = 4.dp
private val CONTAINER_HEIGHT = 10.dp
private val DETERMINATE_WAVELENGTH = 40.dp
private val INDETERMINATE_WAVELENGTH = 20.dp
/** 相位速度：一个波长 / 秒 */
private const val WAVE_PERIOD_MS = 1000

/** 确定态波浪进度条。[progress] 为 0..1，null 表示不定态（来回跑）。 */
@Composable
fun WavyLinearProgress(
    modifier: Modifier = Modifier,
    progress: Float?,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    /** 关闭波浪，退回官方经典样式（4dp 直线） */
    wavy: Boolean = true,
) {
    val animating = !reducedMotion() && uiIsResumed()
    val transition = rememberInfiniteTransition(label = "wavy-progress")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(WAVE_PERIOD_MS, easing = LinearEasing),
            RepeatMode.Restart,
        ),
        label = "wavy-phase",
    )

    // 不定态：指示段来回生长/收缩（官方的不定态是四段关键帧；这里用一个等价的
    // 往返生长，观感一致但少一份关键帧表 —— 本应用的进度基本都是确定态的）
    val indeterminatePhase by transition.animateFloat(
        initialValue = 0.06f,
        targetValue = 0.94f,
        animationSpec = infiniteRepeatable(
            tween(WAVE_PERIOD_MS * 3, easing = M3Motion.emphasized),
            RepeatMode.Reverse,
        ),
        label = "wavy-sweep",
    )

    val wavelength = if (progress == null) INDETERMINATE_WAVELENGTH else DETERMINATE_WAVELENGTH
    val p = if (animating) phase else 0f
    val sweep = if (animating) indeterminatePhase else 0.5f

    Canvas(
        modifier
            .fillMaxWidth()
            .height(if (wavy) CONTAINER_HEIGHT else TRACK_THICKNESS)
    ) {
        val thickness = TRACK_THICKNESS.toPx()
        val containerH = if (wavy) CONTAINER_HEIGHT.toPx() else thickness
        val lambda = wavelength.toPx()

        val fraction = progress?.coerceIn(0f, 1f) ?: sweep.coerceIn(0f, 1f)

        if (!wavy) {
            // 经典直线版：轨道 + 指示条
            drawLine(
                trackColor,
                Offset(0f, containerH / 2f),
                Offset(size.width, containerH / 2f),
                thickness,
                StrokeCap.Round,
            )
            if (fraction > 0f) {
                val r = thickness / 2f
                drawLine(
                    color,
                    Offset(r, containerH / 2f),
                    Offset((size.width * fraction).coerceAtLeast(r), containerH / 2f),
                    thickness,
                    StrokeCap.Round,
                )
            }
            return@Canvas
        }

        // 轨道：同一套波形，只是换颜色
        drawWave(
            color = trackColor,
            widthPx = size.width,
            containerH = containerH,
            thickness = thickness,
            lambda = lambda,
            phase = p,
        )
        if (fraction > 0f) {
            val activeW = size.width * fraction
            drawWave(
                color = color,
                widthPx = activeW,
                containerH = containerH,
                thickness = thickness,
                lambda = lambda,
                phase = p,
            )
            // 端点的 stop 圆点（官方 StopSize 4dp）
            if (progress != null) {
                drawCircle(
                    color,
                    radius = thickness / 2f,
                    center = Offset((activeW - thickness / 2f).coerceAtLeast(thickness / 2f), containerH / 2f),
                )
            }
        }
    }
}

/**
 * 画一段波浪：官方做法是每 λ/2 一个 y=0 的锚点，控制点落在两个锚点的中点、
 * 高度为 `containerH - thickness`，符号交替 —— 于是峰值正好是
 * (containerH − thickness) / 2 = 3dp。
 */
private fun DrawScope.drawWave(
    color: Color,
    widthPx: Float,
    containerH: Float,
    thickness: Float,
    lambda: Float,
    phase: Float,
) {
    if (widthPx <= 0f) return
    val half = lambda / 2f
    val amplitude = (containerH - thickness) / 2f
    val path = Path()
    // 从屏幕外半个波长开始，相位平移时左右都不会露白
    val startX = -lambda
    val endX = widthPx + lambda
    val shift = -phase * lambda
    var x = startX
    var up = true
    path.moveTo(x + shift, 0f)
    while (x < endX) {
        val controlX = x + half / 2f + shift
        val controlY = if (up) -amplitude * 2f else amplitude * 2f
        val nextX = x + half + shift
        path.quadraticTo(controlX, controlY, nextX, 0f)
        x += half
        up = !up
    }
    translate(0f, containerH / 2f) {
        drawPath(path, color, style = Stroke(width = thickness, cap = StrokeCap.Round))
    }
}

/** 便捷重载：Int 百分比 */
@Composable
fun WavyLinearProgress(
    progressPercent: Int,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) = WavyLinearProgress(modifier, progressPercent / 100f, color)

/** 轨道高度：给外部对齐用 */
internal val WavyProgressHeight: Dp = CONTAINER_HEIGHT
