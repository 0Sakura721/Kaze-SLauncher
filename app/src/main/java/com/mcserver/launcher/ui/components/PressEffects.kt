package com.mcserver.launcher.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

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
