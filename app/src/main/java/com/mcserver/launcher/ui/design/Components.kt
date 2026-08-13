package com.mcserver.launcher.ui.design

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcserver.launcher.data.ServerState
import com.mcserver.launcher.ui.theme.GlassEffects
import com.mcserver.launcher.ui.theme.LocalKazeTokens
import com.mcserver.launcher.ui.theme.StyleTokens

// ─────────────────────────── 玻璃卡 ───────────────────────────

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    corner: Dp? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val tokens = LocalKazeTokens.current
    var m = modifier.glassSurfaceLocal(tokens, corner ?: tokens.cornerMedium)
    if (onClick != null) {
        m = m.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
        ) { onClick() }
    }
    Box(m) {
        content()
        GlassEffects.HighlightOverlay(tokens)
    }
}

private fun Modifier.glassSurfaceLocal(tokens: StyleTokens, corner: Dp, elevation: Dp = 10.dp): Modifier =
    GlassEffects.glassSurface(this, tokens, corner, elevation)

// ─────────────────────────── 渐变按钮 ───────────────────────────

@Composable
fun GradientButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val tokens = LocalKazeTokens.current
    val colors = if (enabled) {
        listOf(tokens.primary, tokens.secondary)
    } else {
        listOf(tokens.outline, tokens.outline)
    }
    Box(
        modifier
            .clip(RoundedCornerShape(tokens.cornerMedium))
            .background(Brush.linearGradient(colors))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 22.dp, vertical = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
        )
    }
}

// ─────────────────────────── 弧形仪表环 ───────────────────────────

@Composable
fun MetricRing(
    value: Float,          // 0..1 归一化
    label: String,
    valueText: String,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 92.dp,
    strokeWidth: Dp = 9.dp,
) {
    val tokens = LocalKazeTokens.current
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val stroke = Stroke(strokeWidth.toPx(), cap = StrokeCap.Round)
            // 底环
            drawArc(
                color = tokens.outline.copy(alpha = 0.5f),
                startAngle = 135f, sweepAngle = 270f,
                useCenter = false, style = stroke,
            )
            // 值环
            drawArc(
                brush = Brush.sweepGradient(listOf(color.copy(alpha = 0.7f), color)),
                startAngle = 135f,
                sweepAngle = 270f * value.coerceIn(0f, 1f),
                useCenter = false,
                style = stroke,
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                valueText,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = tokens.onSurface,
                maxLines = 1,
            )
            Text(
                label,
                fontSize = 10.sp,
                color = tokens.onSurface.copy(alpha = 0.6f),
            )
        }
    }
}

// ─────────────────────────── 状态呼吸灯 ───────────────────────────

@Composable
fun StatusDot(state: ServerState, modifier: Modifier = Modifier, size: Dp = 10.dp) {
    val tokens = LocalKazeTokens.current
    val color = when (state) {
        is ServerState.Running -> tokens.accent
        is ServerState.Starting -> tokens.primary
        is ServerState.Stopping -> tokens.secondary
        is ServerState.Crashed -> Color(0xFFE53935)
        else -> tokens.onSurface.copy(alpha = 0.35f)
    }
    val active = state is ServerState.Running || state is ServerState.Starting
    val transition = rememberInfiniteTransition(label = "dot")
    val alpha by transition.animateFloat(
        initialValue = 1f, targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
        label = "dotAlpha",
    )
    val scale by transition.animateFloat(
        initialValue = 1f, targetValue = 0.8f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
        label = "dotScale",
    )
    Box(
        modifier
            .size(size)
            .scale(if (active) scale else 1f)
            .clip(CircleShape)
            .background(color.copy(alpha = if (active) alpha else 1f))
    )
}

// ─────────────────────────── 贯穿式液体胶囊 ───────────────────────────

@Composable
fun LiquidCapsule(
    title: String,
    subtitle: String,
    state: ServerState,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalKazeTokens.current
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(
                Brush.linearGradient(
                    listOf(
                        tokens.primary.copy(alpha = 0.18f),
                        tokens.secondary.copy(alpha = 0.18f),
                        tokens.accent.copy(alpha = 0.10f),
                    )
                )
            )
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(state, size = 12.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = tokens.onSurface)
            Text(subtitle, fontSize = 12.sp, color = tokens.onSurface.copy(alpha = 0.6f))
        }
    }
}

// ─────────────────────────── 分段滑轨（内存档位） ───────────────────────────

