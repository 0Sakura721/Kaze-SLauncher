package com.kaze.newage.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Indication
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.animateFloatAsState
import com.kaze.newage.ui.theme.AppThemeMode
import com.kaze.newage.ui.theme.LocalAppTheme
import com.kaze.newage.ui.theme.LocalDarkTheme
import com.kaze.newage.ui.theme.M3Motion
import com.kaze.newage.ui.theme.M3Shape
import com.kaze.newage.ui.theme.M3Spacing
import com.kaze.newage.ui.theme.MiuixSpec

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
    val isMiuix = LocalAppTheme.current == AppThemeMode.MIUIX
    // Miuix（HyperOS 观感）：三种 variant 统一成「白卡片 + 细描边 + 大圆角」，无投影 ——
    // HyperOS 的列表分组没有海拔概念，靠底色对比（灰底上的白卡）。
    val container = if (isMiuix) {
        scheme.surface
    } else when (variant) {
        M3ECardVariant.Filled -> scheme.surfaceContainerHighest
        M3ECardVariant.Elevated -> scheme.surfaceContainerLow
        M3ECardVariant.Outlined -> scheme.surface
    }
    val border = when {
        isMiuix -> BorderStroke(0.5.dp, scheme.outlineVariant.copy(alpha = 0.6f))
        variant == M3ECardVariant.Outlined -> BorderStroke(1.dp, scheme.outlineVariant)
        else -> null
    }
    val elevation = if (!isMiuix && variant == M3ECardVariant.Elevated) 1.dp else 0.dp
    // Miuix 的卡片圆角取它的真实 token（CardDefaults.CornerRadius = 16dp），不再用 18dp
    // 这种「看起来差不多」的值 —— 同一屏里 Miuix 自带组件与 M3E 卡片必须同圆角，
    // 否则分组卡与行内控件会差 2dp，眼睛看得出来。
    val cardShape = if (isMiuix) RoundedCornerShape(MiuixSpec.cardCorner) else M3Shape.largeIncreased

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
        shape = cardShape,
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
    /**
     * 自定义左侧内容（模组图标等真实图片）。
     * 给定时**优先于** [leadingIcon]：图标要按内容渲染，ImageVector 表达不了位图。
     */
    leading: (@Composable () -> Unit)? = null,
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
    val isMiuix = LocalAppTheme.current == AppThemeMode.MIUIX
    // MIUIX 用 HyperOS 的矩形高亮代替缩放反馈，所以缩放恒为 1f —— 但 targetValue
    // 也必须一起改：老写法只把 graphicsLayer 里的 scale 覆盖成 1f，而
    // animateFloatAsState 仍在每次按下时跑一个弹簧动画，等于每按一行白算一帧
    // （列表长时是可见的掉帧）。
    val scale by animateFloatAsState(
        targetValue = if (isMiuix) 1f else if (pressed) M3Motion.PRESS_SCALE else 1f,
        animationSpec = M3Motion.fastSpatial(),
        label = "item-press",
    )
    // Miuix：行要么是「分组卡里的一条」（平角，由外层卡片剪裁 + 分隔线），
    // 要么是独立卡片（16dp 连续圆角）。这里用 RectangleShape 作为「平角」的哨兵，
    // 传进来的是平角就保持平角，否则统一归到 HyperOS 的 16dp。
    val resolvedShape = if (!isMiuix) {
        shape
    } else if (shape == RectangleShape) {
        RectangleShape
    } else {
        androidx.compose.foundation.shape.RoundedCornerShape(MiuixSpec.cardCorner)
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = if (isMiuix) themedIndication() else ripple(),
                        onClick = onClick,
                    )
                } else Modifier
            ),
        shape = resolvedShape,
        // Miuix（HyperOS 观感）：列表行坐在白卡片上，行底=卡片色；高亮用浅蓝主容器
        // （secondaryContainer 在 Miuix 灰白体系里偏灰，高亮辨识度不足）。
        color = if (isMiuix) {
            if (highlighted) scheme.primaryContainer else scheme.surface
        } else if (highlighted) {
            scheme.secondaryContainer
        } else {
            scheme.surfaceContainerLow
        },
        // 前景色必须与上面的底色配套：Miuix 高亮底是 primaryContainer（浅蓝），
        // 沿用 onSecondaryContainer（灰）会变成「浅蓝底 + 灰字」，对比度不足、
        // 字看起来发闷（M3 那边底是 secondaryContainer，配 onSecondaryContainer 才对）。
        contentColor = when {
            isMiuix ->
                if (highlighted) scheme.onPrimaryContainer else scheme.onSurface
            highlighted -> scheme.onSecondaryContainer
            else -> scheme.onSurface
        },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                // 用 heightIn 而不是 height：supporting 允许 2 行，固定 72dp 会把第二行压掉，
                // 出现「Java 21 · 4096 M…」这种断词（真机截图实锤）。官方 72dp 是「标题+一行正文」的规格，
                // 需要两行时让行高自然增长，而不是裁掉内容。
                //
                // Miuix：HyperOS 的行高是 heightIn(min = 56.dp)（BasicComponent 实测值），
                // 不是 M3 Expressive 的 72dp —— 那一档在 HyperOS 观感里显得松散。
                .heightIn(min = if (isMiuix) MiuixSpec.rowMinHeight else 72.dp)
                .padding(horizontal = if (isMiuix) MiuixSpec.rowPadding else 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (leading != null) {
                leading()
            } else if (leadingIcon != null) {
                if (iconContainer != null) {
                    // Miuix：HyperOS 用 32dp 圆角方块（8dp 圆角），不是 M3 的 40dp 正圆。
                    val box = if (isMiuix) MiuixSpec.iconBox else 40.dp
                    val boxShape = if (isMiuix) {
                        androidx.compose.foundation.shape.RoundedCornerShape(MiuixSpec.iconBoxCorner)
                    } else {
                        CircleShape
                    }
                    Box(
                        Modifier
                            .size(box)
                            .clip(boxShape)
                            .background(iconContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            leadingIcon,
                            contentDescription = null,
                            tint = if (isMiuix) scheme.onSurface else scheme.onPrimaryContainer,
                            modifier = Modifier.size(if (isMiuix) 18.dp else 22.dp),
                        )
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
                        // 辅助文字的色角色必须跟着底色走：
                        //  - MIUIX 高亮底 = primaryContainer（浅蓝/深蓝）→ 用 onPrimaryContainer；
                        //    原来这里漏了 MIUIX 分支，浅蓝底上压 onSecondaryContainer 的灰字，
                        //    深色下更是「深蓝底 + 灰字」，几乎读不出来（截图核对时发现）。
                        //  - M3 高亮底 = secondaryContainer → 用 onSecondaryContainer。
                        color = when {
                            isMiuix && highlighted -> scheme.onPrimaryContainer.copy(alpha = 0.8f)
                            highlighted -> scheme.onSecondaryContainer.copy(alpha = 0.8f)
                            else -> scheme.onSurfaceVariant
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
 * 主题感知的按压反馈。
 *
 * - M3 / GLASS / SHELL：Material 的圆形水波纹（`ripple()`）
 * - MIUIX：HyperOS 的**矩形按压高亮**（整个行/卡范围内均匀加深，Miuix 的
 *   `MiuixIndication` 就是这套，120ms 淡入淡出）—— 这是 HyperOS 观感里
 *   最容易被认出来的触感细节，水波纹一眼就是 Material。
 */
@Composable
fun themedIndication(): Indication {
    val pressColor = MaterialTheme.colorScheme.onSurface
    return if (LocalAppTheme.current == AppThemeMode.MIUIX) {
        // 颜色必须显式给：MiuixIndication 的默认值是 Color.Black，
        // 深色主题下黑色高亮压在黑底上等于没有反馈。
        remember(pressColor) { top.yukonga.miuix.kmp.utils.MiuixIndication(pressColor) }
    } else {
        ripple()
    }
}

/**
 * 把若干列表项画成「一串」。
 *
 * - M3：官方 Expressive 相连列表（首尾 28dp、相邻内侧 8dp、项间 3dp）。
 * - MIUIX：HyperOS 的**分组卡**——一个 16dp 圆角的白底容器，行与行之间用
 *   一条左侧缩进的分隔线，行本身是平角。这是 HyperOS 设置页的标准形态，
 *   与 M3 的「每行一张小卡」完全不同。
 */
@Composable
fun M3EConnectedList(
    count: Int,
    modifier: Modifier = Modifier,
    item: @Composable (index: Int, shape: Shape) -> Unit,
) {
    if (LocalAppTheme.current == AppThemeMode.MIUIX) {
        val scheme = MaterialTheme.colorScheme
        val cardShape = RoundedCornerShape(MiuixSpec.cardCorner)
        Column(
            modifier
                .fillMaxWidth()
                .clip(cardShape)
                .background(scheme.surface)
                .then(
                    // 0.5dp 细描边：HyperOS 的白卡靠描边与灰底分层，不靠投影
                    Modifier.border(BorderStroke(0.5.dp, scheme.outlineVariant.copy(alpha = 0.6f)), cardShape)
                ),
        ) {
            repeat(count) { i ->
                item(i, RectangleShape)
                if (i < count - 1) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = MiuixSpec.dividerInset)
                            .height(0.5.dp)
                            .background(scheme.outlineVariant.copy(alpha = 0.7f))
                    )
                }
            }
        }
        return
    }
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
/**
 * 主题感知开关：Miuix 主题下用 Miuix 的 Switch（HyperOS 的细长胶囊 + 白钮 + 蓝轨），
 * 其它主题用 M3 Switch。
 *
 * **只替换开关本体，不碰行的点击语义** —— 现有列表项的写法是「整行 onClick 负责切换、
 * Switch 的 onCheckedChange = null」（两个都设会让点一下被切两次再弹回原位，见
 * SettingsScreen 里那几处注释）。所以这里保留 [onCheckedChange] 可为 null 的签名，
 * 调用方一行不用改。
 */
@Composable
fun M3ESwitch(
    checked: Boolean,
    modifier: Modifier = Modifier,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    enabled: Boolean = true,
) {
    if (LocalAppTheme.current == AppThemeMode.MIUIX) {
        top.yukonga.miuix.kmp.basic.Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = modifier,
            enabled = enabled,
        )
    } else {
        androidx.compose.material3.Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = modifier,
            enabled = enabled,
        )
    }
}

@Composable
fun M3EScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    /** 标题左侧的动作（返回键、关闭键） */
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val isMiuix = LocalAppTheme.current == AppThemeMode.MIUIX
    Row(
        modifier
            .fillMaxWidth()
            .padding(
                horizontal = M3Spacing.screenMargin,
                // HyperOS 的页头更紧凑：标题与首卡片之间不留 Expressive 那么大的呼吸
                vertical = if (isMiuix) 10.dp else 12.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(
                title,
                // MIUIX 下 headlineSmall 已被 miuixTypography() 改写为 HyperOS 的
                // 20sp / 26sp / SemiBold（见 theme/MiuixTheme.kt），这里只需给出字重。
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = if (isMiuix) FontWeight.SemiBold else FontWeight.Medium,
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
 *
 * MIUIX：HyperOS 的标签是**小圆角方块**（6dp）+ 12% 同色底，不是全圆的 Material 胶囊。
 */
@Composable
fun M3EStatusChip(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val isMiuix = LocalAppTheme.current == AppThemeMode.MIUIX
    Surface(
        modifier = modifier,
        shape = if (isMiuix) RoundedCornerShape(6.dp) else CircleShape,
        color = color.copy(alpha = if (isMiuix) 0.12f else 0.16f),
        contentColor = color,
    ) {
        Row(
            Modifier.padding(horizontal = if (isMiuix) 8.dp else 10.dp, vertical = if (isMiuix) 3.dp else 5.dp),
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
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 分段选择（segmented button）：Expressive 里常用的「一组互斥选项」。
 *
 * - M3：官方 connected button group（间隙 2dp、外角全圆、内角 8dp、选中用 secondaryContainer）。
 * - MIUIX：HyperOS 的分段控件是**一个胶囊轨道 + 一个白色滑块**——轨道整体是浅灰底
 *   （surfaceContainerHigh），选中项浮起一块白底圆角，未选中项没有描边。这是它和
 *   Material 分段按钮最容易分辨的差异（后者每一项都是独立带描边的按钮）。
 */
@Composable
fun <T> M3ESegmentedRow(
    options: List<T>,
    selected: T?,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 轨道高度。默认值刻意留 null，表示"按主题取默认" —— `@Composable` 的默认参数
     * 不能读 Compose 状态（`LocalAppTheme`），所以主题默认值只能在函数体内解析：
     * MIUIX 用 `MiuixSpec.segmentedHeight`(32dp)，其它用 M3 的 40dp。
     * 调用方显式传值时（弹窗里的 38/44dp）一律以传入为准，不覆盖。
     */
    height: Dp? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val isMiuix = LocalAppTheme.current == AppThemeMode.MIUIX
    val resolvedHeight = height ?: if (isMiuix) MiuixSpec.segmentedHeight else 40.dp
    if (isMiuix) {
        // 用 resolvedHeight：老实现写死 MiuixSpec.segmentedHeight(32dp)，
        // 6 个调用点里传 38dp / 44dp 的（弹窗内）会被**静默忽略** ——
        // 于是 MIUIX 下这些弹窗的分段控件比 M3 主题矮 12dp、与旁边 44dp 的
        // 控件基线错位。参数存在却无效，是典型的"改主题时忘了读参数"。
        val trackHeight = resolvedHeight
        val trackShape = RoundedCornerShape(trackHeight / 2)
        // 轨道与滑块的明暗关系必须跟深浅方向一致：
        //  - 浅色：轨道比页面底略深（#EBEBF0），选中项是**更亮的白**
        //  - 深色：轨道比黑色页面底略亮（#1C1C1E），选中项是**更亮的 #2C2C2E**
        // 直接写死「轨道=surfaceContainerHigh、滑块=surface」在深色下会反过来
        // （选中项比轨道更黑，看着像"没选中"），截图核对时发现了这一点。
        val dark = LocalDarkTheme.current
        val trackColor = if (dark) scheme.surface else scheme.surfaceContainerHigh
        val pillColor = if (dark) scheme.surfaceContainerHigh else scheme.surface
        Row(
            modifier
                .fillMaxWidth()
                .height(trackHeight)
                .clip(trackShape)
                .background(trackColor)
                .padding(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            options.forEach { option ->
                val isSelected = option == selected
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .clip(RoundedCornerShape((trackHeight - 4.dp) / 2))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = themedIndication(),
                        ) { onSelect(option) },
                    shape = RoundedCornerShape((trackHeight - 4.dp) / 2),
                    color = if (isSelected) pillColor else Color.Transparent,
                    contentColor = if (isSelected) scheme.primary else scheme.onSurfaceVariant,
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
        return
    }
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { i, option ->
            val isSelected = option == selected
            val shape = when {
                options.size == 1 -> M3Shape.groupSingle(resolvedHeight.value)
                i == 0 -> M3Shape.groupFirst(resolvedHeight.value)
                i == options.lastIndex -> M3Shape.groupLast(resolvedHeight.value)
                else -> M3Shape.groupMiddle()
            }
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .height(resolvedHeight)
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
