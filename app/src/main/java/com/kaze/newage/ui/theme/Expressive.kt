package com.kaze.newage.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Typography
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/*
 * ══════════════════════════════════════════════════════════════════════════════
 * Material 3 Expressive 的设计令牌（形状 / 动效 / 排版）
 *
 * 来源是 androidx material3 的 token 文件（ShapeTokens.kt、MotionScheme.kt、
 * ExpressiveMotionTokens.kt、TypeScaleTokens.kt）与 material-components-android。
 * 本项目锁在 Compose BOM 2024.12.01（material3 1.3.1），**没有** Expressive API，
 * 所以这里把官方数值落成常量，由手写组件消费 —— 这与 m3e-canvas 的做法一致
 * （它同样是把 material-components-android 的实现搬到了自己的运行时里）。
 * ══════════════════════════════════════════════════════════════════════════════
 */

// ──────────────────────────────────────────────────────────────
// 形状：官方 corner token（dp）
// ──────────────────────────────────────────────────────────────
object M3Shape {
    val none = RoundedCornerShape(0.dp)
    val extraSmall = RoundedCornerShape(4.dp)
    val small = RoundedCornerShape(8.dp)
    val medium = RoundedCornerShape(12.dp)
    val large = RoundedCornerShape(16.dp)
    /** corner-large-increased：Expressive 新增，卡片的默认圆角 */
    val largeIncreased = RoundedCornerShape(20.dp)
    val extraLarge = RoundedCornerShape(28.dp)
    /** corner-extra-large-increased：对话框 */
    val extraLargeIncreased = RoundedCornerShape(32.dp)
    /** corner-extra-extra-large：Expressive 新增 */
    val extraExtraLarge = RoundedCornerShape(48.dp)

    /** 列表：一组相连的列表项，首尾 28dp、相邻内侧 8dp */
    val listFirst = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomStart = 8.dp, bottomEnd = 8.dp)
    val listMiddle = RoundedCornerShape(8.dp)
    val listLast = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomStart = 28.dp, bottomEnd = 28.dp)
    val listSingle = RoundedCornerShape(28.dp)

    /**
     * 相连按钮组（connected button group）：外角是全圆，内角固定 8dp。
     * 官方 ConnectedButtonGroupSmallTokens：高度 40dp、间隙 2dp、
     * 外角 CornerFull、内角 CornerValueSmall = 8dp、按下内角 4dp。
     */
    fun groupFirst(heightDp: Float) = RoundedCornerShape(
        topStart = (heightDp / 2).dp,
        bottomStart = (heightDp / 2).dp,
        topEnd = 8.dp,
        bottomEnd = 8.dp,
    )
    fun groupMiddle() = RoundedCornerShape(8.dp)
    fun groupLast(heightDp: Float) = RoundedCornerShape(
        topStart = 8.dp,
        bottomStart = 8.dp,
        topEnd = (heightDp / 2).dp,
        bottomEnd = (heightDp / 2).dp,
    )
    fun groupSingle(heightDp: Float) = RoundedCornerShape((heightDp / 2).dp)
}

// ──────────────────────────────────────────────────────────────
// 动效：官方 6 个 spring token
//
// 在 material3 1.3.1（本项目的 BOM 2024.12.01）里，
// `MotionScheme` / `ExpressiveMotionTokens` / `StandardMotionTokens` **都不存在**
// ——它们首次出现在 1.4.0-alpha01。1.3.1 只有 `MotionTokens`，而且是 internal，
// 外部引不到。所以这里必须自己落一份常量。
//
// 官方把动效分成两组，这是用法约定：
//   spatial —— 位置、尺寸、形状的变化
//   effects —— 颜色、透明度这类非空间变化
// effects 三档恒为 damping 1.0（临界阻尼、不过冲），expressive 与 standard 的
// effects 完全相同；两者的差异只在 spatial（expressive 用 0.6/0.8 的回弹）。
// ──────────────────────────────────────────────────────────────
object M3Motion {
    /**
     * 显式刚度版本（例如加载指示器的 morph spring：0.6 / 200）。
     *
     * 这里必须写全限定名：本对象内部也叫 `spring`，不加限定的话解析到的是自己，
     * 直接无限递归（StackOverflowError）。上面几档之所以没事，是因为它们带
     * `dampingRatio = / stiffness =` 具名实参，恰好只匹配 Compose 的那个重载。
     */
    fun <T> spring(dampingRatio: Float, stiffness: Float): FiniteAnimationSpec<T> =
        androidx.compose.animation.core.spring(dampingRatio = dampingRatio, stiffness = stiffness)

