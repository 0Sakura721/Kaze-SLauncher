package com.mcserver.launcher.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight

/**
 * Clean 简洁设计令牌
 * 小圆角 · 紧凑间距 · 清晰层级
 */

object KazeSpacing {
    val hairline: Dp = 1.dp
    val xxs: Dp = 2.dp
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 20.dp
    val xxl: Dp = 24.dp
    val xxxl: Dp = 32.dp
    val xxxxl: Dp = 48.dp

    val cardPadding: Dp = lg          // 卡片内部水平 padding
    val cardPaddingV: Dp = sm         // 卡片内部垂直 padding
    val pageHorizontal: Dp = lg       // 页面两侧 padding
    val pageTop: Dp = sm              // 页面顶部 padding
    val groupGap: Dp = xl             // 两个分组卡片之间的间距
    val rowItemH: Dp = 56.dp          // 列表行标准高度(设置行/列表行)
    val rowItemCompactH: Dp = 48.dp   // 紧凑行高度(实例卡片)
    val rowHorizPad: Dp = lg          // 行内容水平 padding
    val searchFieldH: Dp = 44.dp      // 搜索框整体高度
    val sectionTitleGap: Dp = sm      // 分组标题与卡片之间的间距
}

object KazeCorners {
    val tiny: Shape   = RoundedCornerShape(4.dp)
    val small: Shape  = RoundedCornerShape(8.dp)
    val medium: Shape = RoundedCornerShape(12.dp)
    val large: Shape  = RoundedCornerShape(16.dp)
    val xlarge: Shape = RoundedCornerShape(20.dp)
    val pill: Shape   = RoundedCornerShape(50)
    val card: Shape   = RoundedCornerShape(14.dp)   // 分组卡片圆角
    val row: Shape    = RoundedCornerShape(10.dp)   // 行卡片圆角
    val navPill: Shape = RoundedCornerShape(16.dp)
}

object KazeSizes {
    val iconTiny: Dp = 14.dp
    val iconSmall: Dp = 18.dp
    val iconMedium: Dp = 22.dp
    val iconLarge: Dp = 28.dp
    val iconHuge: Dp = 36.dp

    val badgeSmall: Dp = 34.dp        // 实例卡片左侧徽标
    val badgeMedium: Dp = 44.dp
    val badgeLarge: Dp = 56.dp
    val badgeHuge: Dp = 72.dp

    val buttonHeight: Dp = 48.dp
    val compactButtonHeight: Dp = 36.dp
    val navBarHeight: Dp = 64.dp

    val strokeThin: Dp = 1.dp
    val strokeMedium: Dp = 1.dp
    val strokeThick: Dp = 2.dp
    val groupStroke: Dp = 0.8.dp      // 分组卡片描边粗细,更精致
}

object KazeMotion {
    const val instant: Int = 80
    const val fast: Int = 150
    const val normal: Int = 300
    const val slow: Int = 500
    const val glow: Int = 1600
    const val springDamping: Float = 0.82f
    const val springStiff: Float = 450f
}

object KazeElevation {
    val flat = 0.dp
    val soft = 0.5.dp                  // 分组卡片微微浮起
    val raised = 1.5.dp
    val floating = 3.dp
    val bottomBar = 2.dp               // 顶部/底部导航栏
}

object KazeType {
    val hero: TextStyle
        @Composable get() = TextStyle(
            fontSize = 28.sp,
            lineHeight = 34.sp,
            fontWeight = FontWeight.Bold
        )
    val display: TextStyle
        @Composable get() = TextStyle(
            fontSize = 24.sp,
            lineHeight = 30.sp,
            fontWeight = FontWeight.Bold
        )
    val headline: TextStyle
        @Composable get() = TextStyle(
            fontSize = 20.sp,
            lineHeight = 26.sp,
            fontWeight = FontWeight.Bold
        )
    val title: TextStyle
        @Composable get() = TextStyle(
            fontSize = 16.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.SemiBold
        )
    val subtitle: TextStyle
        @Composable get() = TextStyle(
            fontSize = 14.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.Medium
        )
    val body: TextStyle
        @Composable get() = TextStyle(
            fontSize = 14.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.Normal
        )
    val caption: TextStyle
        @Composable get() = TextStyle(
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Normal
        )
    val tiny: TextStyle
        @Composable get() = TextStyle(
            fontSize = 10.sp,
            lineHeight = 13.sp,
            fontWeight = FontWeight.Medium
        )
}

val KazeShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp)
)
