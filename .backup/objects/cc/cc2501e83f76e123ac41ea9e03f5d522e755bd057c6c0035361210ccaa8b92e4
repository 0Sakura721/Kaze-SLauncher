package com.mcserver.launcher.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 风格键 */
object StyleKeys {
    const val PILIPLUS = "piliplus"
    const val BILI = "bili"
    const val MATERIAL = "material"
    const val OLED = "oled"
    val ALL = listOf(PILIPLUS, BILI, MATERIAL, OLED)
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

    // 哔哩品牌色
    private val BiliBlue = Color(0xFF00A1D6)
    private val BiliPink = Color(0xFFFB7299)
    private val BiliCyan = Color(0xFF0AC8B9)

    private val LightBg = Color(0xFFF3F5F9)
    private val DarkBg = Color(0xFF0E1014)
    private val LightSurface = Color(0xFFFFFFFF)
    private val DarkSurface = Color(0xFF191C22)
    private val DarkSurfaceVariant = Color(0xFF232830)
    private val DarkOnSurface = Color(0xFFE8EAF0)
    private val DarkOutline = Color(0xFF343A46)

    private val piliplusLight = StyleTokens(
        key = StyleKeys.PILIPLUS, label = "PiliPlus 液态玻璃",
        primary = BiliBlue, secondary = BiliPink, accent = BiliCyan,
        background = LightBg, surface = Color(0xE6FFFFFF),
        surfaceVariant = Color(0xB3FFFFFF),
        onBackground = Color(0xFF1A1C22), onSurface = Color(0xFF1A1C22),
        outline = Color(0x4DFFFFFF),
        cornerSmall = 14.dp, cornerMedium = 20.dp, cornerLarge = 26.dp,
        glassEnabled = true, blurEnabled = true,
        glowColors = listOf(BiliPink.copy(alpha = 0.30f), BiliBlue.copy(alpha = 0.30f), BiliCyan.copy(alpha = 0.22f)),
        dynamicColor = false, navTrayEnabled = true,
    )

    private val piliplusDark = StyleTokens(
        key = StyleKeys.PILIPLUS, label = "PiliPlus 液态玻璃",
        primary = Color(0xFF29B8E8), secondary = BiliPink, accent = BiliCyan,
        background = DarkBg, surface = Color(0xE61C2028),
        surfaceVariant = Color(0xB31C2028),
        onBackground = Color(0xFFE8EAF0), onSurface = DarkOnSurface,
        outline = Color(0x3DFFFFFF),
        cornerSmall = 14.dp, cornerMedium = 20.dp, cornerLarge = 26.dp,
        glassEnabled = true, blurEnabled = true,
        glowColors = listOf(BiliPink.copy(alpha = 0.20f), BiliBlue.copy(alpha = 0.22f), BiliCyan.copy(alpha = 0.15f)),
        dynamicColor = false, navTrayEnabled = true,
    )

    private val biliLight = StyleTokens(
        key = StyleKeys.BILI, label = "哔哩经典",
        primary = BiliBlue, secondary = BiliPink, accent = BiliCyan,
        background = Color(0xFFF6F7F8), surface = LightSurface,
        surfaceVariant = Color(0xFFF0F1F4),
        onBackground = Color(0xFF18191C), onSurface = Color(0xFF18191C),
        outline = Color(0xFFE3E5E8),
        cornerSmall = 8.dp, cornerMedium = 12.dp, cornerLarge = 16.dp,
        glassEnabled = false, blurEnabled = false,
        glowColors = emptyList(),
        dynamicColor = false, navTrayEnabled = false,
    )

    private val biliDark = StyleTokens(
        key = StyleKeys.BILI, label = "哔哩经典",
        primary = BiliBlue, secondary = BiliPink, accent = BiliCyan,
        background = DarkBg, surface = DarkSurface,
        surfaceVariant = DarkSurfaceVariant,
        onBackground = Color(0xFFE8EAF0), onSurface = DarkOnSurface,
        outline = DarkOutline,
        cornerSmall = 8.dp, cornerMedium = 12.dp, cornerLarge = 16.dp,
        glassEnabled = false, blurEnabled = false,
        glowColors = emptyList(),
        dynamicColor = false, navTrayEnabled = false,
    )

    private val materialLight = StyleTokens(
        key = StyleKeys.MATERIAL, label = "Material You",
        primary = Color(0xFF6750A4), secondary = Color(0xFF9A82DB), accent = Color(0xFF00A1D6),
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
        primary = Color(0xFFCFBCFF), secondary = Color(0xFFCCC2DC), accent = Color(0xFF29B8E8),
        background = Color(0xFF1C1B1F), surface = Color(0xFF2B2930),
        surfaceVariant = Color(0xFF49454F),
        onBackground = Color(0xFFE6E1E5), onSurface = Color(0xFFE6E1E5),
        outline = Color(0xFF938F99),
        cornerSmall = 12.dp, cornerMedium = 16.dp, cornerLarge = 28.dp,
        glassEnabled = false, blurEnabled = false,
        glowColors = emptyList(),
        dynamicColor = true, navTrayEnabled = false,
    )

    private val oledLight = StyleTokens(
        key = StyleKeys.OLED, label = "OLED 极夜",
        primary = BiliBlue, secondary = BiliPink, accent = BiliCyan,
        background = Color(0xFF000000), surface = Color(0xFF101014),
        surfaceVariant = Color(0xFF16161A),
        onBackground = Color(0xFFE8EAF0), onSurface = Color(0xFFE8EAF0),
        outline = Color(0xFF2A2A30),
        cornerSmall = 10.dp, cornerMedium = 16.dp, cornerLarge = 22.dp,
        glassEnabled = false, blurEnabled = false,
        glowColors = emptyList(),
        dynamicColor = false, navTrayEnabled = false,
    )

    fun forKey(styleKey: String, isDark: Boolean): StyleTokens = when (styleKey) {
        StyleKeys.PILIPLUS -> if (isDark) piliplusDark else piliplusLight
        StyleKeys.BILI -> if (isDark) biliDark else biliLight
        StyleKeys.MATERIAL -> if (isDark) materialDark else materialLight
        StyleKeys.OLED -> oledLight // OLED 只有暗色
        else -> if (isDark) piliplusDark else piliplusLight
    }

    fun labelOf(key: String): String = when (key) {
        StyleKeys.PILIPLUS -> "PiliPlus 液态玻璃"
        StyleKeys.BILI -> "哔哩经典"
        StyleKeys.MATERIAL -> "Material You"
        StyleKeys.OLED -> "OLED 极夜"
        else -> key
    }
}