    // ── Expressive：默认动效方案（本应用采用）──
    fun <T> defaultSpatial(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.8f, stiffness = 380f)
    fun <T> fastSpatial(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.6f, stiffness = 800f)
    fun <T> slowSpatial(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.8f, stiffness = 200f)
    fun <T> defaultEffects(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 1600f)
    fun <T> fastEffects(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 3800f)
    fun <T> slowEffects(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = 800f)

    // ── Standard：另一套 spatial（effects 与上面三档完全相同，故不重复）──
    fun <T> standardDefaultSpatial(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.9f, stiffness = 700f)
    fun <T> standardFastSpatial(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.9f, stiffness = 1400f)
    fun <T> standardSlowSpatial(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.9f, stiffness = 300f)

    // ── 官方缓动曲线（MotionTokens）──
    // 注意：emphasized 与 standard 的数值**完全相同**，官方并没有为 Expressive
    // 新增曲线 —— Expressive 的观感来自 spring，不是新贝塞尔。
    val emphasized: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val standard: Easing = emphasized
    val emphasizedAccelerate: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    val emphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    // ── 官方时长（少数老组件仍用时长而非 spring）──
    object DurationMs {
        const val short2 = 100
        const val short4 = 200
        const val medium2 = 300
        const val medium4 = 400
        const val long2 = 500
        const val long4 = 600
        const val extraLong2 = 800
        const val extraLong4 = 1000
    }

    /** 按压反馈的缩放：M3 Expressive 的可点组件都要有 ripple + 轻微缩小 */
    const val PRESS_SCALE = 0.96f
}

// ──────────────────────────────────────────────────────────────
// 排版：Expressive 的 emphasized 字阶
//   官方规则：15 个字阶都有 Emphasized 变体，**字号与行高不变**，
//   只有字重与字距变：
//     display*/headline*/body*/titleLarge  → Medium (500)
//     titleMedium/titleSmall/label*        → Bold (700)
// ──────────────────────────────────────────────────────────────
private fun Typography.withEmphasizedStyles(): Typography = copy(
    displayLarge = displayLarge.copy(fontWeight = FontWeight.Medium),
    displayMedium = displayMedium.copy(fontWeight = FontWeight.Medium),
    displaySmall = displaySmall.copy(fontWeight = FontWeight.Medium),
    headlineLarge = headlineLarge.copy(fontWeight = FontWeight.Medium),
    headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Medium),
    headlineSmall = headlineSmall.copy(fontWeight = FontWeight.Medium),
    titleLarge = titleLarge.copy(fontWeight = FontWeight.Medium),
    titleMedium = titleMedium.copy(fontWeight = FontWeight.Bold),
    titleSmall = titleSmall.copy(fontWeight = FontWeight.Bold),
    labelLarge = labelLarge.copy(fontWeight = FontWeight.Bold),
    labelMedium = labelMedium.copy(fontWeight = FontWeight.Bold),
    labelSmall = labelSmall.copy(fontWeight = FontWeight.Bold),
)

/** 应用排版：M3 基线 + 强调字阶（Expressive 的「emphasized」开关） */
fun expressiveTypography(base: Typography = Typography(), emphasized: Boolean = true): Typography =
    if (emphasized) base.withEmphasizedStyles() else base

/** 间距规范：屏幕边距 16dp，组件之间 8〜16dp（m3e-canvas prompt 的「整体原则」） */
object M3Spacing {
    val screenMargin = 16.dp
    val betweenParts = 8.dp
    val betweenGroups = 16.dp
    val cardPadding = 20.dp
    /** 常驻底栏占位：内容要能滚到底栏之上 */
    val bottomBarSpace = 96.dp
}
