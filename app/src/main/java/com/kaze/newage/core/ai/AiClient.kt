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
    // 深度思考先出推理再出答案，时间翻倍是常态，给到 4 分钟。
    private const val READ_TIMEOUT_MS = 120_000
    private const val THINKING_READ_TIMEOUT_MS = 240_000
    private const val USER_AGENT = "KazeSLauncher/0.4 (com.kaze.newage; ai assistant)"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 当前在飞的连接。
     *
     * 存在的理由：HttpURLConnection 是阻塞的，"取消"如果只置一个标志位，用户点了取消
     * 之后线程仍然卡在 `responseCode` / `read` 上直到 readTimeout（思考模式最长 240 秒），
     * 界面看着像没反应。[cancelActive] 关掉这条连接，让阻塞中的调用尽早结束。
     *
     * **实测边界（CI 上抓到的，别当成"立刻中断"）**：读取已经卡在 socket 上时，
     * JDK 的 `disconnect()` 自身会一直阻塞到 readTimeout 才返回（实测约 119 秒），
     * 之后的读才以异常结束 —— 所以：
     *  - 调用方**必须**走 [cancelActiveAsync]（独立线程），否则会把 UI 线程卡住；
     *  - 已经发出去的请求，服务端该计费还是计费（这是服务端的账），我们能保证的是
     *    界面立刻停止等待、本轮结果丢弃、不再发起下一轮（见 AppViewModel 的停止逻辑）。
     */
    private val activeCall = java.util.concurrent.atomic.AtomicReference<HttpURLConnection?>(null)

    /**
     * 中断当前在飞的请求（没有请求时是空操作）。
     *
     * **会阻塞**（见 [activeCall] 的说明）：只允许在后台线程调用 —— 界面路径请用
     * [cancelActiveAsync]。
     */
    fun cancelActive() {
        activeCall.getAndSet(null)?.let { conn ->
            runCatching { conn.disconnect() }
        }
    }

    /** 取消的界面入口：把可能阻塞的 [cancelActive] 丢到独立守护线程，绝不让 UI 线程等它 */
    fun cancelActiveAsync() {
        kotlin.concurrent.thread(isDaemon = true, name = "kaze-ai-cancel") { cancelActive() }
    }

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
     * 同步发起一轮对话，返回 assistant 文本。**阻塞调用**：必须在 IO 线程跑
     * （调用方负责放独立线程 + 界面可见超时，见 AppViewModel.askAi）。
     */
    fun chat(config: AiConfig, messages: List<AiMessage>, maxTokens: Int = 1024): AiReply {
        val endpoint = config.endpoint
        if (endpoint.isEmpty()) {
            throw RuntimeException("AI 服务地址无效：${config.baseUrl.trim()}（需以 http(s):// 开头）")
        }
        if (config.apiKey.isBlank()) throw RuntimeException("尚未配置 API Key")
        // 明文 http 只放行本机（与 res/xml/network_security_config.xml 的名单一致）。
        // 平台在 targetSdk 28+ 直接禁明文，报的是"CLEARTEXT communication not permitted"这种
        // 底层文案；这里提前拦下并说清规则，用户才知道该改什么（局域网/公网一律走 https）。
        val schemeHost = runCatching { java.net.URI(endpoint) }.getOrNull()
        if (schemeHost?.scheme.equals("http", ignoreCase = true) &&
            !AiConfig.isCleartextHostAllowed(schemeHost?.host)
        ) {
            throw RuntimeException(
                "明文 http 只允许本机地址（localhost / 127.0.0.1）：" +
                    "当前地址 ${schemeHost?.host ?: "?"} 请改用 https"
            )
        }
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        // 登记在飞连接：取消/离开页面时才能真的把它掐断（见 activeCall 的说明）。
        // 同一时刻只会有一轮请求（调用方有 _aiBusy 门闩），这里仍然用 CAS 清理，
        // 避免"两轮请求交错"时后结束的那个把新的那个从登记表里抹掉。
        activeCall.set(conn)
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
                val errBody = runCatching { BodyLimit.readErrorText(conn.errorStream) }.getOrDefault("")
                throw RuntimeException(describeHttpError(code, errBody))
            }
            // 流式读取 + 上限：readText() 会让恶意/异常端点用一个大 body 把应用 OOM（见 BodyLimit）
            val body = BodyLimit.read(conn.inputStream)
            if (body.truncated) {
                // 截断的 JSON 必然解析不出来，直接说清原因 —— 别让用户看到"返回了无法解析的内容"
                // 这种误导性结论（他会去怀疑模型，而真正的问题是端点回了超大响应）
                throw RuntimeException(
                    "AI 返回的响应体超过 ${BodyLimit.MAX_BYTES / 1024 / 1024}MB，已中止读取：" +
                        "该端点响应异常，请检查服务地址是否正确"
                )
            }
            return parseReply(body.text)
        } catch (e: IOException) {
            // DNS 失败 / 连不上 / 超时都到这里；底层 message 很晦涩（如 "Unable to resolve host"），补一句人话
            throw RuntimeException("无法连接 AI 服务（${e.message ?: "网络错误"}）：请检查网络与 API 地址", e)
        } finally {
            activeCall.compareAndSet(conn, null)
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
     *
     * 附上时必须标明这是**服务端返回的原文**：那一行可能是 HTML、JSON 片段或任意第三方文案，
     * 不加标注就会和应用自己的话混成一句，看起来像启动器在说胡话（也容易被当成应用的提示照做）。
     * 错误体在进入这里之前已由 [BodyLimit] 限制过体量。
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
        return if (hint.isBlank()) base else "$base（服务端返回：$hint）"
    }
}
