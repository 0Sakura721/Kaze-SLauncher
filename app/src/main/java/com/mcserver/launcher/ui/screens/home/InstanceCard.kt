package com.mcserver.launcher.ui.screens.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcserver.launcher.core.server.ServerManager
import com.mcserver.launcher.data.InstanceStatus
import com.mcserver.launcher.data.ServerInstance
import com.mcserver.launcher.ui.theme.KazeCorners
import com.mcserver.launcher.ui.theme.KazeMotion
import com.mcserver.launcher.ui.theme.KazeSizes
import com.mcserver.launcher.ui.theme.KazeSpacing
import com.mcserver.launcher.ui.theme.badgeColor
import com.mcserver.launcher.ui.theme.badgeLetter

/**
 * 紧凑实例行式卡片（参考 FCL/Zalith 风格）
 * 结构：[徽标] [名称+信息行] [状态] [操作按钮]
 * 高度紧凑，一屏能显示更多实例
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun InstanceCard(
    instance: ServerInstance,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val status by ServerManager.status.collectAsState()
    val running = ServerManager.isRunningFor(instance.id)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.99f else 1f,
        spring(KazeMotion.springDamping, KazeMotion.springStiff),
        label = "rowScale"
    )

    Row(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .padding(horizontal = KazeSpacing.pageHorizontal, vertical = KazeSpacing.xxs)
            .clip(KazeCorners.small)
            .background(MaterialTheme.colorScheme.surface)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = KazeSpacing.md, vertical = KazeSpacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左侧：小徽标（32dp，紧凑）
        Box(
            Modifier
                .size(36.dp)
                .clip(KazeCorners.small)
                .background(instance.coreType.badgeColor()),
            contentAlignment = Alignment.Center
        ) {
            Text(
                instance.coreType.badgeLetter(),
                style = androidx.compose.ui.text.TextStyle(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White
                )
            )
        }

        Spacer(Modifier.width(KazeSpacing.md))

        // 中间：名称 + 副信息（占主要宽度，紧凑堆叠）
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    instance.name,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.width(KazeSpacing.xs))
                // 小点表示运行状态
                if (running && status == InstanceStatus.RUNNING) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color(0xFF22C55E))
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            // 副信息:核心 + 版本 + 端口(紧凑单行,最多人数等详情在实例详情页查看)
            Text(
                "${instance.coreType.displayName} ${instance.mcVersion} · :${instance.config.serverPort}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(Modifier.width(KazeSpacing.sm))

        // 右侧：启停按钮（方形 IconButton，紧凑）
        if (running) {
            IconButton(
                onClick = onStop,
                modifier = Modifier
                    .size(KazeSizes.buttonHeight)
                    .clip(KazeCorners.small)
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f))
            ) {
                Icon(
                    Icons.Filled.Stop,
                    "停止",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        } else {
            IconButton(
                onClick = onStart,
                modifier = Modifier
                    .size(KazeSizes.buttonHeight)
                    .clip(KazeCorners.small)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    "启动",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
