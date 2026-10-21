package com.kaze.newage.core.ai

import kotlinx.serialization.Serializable

/**
 * 一次工具执行在界面上留下的一条"步骤"（借鉴 Operit 的任务工作台：执行过程要看得见）。
 *
 * ## 为什么要有它
 * 改造前工具执行只有一行 `· 读取 xxx ·` 小字：**看不出成功还是失败、花了多久、
 * 被策略拒绝还是自己报错**。用户看到"AI 说它读过了"，却不知道到底读到没有 ——
 * 而失败恰恰是最需要被看见的情况（不然会以为回答是"基于数据"给的）。
 *
 * ## 状态语义
 *  - [Status.RUNNING]：正在执行（写盘等慢操作会短暂显示）
 *  - [Status.OK]：执行成功
 *  - [Status.FAILED]：执行抛错/服务端返回失败
 *  - [Status.DENIED]：被策略拦下（用户设了「禁止」、或敏感文件确认被拒）
 *  - [Status.SKIPPED]：本轮没执行（例如一次只处理一个待确认项，其余先跳过）
 *
 * 纯数据 + 纯函数（[isBad]、[statusLabel]），可在 JVM 单测里直接验。
 */
@Serializable
data class AiToolStep(
    /** 做了什么（如「读取 server.properties」），已过展示净化 */
    val title: String,
    /** 补充信息（文件名/原因摘要），可为空 */
    val detail: String = "",
    val status: Status = Status.OK,
    /** 耗时（毫秒）；0 = 未计时 */
    val elapsedMs: Long = 0L,
) {

    @Serializable
    enum class Status(val label: String) {
        RUNNING("进行中"),
        OK("完成"),
        FAILED("失败"),
        DENIED("已拒绝"),
        SKIPPED("已跳过"),
    }

    /** 是不是"用户需要留意的结果"（失败/被拒/被跳过）—— 界面据此上色 */
    val isBad: Boolean
        get() = status == Status.FAILED || status == Status.DENIED || status == Status.SKIPPED

    /** 面的状态文字，如「完成 · 1.2 秒」 */
    fun statusText(): String {
        if (status == Status.RUNNING || elapsedMs <= 0L) return status.label
        return "${status.label} · ${formatElapsed(elapsedMs)}"
    }

    companion object {
        /** 把毫秒渲染成人看的时间：<1s 用毫秒，<60s 用秒，再长用「分 秒」 */
        fun formatElapsed(ms: Long): String = when {
            ms < 1000L -> "${ms}ms"
            ms < 60_000L -> String.format(java.util.Locale.US, "%.1f 秒", ms / 1000.0)
            else -> {
                val total = ms / 1000
                "${total / 60} 分 ${total % 60} 秒"
            }
        }
    }
}