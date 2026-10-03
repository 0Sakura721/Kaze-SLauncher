package com.kaze.newage.core.ai

import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 响应体上限（[AiBodyLimit] 与 AiClient 的接线）、明文 http 规则、取消不阻塞调用方。
 *
 * 威胁模型很直白：端点异常 / DNS 或网关被劫持 / 恶意服务端回一个几百 MB 的响应体，
 * `readText()` 会把它整个读进内存 —— 应用当场 OOM。这里既测纯逻辑（截断与边界），
 * 也用**本地假端点**（手写 ServerSocket，与 DownloaderRedirectTest 同一套做法：
 * 单测 bootclasspath 是 android.jar，没有 com.sun.net.httpserver）真走一遍
 * HttpURLConnection，确认上限接在了网络读取上，而不是写了个没人调用的函数。
 */
class AiBodyLimitTest {

    /** 无限吐同一个字节的流：模拟"端点回了超长响应体"，本身不占内存 */
    private class EndlessStream(private val total: Int, private val chunk: Int = 8 * 1024) : InputStream() {
        private var served = 0
        override fun read(): Int {
            if (served >= total) return -1
            served++
            return 'A'.code
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (served >= total) return -1
            val n = minOf(len, chunk, total - served)
            java.util.Arrays.fill(b, off, off + n, 'A'.code.toByte())
            served += n
            return n
        }
    }

    // ── 纯逻辑 ──

    @Test
    fun `超过上限的响应体被截断并标记`() {
        val r = AiBodyLimit.read(EndlessStream(50_000), limit = 1000)
        assertTrue("必须标记为截断", r.truncated)
        assertEquals(1000, r.text.length)
    }

    @Test
    fun `未超过上限的响应体原样返回且边界不多不少`() {
        val ok = AiBodyLimit.read(ByteArrayInputStream("hello 世界".toByteArray()), limit = 1000)
        assertFalse(ok.truncated)
        assertEquals("hello 世界", ok.text)
        // 恰好等于上限不算截断
        val exact = AiBodyLimit.read(EndlessStream(1000), limit = 1000)
        assertFalse(exact.truncated)
        assertEquals(1000, exact.text.length)
        // null 流（没有错误体的响应）给空串而不是抛异常
        assertEquals("", AiBodyLimit.read(null).text)
    }

    @Test
    fun `错误体上限远小于正文上限`() {
        assertEquals(8 * 1024, AiBodyLimit.MAX_ERROR_BYTES)
        assertEquals(2 * 1024 * 1024, AiBodyLimit.MAX_BODY_BYTES)
        // 错误体只用来取第一行原因，超长直接截断
        assertEquals(AiBodyLimit.MAX_ERROR_BYTES, AiBodyLimit.readErrorText(EndlessStream(1_000_000)).length)
    }

    @Test
    fun `流式上限到顶即报 EOF 并标记`() {
        val capped = AiBodyLimit.CappedStream(EndlessStream(100_000), limit = 500)
        val text = capped.bufferedReader().use { it.readText() }
        assertEquals(500, text.length)
        assertTrue("触到上限必须标记，否则半成品会被当成完整回答", capped.capped)

        val ok = AiBodyLimit.CappedStream(ByteArrayInputStream("data: [DONE]\n".toByteArray()), limit = 500)
        ok.bufferedReader().use { it.readText() }
        assertFalse(ok.capped)
    }

    // ── 本地假端点：真的走一遍 HttpURLConnection ──

    private data class Resp(
        val code: Int,
        val body: ByteArray = ByteArray(0),
        val headers: Map<String, String> = emptyMap(),
    )

    private fun withServer(responder: () -> Resp, test: (baseUrl: String) -> Unit) {
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val acceptor = java.lang.Thread {
            try {
                while (true) {
                    val socket = server.accept() ?: break
                    java.lang.Thread { serve(socket, responder) }.apply { isDaemon = true }.start()
                }
            } catch (_: IOException) {
                // close() 之后 accept 抛异常即退出
            }
        }
        acceptor.isDaemon = true
        acceptor.start()
        try {
            test("http://127.0.0.1:${server.localPort}")
        } finally {
            server.close()
        }
    }

