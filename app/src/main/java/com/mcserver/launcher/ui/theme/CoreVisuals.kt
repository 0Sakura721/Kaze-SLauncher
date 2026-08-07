package com.mcserver.launcher.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.mcserver.launcher.data.CoreType

/**
 * 核心类型视觉标识(FCL 式加载器色块)。
 * 每个核心类型有专属渐变色 + 缩写字母,用于首页卡片/新建页/详情页的圆形标识。
 */
fun CoreType.badgeColor(): Color = when (this) {
    CoreType.VANILLA -> Color(0xFF757575)
    CoreType.PAPER -> Color(0xFFFF9800)
    CoreType.PURPUR -> Color(0xFF9C27B0)
    CoreType.SPIGOT -> Color(0xFF795548)
    CoreType.FABRIC -> Color(0xFF4CAF50)
    CoreType.FORGE -> Color(0xFF2196F3)
    CoreType.NEOFORGE -> Color(0xFF00BCD4)
}

/** 核心类型渐变色(游戏风光泽) */
fun CoreType.badgeGradient(): Brush = Brush.linearGradient(
    colors = when (this) {
        CoreType.VANILLA -> listOf(Color(0xFF9E9E9E), Color(0xFF616161))
        CoreType.PAPER -> listOf(Color(0xFFFFB74D), Color(0xFFEF6C00))
        CoreType.PURPUR -> listOf(Color(0xFFBA68C8), Color(0xFF7B1FA2))
        CoreType.SPIGOT -> listOf(Color(0xFF8D6E63), Color(0xFF5D4037))
        CoreType.FABRIC -> listOf(Color(0xFF81C784), Color(0xFF388E3C))
        CoreType.FORGE -> listOf(Color(0xFF64B5F6), Color(0xFF1565C0))
        CoreType.NEOFORGE -> listOf(Color(0xFF4DD0E1), Color(0xFF00838F))
    }
)

/** 色块上显示的字母(FCL 式加载器徽标) */
fun CoreType.badgeLetter(): String = when (this) {
    CoreType.VANILLA -> "V"
    CoreType.PAPER -> "P"
    CoreType.PURPUR -> "P"
    CoreType.SPIGOT -> "S"
    CoreType.FABRIC -> "F"
    CoreType.FORGE -> "F"
    CoreType.NEOFORGE -> "N"
}

/** 核心类型简短描述 */
fun CoreType.shortDesc(): String = when (this) {
    CoreType.VANILLA -> "官方原版"
    CoreType.PAPER -> "高性能插件服"
    CoreType.PURPUR -> "Paper 分支"
    CoreType.SPIGOT -> "经典 Bukkit"
    CoreType.FABRIC -> "轻量模组"
    CoreType.FORGE -> "经典模组"
    CoreType.NEOFORGE -> "现代模组"
}
