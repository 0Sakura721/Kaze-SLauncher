package com.kaze.newage.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
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
    val (tint, icon) = when (type) {
        CoreType.VANILLA -> Color(0xFF5A9E52) to Icons.Filled.Grass
        CoreType.PAPER -> Color(0xFF7A93A8) to Icons.Filled.Description
        CoreType.CUSTOM -> Color(0xFF6E7B8F) to Icons.Filled.Archive
        else -> Color(0xFF6E7B8F) to Icons.Filled.Archive
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
