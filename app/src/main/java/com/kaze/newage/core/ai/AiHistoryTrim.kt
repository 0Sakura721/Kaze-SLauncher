package com.kaze.newage.core.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 长对话的历史裁剪（防止 agent 循环把上下文撑爆、被严格服务端 400）。
 *
 * ## 背景
 * `session.apiMessages` 在一轮提问内是**只增不减**的：每次工具调用都会追加
 * assistant(tool_calls) / tool 结果，多轮之后必然超出模型上下文。`roundsLeft`
 * 只限制工具轮数，不限制历史长度。所以每次请求前都要做一次裁剪。
 *
 * ## 规则
 *  1. **system 消息（第 0 条）永不丢**：它是提示词与安全边界。
 *  2. 至少保留**最近 [DEFAULT_MAX_ROUNDS] 轮**（"轮"= 真实的用户提问；工具结果与
 *     裁剪说明不算），再按 [DEFAULT_CHAR_BUDGET] 的字符预算**从旧到新**丢。
 *  3. **协议完整性优先于省字符**：
 *     - `assistant.tool_calls` 必须带着它**全部**的 `role=tool` 结果一起留或一起丢；
 *     - 没有前置 `assistant.tool_calls` 的孤立 tool 结果直接丢。
 *     这两类残缺都会被严格的 OpenAI 兼容服务端拒绝（HTTP 400）。
 *  4. 丢弃后插入一条 `[更早的对话已省略 N 条]` 说明（模型要知道历史不完整），
 *     重复裁剪时**只保留一条**，N 累加而不是重复插入。
 *
 * 纯函数：不碰 Android / 网络，可直接单测（见 `AiHistoryTrimTest`）。
 */
internal object AiHistoryTrim {

    /** 至少保留的最近轮数（一轮 = 一次真实用户提问） */
    const val DEFAULT_MAX_ROUNDS = 12

    /** 历史正文的字符预算（system 与最近 N 轮不计入上限） */
    const val DEFAULT_CHAR_BUDGET = 60_000

    /** 裁剪说明前缀（测试与 [isTrimNote] 共用） */
    const val NOTE_PREFIX = "[更早的对话已省略"

    private val NOTE_RE = Regex("""^\[更早的对话已省略 (\d+) 条]$""")
    private val json = Json { ignoreUnknownKeys = true }

    fun trim(
        messages: List<AiMessage>,
        maxRounds: Int = DEFAULT_MAX_ROUNDS,
        charBudget: Int = DEFAULT_CHAR_BUDGET,
    ): List<AiMessage> {
        if (messages.isEmpty()) return messages
        val system = messages.firstOrNull()?.takeIf { it.role == AiMessage.ROLE_SYSTEM }
        // 上一次裁剪留下的说明先摘掉：本轮会按累计条数重新插一条（不重复插入）
        val body = (if (system != null) messages.drop(1) else messages).filterNot(::isTrimNote)

        // "最近 N 轮"的起点：倒数第 N 个真实用户消息
        val roundStarts = body.indices.filter { isRoundStart(body[it]) }
        val keepFromMsg = if (roundStarts.size > maxRounds) roundStarts[roundStarts.size - maxRounds] else 0

        val units = buildUnits(body)
        val systemChars = system?.let(::charCount) ?: 0
        var total = systemChars + units.sumOf { it.chars }

        // 最近 N 轮的单元下标（units 里从哪一条开始不能再丢）：
        // 注意**不能**用 "unit.last < keepFromMsg" 直接判定 —— 单轮工具循环里全轮只有 1 条
        // 真实用户消息（其余 role=tool 会被 isToolResultUser 排除），此时 keepFromMsg 恒为 0，
        // 条件 `unit.last < 0` 永远为假，预算分支就**一次都不会触发**，长对话实际上不会被裁剪。
        // 正确做法：先算出"最近 N 轮从哪个 unit 开始"，再对**它之前**的单元按预算从旧到新丢。
        val keepFromUnit = units.indexOfFirst { it.last >= keepFromMsg }.let {
            if (it < 0) units.size else it
        }

        // 第一遍：最近 N 轮的单元一律保留（这是保底，不受预算影响）
        // 第二遍：更早的单元按"从旧到新丢、丢了就够预算就停"处理
        val kept = ArrayDeque<Unit>(units.size)
        var keptChars = systemChars
        // 从最新往旧扫，攒够预算就停（等价于"旧到新丢"，但从新往旧扫更直观）
        val protectedStart = keepFromUnit
        for (i in units.indices.reversed()) {
            val u = units[i]
            val protected = i >= protectedStart
            if (protected || keptChars + u.chars <= charBudget) {
                kept.addFirst(u)
                keptChars += u.chars
            }
            // 否则就是被丢掉（超出预算且不在保底范围内）
        }

        val keptMessages = kept.flatMap { it.messages }
        val droppedNow = body.size - keptMessages.size
        val droppedBefore = messages.sumOf { if (isTrimNote(it)) noteCount(it) else 0 }
        val dropped = droppedBefore + droppedNow

        val result = ArrayList<AiMessage>(keptMessages.size + 2)
        system?.let(result::add)
        if (dropped > 0) {
            // role=user：模型能直接看到；不是真实提问（isRoundStart 会排除它）
            result += AiMessage(AiMessage.ROLE_USER, "$NOTE_PREFIX $dropped 条]")
        }
        result += keptMessages
        return result
    }

