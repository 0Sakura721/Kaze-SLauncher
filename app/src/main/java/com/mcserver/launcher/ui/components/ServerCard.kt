package com.mcserver.launcher.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mcserver.launcher.data.ServerState
import com.mcserver.launcher.data.ServerStatus

@Composable
fun ServerStatusCard(
    status: ServerStatus,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            // 状态指示器
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusDot(state = status.state)
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = when (status.state) {
                        ServerState.STOPPED -> "服务器已停止"
                        ServerState.STARTING -> "正在启动..."
                        ServerState.RUNNING -> "运行中"
                        ServerState.STOPPING -> "正在停止..."
                        ServerState.ERROR -> "错误"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 运行信息
            if (status.state == ServerState.RUNNING) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    InfoItem("运行时间", formatUptime(status.uptimeSeconds))
                    InfoItem("内存", "${status.memoryUsedMB} MB")
                    InfoItem("玩家", "${status.playerCount}")
                }
                Spacer(modifier = Modifier.height(16.dp))

                // 在线玩家
                if (status.players.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.People,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = status.players.joinToString("、"),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            // 操作按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (status.state == ServerState.STOPPED || status.state == ServerState.ERROR) {
                    Button(
                        onClick = onStart,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("启动服务器")
                    }
                } else if (status.state == ServerState.RUNNING) {
                    OutlinedButton(
                        onClick = onStop,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Stop,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("停止服务器")
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusDot(state: ServerState) {
    val color = when (state) {
        ServerState.STOPPED -> Color(0xFF9E9E9E)
        ServerState.STARTING -> Color(0xFFFFC107)
        ServerState.RUNNING -> Color(0xFF4CAF50)
        ServerState.STOPPING -> Color(0xFFFF9800)
        ServerState.ERROR -> Color(0xFFF44336)
    }
    Surface(
        modifier = Modifier.size(12.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = color
    ) {}
}

@Composable
private fun InfoItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

fun formatUptime(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m ${s}s"
}
