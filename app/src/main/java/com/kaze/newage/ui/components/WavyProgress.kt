package com.kaze.newage.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Locale
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate

/*
 * ══════════════════════════════════════════════════════════════════════════════
 * 线性进度条（直线 + 末端百分比）
 *
 * 历史：这里原本是 M3 Expressive 的**波浪形**进度条（wavy linear progress）。
 * 波浪在真机上有两个问题，用户要求全部改成直线：
 *  1. 波峰让实际占位比轨道高（10dp 容器画 4dp 线），与相邻控件基线对不齐；
 *  2. 进度条的用途是"还要多久"，波浪的流动反而让读数变难 ——
 *     尤其在下拉/滚动时，波浪相位一直在动，看起来像卡住在转。
 *
 * 现在统一：直线轨道 + 直线指示段 + **末端百分比文字**。
 * 函数名保留 `WavyLinearProgress` 是为了不动 20 多个调用点；
 * `wavy` 参数也保留（传 true 仍是老的波浪实现），但默认已是 false。
 * ══════════════════════════════════════════════════════════════════════════════
 */

/** 轨道粗细（直线模式） */
private val TRACK_THICKNESS = 4.dp

/** 百分比文字与轨道之间的间距 */
private val PERCENT_GAP = 8.dp

/** 百分比文字的宽度下限：给 `100%` 留够，避免进度到 100 时文字挤动轨道 */
private val PERCENT_MIN_WIDTH = 40.dp

/**
 * 确定态/不定态进度条。
 *
 * @param progress 0..1；**null = 不定态**（此时不显示百分比，指示段来回跑）
 * @param wavy 是否用旧的波浪样式（默认 false = 直线）
 * @param showPercent 是否在末端显示百分比（默认 true；不定态下自动不显示）
 * @param animated 旧参数，保留兼容；直线模式下只影响不定态的扫动
 */
@Composable
fun WavyLinearProgress(
    modifier: Modifier = Modifier,
    progress: Float?,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    wavy: Boolean = false,
    animated: Boolean = true,
    /**
     * 是否在末端显示百分比数字。
     *
     * 默认 true。个别"步骤指示"式的场景（progress 只用来表示第几步）关掉更干净，
     * 但绝大多数调用是真实的下载/解压进度，用户明确要求看到百分比。
     */
    showPercent: Boolean = true,
) {
    val fraction = progress?.coerceIn(0f, 1f)
    val showText = showPercent && fraction != null
    // 不定态相位必须在**组合期**取：Canvas 的 DrawScope 不是 @Composable 上下文。
    // 确定态不需要，但 Compose 不允许条件调用 @Composable，这里总是取一次
    // （rememberInfiniteTransition 在 progress != null 时不影响视觉，只多一个动画对象；
    // 为了省这点开销而让「确定态/不定态」走两条组合分支反而更容易出错）。
    val sweep = if (fraction == null) indeterminatePhase() else 0f

    if (!wavy) {
        // ── 直线模式（默认）──
        Row(
            modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PERCENT_GAP),
        ) {
            Canvas(
                Modifier
                    .weight(1f)
                    .height(TRACK_THICKNESS)
            ) {
                val thickness = TRACK_THICKNESS.toPx()
                val cy = size.height / 2f
                drawLine(
                    trackColor,
                    Offset(0f, cy),
                    Offset(size.width, cy),
                    thickness,
                    StrokeCap.Round,
                )
                val f = fraction
                if (f != null) {
                    if (f > 0f) {
                        val r = thickness / 2f
                        drawLine(
                            color,
                            Offset(r, cy),
                            Offset((size.width * f).coerceAtLeast(r), cy),
                            thickness,
                            StrokeCap.Round,
                        )
                    }
                } else {
                    // 不定态：一段固定宽度的指示块来回扫（不显示百分比）
                    val segW = size.width * 0.3f
                    val start = ((size.width - segW) * sweep).coerceAtLeast(0f)
                    drawLine(
                        color,
                        Offset(start + thickness / 2f, cy),
                        Offset(start + segW - thickness / 2f, cy),
                        thickness,
                        StrokeCap.Round,
                    )
                }
            }
            if (showText) {
                Text(
                    // 用 Locale.US：RTL 语言下百分比数字不该被反转
                    String.format(Locale.US, "%d%%", (fraction * 100f).toInt()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.widthIn(min = PERCENT_MIN_WIDTH),
                )
            }
        }
        return
    }

    // ── 旧的波浪模式（wavy = true 才走）──
    WavyProgressLegacy(
        modifier = modifier,
        progress = progress,
        color = color,
        trackColor = trackColor,
        animated = animated,
        showPercent = showPercent,
    )
}

