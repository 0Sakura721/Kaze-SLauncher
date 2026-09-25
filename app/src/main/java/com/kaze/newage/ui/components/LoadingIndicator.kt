package com.kaze.newage.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kaze.newage.ui.theme.reducedMotion
import com.kaze.newage.ui.theme.uiIsResumed
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * ══════════════════════════════════════════════════════════════════════════════
 * Material 3 Expressive 加载指示器（形状变化版）—— 官方的「会变形的」那种，
 * 不是圆环转圈。
 *
 * 官方参数（androidx tokens 与 material-components-android）：
 *  - 形状 38dp、容器 48dp（形状占容器 38/48 ≈ 0.79 左右，这里取 0.80 让边缘更饱满）
 *  - 每 650ms 变一次形状，7 个形状一循环（4500ms）
 *  - 变形由弹簧驱动：dampingRatio 0.6、stiffness 200（会轻微过冲）
 *  - 旋转 = (50°+90°)×已完成形数 + 50°×本形线性进度 + 90°×本形弹簧进度
 *
 * 本应用把它当作「服务器生命体征」：运行中常速旋转，启动中加速，
 * 停止时定格成单个形状（[ExpressiveLoadingGlyph]）。
 *
 * 实现上刻意只用**一个** `rememberInfiniteTransition` 驱动全部动画：
 * 手写 `withFrameMillis` 循环会永远请求下一帧，Compose 测试的 waitForIdle
 * 因此永远等不到空闲（Robolectric 截图测试会直接超时）。
 * ══════════════════════════════════════════════════════════════════════════════
 */

/** 变形间隔：官方 MorphIntervalMillis */
private const val MORPH_INTERVAL_MS = 650
/** 一个完整循环：7 × 650ms */
private const val CYCLE_MS = MORPH_INTERVAL_MS * 7
/** 每形固定旋转：官方 CONSTANT_ROTATION_PER_SHAPE_DEGREES */
private const val CONSTANT_ROTATION_DEG = 50f
/** 每形额外旋转（由弹簧过冲驱动）：官方 EXTRA_ROTATION_PER_SHAPE_DEGREES */
private const val EXTRA_ROTATION_DEG = 90f
/** 形状尺寸占容器尺寸的比例：官方 38/48 */
private const val SHAPE_SCALE = 0.80f

/** 弹簧：官方 morph 用 dampingRatio 0.6 + stiffness 200（质量 1） */
private const val SPRING_ZETA = 0.6f
private const val SPRING_STIFFNESS = 200f

/**
 * 弹簧阶跃响应（欠阻尼），与官方 `SpringForce` 的到位过程一致：
 * `1 - e^(-ζωt)·(cos(ωd·t) + ζω/ωd·sin(ωd·t))`，ζ=0.6 时峰值过冲约 9%。
 * 解析式而不是 `Animatable`：形状序号是「跳变 + 回落」的循环，
 * 用有限动画去追一个不断跳变的目标既不准，也会让测试等不到空闲。
 */
private fun springStep(tMs: Float): Float {
    val zeta = SPRING_ZETA
    val omega = sqrt(SPRING_STIFFNESS)
    val t = tMs / 1000f
    val damped = omega * sqrt(1f - zeta * zeta)
    val e = exp(-zeta * omega * t)
    return 1f - e * (cos(damped * t) + (zeta * omega / damped) * sin(damped * t))
}

/**
 * 形状变化加载指示器。
 *
 * @param speed 变形与旋转的速度倍率。1 = 官方速度，启动中可给 1.8 表示「正在忙」
 * @param phaseOffsetMs 同屏多个指示器的相位错开（0..[CYCLE_MS]）
 */
