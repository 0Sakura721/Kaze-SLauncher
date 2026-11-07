package com.kaze.newage.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kaze.newage.ui.theme.AppThemeMode
import com.kaze.newage.ui.theme.LocalAppTheme
import com.kaze.newage.ui.theme.MiuixSpec

/*
 * ══════════════════════════════════════════════════════════════════════════════
 * Miuix（HyperOS）主题下的原生组件替换
 *
 * 0.8.0 只把「开关本体」换成了 Miuix 的 Switch，其余交互控件（滑块、勾选框、
 * 进度条、分隔线）都还是 Material 3 的形态 —— 在 HyperOS 观感里，这几个组件比开关
 * 更容易暴露"这是套了个皮"：
 *   - M3 Slider 是粗轨道 + 大圆钮（20dp），HyperOS 是 3dp 细轨道 + 小圆钮；
 *   - M3 Checkbox 是 20dp 方框，HyperOS 是 22dp 圆角方块 + 勾选弹簧；
 *   - M3 LinearProgressIndicator 是 4dp 圆角条 + 无限态往返滑动，
 *     HyperOS 是 6dp 条 + 无限态的单侧循环扫光；
 *   - M3 HorizontalDivider 是 1dp 实线，HyperOS 是 0.5dp 细线 + 行内缩进。
 *
 * 这里按 `LocalAppTheme.current == MIUIX` 分支提供同名组件，调用方只改一行 import。
 * 全部依赖 miuix 0.7.2 的公开 API（Apache-2.0，见 THIRD_PARTY_NOTICES）。
 * ══════════════════════════════════════════════════════════════════════════════
 */

/**
 * 主题感知的勾选框。
 *
 * 与 [M3ESwitch] 同一套约定：**整行的 toggleable / clickable 负责切换**，
 * 勾选框自身的 onCheckedChange 传 null（两个都设会让点一下被切两次再弹回）。
 */
@Composable
fun M3ECheckbox(
    checked: Boolean,
    modifier: Modifier = Modifier,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    enabled: Boolean = true,
) {
    if (LocalAppTheme.current == AppThemeMode.MIUIX) {
        top.yukonga.miuix.kmp.basic.Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = modifier,
            enabled = enabled,
        )
    } else {
        androidx.compose.material3.Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = modifier,
            enabled = enabled,
        )
    }
}

/**
 * 主题感知的滑块（无自定义 thumb）。
 *
 * MIUIX 下换成 Miuix 的 Slider：30dp 行高、3dp 细轨、可吸附刻度点。
 * `steps` 语义两边一致（0 = 连续，n = n 个中间刻度），所以调用方直接透传。
 *
 * 注意：带自定义 thumb 的 Slider（见 [MemorySliderRow]）**不走这里** ——
 * Miuix 的 Slider 没有 thumb 插槽，硬套会把「当前内存值」那个小标签弄丢。
 */
@Composable
fun M3ESlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    if (LocalAppTheme.current == AppThemeMode.MIUIX) {
        top.yukonga.miuix.kmp.basic.Slider(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            enabled = enabled,
            valueRange = valueRange,
            steps = steps,
            onValueChangeFinished = onValueChangeFinished,
        )
    } else {
        androidx.compose.material3.Slider(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            enabled = enabled,
            valueRange = valueRange,
            steps = steps,
            onValueChangeFinished = onValueChangeFinished,
        )
    }
}

/**
 * 主题感知的线性进度条。
 *
 * @param progress null = 不确定进度（M3 往返滑动 / Miuix 单侧扫光）
 */
@Composable
fun M3ELinearProgress(
    progress: Float?,
    modifier: Modifier = Modifier,
) {
    if (LocalAppTheme.current == AppThemeMode.MIUIX) {
        top.yukonga.miuix.kmp.basic.LinearProgressIndicator(
            progress = progress,
            modifier = modifier,
        )
    } else {
        if (progress != null) {
            androidx.compose.material3.LinearProgressIndicator(
                progress = { progress },
                modifier = modifier,
            )
        } else {
            androidx.compose.material3.LinearProgressIndicator(modifier = modifier)
        }
    }
}

/**
 * 主题感知的分隔线。
 *
 * @param inset 左端缩进：HyperOS 的分隔线从文字起点开始（16dp），
 *   而不是像 M3 那样整条贯通到卡片边。
 */
@Composable
fun M3EDivider(modifier: Modifier = Modifier, inset: Dp = 0.dp) {
    val isMiuix = LocalAppTheme.current == AppThemeMode.MIUIX
    Box(
        modifier
            .fillMaxWidth()
            .padding(start = if (isMiuix) inset else 0.dp)
            .height(if (isMiuix) 0.5.dp else 1.dp)
            .background(
                MaterialTheme.colorScheme.outlineVariant.copy(
                    alpha = if (isMiuix) 0.7f else 0.4f,
                )
            )
    )
}

/**
 * 主题感知的分类标签（设置页顶部的分区跳转条用它）。
 *
 * - M3：Material 的 FilterChip（全圆胶囊 + 选中描边 + 前置勾）
 * - MIUIX：HyperOS 的小圆角标签（8dp 圆角、无勾、选中 = 浅蓝底 + 蓝字，
 *   未选中 = 白底细描边）。HyperOS 的 chips 比 Material 矮一档、窄一档，
 *   一屏能放下更多分区——设置页有 8 个分区，胶囊版只能露出 4 个半。
 */
@Composable
fun M3ECategoryChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isMiuix = LocalAppTheme.current == AppThemeMode.MIUIX
    if (!isMiuix) {
        androidx.compose.material3.FilterChip(
            selected = selected,
            onClick = onClick,
            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            modifier = modifier,
        )
        return
    }
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(8.dp)
    Surface(
        modifier = modifier
            .height(30.dp)
            .clip(shape)
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = themedIndication(),
                onClick = onClick,
            ),
        shape = shape,
        color = if (selected) scheme.primaryContainer else scheme.surface,
        contentColor = if (selected) scheme.onPrimaryContainer else scheme.onSurface,
        border = if (selected) {
            null
        } else {
            androidx.compose.foundation.BorderStroke(0.5.dp, scheme.outlineVariant.copy(alpha = 0.8f))
        },
    ) {
        Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                color = if (selected) scheme.onPrimaryContainer else scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

