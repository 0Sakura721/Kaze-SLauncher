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
 * - GlassBackground：漂浮光斑背景（液态风格启用；API31+ 真实模糊）
 * - glassSurface：玻璃卡 Modifier（半透明底 + 渐变描边 + 投影）
 * - glassHighlight：卡片左上高光叠层
 */
object GlassEffects {

    /** 玻璃卡表面（普通成员函数，外部以 GlassEffects.glassSurface(modifier, ...) 调用） */
    fun glassSurface(
        modifier: Modifier,
        tokens: StyleTokens,
        corner: Dp = tokens.cornerMedium,
        elevation: Dp = 10.dp,
    ): Modifier {
        val shape = RoundedCornerShape(corner)
        val base = tokens.surface
        // 液态玻璃：半透明底 + 上亮下暗描边 + 投影
        val alpha = if (tokens.glassEnabled) 0.62f else 1f
        var m = modifier
            .shadow(elevation, shape, clip = false)
            .clip(shape)
            .background(base.copy(alpha = alpha))
        m = if (tokens.glassEnabled) {
            m.border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.72f),
                        Color.White.copy(alpha = 0.16f),
                        Color.White.copy(alpha = 0.04f),
                    ),
                    start = Offset.Zero,
                    end = Offset(0f, 420f),   // 竖直渐变：顶部受光最亮
                ),
                shape = shape,
            )
        } else {
            m.border(1.dp, tokens.outline, shape)
        }
        return m
    }

    /** 玻璃卡左上高光（叠加层：斜向主光 + 右上柔光斑） */
    @Composable
    fun HighlightOverlay(tokens: StyleTokens) {
        if (!tokens.glassEnabled) return
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.22f),
                            Color.White.copy(alpha = 0.05f),
                            Color.Transparent,
                            Color.Transparent,
                        ),
                        start = Offset.Zero,
                        end = Offset(720f, 420f),
                    )
                )
        )
        // 右上柔光斑（bilipai 式玻璃受光点）
        Box(
            Modifier
                .align(androidx.compose.ui.Alignment.TopEnd)
                .size(120.dp)
                .offset(x = 36.dp, y = (-46).dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color.White.copy(alpha = 0.18f), Color.Transparent),
                        radius = 300f,
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
        // 高亮核心：快速漂移的小光点，模拟液态表面的受光折射
        val fast by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(7000, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "glowFast",
        )
        Box(
            Modifier
                .offset(x = (fast * 200 - 100).dp, y = ((1f - fast) * 260 - 60).dp)
                .size(150.dp)
                .then(if (useBlur) Modifier.blur(36.dp) else Modifier)
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color.White.copy(alpha = 0.55f), Color.Transparent),
                        radius = 700f,
                    )
                )
        )
    }
}