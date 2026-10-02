package com.kaze.newage.core.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 模型回复的解析与建议命令的安全闸门。
 *
 * 模型输出是**不可信输入**：可能输出散文、markdown 围栏、编造多行命令。
 * 这里统一收口成 {analysis, command}；命令必须过 [sanitizeCommand]
 * （去 / 前缀、拒绝换行与超长）才允许上屏，解析不出 JSON 时整段当分析文本、
 * 绝不把未经清洗的文字当命令。
 */
object AiSuggestion {

    @Serializable
    internal data class RawReply(val analysis: String = "", val command: String = "")

    data class Parsed(val analysis: String, val command: String)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 代码围栏 ```json {…}``` 里的 JSON（DOT_MATCHES_ALL 让 . 跨行） */
    private val FENCED = Regex("""```(?:json)?\s*(\{.*?\})\s*```""", RegexOption.DOT_MATCHES_ALL)

    /** 思考类模型（DeepSeek 之外的部分服务商）会把推理以 <think> 段混进 content */
    private val THINK_BLOCK = Regex("""<think>.*?</think>""", RegexOption.DOT_MATCHES_ALL)
    private val UNCLOSED_THINK = Regex("""^\s*<think>(?!.*?</think>).*""", RegexOption.DOT_MATCHES_ALL)

    /**
     * 命令最大长度。MC 命令上限 32500，这里远小于它 —— 超过 200 字符的
     * "控制台命令"几乎必然是模型编造的（真正的命令都极短）。
     */
    private const val MAX_COMMAND_LEN = 200

    /** 分析文本上限： UI 直接展示，防模型跑飞把气泡撑成几千行 */
    private const val MAX_ANALYSIS_LEN = 2000

    /**
     * 剥离思考类模型混在正文里的 <think> 推理段。
     * DeepSeek 官方把推理放在独立的 reasoning_content 字段（content 本来就干净），
     * 这里主要兜住把推理直接写进 content 的其他 OpenAI 兼容服务。
     */
    fun stripThinking(raw: String): String {
        val stripped = THINK_BLOCK.replace(raw, " ")
        // 开头就是未闭合的 <think>（整段只有推理、没有结论）：没有可用的答案
        return UNCLOSED_THINK.replace(stripped, "").trim()
    }

    fun parse(raw: String): Parsed {
        val text = stripThinking(raw)
        if (text.isEmpty()) return Parsed("", "")
        val fenced = FENCED.find(text)?.groupValues?.get(1)
        val payload = fenced ?: run {
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start >= 0 && end > start) text.substring(start, end + 1) else null
        }
        if (payload != null) {
            val reply = runCatching { json.decodeFromString<RawReply>(payload) }.getOrNull()
            if (reply != null) {
                val cmd = sanitizeCommand(reply.command) ?: ""
                return Parsed(reply.analysis.trim().take(MAX_ANALYSIS_LEN), cmd)
            }
        }
        return Parsed(text.take(MAX_ANALYSIS_LEN), "")
    }

    /**
     * 清洗模型给出的命令；不可用返回 null。
     *
     * 拒绝：任何控制字符（\n \r \t 都在 < ' ' 里 —— 换行意味着"一条消息塞多条命令"，
     * 是最必须堵的口子）、超长；修正：去首尾空白、剥开头的 /
     * （控制台走的是服务端 stdin，不需要斜杠，带了反而 Unknown command）。
     */
    fun sanitizeCommand(raw: String?): String? {
        if (raw == null) return null
        var cmd = raw.trim()
        if (cmd.startsWith("/")) cmd = cmd.trimStart('/').trim()
        if (cmd.isEmpty()) return null
        if (cmd.any { it < ' ' || it.code == 0x7F }) return null
        if (cmd.length > MAX_COMMAND_LEN) return null
        return cmd
    }

    /**
     * 危险命令分级（P0 已经是"建议 + 人工确认"，但卡片上仍要给醒目提示）。
     * 这些命令直接影响玩家 / 存档 / 权限 / 服务端生命。
     */
    private val DANGEROUS_FIRST_WORDS = setOf(
        "stop", "op", "deop", "ban", "ban-ip", "pardon", "pardon-ip",
        "kick", "kill", "whitelist", "save-off", "save-on",
    )

    fun isDangerous(command: String): Boolean =
        command.trim().lowercase().substringBefore(' ') in DANGEROUS_FIRST_WORDS
}
