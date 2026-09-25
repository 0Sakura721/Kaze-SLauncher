package com.kaze.newage.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Grass
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.kaze.newage.data.model.CoreType

/**
 * 实例类型图标：按核心类型着色的圆角方块 + 白色图标。
 * 对应 ZalithLauncher2 的 VersionIconImage（GPL-3.0，无自定义图时按类型回退默认图标）。
 */
@Composable
fun InstanceIcon(
    type: CoreType,
    modifier: Modifier = Modifier,
    size: Int = 44,
) {
    // 每种核心给一个可区分的图标与配色：此前只有 VANILLA/PAPER/其他 三种，
    // Purpur / Spigot / Fabric / Forge / NeoForge 全部落到同一个灰 Archive ——
    // 新建向导里连着 5 个一模一样的灰块，光看图分不出选的是哪个。
    val (tint, icon) = when (type) {
        CoreType.VANILLA -> Color(0xFF5A9E52) to Icons.Filled.Grass          // 原版：草方块
        CoreType.PAPER -> Color(0xFF4A8FD4) to Icons.Filled.Description      // Paper：文档
        CoreType.PURPUR -> Color(0xFF8E6FC4) to Icons.Filled.AutoAwesome     // 增强：紫
        CoreType.SPIGOT -> Color(0xFFC08A3E) to Icons.Filled.Extension       // 经典插件：橙
        CoreType.FABRIC -> Color(0xFFD9A441) to Icons.Filled.Bolt            // 轻量加载器：快
        CoreType.FORGE -> Color(0xFF5C6B7A) to Icons.Filled.Build            // 经典加载器：扳手
        CoreType.NEOFORGE -> Color(0xFF3FA08F) to Icons.Filled.LocalFireDepartment // 新生代：青
        CoreType.CUSTOM -> Color(0xFF6E7B8F) to Icons.Filled.Archive         // 自定义：归档
    }
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(RoundedCornerShape((size / 3.5f).dp))
            .background(Brush.verticalGradient(listOf(lerp(tint, Color.White, 0.16f), tint))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = type.displayName,
            tint = Color.White.copy(alpha = 0.95f),
            modifier = Modifier.size((size * 0.5f).dp),
        )
    }
}
