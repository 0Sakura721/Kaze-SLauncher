package com.mcserver.launcher.ui.theme

import android.graphics.Color as AndroidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 风格键 */
object StyleKeys {
    const val LIQUID = "liquid"
    const val CLASSIC = "classic"
    const val MATERIAL = "material"
    const val OLED = "oled"
    val ALL = listOf(LIQUID, CLASSIC, MATERIAL, OLED)
}

/**
 * 一套风格的全部视觉令牌。
 * 布局骨架全局不变，风格只换肤（颜色/圆角/玻璃开关/动态色）。
 */
data class StyleTokens(
    val key: String,
    val label: String,
    val primary: Color,
    val secondary: Color,
    val accent: Color,
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val onBackground: Color,
    val onSurface: Color,
    val outline: Color,
    val cornerSmall: Dp,
    val cornerMedium: Dp,
    val cornerLarge: Dp,
    val glassEnabled: Boolean,
    val blurEnabled: Boolean,
    val glowColors: List<Color>,
    val dynamicColor: Boolean,
    val navTrayEnabled: Boolean,
)

object Styles {

    // ── 简单基础色板（低饱和、克制） ──
    private val SeedBlue = Color(0xFF2F9BF4)
    private val SeedCyan = Color(0xFF31C6C6)

    private val LightBg = Color(0xFFF3F5F9)
    private val DarkBg = Color(0xFF0E1014)
    private val LightSurface = Color(0xFFFFFFFF)
    private val DarkSurface = Color(0xFF191C22)
    private val DarkSurfaceVariant = Color(0xFF232830)
    private val DarkOnSurface = Color(0xFFE8EAF0)
    private val DarkOutline = Color(0xFF343A46)

    /** 自定义主题预设色板 */
    val CustomPresets = listOf(
        Color(0xFF2F9BF4), // 蓝
        Color(0xFF22B8A0), // 青
        Color(0xFF7C6FF0), // 紫
        Color(0xFFF0567A), // 粉
        Color(0xFFF09A3E), // 橙
        Color(0xFF67C23A), // 绿
        Color(0xFFE05038), // 红
        Color(0xFF8E99A8), // 灰
    )

    // ── 液态（默认：玻璃质感 + 柔和光斑） ──
    private val liquidLight = StyleTokens(
        key = StyleKeys.LIQUID, label = "液态",
        primary = SeedBlue, secondary = Color(0xFF5FB4F8), accent = SeedCyan,
        background = LightBg, surface = Color(0xE6FFFFFF),
        surfaceVariant = Color(0xB3FFFFFF),
        onBackground = Color(0xFF1A1C22), onSurface = Color(0xFF1A1C22),
        outline = Color(0x4DFFFFFF),
        cornerSmall = 14.dp, cornerMedium = 20.dp, cornerLarge = 26.dp,
        glassEnabled = true, blurEnabled = true,
        glowColors = listOf(SeedBlue.copy(alpha = 0.22f), SeedCyan.copy(alpha = 0.16f)),
        dynamicColor = false, navTrayEnabled = true,
    )

    private val liquidDark = StyleTokens(
        key = StyleKeys.LIQUID, label = "液态",
        primary = Color(0xFF4FA8F5), secondary = Color(0xFF3E8FD8), accent = SeedCyan,
        background = DarkBg, surface = Color(0xE61C2028),
        surfaceVariant = Color(0xB31C2028),
        onBackground = Color(0xFFE8EAF0), onSurface = DarkOnSurface,
        outline = Color(0x3DFFFFFF),
        cornerSmall = 14.dp, cornerMedium = 20.dp, cornerLarge = 26.dp,
        glassEnabled = true, blurEnabled = true,
        glowColors = listOf(Color(0xFF4FA8F5).copy(alpha = 0.20f), SeedCyan.copy(alpha = 0.14f)),
        dynamicColor = false, navTrayEnabled = true,
    )

    // ── 经典（扁平、无玻璃、中性色） ──
    private val classicLight = StyleTokens(
        key = StyleKeys.CLASSIC, label = "经典",
        primary = Color(0xFF2F7DE1), secondary = Color(0xFF5B9DF0), accent = Color(0xFF1FB0A8),
        background = Color(0xFFF6F7F8), surface = LightSurface,
        surfaceVariant = Color(0xFFF0F1F4),
        onBackground = Color(0xFF18191C), onSurface = Color(0xFF18191C),
        outline = Color(0xFFE3E5E8),
        cornerSmall = 8.dp, cornerMedium = 12.dp, cornerLarge = 16.dp,
        glassEnabled = false, blurEnabled = false,
        glowColors = emptyList(),
        dynamicColor = false, navTrayEnabled = false,
    )

