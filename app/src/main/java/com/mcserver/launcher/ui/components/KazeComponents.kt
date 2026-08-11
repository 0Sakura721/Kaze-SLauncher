package com.mcserver.launcher.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mcserver.launcher.ui.theme.KazeCorners
import com.mcserver.launcher.ui.theme.KazeError
import com.mcserver.launcher.ui.theme.KazeMotion
import com.mcserver.launcher.ui.theme.KazeSizes
import com.mcserver.launcher.ui.theme.KazeSpacing
import com.mcserver.launcher.ui.theme.KazeSuccess
import com.mcserver.launcher.ui.theme.KazeType
import com.mcserver.launcher.ui.theme.KazeWarning
import com.mcserver.launcher.ui.theme.LocalGlassPalette
import com.mcserver.launcher.ui.theme.GlassDialog
import com.mcserver.launcher.ui.theme.PrimaryGradient
import com.mcserver.launcher.ui.theme.KazeGlass
import androidx.compose.ui.graphics.luminance

/**
 * 玻璃卡片:半透明填充 + 顶部高光描边 + 细描边。
 * gradient 非空时覆盖默认玻璃质感（用于强调卡）。
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = KazeCorners.card,
    gradient: Brush? = null,
    contentPadding: Dp = KazeSpacing.cardPadding,
    pressScale: Boolean = true,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val fill = gradient ?: Color.White.copy(alpha = if (isDark) KazeGlass.cardAlphaDark else KazeGlass.cardAlphaLight)
    val borderColor = Color.White.copy(alpha = if (isDark) KazeGlass.cardBorderAlphaDark else KazeGlass.cardBorderAlphaLight)
    val highlight = Brush.verticalGradient(
        colors = listOf(
            Color.White.copy(alpha = if (isDark) KazeGlass.highlightAlpha else 0.5f),
            Color.Transparent
        )
    )
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressScale && pressed) 0.98f else 1f,
        animationSpec = spring(
            dampingRatio = KazeMotion.springDamping,
            stiffness = KazeMotion.springStiff
        ),
        label = "cardScale"
    )

    Box(
        modifier = Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .then(modifier)
            .clip(shape)
            .background(fill)
            .border(BorderStroke(KazeSizes.strokeThin, borderColor), shape)
            .then(
                if (onClick != null) Modifier.clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick
                ) else Modifier
            )
    ) {
        Box(Modifier.fillMaxWidth().height(34.dp).background(highlight))
        Box(Modifier.padding(contentPadding)) { content() }
    }
}

@Composable
fun KazeCard(
    modifier: Modifier = Modifier,
    tonalElevation: Dp = 2.dp,
    corner: Shape = KazeCorners.medium,
    content: @Composable () -> Unit
) {
    GlassCard(modifier = modifier, shape = corner) {
        Column(Modifier.fillMaxWidth()) { content() }
    }
}

@Composable
fun KazeSection(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    accent: Brush = PrimaryGradient,
    content: @Composable ColumnScope.() -> Unit
) {
    GlassCard(modifier = modifier.fillMaxWidth()) {
        Column {
            Text(title, style = KazeType.headline)
            if (subtitle != null) {
                Spacer(Modifier.height(KazeSpacing.xs))
                Text(
                    subtitle,
                    style = KazeType.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(KazeSpacing.md))
            content()
        }
    }
}

/**
 * 主按钮:纯色填充
 */
@Composable
fun GradientButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    gradient: Brush = PrimaryGradient,
    minHeight: Dp = KazeSizes.buttonHeight,
    icon: ImageVector? = null,
    label: String
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed && enabled) 0.97f else 1f,
        spring(KazeMotion.springDamping, KazeMotion.springStiff),
        label = "btnScale"
    )
    val btnAlpha = if (enabled) 1f else 0.45f

    Box(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = btnAlpha }
            .height(minHeight)
            .clip(KazeCorners.medium)
            .background(MaterialTheme.colorScheme.primary)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .padding(horizontal = KazeSpacing.lg),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(KazeSizes.iconSmall))
                Spacer(Modifier.width(KazeSpacing.sm))
            }
            Text(label, style = KazeType.title, color = MaterialTheme.colorScheme.onPrimary)
        }
    }
}

/**
 * 次级按钮:描边
 */