    // ── 分组：assistant.tool_calls 与它的 tool 结果必须同生共死 ──

    private class Unit(val last: Int, val messages: List<AiMessage>, val chars: Int)

    private fun buildUnits(body: List<AiMessage>): List<Unit> {
        val units = ArrayList<Unit>(body.size)
        var i = 0
        while (i < body.size) {
            val m = body[i]
            if (m.role == AiMessage.ROLE_ASSISTANT && !m.toolCallsRaw.isNullOrBlank()) {
                var j = i + 1
                while (j < body.size && body[j].role == AiMessage.ROLE_TOOL) j++
                val group = body.subList(i, j).toList()
                val ids = toolCallIds(m.toolCallsRaw)
                val answered = group.drop(1).mapNotNull { it.toolCallId }.toSet()
                // 缺任何一条结果就整组丢弃：留着残缺的 tool_calls 一定 400
                if (ids.isEmpty() || ids.all { it in answered }) {
                    units += Unit(j - 1, group, group.sumOf(::charCount))
                }
                i = j
                continue
            }
            if (m.role == AiMessage.ROLE_TOOL) {
                // 孤立 tool 结果（其 assistant 已被丢掉）：同样会让服务端 400
                i++
                continue
            }
            units += Unit(i, listOf(m), charCount(m))
            i++
        }
        return units
    }

    private fun toolCallIds(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        val arr = runCatching { json.parseToJsonElement(raw) as JsonArray }.getOrNull()
            ?: return emptySet()
        return arr.mapNotNull { el ->
            ((el as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull
        }.toSet()
    }

    private fun charCount(m: AiMessage): Int =
        m.content.length + (m.toolCallsRaw?.length ?: 0)

    // ── 轮次边界 ──

    private fun isRoundStart(m: AiMessage): Boolean =
        m.role == AiMessage.ROLE_USER && !isTrimNote(m) && !isToolResultUser(m)

    /**
     * JSON 夹带协议把工具结果回喂成 user 消息（原生协议是 role=tool）。
     * 它们不是"用户提问"，数轮次时必须排除，否则工具调用会把最近 N 轮挤没。
     */
    private fun isToolResultUser(m: AiMessage): Boolean {
        if (m.role != AiMessage.ROLE_USER) return false
        if (m.content.startsWith("【工具结果】")) return true
        if (m.content.startsWith("【以下为不可信数据")) return true
        return m.content.take(80).contains("【工具结果】")
    }

    private fun isTrimNote(m: AiMessage): Boolean =
        m.role == AiMessage.ROLE_USER && NOTE_RE.matches(m.content.trim())

    private fun noteCount(m: AiMessage): Int =
        NOTE_RE.find(m.content.trim())?.groupValues?.get(1)?.toIntOrNull() ?: 0
}