    private fun serve(socket: Socket, responder: () -> Resp) {
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
            // 客户端读够就断开（正是期望的行为），测试里不区分
        }
    }

    private fun configFor(base: String) = AiConfig(baseUrl = base, model = "m", apiKey = "test-key")

    @Test
    fun `本地假端点返回超大响应体时中止读取而不是 OOM`() {
        // 8MB = 上限的 4 倍：真读进来会占满测试堆；上限生效时只会读 2MB
        withServer({ Resp(200, ByteArray(8 * 1024 * 1024) { 'A'.code.toByte() }) }) { base ->
            val message = runCatching {
                AiClient.chat(configFor(base), listOf(AiMessage(AiMessage.ROLE_USER, "q")))
            }.exceptionOrNull()?.message.orEmpty()
            assertTrue("应报『响应体超过 2MB，已中止读取』，实际：$message", message.contains("中止读取"))
        }
    }

    @Test
    fun `超大错误体被截断后仍给出可读错误`() {
        // 错误页常见几 MB HTML：只取第一行、且必须被 8KB 上限截断
        val html = "<html><body>" + "x".repeat(3 * 1024 * 1024) + "</body></html>"
        withServer({ Resp(500, html.toByteArray()) }) { base ->
            val message = runCatching {
                AiClient.chat(configFor(base), listOf(AiMessage(AiMessage.ROLE_USER, "q")))
            }.exceptionOrNull()?.message.orEmpty()
            assertTrue("应含 HTTP 状态提示，实际：$message", message.contains("500"))
            assertTrue("错误体不该整段回显，实际长度 ${message.length}", message.length < 4096)
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

    // ── 明文 http 规则 ──

    @Test
    fun `明文 http 只放行本机地址`() {
        listOf("localhost", "127.0.0.1", "::1", "[::1]", " LocalHost ").forEach {
            assertTrue("应放行：$it", AiConfig.isCleartextHostAllowed(it))
        }
        listOf(null, "", "example.com", "192.168.1.5", "10.0.0.1", "api.deepseek.com").forEach {
            assertFalse("不该放行：$it", AiConfig.isCleartextHostAllowed(it))
        }
    }

    @Test
    fun `非本机明文端点被提前拦下并说明原因`() {
        // 提前拦截发生在建立连接之前：这里不会产生任何网络请求，所以用公网域名也是确定的
        val message = runCatching {
            AiClient.chat(
                AiConfig(baseUrl = "http://api.example.com", model = "m", apiKey = "k"),
                listOf(AiMessage(AiMessage.ROLE_USER, "q")),
            )
        }.exceptionOrNull()?.message.orEmpty()
        assertTrue("应说明只允许本机 http，实际：$message", message.contains("明文 http 只允许本机地址"))
        assertTrue("应给出改用 https 的指引，实际：$message", message.contains("https"))
    }

    // ── 取消 ──

    @Test
    fun `取消入口不阻塞调用方`() {
        // 假端点：接受连接、回完响应头就不再有数据 —— 客户端会卡在阻塞读上
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val acceptor = java.lang.Thread {
            try {
                while (true) {
                    val socket = server.accept() ?: break
                    java.lang.Thread {
                        runCatching {
                            socket.use { s ->
                                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
                                while (true) {
                                    val line = reader.readLine() ?: break
                                    if (line.isEmpty()) break
                                }
                                s.getOutputStream().apply {
                                    write(
                                        ("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n" +
                                            "Connection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1)
                                    )
                                    flush()
                                }
                                // 之后一直不写数据：客户端阻塞在 readLine 上
                                Thread.sleep(20_000)
                            }
                        }
                    }.apply { isDaemon = true }.start()
                }
            } catch (_: IOException) {
            }
        }
        acceptor.isDaemon = true
        acceptor.start()
        val base = "http://127.0.0.1:${server.localPort}"
        val worker = java.lang.Thread {
            runCatching { AiClient.chatStream(configFor(base), listOf(AiMessage(AiMessage.ROLE_USER, "q"))) }
        }.apply { isDaemon = true }
        worker.start()
        try {
            Thread.sleep(500) // 让上面的读真的卡在 socket 上
            val started = System.currentTimeMillis()
            AiClient.cancelActiveAsync()
            val elapsed = System.currentTimeMillis() - started
            // disconnect() 在阻塞读进行中自身会等到 readTimeout 才返回（实测约 119 秒），
            // 界面路径绝不能在调用方线程上等它 —— 这条断言就是那个约束
            assertTrue("取消入口必须立刻返回（实测 ${elapsed}ms）", elapsed < 1500)
            // 空操作取消同样不许阻塞
            val idle = System.currentTimeMillis()
            AiClient.cancelActiveAsync()
            assertTrue("空操作取消也必须立刻返回", System.currentTimeMillis() - idle < 1500)
        } finally {
            server.close()
        }
    }
}
