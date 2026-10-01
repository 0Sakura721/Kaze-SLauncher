package com.kaze.newage.util

import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Downloader 重定向处理测试：
 *  - 缺 Location：必须报错，且连接已归还（不泄漏）
 *  - 明文 http 目标：一律拒绝，且**不得**真的去请求目标（降级攻击从源头堵住）
 *  - 相对 Location：按当前跳解析，正常下载不受影响
 *
 * 服务端用 ServerSocket 手写最小 HTTP 响应 —— 单测编译的 bootclasspath 是 android.jar，
 * 没有 com.sun.net.httpserver；这里只需要「收请求 → 回一行状态 + 头 + 体」，
 * 每个响应都带 Connection: close，一条连接一个响应，无需处理 keep-alive 复用。
 */
class DownloaderRedirectTest {

    private data class Resp(
        val code: Int,
        val headers: Map<String, String> = emptyMap(),
        val body: ByteArray = ByteArray(0),
    )

    private val payload = "kaze-payload-0123456789abcdef".toByteArray()

    private fun withServer(
        responderFor: (port: Int) -> (path: String) -> Resp,
        test: (baseUrl: String) -> Unit,
    ) {
        val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val responder = responderFor(serverSocket.localPort)
        thread(isDaemon = true) {
            try {
                while (true) {
                    val socket = serverSocket.accept() ?: break
                    thread(isDaemon = true) { handle(socket, responder) }
                }
            } catch (_: IOException) {
                // stop() 会 close 掉 ServerSocket，accept 抛异常即退出
            }
        }
        try {
            test("http://127.0.0.1:${serverSocket.localPort}")
        } finally {
            serverSocket.close()
        }
    }

    private fun handle(socket: Socket, responder: (String) -> Resp) {
        try {
            socket.use { s ->
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
                val requestLine = reader.readLine() ?: return
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
                val path = requestLine.split(" ").getOrNull(1) ?: "/"
                val resp = responder(path)
                val head = buildString {
                    append("HTTP/1.1 ${resp.code} Reason\r\n")
                    resp.headers.forEach { (k, v) -> append("$k: $v\r\n") }
                    append("Content-Length: ${resp.body.size}\r\n")
                    append("Connection: close\r\n")
                    append("\r\n")
                }
                val out = s.getOutputStream()
                out.write(head.toByteArray(Charsets.ISO_8859_1))
                out.write(resp.body)
                out.flush()
            }
        } catch (_: Exception) {
            // 客户端提前断开等，测试里无需区分
        }
    }

    private fun tempDest(): File = File.createTempFile("redirect-test", ".bin").apply { delete() }

    @Test
    fun `重定向缺 Location 时报错`() {
        withServer({ _ ->
            { path: String ->
                if (path == "/noloc") Resp(302) else Resp(404)
            }
        }) { base ->
            val dest = tempDest()
            try {
                var message = ""
                try {
                    Downloader.download("$base/noloc", dest, validate = { true })
                } catch (e: RuntimeException) {
                    message = e.message ?: ""
                }
                assertTrue("应报『重定向无 Location』，实际：$message", message.contains("Location"))
            } finally {
                dest.delete()
            }
        }
    }

    @Test
    fun `明文 http 重定向被拒绝且不请求目标`() {
        val payloadHit = AtomicBoolean(false)
        withServer({ port ->
            { path: String ->
                when (path) {
                    "/downgrade" -> Resp(302, headers = mapOf("Location" to "http://127.0.0.1:$port/payload"))
                    "/payload" -> { payloadHit.set(true); Resp(200, body = payload) }
                    else -> Resp(404)
                }
            }
        }) { base ->
            val dest = tempDest()
            try {
                var message = ""
                try {
                    Downloader.download("$base/downgrade", dest, validate = { true })
                } catch (e: RuntimeException) {
                    message = e.message ?: ""
                }
                assertTrue("应拒绝明文 http 目标，实际：$message", message.contains("明文"))
                assertFalse("拒绝后不得真的请求明文目标", payloadHit.get())
            } finally {
                dest.delete()
            }
        }
    }

    @Test
    fun `相对 Location 按当前跳解析可正常下载`() {
        withServer({ _ ->
            { path: String ->
                when (path) {
                    "/rel" -> Resp(302, headers = mapOf("Location" to "/payload"))
                    "/payload" -> Resp(200, body = payload)
                    else -> Resp(404)
                }
            }
        }) { base ->
            val dest = tempDest()
            try {
                Downloader.download("$base/rel", dest, validate = { true })
                assertTrue("下载内容应与源一致", payload.contentEquals(dest.readBytes()))
            } finally {
                dest.delete()
            }
        }
    }

    @Test
    fun `downloadText 同样拒绝明文 http 重定向`() {
        withServer({ port ->
            { path: String ->
                if (path == "/downgrade") {
                    Resp(302, headers = mapOf("Location" to "http://127.0.0.1:$port/anything"))
                } else {
                    Resp(404)
                }
            }
        }) { base ->
            var message = ""
            try {
                Downloader.downloadText("$base/downgrade")
            } catch (e: RuntimeException) {
                message = e.message ?: ""
            }
            assertTrue("downloadText 应走同一套重定向规则，实际：$message", message.contains("明文"))
        }
    }
}