@Composable
fun ExpressiveLoadingIndicator(
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    color: Color = Color.Unspecified,
    speed: Float = 1f,
    phaseOffsetMs: Int = 0,
) {
    val resolved = if (color == Color.Unspecified) MaterialTheme.colorScheme.primary else color
    // 后台不跑无限动画：本应用常驻前台服务，界面可能在后台待很久，动画时钟纯耗电。
    // 减弱动画（系统「动画时长缩放=0」）时同样定格。
    val still = reducedMotion() || !uiIsResumed()
    val speedSafe = speed.coerceIn(0.25f, 4f)

    // 一个循环的进度 0..1；形状序号与形内进度都从它派生
    val cycle = rememberInfiniteTransition(label = "expressive-loading")
    val cycleValue by cycle.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = (CYCLE_MS / speedSafe).toInt(),
                easing = LinearEasing,
            ),
            repeatMode = RepeatMode.Restart,
        ),
        label = "loading-cycle",
    )

    val progress = if (still) 0f else cycleValue
    // 形内进度加上相位偏移（取模保证落在 [0,1)）
    val withPhase = (progress + phaseOffsetMs.toFloat() / CYCLE_MS) % 1f
    val position = withPhase * 7f
    val shapeIndex = position.toInt().coerceIn(0, 6)
    val inShape = position - shapeIndex

    // 变形进度：整数部分选形状对，小数部分是弹簧回落后的 t
    val morph = shapeIndex + springStep(inShape * MORPH_INTERVAL_MS)

    // 旋转：每形 (50 + 90)°，其中 90° 由弹簧进度给出。因为一个循环正好 7 × 140° = 980°，
    // 980 mod 360 = 260 ≠ 0，会「跳」一下；官方靠全局连续旋转补掉这个差，
    // 这里等价地再叠一个线性补偿，使整圈匀速、跨形不跳。
    val springEase = springStep(inShape * MORPH_INTERVAL_MS)
    val rotation = (
        (CONSTANT_ROTATION_DEG + EXTRA_ROTATION_DEG) * shapeIndex +
            CONSTANT_ROTATION_DEG * inShape +
            EXTRA_ROTATION_DEG * springEase +
            260f * withPhase
        ) % 360f

    val shape = remember(morph) { morphedShape(morph) }

    Canvas(modifier.size(size)) {
        val r = this.size.minDimension / 2f * SHAPE_SCALE
        val path = shape.toPath(this.size.width / 2f, this.size.height / 2f, r)
        rotate(rotation) { drawPath(path, resolved) }
    }
}

/**
 * 单个形状的静止造型：用于「已停止」「空闲」这类不需要动画的状态。
 * 与指示器共用同一套形状几何，所以两者在一屏里始终是同一个视觉体系。
 */
@Composable
fun ExpressiveLoadingGlyph(
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    color: Color = Color.Unspecified,
    shapeIndex: Int = 0,
) {
    val resolved = if (color == Color.Unspecified) MaterialTheme.colorScheme.primary else color
    val shape = remember(shapeIndex) { morphedShape(shapeIndex.toFloat()) }
    Canvas(modifier.size(size)) {
        val r = this.size.minDimension / 2f * SHAPE_SCALE
        val path = shape.toPath(this.size.width / 2f, this.size.height / 2f, r)
        drawPath(path, resolved)
    }
}

/**
 * 细描边版本：给表格行、按钮内这类小位置用。
 * 几何与 [ExpressiveLoadingIndicator] 完全相同，只是换成描边。
 */
@Composable
fun ExpressiveLoadingRing(
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    color: Color = Color.Unspecified,
    strokeWidth: Dp = 2.dp,
    speed: Float = 1f,
) {
    val resolved = if (color == Color.Unspecified) MaterialTheme.colorScheme.primary else color
    val still = reducedMotion() || !uiIsResumed()
    val speedSafe = speed.coerceIn(0.25f, 4f)

    val cycle = rememberInfiniteTransition(label = "expressive-ring")
    val cycleValue by cycle.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = (CYCLE_MS / speedSafe).toInt(),
                easing = LinearEasing,
            ),
            repeatMode = RepeatMode.Restart,
        ),
        label = "ring-cycle",
    )

    val progress = if (still) 0f else cycleValue
    val position = progress * 7f
    val shapeIndex = position.toInt().coerceIn(0, 6)
    val inShape = position - shapeIndex
    val morph = shapeIndex + springStep(inShape * MORPH_INTERVAL_MS)
    val rotation = ((CONSTANT_ROTATION_DEG + EXTRA_ROTATION_DEG) * shapeIndex +
        CONSTANT_ROTATION_DEG * inShape +
        EXTRA_ROTATION_DEG * springStep(inShape * MORPH_INTERVAL_MS) +
        260f * progress) % 360f

    val shape = remember(morph) { morphedShape(morph) }

    Canvas(modifier.size(size)) {
        val r = this.size.minDimension / 2f * SHAPE_SCALE
        val strokePx = strokeWidth.toPx()
        val path = shape.toPath(this.size.width / 2f, this.size.height / 2f, r)
        rotate(rotation) { drawPath(path, resolved, style = Stroke(width = strokePx)) }
    }
}

/**
 * 轮廓点 → 闭合 Path。
 * 归一化轮廓落在 [-1,1] 里，这里一次性把它缩放 [r] 倍并平移到 ([cx],[cy])，
 * 于是绘制时不再需要 translate/scale 作用域，路径本身就在正确的位置上。
 */
private fun List<Offset>.toPath(cx: Float, cy: Float, r: Float): Path {
    val p = Path()
    forEachIndexed { i, pt ->
        val x = cx + pt.x * r
        val y = cy + pt.y * r
        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    p.close()
    return p
}

/** 官方的 7 形顺序（索引与 [morphedShape] 的整数部分一致） */
internal val LOADING_SHAPE_NAMES =
    listOf("SoftBurst", "Cookie9", "Pentagon", "Pill", "Sunny", "Cookie4", "Oval")

/** 一个循环的毫秒数（提示用） */
internal const val LOADING_CYCLE_MS: Int = CYCLE_MS
