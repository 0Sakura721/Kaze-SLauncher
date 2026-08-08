package com.mcserver.launcher.ui.screens.home

import androidx.compose.runtime.Composable
import com.mcserver.launcher.data.InstanceStatus

/**
 * 顶部紧凑信息块（Deprecated，已合并到 HomeScreen 的 CompactTopBar 中）
 * 保留此函数以兼容其他可能的引用，内容改为空。
 * 实际主页顶部显示使用 CompactTopBar。
 */
@Deprecated("Use CompactTopBar in HomeScreen directly")
@Composable
fun DashboardHeader(
    status: InstanceStatus,
    instanceCount: Int,
    playerCount: Int,
    uptimeSec: Long,
    envReady: Boolean
) {
    // No-op：信息已移至 HomeScreen 顶部紧凑统计条
}
