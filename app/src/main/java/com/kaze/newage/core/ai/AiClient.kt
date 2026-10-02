package com.kaze.newage.core.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

@Serializable
internal data class ChatResponseDto(
    val choices: List<ChoiceDto> = emptyList(),
    val usage: UsageDto? = null,
)

@Serializable
internal data class UsageDto(
    val prompt_tokens: Int = 0,
    val completion_tokens: Int = 0,
    val prompt_tokens_details: PromptDetailsDto? = null,
    val completion_tokens_details: CompletionDetailsDto? = null,
)

@Serializable
internal data class PromptDetailsDto(val cached_tokens: Int = 0)

@Serializable
internal data class CompletionDetailsDto(val reasoning_tokens: Int = 0)

private fun UsageDto?.toAiUsage(): AiUsage? = this?.let {
    AiUsage(
        promptTokens = it.prompt_tokens,
        completionTokens = it.completion_tokens,
        cachedTokens = it.prompt_tokens_details?.cached_tokens ?: 0,
        reasoningTokens = it.completion_tokens_details?.reasoning_tokens ?: 0,
    )
}

@Serializable
internal data class ChoiceDto(
    val message: MessageDto = MessageDto(),
    val finish_reason: String? = null,
)

@Serializable
internal data class MessageDto(
    val role: String = "",
    val content: String? = null,
    /** 思考内容存在多种形态，统一用 JsonElement 接再规范化（见 [normalizeReasoning]） */
    val reasoning_content: kotlinx.serialization.json.JsonElement? = null,
    /** OpenRouter 风格的推理字段（字符串 / 数组 / 对象都可能） */
    val reasoning: kotlinx.serialization.json.JsonElement? = null,
    /** OpenRouter 新版的部分供应商走 reasoning_details */
    val reasoning_details: kotlinx.serialization.json.JsonElement? = null,
    /** 原生工具调用（非流式响应） */
    val tool_calls: List<StreamToolCallDto> = emptyList(),
)

// ── 流式（SSE）──

@Serializable
internal data class ChatStreamChunkDto(
    val choices: List<StreamChoiceDto> = emptyList(),
    val usage: UsageDto? = null,
)

@Serializable
internal data class StreamChoiceDto(
    val delta: DeltaDto = DeltaDto(),
    val finish_reason: String? = null,
)

@Serializable
internal data class DeltaDto(
    val content: String? = null,
    val reasoning_content: String? = null,
    /** 流式原生工具调用：按 index 分片到达，需要拼装 */
    val tool_calls: List<StreamToolCallDto> = emptyList(),
)

@Serializable
internal data class StreamToolCallDto(
    val index: Int = 0,
    val id: String? = null,
    val type: String? = null,
    val function: FnDto? = null,
)

@Serializable
internal data class FnDto(val name: String? = null, val arguments: String? = null)

/** 一轮对话的完整回复：正文 + 可选的思考过程 + 用量 + 原生工具调用 */
data class AiReply(
    /** 模型的推理过程（reasoning_content 或正文里的 <think> 段）；null = 没有思考内容 */
    val reasoning: String?,
    /** 最终回答正文 */
    val content: String,
    /** token 用量（含缓存命中/思考拆分）；null = 服务商未返回 */
    val usage: AiUsage? = null,
    /** 原生工具调用（function calling）；空 = 无 */
    val toolCalls: List<NativeToolCall> = emptyList(),
    /** 原生工具调用的原始 JSON（回喂历史保真用） */
    val rawToolCallsJson: String? = null,
    /** true = 因取消/超时中止，内容是不完整的半成品 */
    val aborted: Boolean = false,
)

/** 原生 function calling 的一次调用（arguments 是待解析的 JSON 字符串） */
data class NativeToolCall(val id: String, val name: String, val arguments: String)

/** token 用量（官方 usage 字段，含缓存命中与思考拆分） */
data class AiUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val cachedTokens: Int,
    val reasoningTokens: Int,
) {
    /** 气泡尾部的摘要行；无数据返回 null */
    fun summary(): String? {
        if (promptTokens <= 0 && completionTokens <= 0) return null
        return buildString {
            append("输入 ").append(fmt(promptTokens))
            if (cachedTokens > 0) append("（缓存命中 ").append(fmt(cachedTokens)).append('）')
            append(" · 输出 ").append(fmt(completionTokens))
            if (reasoningTokens > 0) append("（思考 ").append(fmt(reasoningTokens)).append('）')
        }
    }

    private fun fmt(n: Int): String =
        if (n >= 1000) "${"%.1f".format(n / 1000f)}K" else "$n"
}