    private val classicDark = StyleTokens(
        key = StyleKeys.CLASSIC, label = "经典",
        primary = Color(0xFF5B9DF0), secondary = Color(0xFF3E7FC9), accent = Color(0xFF2BC4BA),
        background = DarkBg, surface = DarkSurface,
        surfaceVariant = DarkSurfaceVariant,
        onBackground = Color(0xFFE8EAF0), onSurface = DarkOnSurface,
        outline = DarkOutline,
        cornerSmall = 8.dp, cornerMedium = 12.dp, cornerLarge = 16.dp,
        glassEnabled = false, blurEnabled = false,
        glowColors = emptyList(),
        dynamicColor = false, navTrayEnabled = false,
    )

    // ── Material You ──
    private val materialLight = StyleTokens(
        key = StyleKeys.MATERIAL, label = "Material You",
        primary = Color(0xFF6750A4), secondary = Color(0xFF9A82DB), accent = Color(0xFF2F9BF4),
        background = Color(0xFFFFFBFE), surface = Color(0xFFFFFBFE),
        surfaceVariant = Color(0xFFE7E0EC),
        onBackground = Color(0xFF1C1B1F), onSurface = Color(0xFF1C1B1F),
        outline = Color(0xFF79747E),
        cornerSmall = 12.dp, cornerMedium = 16.dp, cornerLarge = 28.dp,
        glassEnabled = false, blurEnabled = false,
        glowColors = emptyList(),
        dynamicColor = true, navTrayEnabled = false,
    )

    private val materialDark = StyleTokens(
        key = StyleKeys.MATERIAL, label = "Material You",
        primary = Color(0xFFCFBCFF), secondary = Color(0xFFCCC2DC), accent = Color(0xFF8BCBFF),
        background = Color(0xFF1C1B1F), surface = Color(0xFF2B2930),
        surfaceVariant = Color(0xFF49454F),
        onBackground = Color(0xFFE6E1E5), onSurface = Color(0xFFE6E1E5),
        outline = Color(0xFF938F99),
        cornerSmall = 12.dp, cornerMedium = 16.dp, cornerLarge = 28.dp,
        glassEnabled = false, blurEnabled = false,
        glowColors = emptyList(),
        dynamicColor = true, navTrayEnabled = false,
    )

    // ── OLED 极夜（仅深色） ──
    private val oledDark = StyleTokens(
        key = StyleKeys.OLED, label = "OLED 极夜",
        primary = Color(0xFF4FA8F5), secondary = Color(0xFF3E7FC9), accent = SeedCyan,
        background = Color(0xFF000000), surface = Color(0xFF101014),
        surfaceVariant = Color(0xFF16161A),
        onBackground = Color(0xFFE8EAF0), onSurface = Color(0xFFE8EAF0),
        outline = Color(0xFF2A2A30),
        cornerSmall = 10.dp, cornerMedium = 16.dp, cornerLarge = 22.dp,
        glassEnabled = false, blurEnabled = false,
        glowColors = emptyList(),
        dynamicColor = false, navTrayEnabled = false,
    )

    /**
     * 取风格令牌。
     * @param customSeed 用户自定义主色（ARGB，0 = 使用风格默认色）
     */
    fun forKey(styleKey: String, isDark: Boolean, customSeed: Int = 0): StyleTokens {
        val base = when (styleKey) {
            StyleKeys.LIQUID -> if (isDark) liquidDark else liquidLight
            StyleKeys.CLASSIC -> if (isDark) classicDark else classicLight
            StyleKeys.MATERIAL -> if (isDark) materialDark else materialLight
            StyleKeys.OLED -> oledDark // OLED 只有深色
            else -> if (isDark) liquidDark else liquidLight
        }
        if (customSeed == 0) return base
        val hsv = FloatArray(3)
        AndroidColor.colorToHSV(customSeed, hsv)
        val primary = Color(customSeed)
        val secondary = Color.hsv((hsv[0] + 14f) % 360f, 0.50f, if (isDark) 0.78f else 0.62f)
        val accent = Color.hsv((hsv[0] + 45f) % 360f, 0.58f, if (isDark) 0.82f else 0.55f)
        return base.copy(
            primary = primary,
            secondary = secondary,
            accent = accent,
            glowColors = if (base.glassEnabled) {
                listOf(primary.copy(alpha = 0.20f), accent.copy(alpha = 0.15f))
            } else emptyList(),
        )
    }

    fun labelOf(key: String): String = when (key) {
        StyleKeys.LIQUID -> "液态"
        StyleKeys.CLASSIC -> "经典"
        StyleKeys.MATERIAL -> "Material You"
        StyleKeys.OLED -> "OLED 极夜"
        else -> key
    }
}