package com.kaze.newage.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ripple
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.animateFloatAsState
import com.kaze.newage.ui.theme.LocalDarkTheme
import com.kaze.newage.ui.theme.M3Motion
import com.kaze.newage.ui.theme.M3Shape
import com.kaze.newage.ui.theme.M3Spacing

/*
 * ══════════════════════════════════════════════════════════════════════════════
 * KAZE 的 Material 3 Expressive 组件层
 *
 * 这一层的存在理由：本项目锁在 Compose BOM 2024.12.01（material3 1.3.1），
 * 没有 Expressive API（它从 1.4.0-alpha01 才有）。Expressive 不只是"圆角更大"，
 * 它的版式约定是：
 *   - 卡片圆角 20dp（corner-large-increased），对话框 28dp，按钮全圆
 *   - 可点组件一律有 ripple + 轻微缩小反馈
 *   - 动效用 spatial/effects 两组弹簧，位置变化带轻微回弹
 *   - 列表项 72dp 高，成组列表首尾 28dp、相邻内侧 8dp
 *   - 排版用强调字阶（titleMedium 及以下加粗到 Bold）
 *
 * 屏幕实现只应该用这里的组件，不要各自画一套 —— 版式一致性全靠它。
 * ══════════════════════════════════════════════════════════════════════════════
 */

/** 卡片的三种官方变体（对应 M3 的 filled / elevated / outlined） */
enum class M3ECardVariant { Filled, Elevated, Outlined }

/**
 * M3 Expressive 卡片。
 *
 * 官方底色（CardDefaults）：填充 surfaceContainerHighest / 浮起 surfaceContainerLow
 * + Level1 阴影 / 描边 1dp outlineVariant + surface。圆角 20dp，内边距 20dp。
 *
 * 注意 [content] **必须是最后一个参数**：这样 `M3ECard(...) { … }` 的尾随 lambda
 * 会落到卡片正文里。它在标题行的 [trailing] 之后，尾随 lambda 就不可能被吸到标题行上
 * —— 那个错位编译器不报错（除非 lambda 里用到 ColumnScope），只会让内容挤在标题旁边。
 */
@Composable
fun M3ECard(
    modifier: Modifier = Modifier,
    variant: M3ECardVariant = M3ECardVariant.Elevated,
    onClick: (() -> Unit)? = null,
    /** 卡片标题；为空时不占位 */
    title: String? = null,
    /** 标题左侧图标 */
    titleIcon: ImageVector? = null,
    /** 标题下方的正文 */
    supporting: String? = null,
    /** 标题行右侧的动作 */
    trailing: @Composable () -> Unit = {},
    /** 正文下方的自定义内容（进度条、指标行等）。刻意放在最后：见上面的说明 */
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    val container = when (variant) {
        M3ECardVariant.Filled -> scheme.surfaceContainerHighest
        M3ECardVariant.Elevated -> scheme.surfaceContainerLow
        M3ECardVariant.Outlined -> scheme.surface
    }
    val border = if (variant == M3ECardVariant.Outlined) {
        BorderStroke(1.dp, scheme.outlineVariant)
    } else null
    val elevation = if (variant == M3ECardVariant.Elevated) 1.dp else 0.dp

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) M3Motion.PRESS_SCALE else 1f,
        animationSpec = M3Motion.fastSpatial(),
        label = "card-press",
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = ripple(),
                        onClick = onClick,
                    )
                } else Modifier
            ),
        shape = M3Shape.largeIncreased,
        color = container,
        contentColor = scheme.onSurface,
        border = border,
        shadowElevation = elevation,
    ) {
        Column(Modifier.padding(M3Spacing.cardPadding)) {
            if (title != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (titleIcon != null) {
                        Icon(
                            titleIcon,
                            contentDescription = null,
                            tint = scheme.primary,
                            modifier = Modifier.size(24.dp),
                        )
                        androidx.compose.foundation.layout.Spacer(Modifier.width(12.dp))
                    }
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        color = scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    trailing()
                }
            }
            if (supporting != null) {
                Text(
                    supporting,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = if (title != null) 4.dp else 0.dp),
                )
            }
            Column(Modifier.padding(top = if (title != null || supporting != null) 12.dp else 0.dp)) {
                content()
            }
        }
    }
}

/**
 * M3 Expressive 列表项（官方 72dp 高）。
 *
 * 左侧是 24dp 图标，默认坐在 40dp 的容器色圆底上；主文本 bodyLarge，
 * 辅助文本 bodyMedium / onSurfaceVariant。
 */
