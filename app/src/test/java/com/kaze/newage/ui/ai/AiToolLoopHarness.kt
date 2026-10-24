package com.kaze.newage.ui.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.kaze.newage.core.ai.AiProfile
import com.kaze.newage.core.console.ConsoleLine
import com.kaze.newage.core.console.ConsoleStream
import com.kaze.newage.core.monitor.ProcessStats
import com.kaze.newage.core.server.ServerManager
import com.kaze.newage.core.server.ServerState
import com.kaze.newage.data.InstanceStore
import com.kaze.newage.data.model.ServerInstance
import com.kaze.newage.data.prefs.SettingsPrefs
import com.kaze.newage.ui.AppViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

/**
 * AI 工具循环的端到端测试台（假宿主 + 本地假 SSE 端点）。
 *
 * 被测对象是 [AiChatController] —— 工具权限档位、写入/敏感读取的挂起恢复、
 * 双协议（原生 function calling / JSON 夹带）回喂格式、上下文裁剪、命令执行闸门。
 *
 * 三块替身：
 *  1. [FakeAiChatHost]：把 [AiChatHost] 要的 Context / prefs / 实例仓库 / ServerManager /
 *     现场快照全换成测试可控的内存对象（[FakeServerManager] 记录 sendCommand 调用）；
 *  2. [FakeSseEndpoint]：手写 ServerSocket 的假 SSE 端点，按脚本逐轮返回 chunk，
 *     **并且把每一轮收到的请求体原样存下来** —— 回喂格式（role=tool + tool_call_id）与
 *     上下文裁剪都是在请求体里才看得见的，这是本测试台存在的理由；
 *  3. 临时目录做实例目录，读写工具真的落盘/真的读文件。
 *
 * 每一步都通过轮询 [AiChatController.aiBusy] 等待线程池里的本轮跑完，不依赖 sleep 猜时长。
 */
internal const val NATIVE_MODEL = "deepseek-flash" // 命中 supportsNativeThinkingParam → 原生工具协议
internal const val JSON_MODEL = "qwen3-max"        // 第三方模型 → JSON 夹带协议

// ── 假宿主依赖 ──

/** 记录 sendCommand 调用的 ServerManager 替身（不碰任何进程） */
internal class FakeServerManager : ServerManager {
    val sent: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    private val _states = MutableStateFlow<Map<String, ServerState>>(emptyMap())
    override val states: StateFlow<Map<String, ServerState>> = _states

    private val consoles = LinkedHashMap<String, ConsoleStream>()
    private val uptimes = LinkedHashMap<String, MutableStateFlow<Long>>()

    fun setState(instanceId: String, state: ServerState) {
        _states.value = _states.value + (instanceId to state)
    }

    @Synchronized
    override fun consoleFor(instanceId: String): ConsoleStream =
        consoles.getOrPut(instanceId) { ConsoleStream() }

    @Synchronized
    override fun uptimeSec(instanceId: String): StateFlow<Long> =
        uptimes.getOrPut(instanceId) { MutableStateFlow(0L) }

    override fun isRunning(instanceId: String): Boolean = _states.value[instanceId] == ServerState.Running

    override suspend fun start(instance: ServerInstance) = Unit
    override suspend fun stop(instance: ServerInstance) = Unit
    override fun forceStop(instance: ServerInstance) = Unit

    override fun sendCommand(instance: ServerInstance, command: String) {
        sent += command
    }

    override fun release(instanceId: String) = Unit
}

/** [AiChatHost] 的测试替身：所有现场快照都是内存 StateFlow，目录是临时目录 */
internal class FakeAiChatHost(
    override val appContext: Context,
    override val uiPrefs: SettingsPrefs,
    override val instanceStore: InstanceStore,
    override val serverManager: ServerManager,
    instanceId: String?,
) : AiChatHost {

    private val job = SupervisorJob()
    override val appScope: CoroutineScope = CoroutineScope(job + Dispatchers.IO)
    override val scope: CoroutineScope = CoroutineScope(job + Dispatchers.IO)

    override val currentInstanceId = MutableStateFlow<String?>(instanceId)
    override val serverState = MutableStateFlow(ServerState.Running)
    override val uptimeSec = MutableStateFlow(0L)
    override val onlinePlayers = MutableStateFlow<List<String>>(emptyList())
    override val procStats = MutableStateFlow<ProcessStats.Reading?>(null)
    override val consoleLines = MutableStateFlow<List<ConsoleLine>>(emptyList())

    fun cancel() {
        job.cancel()
    }
}

// ── 假 SSE 端点（按脚本逐轮应答，并逐轮存下请求体）──

/**
 * 手写 HTTP/1.1 的假 SSE 端点（套路同 `AiWatchdogTest`），多一个能力：
 * 每收一轮请求就把请求体 UTF-8 解出来存进 [requests]，供断言回喂格式与裁剪结果。
 *
 * 第 N 轮用 `responses[N]`；脚本用尽后统一回一句最终回答（避免测试因"多问了一轮"直接崩，
 * 计数由 [servedCount] 断言）。
 */
internal class FakeSseEndpoint(private val responses: List<String>) {

