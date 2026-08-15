package com.kaze.newage.ui

import com.kaze.newage.core.server.ServerState
import com.kaze.newage.ui.components.StatusTone

/** ServerState → 状态球基调 */
fun ServerState.toTone(): StatusTone = when (this) {
    ServerState.Running -> StatusTone.Running
    ServerState.Starting, ServerState.FirstRun, ServerState.AcceptingEula, ServerState.Stopping -> StatusTone.Busy
    ServerState.Error -> StatusTone.Error
    else -> StatusTone.Idle
}

/** ServerState → 中文状态文案（全应用统一词汇） */
fun ServerState.toLabel(): String = when (this) {
    ServerState.Idle -> "未启动"
    ServerState.Starting -> "启动中"
    ServerState.FirstRun -> "首次启动 · 生成 eula.txt"
    ServerState.AcceptingEula -> "已同意 EULA，正在重启"
    ServerState.Running -> "运行中"
    ServerState.Stopping -> "停止中"
    ServerState.Stopped -> "已停止"
    ServerState.Error -> "启动失败"
}

/** 是否处于过渡状态（不可操作启停） */
fun ServerState.isBusy(): Boolean =
    this == ServerState.Starting || this == ServerState.FirstRun ||
        this == ServerState.AcceptingEula || this == ServerState.Stopping

/** 秒 → 「X 小时 Y 分」/「X 分 Y 秒」 */
fun formatUptime(totalSec: Long): String {
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return when {
        h > 0 -> "${h} 小时 ${m} 分"
        m > 0 -> "${m} 分 ${s} 秒"
        else -> "${s} 秒"
    }
}
