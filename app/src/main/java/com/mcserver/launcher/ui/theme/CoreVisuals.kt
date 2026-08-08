package com.mcserver.launcher.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.mcserver.launcher.data.CoreType

/**
 * 核心类型视觉标识 · 纯色简洁风格
 */
fun CoreType.badgeColor(): Color = when (this) {
    CoreType.VANILLA -> Color(0xFF84CC16)
    CoreType.PAPER -> Color(0xFFF97316)
    CoreType.PURPUR -> Color(0xFFA855F7)
    CoreType.SPIGOT -> Color(0xFF22C55E)
    CoreType.FABRIC -> Color(0xFFEC4899)
    CoreType.FORGE -> Color(0xFFEF4444)
    CoreType.NEOFORGE -> Color(0xFF6366F1)
}

fun CoreType.badgeGradient(): Brush = Brush.linearGradient(
    listOf(badgeColor(), badgeColor())
)

fun CoreType.badgeLetter(): String = when (this) {
    CoreType.VANILLA -> "Va"
    CoreType.PAPER -> "Pa"
    CoreType.PURPUR -> "Pu"
    CoreType.SPIGOT -> "Sp"
    CoreType.FABRIC -> "Fa"
    CoreType.FORGE -> "Fo"
    CoreType.NEOFORGE -> "Ne"
}

fun CoreType.shortDesc(): String = when (this) {
    CoreType.VANILLA -> "官方原版"
    CoreType.PAPER -> "高性能插件服"
    CoreType.PURPUR -> "Paper 优化分支"
    CoreType.SPIGOT -> "经典 Bukkit"
    CoreType.FABRIC -> "轻量模组"
    CoreType.FORGE -> "经典模组"
    CoreType.NEOFORGE -> "现代模组"
}