/**
 * OpenAI 兼容对话客户端（/chat/completions，非流式）。
 *
 * 与 ModrinthApi 同一套做法：同步 HttpURLConnection + kotlinx-serialization，
 * 无新增依赖。所有失败都以 RuntimeException 抛出、消息面向用户 ——
 * 特别注意错误信息里**绝不回显请求头**（那里面有 API Key）。
 */
object AiClient {

    private const val CONNECT_TIMEOUT_MS = 15_000
    // 模型生成本来就可能要几十秒：readTimeout 必须给足，否则长回答永远超时。
    // 深度思考先出推理再出答案，时间翻倍是常态，给到 4 分钟。
    private const val READ_TIMEOUT_MS = 120_000
    private const val THINKING_READ_TIMEOUT_MS = 240_000
    private const val USER_AGENT = "KazeSLauncher/0.4 (com.kaze.newage; ai assistant)"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 流式对话（SSE）：思考过程与正文**逐 token 回调**，取消/超时即时生效。
     *
     * 与 [chat] 的分工：chat 留给小请求（搜索查询词生成）；主对话一律走这里 ——
     * 实时显示思考过程、取消真生效（掐断连接）、Qwen 等仅流式返回思考内容的服务商也能兼容。
     *
     * SSE 行协议：`data: {chunk}` / `data: [DONE]`；`:` 开头的 keep-alive 注释跳过。
     * [shouldStop] 在每行边界检查（取消 / 整体超时），命中时**停止读取**并返回
     * 已累积的半成品（[AiReply.aborted] = true）—— socket 由 finally 关闭。
     */
    fun chatStream(
        config: AiConfig,
        messages: List<AiMessage>,
        maxTokens: Int = 1024,
        extraJson: String? = null,
        thinkingEnabled: Boolean = false,
        includeNativeThinkingParam: Boolean = false,
        includeTools: Boolean = false,
        shouldStop: () -> Boolean = { false },
        onDelta: (reasoningDelta: String?, contentDelta: String?) -> Unit = { _, _ -> },
    ): AiReply {
        val endpoint = config.endpoint
        if (endpoint.isEmpty()) {
            throw RuntimeException("AI 服务地址无效：${config.baseUrl.trim()}（需以 http(s):// 开头）")
        }
        if (config.apiKey.isBlank()) throw RuntimeException("尚未配置 API Key")
        val requestBody = buildRequestBody(
            config.requestModel, messages, maxTokens, extraJson,
            thinkingEnabled, includeNativeThinkingParam, stream = true, includeTools = includeTools,
        )
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        val reasoning = StringBuilder()
        val content = StringBuilder()
        var usage: AiUsage? = null
        val toolCallsById = LinkedHashMap<Int, StringBuilder>()          // arguments 分片
        val toolCallNames = LinkedHashMap<Int, String>()
        val toolCallIds = LinkedHashMap<Int, String>()
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = if (config.thinking) THINKING_READ_TIMEOUT_MS else READ_TIMEOUT_MS
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "text/event-stream")
            conn.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code != 200) {
                val errBody = runCatching {
                    conn.errorStream?.bufferedReader()?.use { reader -> reader.readText() } ?: ""
                }.getOrDefault("")
                throw RuntimeException(describeHttpError(code, errBody))
            }
            var aborted = false
            conn.inputStream.bufferedReader().use { reader ->
                while (true) {
                    if (shouldStop()) {
                        aborted = true
                        break
                    }
                    val line = reader.readLine() ?: break
                    val t = line.trim()
                    if (t.isEmpty() || t.startsWith(":")) continue
                    if (!t.startsWith("data:")) continue
                    val payload = t.removePrefix("data:").trim()
                    if (payload == "[DONE]") break
                    val chunk = runCatching { json.decodeFromString<ChatStreamChunkDto>(payload) }.getOrNull()
                        ?: continue
                    chunk.usage?.let { usage = it.toAiUsage() }
                    val delta = chunk.choices.firstOrNull()?.delta ?: continue
                    delta.reasoning_content?.takeIf { it.isNotEmpty() }?.let {
                        reasoning.append(it)
                        onDelta(it, null)
                    }
                    delta.content?.takeIf { it.isNotEmpty() }?.let {
                        content.append(it)
                        onDelta(null, it)
                    }
                    for (tc in delta.tool_calls) {
                        tc.id?.let { toolCallIds[tc.index] = it }
                        tc.function?.name?.let { toolCallNames[tc.index] = it }
                        tc.function?.arguments?.let { arg ->
                            toolCallIds.getOrPut(tc.index) { "" }
                            toolCallNames.getOrPut(tc.index) { "" }
                            toolCallById(toolCallsById, tc.index).append(arg)
                        }
                    }
                }
            }
            val toolCalls = toolCallNames.mapNotNull { (index, name) ->
                if (name.isEmpty()) return@mapNotNull null
                NativeToolCall(
                    id = toolCallIds[index] ?: "call_$index",
                    name = name,
                    arguments = toolCallsById[index]?.toString() ?: "",
                )
            }
            return AiReply(
                reasoning = reasoning.toString().takeIf { it.isNotBlank() },
                content = content.toString(),
                usage = usage,
                toolCalls = toolCalls,
                rawToolCallsJson = toolCalls.takeIf { it.isNotEmpty() }
                    ?.let { list -> json.encodeToString(list.map { JsonTc(id = it.id, name = it.name, arguments = it.arguments) }) },
                aborted = aborted,
            )
        } catch (e: IOException) {
            // 半成品也保得住：流中断时已有内容的场景，交给调用方决定展示或报错
            if (content.isNotEmpty() || reasoning.isNotEmpty()) {
                return AiReply(
                    reasoning = reasoning.toString().takeIf { it.isNotBlank() },
                    content = content.toString(),
                    usage = usage,
                    aborted = true,
                )
            }
            throw RuntimeException("无法连接 AI 服务（${e.message ?: "网络错误"}）：请检查网络与 API 地址", e)
        } finally {
            conn.disconnect()
        }
    }

    @Serializable
    private data class JsonTc(val id: String, val name: String, val arguments: String)

    private fun toolCallById(map: LinkedHashMap<Int, StringBuilder>, index: Int): StringBuilder =
        map.getOrPut(index) { StringBuilder() }

    /**
     * 请求体构建（独立出来便于单测：不发起真实网络请求就能锁住请求格式）。
     * temperature 压低（0.3）：诊断要的是准确，不是发散；stream 显式 false。
     */
    internal fun buildRequestBody(
        model: String,
        messages: List<AiMessage>,
        maxTokens: Int = 1024,
        extraJson: String? = null,
        thinkingEnabled: Boolean = false,
        includeNativeThinkingParam: Boolean = false,
        stream: Boolean = false,
        includeTools: Boolean = false,
    ): String = buildJsonObject {
        put("model", model)
        put("stream", stream)
        // 用量走最后一个内容 chunk 回来（官方规则：include_usage 必须配 stream，否则 400）
        if (stream) {
            putJsonObject("stream_options") { put("include_usage", true) }
        }
        put("temperature", 0.3)
        put("max_tokens", maxTokens)
        // 原生 function calling（仅官方 DeepSeek 场景；第三方走 JSON 夹带协议）。
        // 官方约束：思考模式下 tool_choice 不支持 required/named —— 我们用默认 auto，合规。
        if (includeTools) {
            runCatching { put("tools", json.parseToJsonElement(FILE_TOOLS_SPEC)) }
        }
        putJsonArray("messages") {
            messages.forEach { m ->
                add(buildJsonObject {
                    put("role", m.role)
                    if (m.role == AiMessage.ROLE_TOOL) {
                        // 工具结果消息：content + 对应的调用 id
                        put("content", m.content)
                        m.toolCallId?.let { put("tool_call_id", it) }
                    } else {
                        // 带原生工具调用的 assistant 消息：content 可为空，工具调用原样回喂
                        if (m.content.isNotEmpty()) put("content", m.content)
                        m.toolCallsRaw?.takeIf { it.isNotBlank() }?.let { raw ->
                            runCatching { put("tool_calls", json.parseToJsonElement(raw)) }
                        }
                        if (m.content.isEmpty() && m.toolCallsRaw == null) put("content", "")
                    }
                })
            }
        }
        // DeepSeek V4 机制：思考模式由 thinking 参数控制（enabled/disabled，官方默认 enabled）。
        // 显式发送保证与「深度思考」开关的确定性对应。只在官方 DeepSeek 场景发送 ——
        // 第三方网关（模型名同为 deepseek-* 但走别家协议）对未知参数可能直接 400。
        if (includeNativeThinkingParam) {
            put("thinking", buildJsonObject {
                put("type", if (thinkingEnabled) "enabled" else "disabled")
            })
        }
        // 档案的附加参数最后合入：其它服务商要显式开启思考输出（如 Qwen 的
        // {"enable_thinking":true}），也允许覆盖 temperature 等采样参数；
        // model 与 messages 不允许被覆盖，非法 JSON 整体忽略
        val extra = extraJson?.trim().takeUnless { it.isNullOrEmpty() }?.let {
            runCatching { kotlinx.serialization.json.Json.parseToJsonElement(it) }.getOrNull()
        }
        if (extra is kotlinx.serialization.json.JsonObject) {
            extra.forEach { (key, value) ->
                if (key != "model" && key != "messages") put(key, value)
            }
        }
    }.toString()

    /**
     * 是否发送 DeepSeek 原生 `thinking` 参数：官方 V4 模型名，或端点就是 DeepSeek 官方域。
     * 第三方网关一律不发，避免未知参数被严格校验拒绝（要用就走「附加请求参数」显式指定）。
     */
    internal fun supportsNativeThinkingParam(model: String, baseUrl: String): Boolean {
        val m = model.trim().lowercase()
        if (m in DEEPSEEK_V4_MODELS) return true
        return baseUrl.contains("deepseek.com", ignoreCase = true) && m.startsWith("deepseek")
    }

    /** 官方现行模型名（V4 家族；thinking 参数由它们支持） */
    private val DEEPSEEK_V4_MODELS = setOf(
        "deepseek-flash",
        "deepseek-v4-pro",
        "deepseek-v4-flash",
        "deepseek-v4-flash-vision-exp",
    )

    /**
     * 原生 function calling 的工具清单（OpenAI function 格式）。
     * 只覆盖只读工具 + write_file；写文件仍走"用户确认卡片"的同一套审批，
     * 协议换了安全语义不变。arguments 由模型生成，**可能是非法 JSON**（官方文档明示），
     * 调用方必须 runCatching 解析。
     */
    internal const val FILE_TOOLS_SPEC = """[
{"type":"function","function":{"name":"read_file","description":"读取服务端实例目录下的文本文件（配置/日志/脚本）。app: 前缀 = 启动器应用目录，只读。二进制与大文件会被拒绝。","parameters":{"type":"object","properties":{"path":{"type":"string","description":"相对实例根目录的路径"}},"required":["path"]}}},
{"type":"function","function":{"name":"list_dir","description":"列出实例目录（或 app: 应用目录）下的条目，目录在前。","parameters":{"type":"object","properties":{"path":{"type":"string","description":"相对路径，. 表示根目录"}},"required":["path"]}}},
{"type":"function","function":{"name":"write_file","description":"写入/覆盖实例目录下的文本文件（需用户在界面上确认；覆盖已有文件自动留 .bak）。.jar 等二进制与授权类文件（ops.json/eula.txt/whitelist.json 等）被禁止。","parameters":{"type":"object","properties":{"path":{"type":"string","description":"相对实例根目录的路径"},"content":{"type":"string","description":"完整的新文件内容"}},"required":["path","content"]}}},
{"type":"function","function":{"name":"fetch_page","description":"抓取搜索结果里出现的网页正文（http/https），用于把攻略或文档读全。","parameters":{"type":"object","properties":{"url":{"type":"string","description":"完整网页地址"}},"required":["url"]}}}
]"""

    /**
     * 同步发起一轮对话，返回 assistant 文本。**阻塞调用**：必须在 IO 线程跑
     * （调用方负责放独立线程 + 界面可见超时，见 AppViewModel.askAi）。
     */
    fun chat(config: AiConfig, messages: List<AiMessage>, maxTokens: Int = 1024): AiReply {
        val endpoint = config.endpoint
        if (endpoint.isEmpty()) {
            throw RuntimeException("AI 服务地址无效：${config.baseUrl.trim()}（需以 http(s):// 开头）")
        }
        if (config.apiKey.isBlank()) throw RuntimeException("尚未配置 API Key")
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = if (config.thinking) THINKING_READ_TIMEOUT_MS else READ_TIMEOUT_MS
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.outputStream.use {
                it.write(
                    buildRequestBody(
                        config.requestModel,
                        messages,
                        maxTokens,
                        config.extraBody,
                        thinkingEnabled = config.thinking,
                        includeNativeThinkingParam = supportsNativeThinkingParam(
                            config.requestModel, config.baseUrl,
                        ),
                    ).toByteArray(Charsets.UTF_8)
                )
            }
            val code = conn.responseCode
            if (code != 200) {
                val errBody = runCatching {
                    conn.errorStream?.bufferedReader()?.use { reader -> reader.readText() } ?: ""
                }.getOrDefault("")
                throw RuntimeException(describeHttpError(code, errBody))
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            return parseReply(body)
        } catch (e: IOException) {
            // DNS 失败 / 连不上 / 超时都到这里；底层 message 很晦涩（如 "Unable to resolve host"），补一句人话
            throw RuntimeException("无法连接 AI 服务（${e.message ?: "网络错误"}）：请检查网络与 API 地址", e)
        } finally {
            conn.disconnect()
        }
    }

    internal fun parseReply(body: String): AiReply {
        val resp = runCatching { json.decodeFromString<ChatResponseDto>(body) }.getOrElse {
            throw RuntimeException("AI 返回了无法解析的内容（HTTP 200 但不是预期的 JSON）")
        }
        val message = resp.choices.firstOrNull()?.message
            ?: throw RuntimeException("AI 返回为空（choices 为空）")
        val reasoning = normalizeReasoning(message.reasoning_content)
            ?: normalizeReasoning(message.reasoning)
            ?: normalizeReasoning(message.reasoning_details)
        return AiReply(
            reasoning = reasoning?.takeIf { it.isNotBlank() },
            content = message.content ?: "",
            usage = resp.usage.toAiUsage(),
            toolCalls = message.tool_calls.mapNotNull { it.toNativeToolCall() },
            rawToolCallsJson = message.tool_calls.takeIf { it.isNotEmpty() }
                ?.let { runCatching { kotlinx.serialization.json.Json.encodeToString(it) }.getOrNull() },
        )
    }

    /** 非流式 tool_calls DTO → 公开模型（arguments 可能是非法 JSON，原样交给调用方校验） */
    private fun StreamToolCallDto.toNativeToolCall(): NativeToolCall? {
        val id = id ?: return null
        val name = function?.name ?: return null
        return NativeToolCall(id, name, function.arguments ?: "")
    }

    /**
     * 各家推理字段的形态兼容：
     *  - 字符串（DeepSeek / 硅基流动 / 智谱等绝大多数）
     *  - 数组（分段推理：元素是字符串或 {text|summary|content} 对象）
     *  - 对象（单个 {text|summary|content}）
     * 都归一成一段文本；没有任何文本则返回 null。
     */
    private fun normalizeReasoning(el: kotlinx.serialization.json.JsonElement?): String? = when (el) {
        null -> null
        is kotlinx.serialization.json.JsonNull -> null
        is kotlinx.serialization.json.JsonPrimitive -> el.content.takeIf { it.isNotBlank() }
        is kotlinx.serialization.json.JsonArray -> el.mapNotNull { normalizeReasoning(it) }
            .joinToString("\n").takeIf { it.isNotBlank() }
        is kotlinx.serialization.json.JsonObject -> normalizeReasoning(
            el["text"] ?: el["summary"] ?: el["content"]
        )
    }

    /**
     * 常见错误码 → 人话；响应体第一行（官方通常写明原因，如 402 余额不足）截 160 字符附上。
     */
    internal fun describeHttpError(code: Int, body: String): String {
        val hint = body.lineSequence().firstOrNull { it.isNotBlank() }?.take(160) ?: ""
        val base = when (code) {
            401 -> "API Key 无效或未授权（HTTP 401）"
            402 -> "账户余额不足（HTTP 402）"
            404 -> "接口地址不存在（HTTP 404）：检查服务地址是否需要以 /v1 结尾"
            429 -> "请求过于频繁或额度受限（HTTP 429）"
            in 500..599 -> "AI 服务端错误（HTTP $code）：稍后再试"
            else -> "AI 服务返回 HTTP $code"
        }
        return if (hint.isBlank()) base else "$base：$hint"
    }
}
