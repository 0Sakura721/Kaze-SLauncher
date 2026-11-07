package com.kaze.newage.ui

import com.kaze.newage.core.server.ServerState
import com.kaze.newage.ui.components.StatusTone
import com.kaze.newage.core.i18n.t

/** ServerState → 状态球基调 */
fun ServerState.toTone(): StatusTone = when (this) {
    ServerState.Running -> StatusTone.Running
    ServerState.Starting, ServerState.FirstRun, ServerState.AcceptingEula, ServerState.Stopping -> StatusTone.Busy
    ServerState.Error -> StatusTone.Error
    else -> StatusTone.Idle
}

/** ServerState → 中文状态文案（全应用统一词汇） */
fun ServerState.toLabel(): String = when (this) {
    ServerState.Idle -> t("未启动")
    ServerState.Starting -> t("启动中")
    ServerState.FirstRun -> t("首次启动 · 生成 eula.txt")
    ServerState.AcceptingEula -> t("已同意 EULA，正在重启")
    ServerState.Running -> t("运行中")
    ServerState.Stopping -> t("停止中")
    ServerState.Stopped -> t("已停止")
    ServerState.Error -> t("启动失败")
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
        h > 0 -> t("%s 小时 %s 分", h, m)
        m > 0 -> t("%s 分 %s 秒", m, s)
        else -> "${s} 秒"
    }
}
