package com.kaze.newage.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kaze.newage.ui.theme.AppThemeMode
import com.kaze.newage.ui.theme.LocalAppTheme
import com.kaze.newage.ui.theme.LocalDarkTheme
import com.kaze.newage.ui.theme.LocalGlassBlurEnabled
import com.kaze.newage.ui.theme.LocalHazeState
import com.kaze.newage.ui.theme.cardBorderColor
import com.kaze.newage.ui.theme.cardColor
import com.kaze.newage.ui.theme.cardShape
import com.kaze.newage.ui.theme.cardTitleColor
import com.kaze.newage.ui.theme.glassHazeStyle
import com.kaze.newage.ui.theme.glassSaturation
import com.kaze.newage.ui.theme.onCardColor
import dev.chrisbanes.haze.hazeEffect

/**
 * 背景卡片：按主题差异化（发丝描边 / 液态玻璃），无阴影（玻璃主题带轻微悬浮）。
 * 体系改编自 ZalithLauncher2 ui/components/BackgroundCard.kt（GPL-3.0）。
 * 液态玻璃：通透底色 + 高光描边 + 顶部镜面高光条。
 */
@Composable
fun BackgroundCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = cardShape()
    val isGlass = LocalAppTheme.current == AppThemeMode.GLASS
    val dark = LocalDarkTheme.current
    val hazeState = LocalHazeState.current
    val blurEnabled = LocalGlassBlurEnabled.current
    val colors = CardDefaults.cardColors(
        containerColor = cardColor(),
        contentColor = onCardColor(),
    )
    // 玻璃面板无发丝边框（BiliPai 式：只有顶部高光与边缘光晕，避免"大框架"廉价感）；
    // M3 或显式传入 border 时保留描边
    val effectiveBorder = when {
        border != null -> border
        isGlass -> null
        else -> BorderStroke(1.dp, cardBorderColor())
    }
    val elevation = if (isGlass) {
        CardDefaults.cardElevation(defaultElevation = 3.dp)
    } else {
        CardDefaults.cardElevation(defaultElevation = 0.dp)
    }

    // 玻璃卡片：Haze 原生背景模糊（API 31+；设置开关或低版本时降级为半透明表面）
    // + 饱和度增强（等效 BiliPai vibrancy）
    val cardModifier = if (isGlass && blurEnabled && hazeState != null) {
        modifier
            .hazeEffect(state = hazeState, style = glassHazeStyle())
            .glassSaturation(1.5f)
    } else {
        modifier
    }

    val glassContent: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth()) {
            if (isGlass) {
                // 顶部镜面高光（液态玻璃的标志性反光）
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.White.copy(alpha = if (dark) 0.55f else 0.95f),
                                    Color.Transparent,
                                )
                            )
                        )
                )
            }
            content()
        }
    }

    if (onClick != null) {
        Card(
            modifier = cardModifier,
            shape = shape,
            colors = colors,
            border = effectiveBorder,
            elevation = elevation,
            onClick = onClick,
        ) { glassContent() }
    } else {
        Card(
            modifier = cardModifier,
            shape = shape,
            colors = colors,
            border = effectiveBorder,
            elevation = elevation,
        ) { glassContent() }
    }
}

/**
 * 卡片标题栏：半透明标题 + 分隔线（ZalithLauncher2 CardTitleLayout 风格）。
 */
@Composable
fun CardTitleLayout(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = cardTitleColor(),
            contentColor = onCardColor(),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                trailing?.invoke()
            }
        }
        HorizontalDivider(
            modifier = Modifier.fillMaxWidth(),
            // 玻璃模式分隔线更柔和（避免框架感）
            color = if (LocalAppTheme.current == AppThemeMode.GLASS) {
                Color.White.copy(alpha = if (LocalDarkTheme.current) 0.10f else 0.30f)
            } else {
                cardBorderColor()
            },
        )
        Column(Modifier.padding(16.dp)) { content() }
    }
}
