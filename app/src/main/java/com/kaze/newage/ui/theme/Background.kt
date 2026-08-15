package com.kaze.newage.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.platform.LocalContext
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * 主题背景层。
 * CLEAR=平面渐变；GLASS=纯色渐变底 + 缓慢漂移的柔光斑（用户要求保持纯色，2026-08-15）。
 */

/** 系统「动画时长缩放」为 0 时视为用户要求减弱动画 */
@Composable
fun reducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        }.getOrDefault(false)
    }
}

/** 按当前主题渲染背景 */
@Composable
fun ThemeBackdrop(modifier: Modifier = Modifier) {
    when (LocalAppTheme.current) {
        AppThemeMode.M3 -> ClearBackdrop(modifier)
        AppThemeMode.GLASS -> GlassBackdrop(modifier)
    }
}

/** CLEAR：轻微纵向渐变，保持克制 */
@Composable
private fun ClearBackdrop(modifier: Modifier = Modifier) {
    val dark = LocalDarkTheme.current
    val top = if (dark) Color(0xFF10131B) else Color(0xFFF7F9FC)
    val bottom = if (dark) Color(0xFF0B0E14) else Color(0xFFEEF1F7)
    Box(modifier.background(Brush.verticalGradient(listOf(top, bottom))))
}

/**
 * GLASS 液态玻璃（纯色版）：渐变底 + 缓慢漂移的柔光斑。
 * 深色模式：深空蓝底 + 收敛的霓虹光斑；浅色模式：雾蓝底 + 柔和光斑。
 */
@Composable
private fun GlassBackdrop(modifier: Modifier = Modifier) {
    val dark = LocalDarkTheme.current
    val still = reducedMotion()
    val transition = rememberInfiniteTransition(label = "glass")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(30000, easing = LinearEasing), RepeatMode.Restart),
        label = "glass-phase",
    )
    val p = if (still) 0.4f else phase

    Canvas(modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val m = max(w, h)
        val twoPi = (2 * PI).toFloat()

        // 底色
        drawRect(
            if (dark) {
                Brush.verticalGradient(listOf(Color(0xFF060B19), Color(0xFF0B152B), Color(0xFF0A1224)))
            } else {
                Brush.verticalGradient(listOf(Color(0xFFE9F1FB), Color(0xFFBFD5F1), Color(0xFFDAE7F8)))
            }
        )

        // 柔光斑：缓慢漂移（加法混合，像玻璃背后的漫射光源）
        fun bloom(color: Color, alpha: Float, cx: Float, cy: Float, radius: Float) {
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(color.copy(alpha = alpha), Color.Transparent),
                    center = Offset(cx, cy),
                    radius = radius,
                ),
                size = size,
                blendMode = BlendMode.Screen,
            )
        }
        if (dark) {
            // 深色：光斑收敛、留出大面积深底（玻璃在深底上透光才明显）
            bloom(Color(0xFF4C7DFF), 0.38f, w * (0.20f + 0.20f * sin(twoPi * p)), h * (0.26f + 0.12f * sin(twoPi * p * 1.2f + 1.2f)), m * 0.72f)
            bloom(Color(0xFF9C6BFF), 0.28f, w * (0.68f + 0.16f * sin(twoPi * p * 0.8f + 2.4f)), h * (0.55f + 0.14f * sin(twoPi * p * 1.1f + 0.5f)), m * 0.62f)
            bloom(Color(0xFF2FD4E0), 0.22f, w * (0.42f + 0.22f * sin(twoPi * p * 1.3f + 4.1f)), h * (0.74f + 0.10f * sin(twoPi * p * 0.9f + 5.0f)), m * 0.55f)
            bloom(Color.White, 0.16f, w * 0.16f, h * 0.10f, m * 0.38f)
        } else {
            // 浅色：雾蓝底 + 柔光斑
            bloom(Color(0xFF7FADFF), 0.85f, w * (0.20f + 0.20f * sin(twoPi * p)), h * (0.26f + 0.12f * sin(twoPi * p * 1.2f + 1.2f)), m * 0.95f)
            bloom(Color.White, 0.80f, w * (0.68f + 0.16f * sin(twoPi * p * 0.8f + 2.4f)), h * (0.55f + 0.14f * sin(twoPi * p * 1.1f + 0.5f)), m * 0.85f)
            bloom(Color(0xFFFFB2DE), 0.60f, w * (0.44f + 0.22f * sin(twoPi * p * 1.3f + 4.1f)), h * (0.75f + 0.12f * sin(twoPi * p * 0.9f + 5.0f)), m * 0.75f)
            bloom(Color.White, 0.90f, w * 0.16f, h * 0.10f, m * 0.45f)
        }
    }
}