@Composable
fun GhostButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    gradient: Brush = PrimaryGradient,
    minHeight: Dp = KazeSizes.buttonHeight,
    icon: ImageVector? = null,
    label: String
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed && enabled) 0.97f else 1f,
        spring(KazeMotion.springDamping, KazeMotion.springStiff),
        label = "ghostScale"
    )
    Box(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else 0.45f }
            .height(minHeight)
            .clip(KazeCorners.medium)
            .border(width = KazeSizes.strokeThick, color = MaterialTheme.colorScheme.primary, shape = KazeCorners.medium)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .padding(horizontal = KazeSpacing.lg),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(KazeSizes.iconSmall))
                Spacer(Modifier.width(KazeSpacing.sm))
            }
            Text(label, style = KazeType.title, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** 简洁标题区 */
@Composable
fun HeroHeader(
    eyebrow: String? = null,
    title: String,
    subtitle: String? = null,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = KazeSpacing.pageHorizontal, vertical = KazeSpacing.pageTop),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            if (eyebrow != null) {
                Text(
                    eyebrow,
                    style = KazeType.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(KazeSpacing.xs))
            }
            Text(title, style = KazeType.hero, color = MaterialTheme.colorScheme.onBackground)
            if (subtitle != null) {
                Spacer(Modifier.height(KazeSpacing.sm))
                Text(
                    subtitle,
                    style = KazeType.subtitle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        trailing()
    }
}

/** 顶部返回栏 */
@Composable
fun KazeTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = KazeSpacing.sm, vertical = KazeSpacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            val (press, src) = pressSource()
            IconButton(onClick = onBack, interactionSource = src, modifier = press) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回",
                    tint = MaterialTheme.colorScheme.onBackground)
            }
        }
        if (leading != null) leading()
        Spacer(Modifier.width(KazeSpacing.sm))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = KazeType.headline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = KazeType.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        actions()
    }
}

/** 空状态 */
@Composable
fun EmptyState(
    title: String,
    description: String? = null,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    action: (@Composable () -> Unit)? = null
) {
    Column(
        modifier.fillMaxWidth().padding(vertical = KazeSpacing.xxxxl, horizontal = KazeSpacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            Box(
                Modifier
                    .size(72.dp)
                    .clip(KazeCorners.large)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon, null,
                    Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(KazeSpacing.lg))
        }
        Text(title, style = KazeType.headline, textAlign = TextAlign.Center)
        if (description != null) {
            Spacer(Modifier.height(KazeSpacing.sm))
            Text(
                description,
                style = KazeType.body,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
        if (action != null) {
            Spacer(Modifier.height(KazeSpacing.xl))
            action()
        }
    }
}

/** 确认对话框（玻璃容器） */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String = "确认",
    cancelLabel: String = "取消",
    destructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    GlassDialog(onDismiss = onDismiss) {
        Column(Modifier.padding(KazeSpacing.lg)) {
            Text(title, style = KazeType.title, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(KazeSpacing.sm))
            Text(
                message,
                style = KazeType.body,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(KazeSpacing.lg))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) { Text(cancelLabel) }
                Spacer(Modifier.width(KazeSpacing.sm))
                if (destructive) {
                    Button(
                        onClick = onConfirm,
                        colors = ButtonDefaults.buttonColors(containerColor = KazeError)
                    ) { Text(confirmLabel) }
                } else {
                    Button(onClick = onConfirm) { Text(confirmLabel) }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════
//  徽章 & 指示点
// ═══════════════════════════════════════════════════════════════

@Composable
fun Chip(
    text: String,
    modifier: Modifier = Modifier,
    gradient: Brush = PrimaryGradient,
    textColor: Color = Color.White,
    compact: Boolean = false
) {
    val hp = if (compact) KazeSpacing.sm else KazeSpacing.md
    val vp = if (compact) KazeSpacing.xxs else KazeSpacing.xs
    Box(
        modifier = modifier
            .clip(KazeCorners.small)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
            .padding(horizontal = hp, vertical = vp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            style = if (compact) KazeType.tiny else KazeType.caption,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun StatusDot(
    active: Boolean,
    pulse: Boolean = false,
    modifier: Modifier = Modifier,
    color: Color = if (active) KazeSuccess else MaterialTheme.colorScheme.onSurfaceVariant
) {
    val alpha by animateFloatAsState(
        targetValue = if (pulse && active) 0.5f else 1f,
        animationSpec = tween(if (pulse) 900 else 200),
        label = "dotAlpha"
    )
    Box(
        modifier
            .size(8.dp)
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = alpha))
    )
}

@Composable
fun StatusBadge(status: com.mcserver.launcher.data.InstanceStatus) {
    val (text, color) = when (status) {
        com.mcserver.launcher.data.InstanceStatus.RUNNING -> "运行中" to KazeSuccess
        com.mcserver.launcher.data.InstanceStatus.STARTING -> "启动中" to KazeWarning
        com.mcserver.launcher.data.InstanceStatus.STOPPING -> "停止中" to KazeWarning
        com.mcserver.launcher.data.InstanceStatus.ERROR -> "错误" to KazeError
        com.mcserver.launcher.data.InstanceStatus.STOPPED -> "已停止" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        Modifier
            .clip(KazeCorners.small)
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = KazeSpacing.sm, vertical = KazeSpacing.xxs)
    ) {
        Text(text, style = KazeType.tiny, color = color, fontWeight = FontWeight.SemiBold)
    }
}

// ═══════════════════════════════════════════════════════════════
//  列表分组容器(整洁化核心)
//  参考 iOS / FCL 风格:section title + card group with rows
// ═══════════════════════════════════════════════════════════════

/**
 * 分组标题:一行左侧加粗标题 + 右侧辅助按钮(或计数文字)。
 * 与下方分组卡片之间留固定 sectionTitleGap。
 */
@Composable
fun SectionHeader(
    title: String,
    subtitle: String? = null,
    count: Int? = null,
    modifier: Modifier = Modifier,
    trailing: @Composable (RowScope.() -> Unit)? = null
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = KazeSpacing.pageHorizontal)
            .padding(bottom = KazeSpacing.sectionTitleGap)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground
            )
            if (count != null) {
                Spacer(Modifier.width(KazeSpacing.xs))
                Text(
                    "$count",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )
            }
            if (trailing != null) {
                Spacer(Modifier.weight(1f))
                trailing()
            }
        }
        if (subtitle != null) {
            Spacer(Modifier.height(KazeSpacing.xxs))
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 分组卡片容器:surface 背景 + 大圆角 + 细描边 + 微浮起。
 * 所有行(RowItem)内部用分隔线串联,首/尾行自动裁剪圆角。
 *
 * 使用:
 * ```
 * ListGroup {
 *     RowItem { ... }
 *     RowItemDivider()
 *     RowItem { ... }
 * }
 * ```
 */
@Composable
fun ListGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val fill = Color.White.copy(alpha = if (isDark) KazeGlass.cardAlphaDark else KazeGlass.cardAlphaLight)
    val borderColor = Color.White.copy(alpha = if (isDark) KazeGlass.cardBorderAlphaDark else KazeGlass.cardBorderAlphaLight)
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = KazeSpacing.pageHorizontal)
            .clip(KazeCorners.card)
            .background(fill)
            .border(KazeSizes.groupStroke, borderColor, KazeCorners.card)
    ) {
        content()
    }
}

