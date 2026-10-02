package com.kaze.newage.core.ai

/**
 * 控制台命令的执行策略（借鉴 Harness 的权限分层：模式决定工具是否放开）。
 *
 * 三档：
 *  - SUGGEST（默认）：AI 只建议命令，用户点「执行」才发送 —— 对应 P0 安全边界；
 *  - SAFE：查询/低风险命令（list、tps、time、weather…）AI 可直接执行，
 *    其余仍降级为建议卡片；
 *  - ALL：AI 可直接执行任何控制台命令（含 stop 等慎用命令），
 *    用户显式选择才生效，所有执行都记入审计日志。
 *
 * 模组/插件自定义命令（LuckPerms 之类）无法枚举 —— SAFE 档下它们天然落不到白名单，
 * 这是有意为之：宁可直接建议，也不放未知命令自动执行。
 */
object AiCommandPolicy {

    enum class Mode(val id: String, val label: String, val desc: String) {
        SUGGEST(
            "suggest", "仅建议",
            "AI 只给建议命令，由你点「执行」才发送（最安全，默认）",
        ),
        SAFE(
            "safe", "白名单自动",
            "查询类命令（list / tps / time / weather / gamerule 等）AI 直接执行，其余仍给建议卡片",
        ),
        ALL(
            "all", "全部自动",
            "AI 可直接执行任何控制台命令（含 stop 等慎用命令），全部记入审计日志",
        ),
    }

    fun modeById(id: String): Mode = Mode.entries.firstOrNull { it.id == id } ?: Mode.SUGGEST

    /** SAFE 档可直接执行的首词（查询与可逆、低影响的操作） */
    private val SAFE_FIRST_WORDS = setOf(
        "list", "tps", "ping", "seed", "version", "help",
        "save-all", "save-on",
        "time", "weather", "gamerule", "difficulty",
    )

    /** 该命令在指定模式下是否允许 AI 自动执行 */
    fun canAuto(mode: Mode, command: String): Boolean = when (mode) {
        Mode.ALL -> true
        Mode.SAFE -> command.trim().lowercase().substringBefore(' ') in SAFE_FIRST_WORDS
        Mode.SUGGEST -> false
    }
}
