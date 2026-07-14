package com.mcserver.launcher.data

import com.mcserver.launcher.server.FileManager
import com.mcserver.launcher.server.HealthChecker
import com.mcserver.launcher.server.ServerStateManager

/**
 * 服务器仪表盘摘要数据 — 聚合首页需要展示的所有关键指标。
 * 借鉴 Pterodactyl 和 MCSManager 的仪表盘设计。
 */
data class DashboardSummary(
    val serverStatus: ServerStatus = ServerStatus(),
    val jreInfo: JreInfo = JreInfo(),
    val pluginCount: Int = 0,
    val worldCount: Int = 0,
    val playerCount: Int = 0,
    val opCount: Int = 0,
    val backupCount: Int = 0,
    val diskUsage: FileManager.DiskUsage? = null,
    val healthResult: HealthChecker.HealthResult? = null,
    val stats: ServerStateManager.ServerStats = ServerStateManager.ServerStats(),
    val networkState: String = "未知"
)
