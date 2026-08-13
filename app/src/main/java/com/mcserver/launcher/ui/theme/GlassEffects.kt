package com.mcserver.launcher.ui.theme

import android.os.Build
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 液态玻璃效果层：
 * - GlassBackground：漂浮光斑背景（PiliPlus 风格启用；API31+ 真实模糊）
 * - glassSurface：玻璃卡 Modifier（半透明底 + 渐变描边 + 投影）
 * - glassHighlight：卡片左上高光叠层
 */
object GlassEffects {

    /** 玻璃卡表面 */
    fun Modifier.glassSurface(
        tokens: StyleTokens,
        corner: Dp = tokens.cornerMedium,
        elevation: Dp = 10.dp,
    ): Modifier {
        val shape = RoundedCornerShape(corner)
        val base = tokens.surface
        val alpha = if (tokens.glassEnabled) 0.72f else 1f
        var m = this
            .shadow(elevation, shape, clip = false)
            .clip(shape)
            .background(base.copy(alpha = alpha))
        m = if (tokens.glassEnabled) {
            m.border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.55f),
                        Color.White.copy(alpha = 0.08f),
                    ),
                    start = Offset.Zero,
                    end = Offset(900f, 700f),
                ),
                shape = shape,
            )
        } else {
            m.border(1.dp, tokens.outline, shape)
        }
        return m
    }

    /** 玻璃卡左上高光（叠加层） */
    @Composable
    fun HighlightOverlay(tokens: StyleTokens) {
        if (!tokens.glassEnabled) return
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.16f),
                            Color.Transparent,
                            Color.Transparent,
                        ),
                        start = Offset.Zero,
                        end = Offset(700f, 450f),
                    )
                )
        )
    }
}

/** 漂浮光斑背景（缓慢漂移，运行态/静止态颜色由调用方传入 tokens） */
@Composable
fun GlassBackground(
    tokens: StyleTokens,
    modifier: Modifier = Modifier,
) {
    if (tokens.glowColors.isEmpty()) return
    val transition = rememberInfiniteTransition(label = "glow")
    val useBlur = tokens.blurEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val glowModifier = modifier.fillMaxSize()
    Box(glowModifier) {
        tokens.glowColors.forEachIndexed { i, color ->
            val drift by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(26000 + i * 9000, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "glow$i",
            )
            val x = when (i % 3) {
                0 -> (drift * 140 - 40).dp
                1 -> ((1f - drift) * 120 - 30).dp
                else -> (drift * 80 - 60).dp
            }
            val y = when (i % 3) {
                0 -> ((1f - drift) * 160 - 50).dp
                1 -> (drift * 200 - 80).dp
                else -> ((1f - drift) * 120 - 40).dp
            }
            Box(
                Modifier
                    .offset(x = x, y = y)
                    .size(if (i == 1) 300.dp else 240.dp)
                    .then(if (useBlur) Modifier.blur(60.dp) else Modifier)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(color, Color.Transparent),
                            radius = 1400f,
                        )
                    )
            )
        }
    }
}