    private val server = ServerSocket(0)
    val port: Int = server.localPort

    /** 每一轮请求体（按到达顺序），已解成 JsonObject */
    @JvmField
    val requests: MutableList<JsonObject> = java.util.Collections.synchronizedList(mutableListOf())

    private val served = AtomicInteger(0)

    init {
        kotlin.concurrent.thread(isDaemon = true, name = "fake-sse") { serveLoop() }
    }

    val servedCount: Int get() = served.get()

    fun close() {
        runCatching { server.close() }
    }

    private fun serveLoop() {
        while (true) {
            val client = try {
                server.accept()
            } catch (_: Throwable) {
                return // 端点已关闭
            }
            try {
                val body = readBody(client.getInputStream())
                runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()?.let { requests += it }
                val i = served.getAndIncrement()
                val payload = responses.getOrElse(i) { Sse.reply(jsonFinalReply("（脚本已用尽）")) }
                val out: OutputStream = client.getOutputStream()
                out.write(HEADERS.toByteArray(Charsets.ISO_8859_1))
                out.write(payload.toByteArray(Charsets.UTF_8))
                out.flush()
            } catch (_: Throwable) {
                // 客户端提前断开（取消/超时路径）不是测试失败
            } finally {
                runCatching { client.close() }
            }
        }
    }

    /** 读请求头到空行，再按 Content-Length 精确读正文（正文里有中文，必须按字节读） */
    private fun readBody(input: InputStream): String {
        val stream = BufferedInputStream(input)
        val header = StringBuilder()
        while (true) {
            val b = stream.read()
            if (b < 0) break
            header.append(b.toChar())
            if (header.length >= 4 && header.endsWith("\r\n\r\n")) break
        }
        val len = Regex("(?i)content-length:\\s*(\\d+)")
            .find(header.toString())?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val buf = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = stream.read(buf, off, len - off)
            if (n <= 0) break
            off += n
        }
        return String(buf, 0, off, Charsets.UTF_8)
    }

    private companion object {
        const val HEADERS =
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/event-stream\r\n" +
                "Connection: close\r\n\r\n"
    }
}

// ── SSE 脚本构造 ──

internal object Sse {

    /** 若干正文分片组成的流式回答（末尾 [DONE]） */
    fun reply(vararg contentParts: String): String = buildString {
        contentParts.forEach {
            append("data: {\"choices\":[{\"delta\":{\"content\":").append(jsonString(it)).append("}}]}\n\n")
        }
        append("data: [DONE]\n\n")
    }

    /** 一轮里单个原生工具调用（id / 名字 / arguments 原始 JSON 串） */
    fun toolCall(id: String, name: String, argsJson: String): String =
        toolCalls(Triple(id, name, argsJson))

    /** 一轮里的整批原生工具调用（index 从 0 递增，模拟同批到达） */
    fun toolCalls(vararg calls: Triple<String, String, String>): String = buildString {
        append("data: {\"choices\":[{\"delta\":{\"tool_calls\":[")
        calls.forEachIndexed { i, (id, name, args) ->
            if (i > 0) append(',')
            append("{\"index\":").append(i)
            append(",\"id\":").append(jsonString(id))
            append(",\"function\":{\"name\":").append(jsonString(name))
            append(",\"arguments\":").append(jsonString(args)).append("}}")
        }
        append("]}}]}\n\n")
        append("data: [DONE]\n\n")
    }

    /** 原生协议下一轮的最终回答（正文是 JSON 分析，没有 tool_calls） */
    fun final(analysis: String): String = reply("""{"analysis":${jsonString(analysis)}}""")

    private fun jsonString(s: String): String = buildString {
        append('"')
        for (ch in s) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
        append('"')
    }
}

// ── JSON 夹带协议的回复正文 ──

/** `{"analysis":…,"tool":{…}}`：走 JSON 夹带协议的一轮工具调用 */
internal fun jsonToolReply(analysis: String, name: String, path: String, content: String? = null): String {
    val tool = buildString {
        append("{\"name\":\"$name\",\"path\":${jsonQuote(path)}")
        if (content != null) append(",\"content\":${jsonQuote(content)}")
        append('}')
    }
    return "{\"analysis\":${jsonQuote(analysis)},\"tool\":$tool}"
}

/** `{"analysis":…,"command":…}`：不带工具的一轮（最终回答 / 建议命令） */
internal fun jsonFinalReply(analysis: String, command: String? = null): String = buildString {
    append("{\"analysis\":").append(jsonQuote(analysis))
    if (command != null) append(",\"command\":").append(jsonQuote(command))
    append('}')
}

private fun jsonQuote(s: String): String = buildString {
    append('"')
    for (ch in s) {
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
        }
    }
    append('"')
}

// ── 测试台本体 ──