@Composable
fun SegmentRail(
    options: List<Pair<String, Int>>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalKazeTokens.current
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(tokens.cornerSmall))
            .background(tokens.surfaceVariant)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (label, value) ->
            val isSel = value == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(tokens.cornerSmall - 4.dp))
                    .background(
                        if (isSel) Brush.linearGradient(listOf(tokens.primary, tokens.secondary))
                        else Color.Transparent
                    )
                    .clickable { onSelect(value) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    fontSize = 13.sp,
                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSel) Color.White else tokens.onSurface.copy(alpha = 0.7f),
                )
            }
        }
    }
}

// ─────────────────────────── 竖向胶囊选择条（核心类型） ───────────────────────────

@Composable
fun SideRail(
    options: List<Pair<String, String>>,   // label -> key
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalKazeTokens.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (label, key) ->
            val isSel = key == selected
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (isSel) Brush.linearGradient(listOf(tokens.primary, tokens.secondary))
                        else tokens.surfaceVariant
                    )
                    .clickable { onSelect(key) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    fontSize = 13.sp,
                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSel) Color.White else tokens.onSurface.copy(alpha = 0.75f),
                )
            }
        }
    }
}

// ─────────────────────────── 液态托盘导航 ───────────────────────────

@Composable
fun GlassTray(
    items: List<Pair<String, ImageVector>>,   // key -> icon
    selected: String,
    onSelect: (String) -> Unit,
    centerAction: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalKazeTokens.current
    Row(
        modifier
            .fillMaxWidth()
            .glassSurfaceLocal(tokens, tokens.cornerLarge, elevation = 14.dp)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.take(2).forEach { (key, icon) ->
            TrayItem(icon, key == selected, tokens) { onSelect(key) }
        }
        if (centerAction != null) {
            centerAction()
        } else {
            Spacer(Modifier.width(56.dp))
        }
        items.drop(2).forEach { (key, icon) ->
            TrayItem(icon, key == selected, tokens) { onSelect(key) }
        }
    }
}

@Composable
private fun TrayItem(
    icon: ImageVector,
    selected: Boolean,
    tokens: StyleTokens,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(
                if (selected) Brush.linearGradient(listOf(tokens.primary, tokens.secondary))
                else Color.Transparent
            )
            .clickable { onClick() }
            .padding(12.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (selected) Color.White else tokens.onSurface.copy(alpha = 0.55f),
            modifier = Modifier.size(22.dp),
        )
    }
}

// ─────────────────────────── 底线式内联输入 ───────────────────────────

@Composable
fun InnerInput(
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    fontSize: Int = 16,
    singleLine: Boolean = true,
) {
    val tokens = LocalKazeTokens.current
    Column(modifier) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = singleLine,
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = tokens.onSurface,
                fontSize = fontSize.sp,
                fontWeight = FontWeight.Medium,
            ),
            cursorBrush = Brush.verticalGradient(listOf(tokens.primary, tokens.secondary)),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(
                        hint,
                        fontSize = fontSize.sp,
                        color = tokens.onSurface.copy(alpha = 0.35f),
                    )
                }
                inner()
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(tokens.primary.copy(alpha = 0.8f), tokens.secondary.copy(alpha = 0.2f))
                    )
                )
        )
    }
}

// ─────────────────────────── 状态文字 ───────────────────────────

fun stateLabel(state: ServerState): String = when (state) {
    is ServerState.Idle -> "已停止"
    is ServerState.Starting -> "启动中"
    is ServerState.Running -> "运行中"
    is ServerState.Stopping -> "正在停止"
    is ServerState.Crashed -> "已崩溃"
}

// ─────────────────────────── 运行主按钮（启动/停止大圆钮） ───────────────────────────

@Composable
fun RunButton(
    state: ServerState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
) {
    val tokens = LocalKazeTokens.current
    val running = state is ServerState.Running || state is ServerState.Starting
    Box(
        modifier
            .size(size)
            .offset(y = (-8).dp)
            .clip(CircleShape)
            .background(
                if (running) Brush.linearGradient(listOf(Color(0xFFFF7043), tokens.secondary))
                else Brush.linearGradient(listOf(tokens.primary, tokens.secondary))
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (running) Icons.Filled.Stop else Icons.Filled.PlayArrow,
            contentDescription = if (running) "停止" else "启动",
            tint = Color.White,
            modifier = Modifier.size(30.dp),
        )
    }
}