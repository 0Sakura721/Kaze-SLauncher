package com.kaze.newage.core.ai

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * 流式思考过程的协议兼容（本地假 SSE 端点，不碰真实网络）。
 *
 * 盯四件事：
 *  a. 官方 `reasoning_content` 协议：思考增量按原顺序流出来；
 *  b. 正文内联 `<think>` 协议：思考走 reasoning、正文走 content；
 *  c. 标签跨 chunk 切断（`"<thi"` + `"nk>"`）：不能把半个标签当正文吐出去；
 *  d. 未闭合 `<think>`：已收到部分按思考处理（与 `AiSuggestion.splitThinking` 语义一致）。
 *
 * 假端点的写法参考 `AiWatchdogTest`：起一个一次性 ServerSocket，读完请求头就回
 * 一段 SSE 正文然后关连接。SSE 的一行 = 一个流式 chunk，所以"切断"就是把标签
 * 拆到相邻两条 `data:` 里。
 */
class AiStreamReasoningTest {

    /** 一条流式事件：两个字段最多出现一个（也可以都出现，用于混合来源测试） */
    private data class Event(val reasoning: String? = null, val content: String? = null)

    private fun sseBody(vararg events: Event): String = buildString {
        events.forEach { event ->
            val delta = buildJsonObject {
                event.reasoning?.let { put("reasoning_content", it) }
                event.content?.let { put("content", it) }
            }
            val chunk = buildJsonObject {
                put("choices", buildJsonArray {
                    add(buildJsonObject { put("delta", delta) })
                })
            }
            append("data: ").append(chunk.toString()).append("\n\n")
        }
        append("data: [DONE]\n\n")
    }

    private fun serveOnce(body: String): Pair<Int, Thread> {
        val server = ServerSocket(0)
        val port = server.localPort
        val t = thread(isDaemon = true, name = "fake-sse") {
            try {
                val client = server.accept()
                val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
                val out: OutputStream = client.getOutputStream()
                out.write(
                    ("HTTP/1.1 200 OK\r\n" +
                        "Content-Type: text/event-stream\r\n" +
                        "Connection: close\r\n\r\n").toByteArray(),
                )
                out.write(body.toByteArray())
                out.flush()
                runCatching { client.close() }
                runCatching { server.close() }
            } catch (_: Throwable) {
                runCatching { server.close() }
            }
        }
        return port to t
    }

    private fun config(port: Int) = AiConfig(
        baseUrl = "http://127.0.0.1:$port",
        apiKey = "test-key",
        model = "test-model",
    )

    private class Deltas {
        val reasoning = mutableListOf<String>()
        val content = mutableListOf<String>()
    }

    private fun stream(body: String): Pair<AiReply, Deltas> {
        val (port, serverThread) = serveOnce(body)
        val deltas = Deltas()
        try {
            val reply = AiClient.chatStream(
                config = config(port),
                messages = listOf(AiMessage(AiMessage.ROLE_USER, "hi")),
                maxTokens = 64,
                shouldStop = { false },
                onDelta = { r, c ->
                    r?.let { deltas.reasoning += it }
                    c?.let { deltas.content += it }
                },
            )
            return reply to deltas
        } finally {
            serverThread.join(2000)
        }
    }

    @Test
    fun `原生 reasoning_content 协议：思考增量按顺序流式返回`() {
        val (reply, deltas) = stream(
            sseBody(
                Event(reasoning = "先想"),
                Event(reasoning = "再想"),
                Event(content = "答案"),
            )
        )
        assertEquals("先想再想", reply.reasoning)
        assertEquals(listOf("先想", "再想"), deltas.reasoning)
        assertEquals("答案", reply.content)
        assertEquals(listOf("答案"), deltas.content)
        assertTrue("思考内容不能混进正文增量", deltas.content.none { it.contains("先想") })
    }

    @Test
    fun `正文内联 think 协议：思考走 reasoning 正文走 content`() {
        val (reply, deltas) = stream(
            sseBody(
                Event(content = "<think>思考"),
                Event(content = "过程</think>正"),
                Event(content = "式答案"),
            )
        )
        assertEquals("思考过程", reply.reasoning)
        assertEquals("正式答案", reply.content)
        assertEquals(listOf("思考", "过程"), deltas.reasoning)
        assertEquals(listOf("正", "式答案"), deltas.content)
    }

    @Test
    fun `thinking 长标签同样被识别`() {
        val (reply, deltas) = stream(
            sseBody(Event(content = "<thinking>想一想</thinking>好"))
        )
        assertEquals("想一想", reply.reasoning)
        assertEquals("好", reply.content)
        assertEquals(listOf("想一想"), deltas.reasoning)
        assertEquals(listOf("好"), deltas.content)
    }

    @Test
    fun `开标签跨 chunk 切断不产生残片`() {
        val (reply, deltas) = stream(
            sseBody(
                Event(content = "前半<thi"),
                Event(content = "nk>思考</think>后半"),
            )
        )
        assertEquals("思考", reply.reasoning)
        assertEquals("前半后半", reply.content)
        val content = deltas.content.joinToString("")
        assertFalse("正文里不能出现半个标签：$content", content.contains("<thi"))
        assertFalse("正文里不能出现标签：$content", content.contains("</think"))
        // 半个标签也不能被当成思考吐出来
        assertFalse(deltas.reasoning.joinToString("").contains("<thi"))
    }

    @Test
    fun `闭标签跨 chunk 切断不产生残片`() {
        val (reply, deltas) = stream(
            sseBody(
                Event(content = "<think>思考</thi"),
                Event(content = "nk>答案"),
            )
        )
        assertEquals("思考", reply.reasoning)
        assertEquals("答案", reply.content)
        assertFalse(deltas.content.joinToString("").contains("</thi"))
    }

    @Test
    fun `未闭合的 think 段全部按思考处理`() {
        val (reply, deltas) = stream(
            sseBody(
                Event(content = "答案前<think>只想"),
                Event(content = "到一半"),
            )
        )
        assertEquals("答案前", reply.content)
        assertEquals("只想到一半", reply.reasoning)
        assertEquals(listOf("答案前"), deltas.content)
        assertEquals(listOf("只想", "到一半"), deltas.reasoning)
    }

    @Test
    fun `原生与内联两种思考来源都会合并且不重复`() {
        val (reply, _) = stream(
            sseBody(
                Event(reasoning = "原生推理"),
                Event(content = "<think>内联推理</think>回答"),
            )
        )
        assertEquals("原生推理\n\n内联推理", reply.reasoning)
        assertEquals("回答", reply.content)
    }

    @Test
    fun `流末尾的半截标签被丢弃`() {
        val (reply, deltas) = stream(sseBody(Event(content = "答案<thi")))
        assertEquals("答案", reply.content)
        assertNull(reply.reasoning)
        assertEquals(listOf("答案"), deltas.content)
    }
}
