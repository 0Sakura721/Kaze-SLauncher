package com.kaze.newage.core.ai

import com.kaze.newage.core.console.ConsoleLine
import com.kaze.newage.core.console.LineType
import com.kaze.newage.core.monitor.ProcessStats
import java.util.Locale

/**
 * 给模型准备的"现场快照"：全部来自启动器本地采集，只读、不写任何文件。
 *
 * 控制台喂"最近一段 + 窗口内的告警/报错节选"，而不是整份日志 ——
 * 省 token、避免超长，也减少模型在几千行无关输出里产生幻觉的机会。
 */
object AiContext {

    /** 直接附在上下文里的最新日志行数 */
    private const val TAIL_LINES = 100

    /** 报错扫描窗口（行）：比尾部更早一段里的异常也要让模型看到 */
    private const val SCAN_WINDOW = 3000

    /** 报错节选上限（Forge 启动一次能刷出几百条 stack trace，必须截） */
    private const val MAX_ERROR_LINES = 30

    /** 单行截断长度（Forge 的 stack trace 一行能上千字符） */
    private const val MAX_LINE_LEN = 240

    fun build(
        instanceName: String,
        mcVersion: String,
        coreType: String,
        javaMajor: Int,
        memoryMb: Int,
        stateLabel: String,
        uptimeSec: Long,
        players: List<String>,
        stats: ProcessStats.Reading?,
        lines: List<ConsoleLine>,
    ): String = buildString {
        append("【实例】").append(instanceName.ifBlank { "（未命名）" })
        append("（MC ").append(mcVersion.ifBlank { "未知" })
        append(" · ").append(coreType)
        append(" · Java ").append(javaMajor)
        append(" · 内存上限 ").append(memoryMb).append("MB）\n")
        append("【状态】").append(stateLabel)
        if (uptimeSec > 0) append("，已运行 ").append(formatUptime(uptimeSec))
        append('\n')
        append("【在线玩家】")
        if (players.isEmpty()) append("无") else append("${players.size} 人：").append(players.joinToString("、"))
        append('\n')
        if (stats != null) {
            append("【资源】CPU ").append(fmt("%.0f", stats.cpuPercent)).append('%')
            // 单核占用可以超 100%，按"几个核"说才不会误导
            if (stats.coresUsed >= 1.05f) {
                append("（约 ").append(fmt("%.1f", stats.coresUsed)).append(" 核）")
            }
            if (stats.rssKb < 0) {
                append(" · 服务端内存读不到")
            } else {
                append(" · 服务端内存 ").append(fmt("%.2f", stats.rssKb / 1024f / 1024f)).append("G")
            }
            if (stats.availMemKb >= 0) {
                append(" · 整机可用 ").append(fmt("%.1f", stats.availMemKb / 1024f / 1024f)).append("G")
            }
            if (stats.lowMemory) append("（低内存！）")
            append('\n')
        }
        val tail = lines.takeLast(TAIL_LINES)
        if (tail.isNotEmpty()) {
            append("\n【控制台最近 ").append(tail.size).append(" 行（旧→新）】\n")
            tail.forEach { appendLine(truncate(it.text)) }
            // 窗口里更早的告警/报错（tail 已含的不重复）
            val tailSeq = tail.mapTo(HashSet()) { it.seq }
            val alerts = lines.takeLast(SCAN_WINDOW)
                .filter { it.seq !in tailSeq && isAlert(it) }
                .takeLast(MAX_ERROR_LINES)
            if (alerts.isNotEmpty()) {
                append("\n【更早的告警/报错（最多 ").append(MAX_ERROR_LINES).append(" 条）】\n")
                alerts.forEach { appendLine(truncate(it.text)) }
            }
        }
    }

    /** 告警行：显式的 Warn/Error 级别，或文本含 ERROR / Exception（回填的历史行级别都是 Info） */
    internal fun isAlert(line: ConsoleLine): Boolean =
        line.type == LineType.Error || line.type == LineType.Warn ||
            line.text.contains("ERROR") || line.text.contains("Exception")

    internal fun truncate(text: String): String =
        if (text.length <= MAX_LINE_LEN) text else text.take(MAX_LINE_LEN) + "…"

    internal fun formatUptime(sec: Long): String {
        val h = sec / 3600
        val m = sec % 3600 / 60
        return when {
            h > 0 -> "${h}小时${m}分"
            m > 0 -> "${m}分${sec % 60}秒"
            else -> "${sec}秒"
        }
    }

    /** 指标文本统一 US locale，避免某些系统区域把小数点写成逗号 */
    private fun fmt(pattern: String, v: Float): String = String.format(Locale.US, pattern, v)
}

/**
 * 系统提示词：约束模型只做只读诊断、最多建议一条命令、输出严格 JSON。
 *
 * 提示词里明确"没有的信息就明说"，配合 context 只喂窗口化日志 ——
 * 幻觉的抑制靠"不给它编不出来的素材 + 要求它承认看不到"，而不是指望模型自觉。
 */
object AiPrompt {

    fun system(context: String): String =
        """
        你是 Kaze SLauncher（安卓 Minecraft 服务端启动器）内置的 AI 助手，帮用户诊断和管理他的 Minecraft 服务端。你只能看、不能动：真正的操作永远由用户确认后才执行。

        当前实例的实时状态（由启动器采集，可信）：
        $context

        规则：
        1. 回答只基于上面的状态与日志；没有的信息就明说"日志里看不出来"，绝不编造。
        2. analysis 用简体中文直接回答问题，尽量简短（8 行以内）；有报错就解释原因与修法。
        3. 确需操作时，最多在 command 字段建议一条该核心/版本支持的 Minecraft 服务端控制台命令（不带 /，例如 list、whitelist add Steve）；不需要操作时 command 填空字符串。
        4. stop / op / ban / kick / whitelist 这类影响玩家或服务端生命的命令，只在用户明确要求时才建议。
        5. 只输出一个 JSON 对象，格式：{"analysis":"…","command":""}，不要输出 JSON 之外的任何文字。
        6. 若上下文包含【联网搜索结果】，可引用其中信息并注明来源（域名）；搜索结果与本地日志冲突时，以本地日志为准。
        """.trimIndent()

    /**
     * 联网搜索的第一轮：生成查询词。独立的小请求（思考模式强制关、max_tokens 很小），
     * 模型判断问题属于本地实时状态时输出空 query，省一次搜索调用。
     */
    fun searchQuerySystem(): String =
        """
        你是搜索引擎查询词生成器。根据用户的问题生成一条适合搜索引擎的查询词（中文为主，保留错误码、版本号等原文关键词，例如 "Paper 1.21 UnsupportedClassVersionError"）。
        只输出一个 JSON 对象：{"query":"…"}。
        如果问题只关于服务器本地实时状态（谁在线、内存占用、当前日志现象等）不需要联网，输出 {"query":""}。
        """.trimIndent()
}