/**
 * 列表行:统一高度 rowItemH,左右 padding rowHorizPad。
 * 点击时使用 ripple + 自定义交互来源,并自动裁剪适配组内圆角。
 */
@Composable
fun RowItem(
    modifier: Modifier = Modifier,
    height: Dp = KazeSpacing.rowItemH,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit
) {
    val base = Modifier
        .fillMaxWidth()
        .height(height)
    Row(
        modifier = if (onClick != null) {
            base
                .clip(KazeCorners.row)
                .clickable(onClick = onClick)
                .padding(horizontal = KazeSpacing.rowHorizPad)
        } else {
            base.padding(horizontal = KazeSpacing.rowHorizPad)
        }.then(modifier),
        verticalAlignment = Alignment.CenterVertically
    ) {
        content()
    }
}

/**
 * 组内行分隔线:缩进从左侧图标之后开始(视觉对齐)。
 */
@Composable
fun RowItemDivider(indent: Dp = 60.dp) {
    androidx.compose.material3.HorizontalDivider(
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
        modifier = Modifier.padding(start = indent)
    )
}

/**
 * 紧凑分组:用于"实例卡片 / 下载任务行"等不要求高度 rowItemH 的列表。
 * 与 ListGroup 相同容器,但 padding/描边稍细。
 */
@Composable
fun CompactGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val fill = Color.White.copy(alpha = if (isDark) KazeGlass.cardAlphaDark else KazeGlass.cardAlphaLight)
    val borderColor = Color.White.copy(alpha = if (isDark) KazeGlass.cardBorderAlphaDark else KazeGlass.cardBorderAlphaLight)
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = KazeSpacing.pageHorizontal)
            .clip(KazeCorners.row)
            .background(fill)
            .border(KazeSizes.groupStroke, borderColor, KazeCorners.row)
    ) {
        content()
    }
}