/** 不定态相位：0..1 来回。只在 progress == null 时被读到。 */
@Composable
private fun indeterminatePhase(): Float {
    val t = rememberInfiniteTransition(label = "indeterminate")
    val v by t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(1200, easing = LinearEasing),
            RepeatMode.Reverse,
        ),
        label = "indeterminate-phase",
    )
    return v
}

/** 便捷重载：Int 百分比 */
@Composable
fun WavyLinearProgress(
    progressPercent: Int,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) = WavyLinearProgress(modifier, progressPercent / 100f, color)

/** 轨道高度：给外部对齐用。直线模式下就是轨道粗细。 */
internal val WavyProgressHeight: Dp = TRACK_THICKNESS


// ══════════════════════════════════════════════════════════════════════════════
// 旧的波浪实现（仅 wavy = true 时使用）
// 保留它是因为：个别截图/主题对照仍需要能切回波浪样式做 A/B；
// 默认路径已经不走这里。
// ══════════════════════════════════════════════════════════════════════════════

private val WAVE_TRACK_THICKNESS = 4.dp
private val WAVE_CONTAINER_HEIGHT = 10.dp
private val WAVE_DETERMINATE_WAVELENGTH = 40.dp
private val WAVE_INDETERMINATE_WAVELENGTH = 20.dp
private const val WAVE_PERIOD_MS = 1000

@Composable
private fun WavyProgressLegacy(
    modifier: Modifier,
    progress: Float?,
    color: Color,
    trackColor: Color,
    animated: Boolean,
    showPercent: Boolean,
) {
    val fraction = progress?.coerceIn(0f, 1f)
    val p = wavePhase(animated, progress == null)
    val sweep = waveSweep(animated, progress == null)
    val lambda = if (progress == null) WAVE_INDETERMINATE_WAVELENGTH else WAVE_DETERMINATE_WAVELENGTH

    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PERCENT_GAP),
    ) {
        Canvas(
            Modifier
                .weight(1f)
                .height(WAVE_CONTAINER_HEIGHT)
        ) {
            val thickness = WAVE_TRACK_THICKNESS.toPx()
            val containerH = WAVE_CONTAINER_HEIGHT.toPx()
            val lam = lambda.toPx()
            val f = fraction ?: sweep.coerceIn(0f, 1f)

            drawWavePath(trackColor, size.width, containerH, thickness, lam, p)
            if (f > 0f) {
                val activeW = size.width * f
                drawWavePath(color, activeW, containerH, thickness, lam, p)
                if (progress != null) {
                    drawCircle(
                        color,
                        radius = thickness / 2f,
                        center = Offset(
                            (activeW - thickness / 2f).coerceAtLeast(thickness / 2f),
                            containerH / 2f,
                        ),
                    )
                }
            }
        }
        if (showPercent && fraction != null) {
            Text(
                String.format(Locale.US, "%d%%", (fraction * 100f).toInt()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.widthIn(min = PERCENT_MIN_WIDTH),
            )
        }
    }
}

@Composable
private fun wavePhase(animated: Boolean, indeterminate: Boolean): Float {
    val t = rememberInfiniteTransition(label = "wave-phase")
    val v by t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(
                WAVE_PERIOD_MS,
                easing = LinearEasing,
            ),
            RepeatMode.Restart,
        ),
        label = "wave-phase-v",
    )
    return if (animated) v else 0f
}

@Composable
private fun waveSweep(animated: Boolean, indeterminate: Boolean): Float {
    val t = rememberInfiniteTransition(label = "wave-sweep")
    val v by t.animateFloat(
        initialValue = 0.06f,
        targetValue = 0.94f,
        animationSpec = infiniteRepeatable(
            tween(WAVE_PERIOD_MS * 3),
            RepeatMode.Reverse,
        ),
        label = "wave-sweep-v",
    )
    return if (animated) v else 0.5f
}

/** 画一段波浪：每 λ/2 一个锚点，控制点在中点、高度 `containerH - thickness`，符号交替。 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawWavePath(
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
    val path = androidx.compose.ui.graphics.Path()
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
        drawPath(path, color, style = Stroke(
            width = thickness,
            cap = StrokeCap.Round,
        ))
    }
}