internal class AiLoopHarness(
    responses: List<String>,
    model: String = NATIVE_MODEL,
    val instanceId: String = "inst-1",
) {
    val context: Context = ApplicationProvider.getApplicationContext()

    /** 注入可预测的假加密器（Robolectric 里没有 AndroidKeyStore，同 SettingsPrefsAiTest） */
    val prefs: SettingsPrefs = SettingsPrefs(
        context,
        { "enc1:test:$it" },
        { it.removePrefix("enc1:test:") },
    )

    val instanceDir: File = File(context.filesDir, "instance-$instanceId").apply { mkdirs() }
    val store: InstanceStore = InstanceStore(context, prefs)
    val manager = FakeServerManager()
    val endpoint = FakeSseEndpoint(responses)
    val host: FakeAiChatHost
    val controller: AiChatController

    init {
        store.add(ServerInstance(id = instanceId, name = "测试服", dir = instanceDir))
        manager.setState(instanceId, ServerState.Running)
        prefs.aiProfiles.value = listOf(
            AiProfile(
                id = "profile-1",
                name = "测试档案",
                baseUrl = "http://127.0.0.1:${endpoint.port}",
                apiKey = "test-key",
                models = listOf(model),
            )
        )
        host = FakeAiChatHost(context, prefs, store, manager, instanceId)
        controller = AiChatController(host)
    }

    fun close() {
        endpoint.close()
        host.cancel()
    }

    // ── 等待与提问 ──

    /** 等待本轮（含工具循环、挂起恢复后的续跑）在后台线程上跑完 */
    fun awaitIdle(timeoutMs: Long = 30_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!controller.aiBusy.value) return
            Thread.sleep(15)
        }
        throw AssertionError("AI 轮次在 ${timeoutMs}ms 内没有结束（疑似死等）")
    }

    fun ask(question: String = "服务器怎么样") {
        controller.askAi(question)
        awaitIdle()
    }

    /** 后台等待：确认点下去之后本轮会重新跑起来 */
    fun approveWrite(messageId: Long) {
        controller.approveAiWrite(messageId)
        awaitIdle()
    }

    fun denyWrite(messageId: Long) {
        controller.denyAiWrite(messageId)
        awaitIdle()
    }

    fun approveRead(messageId: Long) {
        controller.approveAiRead(messageId)
        awaitIdle()
    }

    fun denyRead(messageId: Long) {
        controller.denyAiRead(messageId)
        awaitIdle()
    }

    // ── 消息查询 ──

    val messages: List<AppViewModel.AiChatMessage> get() = controller.aiMessages.value

    fun lastWithWriteRequest(): AppViewModel.AiChatMessage? = messages.lastOrNull { it.writeRequest != null }

    fun lastWithReadRequest(): AppViewModel.AiChatMessage? = messages.lastOrNull { it.readRequest != null }

    /** 所有助手气泡拼起来（断言"界面上到底说了什么"） */
    fun assistantText(): String = messages.filter { !it.isUser }.joinToString("\n") { it.text + " ‖ " + (it.toolNote ?: "") }

    // ── 请求体查询 ──

    fun requestCount(): Int = endpoint.requests.size

    /** 第 [index] 轮请求体的原文（断言"某个串根本没被发出去"用它） */
    fun requestBodyText(index: Int): String = endpoint.requests[index].toString()

    /** 第 [index] 轮请求里是否有任何消息文本包含 [needle] */
    fun anyMessageContains(index: Int, needle: String): Boolean =
        requestBodyText(index).contains(needle)

    fun requestMessages(index: Int): JsonArray = endpoint.requests[index]["messages"]!!.jsonArray

    fun requestMessageRoles(index: Int): List<String> =
        requestMessages(index).map { it.jsonObject["role"]!!.jsonPrimitive.content }

    fun requestMessageContents(index: Int): List<String> =
        requestMessages(index).map { it.jsonObject["content"]?.jsonPrimitive?.content.orEmpty() }

    /** 第 [index] 轮请求里所有 role=tool 的消息（含 tool_call_id） */
    fun toolResultMessages(index: Int): List<Pair<String, String>> =
        requestMessages(index).mapNotNull { el ->
            val o = el.jsonObject
            if (o["role"]?.jsonPrimitive?.content != "tool") return@mapNotNull null
            (o["tool_call_id"]?.jsonPrimitive?.content.orEmpty()) to
                (o["content"]?.jsonPrimitive?.content.orEmpty())
        }

    /** 第 [index] 轮请求里 assistant 消息声明过的全部 tool_call id */
    fun declaredToolCallIds(index: Int): Set<String> =
        requestMessages(index).flatMap { el ->
            val arr = el.jsonObject["tool_calls"]?.jsonArray ?: return@flatMap emptyList()
            arr.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.content }
        }.toSet()

    fun toolNames(index: Int): List<String> =
        requestMessages(index).flatMap { el ->
            val arr = el.jsonObject["tools"]?.jsonArray ?: return@flatMap emptyList()
            arr.mapNotNull { it.jsonObject["function"]?.jsonObject?.get("name")?.jsonPrimitive?.content }
        }

    /** 第 [index] 轮请求里有原生工具调用的 assistant 消息全文（含 tool_calls 原样回喂） */
    fun assistantToolCallMessages(index: Int): List<JsonObject> =
        requestMessages(index).map { it.jsonObject }.filter { it["tool_calls"] != null }

    val json = Json { ignoreUnknownKeys = true }
}