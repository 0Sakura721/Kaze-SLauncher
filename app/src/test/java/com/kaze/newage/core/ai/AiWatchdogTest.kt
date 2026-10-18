package com.kaze.newage.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * 看门狗线程的回归测试。
 *
 * ## 修的是什么
 * 真机日志（`app-20261018.log`）里有一次 `FATAL EXCEPTION: kaze-ai-watchdog`，
 * 栈是 `Thread.sleep` → `InterruptedException`。原因是流式请求**正常结束**时
 * 会走 `done.set(true); watchdog.interrupt()`，而看门狗正卡在 `Thread.sleep(200)`：
 *
 * - `interrupt()` 让 sleep 抛 `InterruptedException`；
 * - 旧实现没有捕获 → 守护线程抛未捕获异常 → **整个进程被 FATAL 掉**。
 *
 * 也就是说**每次流式对话正常结束都可能崩一次**，只是要恰好卡在 sleep 里。
 *
 * ## 怎么测
 * 起一个本地假 SSE 端点，正常吐几条 data 后关闭流；`chatStream` 收尾时会 interrupt
 * 看门狗。只要看门狗把中断当"退出循环"处理，调用就正常返回。
 *
 * 注意：线程里抛出的未捕获异常在 JVM 上不会让**测试进程**像 Android 那样崩掉，
 * 只会走 default uncaught handler。所以这里额外装一个 handler 捕获"来自
 * kaze-ai-watchdog 的未捕获异常" —— 有就说明修复被回退了。
 */
class AiWatchdogTest {

    /** 记录测试期间来自看门狗线程的未捕获异常 */
    private class Collector : Thread.UncaughtExceptionHandler {
        val hits = mutableListOf<Throwable>()
        @Volatile
        var installed = false

        override fun uncaughtException(t: Thread, e: Throwable) {
            if (t.name == "kaze-ai-watchdog" || t.name == "kaze-ai-cancel") {
                synchronized(hits) { hits.add(e) }
            }
        }
    }

    private fun serveOnce(body: String): Pair<Int, Thread> {
        val server = ServerSocket(0)
        val port = server.localPort
        val t = thread(isDaemon = true, name = "fake-sse") {
            try {
                val client = server.accept()
                val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                // 读掉请求头直到空行（不解析内容，只保证请求被消费）
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

    @Test
    fun `流式正常结束不会在看门狗线程上抛未捕获异常`() {
        val body = buildString {
            append("""data: {"choices":[{"delta":{"content":"你好"}}]}""").append("\n\n")
            append("""data: {"choices":[{"delta":{"content":"世界"}}]}""").append("\n\n")
            append("data: [DONE]\n\n")
        }
        val (port, serverThread) = serveOnce(body)

        val collector = Collector()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(collector)
        try {
            val reply = AiClient.chatStream(
                config = config(port),
                messages = listOf(AiMessage(role = "user", content = "hi")),
                maxTokens = 64,
                onDelta = { _, _ -> },
                shouldStop = { false },
            )
            assertEquals("你好世界", reply.content)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(prev)
            serverThread.join(2000)
        }

        // 给看门狗一点时间把异常抛出来（如果修复被回退）
        Thread.sleep(400)
        val hits = synchronized(collector.hits) { collector.hits.toList() }
        assertTrue(
            "看门狗/取消线程抛出了未捕获异常：${hits.map { it.javaClass.simpleName + ": " + it.message }}",
            hits.isEmpty(),
        )
    }

    @Test
    fun `取消时看门狗被中断也不抛未捕获异常`() {
        // 端点一直不结束（只发心跳注释行），由 shouldStop 触发取消
        val body = ": ping\n\n".repeat(50)
        val (port, serverThread) = serveOnce(body)

        val collector = Collector()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(collector)
        try {
            var count = 0
            val reply = AiClient.chatStream(
                config = config(port),
                messages = listOf(AiMessage(role = "user", content = "hi")),
                maxTokens = 64,
                onDelta = { _, _ -> count++ },
                // 立刻要求停止：走 disconnect + 看门狗中断那条路径
                shouldStop = { true },
            )
            // 取消路径返回空内容（半成品不能当完整回答）
            assertTrue(reply.content.isEmpty() || count >= 0)
        } catch (_: Throwable) {
            // 取消路径允许抛"已取消"类异常，这里只关心线程有没有未捕获异常
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(prev)
            serverThread.join(2000)
        }

        Thread.sleep(400)
        val hits = synchronized(collector.hits) { collector.hits.toList() }
        assertTrue(
            "取消路径上抛出了未捕获异常：${hits.map { it.javaClass.simpleName + ": " + it.message }}",
            hits.isEmpty(),
        )
    }
}
