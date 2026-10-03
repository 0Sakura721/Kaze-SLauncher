package com.kaze.newage.core.ai

import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 响应体上限（BodyLimit + 两个客户端的接线）与明文 http 规则的测试。
 *
 * 威胁模型很直白：端点异常 / DNS 被劫持 / 恶意服务端回一个几百 MB 的 body，
 * `readText()` 会把它整个读进内存，应用直接 OOM。这里既测"超限会截断/中止"这个纯逻辑，
 * 也用一个**本地假端点**（ServerSocket 手写 HTTP，与 DownloaderRedirectTest 同一套做法：
 * 单测的 bootclasspath 是 android.jar，没有 com.sun.net.httpserver）真的走一遍
 * HttpURLConnection 路径，确认上限接在了网络读取上，而不只是写了个没人调用的函数。
 */
class AiBodyLimitTest {

    /** 永远吐同一个字节的流：模拟"端点回了一个无限长的 body"，本身不占内存 */
    private class EndlessStream(private val total: Int, private val chunk: Int = 8 * 1024) : InputStream() {
        private var served = 0
        override fun read(): Int = throw UnsupportedOperationException("按块读取")
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (served >= total) return -1
            val n = minOf(len, chunk, total - served)
            java.util.Arrays.fill(b, off, off + n, 'A'.code.toByte())
            served += n
            return n
        }
    }

    @Test
    fun `超过上限的响应体被截断并标记`() {
        val limit = 1000
        val r = BodyLimit.read(EndlessStream(50_000), limit)
        assertTrue("必须标记为截断", r.truncated)
        assertEquals(limit, r.text.length)
    }

    @Test
    fun `未超过上限的响应体原样返回`() {
        val r = BodyLimit.read(ByteArrayInputStream("hello 世界".toByteArray()), 1000)
        assertFalse(r.truncated)
        assertEquals("hello 世界", r.text)
        // 恰好等于上限：不算截断（边界不能差一个字节）
        val exact = BodyLimit.read(EndlessStream(1000), 1000)
        assertFalse(exact.truncated)
        assertEquals(1000, exact.text.length)
        // null 流（没有错误体的 200 响应）给空串而不是抛异常
        assertEquals("", BodyLimit.read(null).text)
    }

    // ── 本地假端点：真的走一遍 HttpURLConnection ──

    private data class Resp(val code: Int, val body: ByteArray = ByteArray(0), val headers: Map<String, String> = emptyMap())

    private fun withServer(responder: () -> Resp, test: (baseUrl: String) -> Unit) {
        val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            try {
                while (true) {
                    val socket = serverSocket.accept() ?: break
                    thread(isDaemon = true) { handle(socket, responder) }
                }
            } catch (_: IOException) {
                // close() 后 accept 抛异常即退出
            }
        }
        try {
            test("http://127.0.0.1:${serverSocket.localPort}")
        } finally {
            serverSocket.close()
        }
    }

    private fun handle(socket: Socket, responder: () -> Resp) {
        try {
            socket.use { s ->
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
                reader.readLine() ?: return
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
                val resp = responder()
                val head = buildString {
                    append("HTTP/1.1 ${resp.code} Reason\r\n")
                    resp.headers.forEach { (k, v) -> append("$k: $v\r\n") }
                    append("Content-Length: ${resp.body.size}\r\n")
                    append("Connection: close\r\n\r\n")
                }
                val out = s.getOutputStream()
                out.write(head.toByteArray(Charsets.ISO_8859_1))
                out.write(resp.body)
                out.flush()
            }
        } catch (_: Exception) {
            // 客户端读够就断开（正是我们期望的行为），测试里不需要区分
        }
    }

    private fun configFor(base: String) = AiConfig(baseUrl = base, model = "m", apiKey = "test-key")

    @Test
    fun `本地假端点返回超大响应体时中止读取而不是 OOM`() {
        // 8MB：超过 2MB 上限 4 倍。真读进来会占满测试堆，上限生效时只会读 2MB
        withServer({ Resp(200, ByteArray(8 * 1024 * 1024) { 'A'.code.toByte() }) }) { base ->
            var message = ""
            try {
                AiClient.chat(configFor(base), listOf(AiMessage(AiMessage.ROLE_USER, "q")))
            } catch (e: RuntimeException) {
                message = e.message ?: ""
            }
            assertTrue("应报『响应体过大并中止读取』，实际：$message", message.contains("中止读取"))
        }
    }

    @Test
    fun `正常大小的响应体照常解析`() {
        val body = """{"choices":[{"message":{"role":"assistant","content":"一切正常"}}]}""".toByteArray()
        withServer({ Resp(200, body) }) { base ->
            val reply = AiClient.chat(configFor(base), listOf(AiMessage(AiMessage.ROLE_USER, "q")))
            assertEquals("一切正常", reply.content)
        }
    }

    @Test
    fun `错误体同样限长且标明是服务端返回`() {
        // 500 + 8MB HTML 错误页：进内存的只能是前 8KB
        withServer({ Resp(500, ByteArray(8 * 1024 * 1024) { 'E'.code.toByte() }) }) { base ->
            var message = ""
            try {
                AiClient.chat(configFor(base), listOf(AiMessage(AiMessage.ROLE_USER, "q")))
            } catch (e: RuntimeException) {
                message = e.message ?: ""
            }
            assertTrue("错误信息应带 HTTP 码，实际：$message", message.contains("500"))
            assertTrue("错误体是服务端原文，必须标明来源，实际：$message", message.contains("服务端返回"))
            // 提示行会截到 160 字符：8MB 若真进了内存，这条断言之外早就 OOM 了
            assertTrue("不该把整个错误体塞进提示，实际长度 ${message.length}", message.length < 400)
        }
    }

    @Test
    fun `明文 http 只放行本机地址`() {
        assertTrue(AiConfig.isCleartextHostAllowed("localhost"))
        assertTrue(AiConfig.isCleartextHostAllowed("127.0.0.1"))
        assertTrue(AiConfig.isCleartextHostAllowed("::1"))
        assertTrue(AiConfig.isCleartextHostAllowed("[::1]"))
        assertTrue(AiConfig.isCleartextHostAllowed("LOCALHOST"))
        assertFalse(AiConfig.isCleartextHostAllowed("192.168.1.5"))
        assertFalse(AiConfig.isCleartextHostAllowed("api.deepseek.com"))
        assertFalse(AiConfig.isCleartextHostAllowed(null))
    }

    @Test
    fun `非本机的明文 http 端点被提前拒绝且给出可读原因`() {
        val cfg = AiConfig(baseUrl = "http://192.168.1.5:11434/v1", model = "m", apiKey = "k")
        var message = ""
        try {
            AiClient.chat(cfg, listOf(AiMessage(AiMessage.ROLE_USER, "q")))
        } catch (e: RuntimeException) {
            message = e.message ?: ""
        }
        // 这条路径必须在真正发起连接之前就结束（局域网地址连不上也不该等超时）
        assertTrue("应说明明文只允许本机，实际：$message", message.contains("明文 http 只允许本机"))
    }

    @Test
    fun `错误描述里的服务端原文与提示分得开`() {
        val msg = AiClient.describeHttpError(402, "{\"error\":\"Insufficient Balance\"}")
        assertTrue(msg.contains("余额不足"))
        assertTrue(msg.contains("服务端返回："))
        assertTrue(msg.contains("Insufficient Balance"))
        // 没有错误体时不硬凑一句"服务端返回：（空）"
        assertFalse(AiClient.describeHttpError(503, "").contains("服务端返回"))
    }

    /**
     * 取消**不能卡住调用线程**。
     *
     * 假端点回了状态行但永远不回正文，客户端阻塞在读正文上（readTimeout 120 秒）。
     * 此时调 [AiClient.cancelActiveAsync] 必须立刻返回：JDK 的 `disconnect()` 在读取
     * 进行中会一直阻塞到 readTimeout（本测试第一版断言"数秒内结束"，CI 上实测 119392ms
     * 才返回 —— 如果直接在主线程调它，UI 会僵住两分钟）。所以界面路径只能走异步版本。
     *
     * 这里**不**等待底层请求结束（那要等 readTimeout）：它不是守护线程场景可以慢慢跑，
     * 断言的是"调用方不会被拖住"这一条真正能保证的性质。
     */
    @Test
    fun `取消不会卡住调用线程`() {
        val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            try {
                while (true) {
                    val socket = serverSocket.accept() ?: break
                    thread(isDaemon = true) {
                        runCatching {
                            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
                            reader.readLine()
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isEmpty()) break
                            }
                            // 声明 1MB 正文但一个字节都不发：客户端会阻塞在读取上
                            socket.getOutputStream().apply {
                                write(
                                    ("HTTP/1.1 200 OK\r\nContent-Length: 1048576\r\nConnection: close\r\n\r\n")
                                        .toByteArray(Charsets.ISO_8859_1)
                                )
                                flush()
                            }
                            Thread.sleep(30_000)
                        }
                    }
                }
            } catch (_: IOException) {
            }
        }
        val base = "http://127.0.0.1:${serverSocket.localPort}"
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "ai-cancel-test").apply { isDaemon = true }
        }
        try {
            val future = pool.submit<Throwable?> {
                try {
                    AiClient.chat(configFor(base), listOf(AiMessage(AiMessage.ROLE_USER, "q")))
                    null
                } catch (t: Throwable) {
                    t
                }
            }
            // 等它真的进到"读正文"那一步（状态行已回，正文永远不来）
            Thread.sleep(700)
            assertFalse("请求不该在取消前就结束", future.isDone)

            val t0 = System.currentTimeMillis()
            AiClient.cancelActiveAsync()
            val cost = System.currentTimeMillis() - t0
            assertTrue("取消调用必须立刻返回（UI 线程就是这么调的），实际 ${cost}ms", cost < 1_500)

            // 空操作也要安全：没有在飞请求时取消不该抛异常，也不该阻塞
            val t1 = System.currentTimeMillis()
            AiClient.cancelActiveAsync()
            assertTrue("空操作取消也不该阻塞，实际 ${System.currentTimeMillis() - t1}ms", System.currentTimeMillis() - t1 < 1_500)
        } finally {
            pool.shutdownNow()
            runCatching { serverSocket.close() }
        }
    }
}
