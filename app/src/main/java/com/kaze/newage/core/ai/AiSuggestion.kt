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
    internal data class RawReply(
        val analysis: String = "",
        val command: String = "",
        val tool: ToolCallDto? = null,
    )

    @Serializable
    internal data class ToolCallDto(val name: String = "", val path: String = "", val content: String = "")

    /** 一次工具调用（扁平结构，抗模型输出变形）；name 只认 read_file / list_dir / write_file */
    data class AiToolCall(val name: String, val path: String, val content: String)

    private val KNOWN_TOOLS = setOf("read_file", "list_dir", "write_file")

    data class Parsed(
        val analysis: String,
        val command: String,
        /** 需要执行的工具；null = 本轮是最终回答 */
        val tool: AiToolCall? = null,
    )

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
     * 剥离思考类模型混在正文里的 <think> 推理段，并把推理内容提取出来供 UI 展示。
     * DeepSeek 官方把推理放在独立的 reasoning_content 字段（content 本来就干净），
     * 这里主要兜住把推理直接写进 content 的其他 OpenAI 兼容服务。
     * 返回（剥掉推理后的正文, 推理过程文本或 null）。
     */
    fun splitThinking(raw: String): Pair<String, String?> {
        val blocks = THINK_BLOCK.findAll(raw).mapNotNull { m ->
            m.value.removePrefix("<think>").removeSuffix("</think>").trim().takeIf { it.isNotEmpty() }
        }
        // 开头就是未闭合的 <think>（整段只有推理、没有结论）：没有可用的答案
        val unclosed = UNCLOSED_THINK.find(raw)?.value
            ?.removePrefix("<think>")?.trim()?.takeIf { it.isNotEmpty() }
        val stripped = UNCLOSED_THINK.replace(THINK_BLOCK.replace(raw, " "), "").trim()
        val reasoning = (blocks + listOfNotNull(unclosed)).joinToString("\n\n").trim().takeIf { it.isNotEmpty() }
        return stripped to reasoning
    }

    /** 只需要剥掉推理段时的便捷封装 */
    fun stripThinking(raw: String): String = splitThinking(raw).first

    fun parse(raw: String): Parsed {
        val text = stripThinking(raw)
        if (text.isEmpty()) return Parsed("", "")
        // 两条提取路：代码围栏内的候选 + 首个大括号到末尾大括号的候选。
        // 任一解码成功即用 —— 围栏正则遇到 content 里带 ``` 的 write_file 会被截短，
        // 双路保证这种输出仍能解析出完整对象。
        val fenced = FENCED.find(text)?.groupValues?.get(1)
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        val span = if (start >= 0 && end > start) text.substring(start, end + 1) else null
        val reply = listOfNotNull(fenced, span).distinct().firstNotNullOfOrNull { candidate ->
            runCatching { json.decodeFromString<RawReply>(candidate) }.getOrNull()
        }
        if (reply != null) {
            val cmd = sanitizeCommand(reply.command) ?: ""
            val tool = reply.tool
                ?.takeIf { it.name.trim().lowercase() in KNOWN_TOOLS && it.path.isNotBlank() }
                ?.let { AiToolCall(it.name.trim().lowercase(), it.path.trim(), it.content) }
            return Parsed(reply.analysis.trim().take(MAX_ANALYSIS_LEN), cmd, tool)
        }
        // 解析不出 JSON：**不能**把整段原文当分析上屏。模型跑到一半被截断时，原文里就是
        // 半截工具调用（可能还有 content 字段里成篇的文件内容），直接显示等于把"残缺的
        // 工具 JSON"当成 AI 的结论给用户看 —— 用户会以为 AI 已经读过/改过什么。
        // 这里只保留像人话的部分，全被滤掉就给一句如实说明。
        val fallback = sanitizeFallbackText(text).take(MAX_ANALYSIS_LEN)
        return Parsed(fallback.ifBlank { FALLBACK_NOTE }, "")
    }

    /** JSON 无法解析时的说明文案（不暴露半截 JSON） */
    internal const val FALLBACK_NOTE =
        "（模型这次的回复不是预期的 JSON 格式，已略去其中无法解析的结构化内容。可以再问一次或换个说法。）"

    /**
     * 兜底文本：去掉代码围栏整块与"看起来是 JSON 结构"的行。
     * 保留散文（模型有时就只是想说话），滤掉 `{`、`"analysis": …` 这类结构化残片。
     */
    internal fun sanitizeFallbackText(raw: String): String {
        val withoutFences = FENCED_BLOCK.replace(raw, " ")
        val kept = withoutFences.lines().filterNot { looksLikeJsonLine(it) }
        // 连续空行压成一个，避免滤掉 JSON 行之后留下大片空白
        return kept.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
    }

    private fun looksLikeJsonLine(line: String): Boolean {
        val t = line.trim()
        if (t.isEmpty()) return false
        if (t.startsWith("{") || t.startsWith("}") || t.startsWith("[") || t.startsWith("]")) return true
        if (t.startsWith("```")) return true
        return JSON_KEY_LINE.containsMatchIn(t)
    }

    private val FENCED_BLOCK = Regex("```.*?```", RegexOption.DOT_MATCHES_ALL)

    /** 形如 `"tool": {` / `"content": "…"` 的键值行（残缺 JSON 的典型形态） */
    private val JSON_KEY_LINE = Regex(
        """^"(analysis|command|tool|name|path|content|reasoning)"\s*:"""
    )

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
     *
     * `execute` / `reload` / `restart` 是审计补进来的：它们不改玩家名单，但会让服务端
     * **以服务端权限执行任意命令或整机重启**（execute 是 op 等级 2 的万能入口，
     * reload/restart 会打断所有在线玩家）—— 危害不比 ban 小，提示不能漏。
     */
    private val DANGEROUS_FIRST_WORDS = setOf(
        "stop", "op", "deop", "ban", "ban-ip", "pardon", "pardon-ip",
        "kick", "kill", "whitelist", "save-off", "save-on",
        "execute", "reload", "restart",
    )

    fun isDangerous(command: String): Boolean =
        command.trim().lowercase().substringBefore(' ') in DANGEROUS_FIRST_WORDS
}
