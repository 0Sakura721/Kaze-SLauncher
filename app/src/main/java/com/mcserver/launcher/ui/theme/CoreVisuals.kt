package com.mcserver.launcher.ui.theme

import androidx.compose.ui.graphics.Color
import com.mcserver.launcher.data.CoreType

/**
 * 核心类型视觉标识(FCL 式加载器色块)。
 * 每个核心类型有专属颜色 + 缩写字母,用于首页卡片/新建页/详情页的圆形标识。
 */
fun CoreType.badgeColor(): Color = when (this) {
    CoreType.VANILLA -> Color(0xFF757575)     // 灰:官方原版
    CoreType.PAPER -> Color(0xFFFF9800)       // 橙:Paper
    CoreType.PURPUR -> Color(0xFF9C27B0)      // 紫:Purpur
    CoreType.SPIGOT -> Color(0xFF795548)      // 棕:Spigot
    CoreType.FABRIC -> Color(0xFF4CAF50)      // 绿:Fabric
    CoreType.FORGE -> Color(0xFF2196F3)       // 蓝:Forge
    CoreType.NEOFORGE -> Color(0xFF00BCD4)    // 青:NeoForge
}

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