@Composable
fun M3EListItem(
    headline: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    leadingIcon: ImageVector? = null,
    /** 左侧图标的圆底颜色角色；null = 不用圆底，裸图标 */
    iconContainer: Color? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    /** 一组相连列表里的位置：决定四个角各自的圆角 */
    shape: Shape = M3Shape.listSingle,
    /** 整行高亮（例如「当前实例」） */
    highlighted: Boolean = false,
    /** 主文本用跑马灯而不是省略号（长实例名） */
    marqueeHeadline: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) M3Motion.PRESS_SCALE else 1f,
        animationSpec = M3Motion.fastSpatial(),
        label = "item-press",
    )
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = ripple(),
                        onClick = onClick,
                    )
                } else Modifier
            ),
        shape = shape,
        color = if (highlighted) {
            scheme.secondaryContainer
        } else {
            scheme.surfaceContainerLow
        },
        contentColor = if (highlighted) scheme.onSecondaryContainer else scheme.onSurface,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                // 用 heightIn 而不是 height：supporting 允许 2 行，固定 72dp 会把第二行压掉，
                // 出现「Java 21 · 4096 M…」这种断词（真机截图实锤）。官方 72dp 是「标题+一行正文」的规格，
                // 需要两行时让行高自然增长，而不是裁掉内容。
                .heightIn(min = 72.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (leadingIcon != null) {
                if (iconContainer != null) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(iconContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(leadingIcon, contentDescription = null, tint = scheme.onPrimaryContainer, modifier = Modifier.size(22.dp))
                    }
                } else {
                    Icon(
                        leadingIcon,
                        contentDescription = null,
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    headline,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // 名字可能很长（用户自己起的实例名）：允许调用方改用跑马灯。
                    // 默认关闭——列表里整排文字跑动会互相干扰。
                    modifier = if (marqueeHeadline) {
                        Modifier.basicMarquee(iterations = Int.MAX_VALUE)
                    } else {
                        Modifier
                    },
                )
                if (supporting != null) {
                    Text(
                        supporting,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (highlighted) {
                            scheme.onSecondaryContainer.copy(alpha = 0.8f)
                        } else {
                            scheme.onSurfaceVariant
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            trailing?.invoke()
        }
    }
}

/** 一组相连列表项之间的间距：官方 3dp */
val M3EConnectedListGap: Dp = 3.dp

/**
 * 把若干列表项画成「一串」：首尾 28dp、相邻内侧 8dp、项间 3dp。
 * 官方 Expressive 列表样式。
 */
@Composable
fun M3EConnectedList(
    count: Int,
    modifier: Modifier = Modifier,
    item: @Composable (index: Int, shape: Shape) -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(M3EConnectedListGap)) {
        repeat(count) { i ->
            val shape = when {
                count == 1 -> M3Shape.listSingle
                i == 0 -> M3Shape.listFirst
                i == count - 1 -> M3Shape.listLast
                else -> M3Shape.listMiddle
            }
            item(i, shape)
        }
    }
}

/**
 * 屏幕顶部：大标题 +（可选）副标题 + 右侧动作。
 * m3e-canvas 的草图里每屏顶部就是一个「粗体文本 + 若干按钮」，这里是它的实现。
 */
@Composable
fun M3EScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    /** 标题左侧的动作（返回键、关闭键） */
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = M3Spacing.screenMargin, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke(this)
    }
}

/**
 * 状态胶囊：小圆点 + 文案 + 容器色底。
 * 官方没有这个组件，但它是本应用的签名元素（服务端状态要一眼可见）。
 */
@Composable
fun M3EStatusChip(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = color.copy(alpha = 0.16f),
        contentColor = color,
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
            } else {
                Box(Modifier.size(7.dp).clip(CircleShape).background(color))
            }
            Text(text, style = MaterialTheme.typography.labelMedium, color = color)
        }
    }
}

/**
 * 指标块：一个大号数字 + 一个小标签。
 * 没有数据时显示「—」而不是 0（m3e-canvas 草图的明确要求）。
 */
@Composable
fun M3EMetric(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            value.ifBlank { "—" },
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/**
 * 分段选择（segmented button）：Expressive 里常用的「一组互斥选项」。
 * 官方没有独立的 segmented token 表；这里按 connected button group 的规则实现：
 * 间隙 2dp、外角全圆、内角 8dp、选中用 secondaryContainer。
 */
@Composable
fun <T> M3ESegmentedRow(
    options: List<T>,
    selected: T?,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 40.dp,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { i, option ->
            val isSelected = option == selected
            val shape = when {
                options.size == 1 -> M3Shape.groupSingle(height.value)
                i == 0 -> M3Shape.groupFirst(height.value)
                i == options.lastIndex -> M3Shape.groupLast(height.value)
                else -> M3Shape.groupMiddle()
            }
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .height(height)
                    .clip(shape)
                    .clickable { onSelect(option) },
                shape = shape,
                color = if (isSelected) scheme.secondaryContainer else Color.Transparent,
                contentColor = if (isSelected) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
                border = if (isSelected) null else BorderStroke(1.dp, scheme.outlineVariant),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        label(option),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** 屏幕内容的统一内边距与垂直节奏 */
@Composable
fun M3EScreenColumn(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Column(
            modifier
                .fillMaxWidth()
                .padding(horizontal = M3Spacing.screenMargin),
            verticalArrangement = Arrangement.spacedBy(M3Spacing.betweenParts),
            content = content,
        )
    }
}
