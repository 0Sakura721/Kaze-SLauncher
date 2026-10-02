package com.kaze.newage.core.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

@Serializable
internal data class ChatResponseDto(val choices: List<ChoiceDto> = emptyList())

@Serializable
internal data class ChoiceDto(val message: MessageDto = MessageDto())

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
)

/** 一轮对话的完整回复：正文 + 可选的思考过程 */
data class AiReply(
    /** 模型的推理过程（reasoning_content 或正文里的 <think> 段）；null = 没有思考内容 */
    val reasoning: String?,
    /** 最终回答正文 */
    val content: String,
)

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
    // 深度思考（如 deepseek-reasoner）先出推理再出答案，时间翻倍是常态，给到 4 分钟。
    private const val READ_TIMEOUT_MS = 120_000
    private const val THINKING_READ_TIMEOUT_MS = 240_000
    private const val USER_AGENT = "KazeSLauncher/0.4 (com.kaze.newage; ai assistant)"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 请求体构建（独立出来便于单测：不发起真实网络请求就能锁住请求格式）。
     * temperature 压低（0.3）：诊断要的是准确，不是发散；stream 显式 false。
     */
    internal fun buildRequestBody(
        model: String,
        messages: List<AiMessage>,
        maxTokens: Int = 1024,
        extraJson: String? = null,
    ): String = buildJsonObject {
        put("model", model)
        put("stream", false)
        put("temperature", 0.3)
        put("max_tokens", maxTokens)
        putJsonArray("messages") {
            messages.forEach { m ->
                add(buildJsonObject {
                    put("role", m.role)
                    put("content", m.content)
                })
            }
        }
        // 档案的附加参数最后合入：部分服务商要显式开启思考输出（如 Qwen 的
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
                it.write(buildRequestBody(config.requestModel, messages, maxTokens, config.extraBody).toByteArray(Charsets.UTF_8))
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
        )
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
