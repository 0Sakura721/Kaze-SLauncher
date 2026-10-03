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

    /** 一次工具调用（扁平结构，抗模型输出变形）；name 只认 read_file / list_dir / write_file / fetch_page / execute_command / read_memory / write_memory */
    data class AiToolCall(val name: String, val path: String, val content: String)

    private val KNOWN_TOOLS = setOf(
        "read_file", "list_dir", "write_file", "fetch_page",
        "execute_command", "read_memory", "write_memory",
    )

    /** 原生工具调用 arguments 的解析结果（fetch_page 用 url，execute_command 用 command，其余用 path/content） */
    @Serializable
    data class NativeArgs(
        val path: String = "",
        val content: String = "",
        val url: String = "",
        val command: String = "",
    )

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
        // 解析不出 JSON：整段当分析文本（模型可能只是没按格式输出，用户仍该看到它的话）。
        // 但**残缺的工具调用 JSON** 例外：那是一坨 `{"analysis":"…","tool":{"name":"read_file",…`，
        // 直接上屏会让人以为 AI 在胡言乱语，而真相是它的输出被截断了（max_tokens 用尽）。
        // 这种情况给一句能读懂的解释，别把半截 JSON 当"分析"展示。
        if (looksLikeTruncatedToolJson(text)) {
            return Parsed(
                "模型这一轮返回的工具调用 JSON 不完整（多半是输出被截断），已忽略、未执行任何工具。" +
                    "可以把问题说得更具体，或换个问法重试。",
                "",
            )
        }
        return Parsed(text.take(MAX_ANALYSIS_LEN), "")
    }

    /** 像"工具调用 JSON 但没解析成功"：整段以 { 开头且带 tool/name 字段 */
    private fun looksLikeTruncatedToolJson(text: String): Boolean {
        val t = text.trimStart()
        if (!t.startsWith("{")) return false
        return t.contains("\"tool\"") || t.contains("\"name\"")
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
     * 两档语义：
     *  - [DANGEROUS_FIRST_WORDS]：直接动玩家 / 权限 / 服务端生命 —— 红字"影响玩家或服务端"；
     *  - [DESTRUCTIVE_FIRST_WORDS]：能批量改世界或触发重载执行 —— 红字"会改动世界数据"。
     * 模组/插件自定义命令（LuckPerms 之类）无法枚举 —— 最终防线始终是人工确认这一层，
     * 警示名单只覆盖原版可枚举的部分。
     */
    private val DANGEROUS_FIRST_WORDS = setOf(
        "stop", "op", "deop", "ban", "ban-ip", "pardon", "pardon-ip",
        "kick", "kill", "whitelist", "save-off", "save-on",
        // restart 不是原版命令，但 Paper/Spigot 与多数管理插件都提供它：效果等同重启服务端
        "restart",
    )

    private val DESTRUCTIVE_FIRST_WORDS = setOf(
        "data", "execute", "fill", "clone", "setblock", "forceload",
        "summon", "reload", "tick", "worldborder", "debug", "jfr",
    )

    fun isDangerous(command: String): Boolean {
        val first = command.trim().lowercase().substringBefore(' ')
        return first in DANGEROUS_FIRST_WORDS || first in DESTRUCTIVE_FIRST_WORDS
    }
}
