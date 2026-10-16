package com.kaze.newage.core.mcp

import com.kaze.newage.core.console.ConsoleStream
import com.kaze.newage.core.console.LineType
import com.kaze.newage.core.server.ServerManager
import com.kaze.newage.core.server.ServerProperties
import com.kaze.newage.core.server.ServerState
import com.kaze.newage.data.model.ServerInstance
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files

/**
 * MCP 服务端协议与端口策略测试（纯 JVM，真实 ServerSocket 回环连接）。
 *
 * HTTP 客户端用裸 Socket 而不是 HttpURLConnection：单测 JVM 的代理系统属性
 * （http.proxyHost 等）会把 localhost 请求也带进代理，裸 Socket 不经过那条路。
 */
class McpServerTest {

    private lateinit var tempDir: File
    private lateinit var fake: FakeServerManager
    private lateinit var server: McpServer
    private val instances = mutableListOf<ServerInstance>()
    private var token = "test-token-1234"

    private val tools by lazy {
        McpTools(
            serverManager = fake,
            instanceById = { id -> instances.firstOrNull { it.id == id } },
            allInstances = { instances.toList() },
        )
    }

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("mcp-test").toFile()
        fake = FakeServerManager()
        server = McpServer(tools, "test-version", { token }, bindAddress = "127.0.0.1")
    }

    @After
    fun tearDown() {
        server.stop()
        tempDir.deleteRecursively()
    }

    // ── 协议 ──

    @Test
    fun `初始化握手返回服务信息与工具能力`() {
        val port = startServer()
        val reply = httpPost(
            port,
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26"}}""",
            token,
        )
        assertEquals(200, reply.status)
        assertTrue(reply.body.contains("\"name\":\"kaze-slauncher-mcp\""))
        assertTrue(reply.body.contains("\"version\":\"test-version\""))
        assertTrue(reply.body.contains("\"tools\""))
    }

    @Test
    fun `缺少或错误的令牌一律 401`() {
        val port = startServer()
        assertEquals(401, httpPost(port, """{"jsonrpc":"2.0","id":1,"method":"ping"}""", null).status)
        assertEquals(401, httpPost(port, """{"jsonrpc":"2.0","id":1,"method":"ping"}""", "wrong").status)
    }

    @Test
    fun `令牌为空时全部拒绝`() {
        token = ""
        val port = startServer()
        assertEquals(401, httpPost(port, """{"jsonrpc":"2.0","id":1,"method":"ping"}""", "anything").status)
    }

    @Test
    fun `通知请求返回 202 且无响应体`() {
        val port = startServer()
        val reply = httpPost(port, """{"jsonrpc":"2.0","method":"notifications/initialized"}""", token)
        assertEquals(202, reply.status)
        assertTrue(reply.body.isEmpty())
    }

    @Test
    fun `非法 JSON 返回 400 解析错误`() {
        val port = startServer()
        val reply = httpPost(port, "{这不合法", token)
        assertEquals(400, reply.status)
        assertTrue(reply.body.contains("-32700"))
    }

    @Test
    fun `未知方法返回方法不存在`() {
        val port = startServer()
        val reply = httpPost(port, """{"jsonrpc":"2.0","id":2,"method":"no/such/method"}""", token)
        assertEquals(200, reply.status)
        assertTrue(reply.body.contains("-32601"))
    }

    @Test
    fun `GET 与未知路径被拒`() {
        val port = startServer()
        val socket = Socket("127.0.0.1", port)
        socket.use {
            it.getOutputStream().write("GET /mcp HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n".toByteArray())
            it.getOutputStream().flush()
            val raw = String(it.getInputStream().readBytes(), Charsets.ISO_8859_1)
            assertTrue(raw.startsWith("HTTP/1.1 405"))
        }
    }

    // ── 工具 ──

    @Test
    fun `工具列表包含全部控制工具`() {
        val port = startServer()
        val reply = httpPost(port, """{"jsonrpc":"2.0","id":3,"method":"tools/list"}""", token)
        listOf(
            "list_instances", "get_server_status", "start_server", "stop_server",
            "restart_server", "send_command", "read_console", "list_players",
            "get_server_properties", "set_server_property", "create_backup",
        ).forEach { name -> assertTrue("缺少工具 $name", reply.body.contains("\"name\":\"$name\"")) }
    }

    @Test
    fun `列出实例返回全部实例`() {
        val port = startServer()
        addInstance("生存服")
        addInstance("创造服")
        val reply = toolsCall(port, "list_instances", "{}")
        assertTrue(reply.body.contains("生存服"))
        assertTrue(reply.body.contains("创造服"))
    }

    @Test
    fun `start_server 提交后台执行立即返回`() {
        val port = startServer()
        val i = addInstance("异步启动")
        fake.startGate = CompletableDeferred()
        try {
            val t0 = System.currentTimeMillis()
            val reply = toolsCall(port, "start_server", """{"instanceId":"${i.id}"}""")
            // 启动链路在真实实现里要跑几分钟：HTTP 必须立即返回，否则客户端 30~60s 必超时
            assertTrue(
                "start_server 同步等了 ${System.currentTimeMillis() - t0}ms，应提交后立即返回",
                System.currentTimeMillis() - t0 < 4000,
            )
            assertTrue(reply.body.contains("已提交启动"))
            fake.startGate?.complete(Unit)
            // 稍等后台协程收尾，启动应确实被执行过
            val deadline = System.currentTimeMillis() + 2000
            while (fake.started.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
            assertEquals(listOf(i.id), fake.started)
        } finally {
            fake.startGate?.complete(Unit)
        }
    }

    @Test
    fun `发送命令转发到底层管理器`() {
        val port = startServer()
        val i = addInstance()
        fake.setState(i.id, ServerState.Running)
        val reply = toolsCall(port, "send_command", """{"instanceId":"${i.id}","command":"say hi"}""")
        assertTrue(reply.body.contains("已发送命令"))
        assertEquals(listOf(i.id to "say hi"), fake.sentCommands)
    }

    @Test
    fun `实例名也能寻址`() {
        val port = startServer()
        val i = addInstance("别墅")
        fake.setState(i.id, ServerState.Running)
        toolsCall(port, "send_command", """{"instanceId":"别墅","command":"list"}""")
        assertEquals(listOf("list"), fake.sentCommands.map { it.second })
    }

    @Test
    fun `未运行时发送命令被拒绝`() {
        val port = startServer()
        val i = addInstance()
        val reply = toolsCall(port, "send_command", """{"instanceId":"${i.id}","command":"stop"}""")
        assertTrue(reply.body.contains("未在运行"))
        assertTrue(fake.sentCommands.isEmpty())
    }

    @Test
    fun `读取控制台返回末尾行`() {
        val port = startServer()
        val i = addInstance()
        fake.consoleFor(i.id).emit("第一行", LineType.Info)
        fake.consoleFor(i.id).emit("第二行", LineType.Info)
        val reply = toolsCall(port, "read_console", """{"instanceId":"${i.id}","lines":10}""")
        assertTrue(reply.body.contains("第二行"))
    }

    @Test
    fun `重启工具走停止再启动链路`() {
        val port = startServer()
        val i = addInstance()
        fake.setState(i.id, ServerState.Running)
        val reply = toolsCall(port, "restart_server", """{"instanceId":"${i.id}"}""")
        assertTrue(reply.body.contains("已提交重启"))
        // 后台链路：stop → 等停稳 → start（fake 里全是即时状态，稍等即完成）
        val deadline = System.currentTimeMillis() + 2000
        while ((fake.stopped.isEmpty() || fake.started.isEmpty()) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertEquals(listOf(i.id), fake.stopped)
        assertEquals(listOf(i.id), fake.started)
    }

    @Test
    fun `找不到实例时明确报错`() {
        val port = startServer()
        val reply = toolsCall(port, "get_server_status", """{"instanceId":"不存在的"}""")
        assertTrue(reply.body.contains("找不到实例"))
        assertTrue(reply.body.contains("\"isError\":true"))
    }

    @Test
    fun `读取与修改 server_properties`() {
        val port = startServer()
        val i = addInstance()
        toolsCall(port, "set_server_property", """{"instanceId":"${i.id}","key":"max-players","value":"20"}""")
        assertEquals("20", ServerProperties.load(i.dir)["max-players"])
        val reply = toolsCall(port, "get_server_properties", """{"instanceId":"${i.id}"}""")
        assertTrue(reply.body.contains("max-players"))
    }

    // ── 端口策略 ──

    @Test
    fun `映射端口被占用时按范围顺延`() {
        val base = freePortBlock(2)
        val occupied = ServerSocket(base, 16, InetAddress.getByName("127.0.0.1"))
        occupied.use {
            val port = server.start(base, base, base + 1)
            assertEquals(base + 1, port)
            assertEquals(base + 1, server.boundPort.value)
        }
    }

    @Test
    fun `范围内的端口全部被占用时启动失败并记录原因`() {
        val base = freePortBlock(2)
        val a = ServerSocket(base, 16, InetAddress.getByName("127.0.0.1"))
        val b = ServerSocket(base + 1, 16, InetAddress.getByName("127.0.0.1"))
        a.use { b.use {
            val port = server.start(base, base, base + 1)
            assertNull(port)
            assertNull(server.boundPort.value)
            assertNotNull(server.lastError.value)
        } }
    }

    @Test
    fun `停止后绑定端口释放`() {
        val port = startServer()
        assertEquals(port, server.boundPort.value)
        server.stop()
        assertNull(server.boundPort.value)
    }

    // ── 辅助 ──

    private fun startServer(): Int {
        val base = freePortBlock(2)
        val port = server.start(base, base, base + 1)
        assertNotNull("服务应成功绑定端口", port)
        return port!!
    }

    private fun addInstance(name: String = "测试服"): ServerInstance {
        val dir = File(tempDir, name).apply { mkdirs() }
        val instance = ServerInstance(name = name, dir = dir)
        instances += instance
        return instance
    }

    private fun toolsCall(port: Int, name: String, args: String): HttpReply = httpPost(
        port,
        """{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"$name","arguments":$args}}""",
        token,
    )

    private data class HttpReply(val status: Int, val body: String)

    private fun httpPost(port: Int, body: String, token: String?): HttpReply {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5000
            val bodyBytes = body.toByteArray(Charsets.UTF_8)
            val head = StringBuilder()
            head.append("POST /mcp HTTP/1.1\r\n")
            head.append("Host: 127.0.0.1\r\n")
            head.append("Content-Type: application/json\r\n")
            if (token != null) head.append("Authorization: Bearer $token\r\n")
            head.append("Content-Length: ${bodyBytes.size}\r\n")
            head.append("Connection: close\r\n\r\n")
            socket.getOutputStream().apply {
                write(head.toString().toByteArray(Charsets.ISO_8859_1))
                write(bodyBytes)
                flush()
            }
            return parseReply(socket.getInputStream().readBytes())
        }
    }

    private fun parseReply(raw: ByteArray): HttpReply {
        val sep = "\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
        val idx = indexOf(raw, sep)
        val head = String(raw, 0, idx, Charsets.ISO_8859_1)
        val status = head.lineSequence().first().split(" ")[1].toInt()
        val body = String(raw, idx + sep.size, raw.size - idx - sep.size, Charsets.UTF_8)
        return HttpReply(status, body)
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    /**
     * 找 n 个连续空闲端口并返回起始值。
     *
     * 不用固定端口：CI runner 上前面步骤的出站连接可能刚好占着某个端口（41000 就撞过，
     * 而它落在 Linux 临时端口区 32768–60999 内），`ServerSocket(固定端口)` 直接
     * BindException —— 测试在 CI 上必挂、本地却往往看不出来。这里在临时端口区之外
     * 探测一段连续空闲端口，探测完立即交给被测代码绑定。
     */
    private fun freePortBlock(n: Int): Int {
        for (base in FREE_PORT_BASE downTo FREE_PORT_BASE - 1000) {
            val probe = ArrayList<ServerSocket>(n)
            try {
                for (i in 0 until n) probe += ServerSocket(base + i, 16, InetAddress.getByName("127.0.0.1"))
                probe.forEach { it.close() }
                return base
            } catch (_: IOException) {
                probe.forEach { runCatching { it.close() } }
            }
        }
        throw IllegalStateException("找不到 $n 个连续空闲端口（$FREE_PORT_BASE 起向下探测 1000 个起点）")
    }

    companion object {
        /** 探测起点：远低于临时端口区下限 32768，避开出站连接撞端口 */
        private const val FREE_PORT_BASE = 30000
    }
}

/** 假管理器：记录调用，状态直接置位（restart 的「等停稳」依赖状态流） */
private class FakeServerManager : ServerManager {

    // 后台任务协程（taskScope）与测试线程跨线程读写：必须用并发集合
    val sentCommands = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()
    val started = java.util.concurrent.CopyOnWriteArrayList<String>()
    val stopped = java.util.concurrent.CopyOnWriteArrayList<String>()

    /** 非空时 start() 先挂起等它：用于验证「提交即返回」语义 */
    var startGate: CompletableDeferred<Unit>? = null

    private val consoles = mutableMapOf<String, ConsoleStream>()
    private val _states = MutableStateFlow<Map<String, ServerState>>(emptyMap())
    override val states: StateFlow<Map<String, ServerState>> = _states.asStateFlow()

    fun setState(id: String, state: ServerState) {
        _states.value = _states.value + (id to state)
    }

    override fun consoleFor(instanceId: String): ConsoleStream =
        consoles.getOrPut(instanceId) { ConsoleStream() }

    override fun uptimeSec(instanceId: String): StateFlow<Long> = MutableStateFlow(42L)

    override fun isRunning(instanceId: String): Boolean =
        _states.value[instanceId] == ServerState.Running

    override suspend fun start(instance: ServerInstance) {
        startGate?.await()
        started += instance.id
        setState(instance.id, ServerState.Running)
    }

    override suspend fun stop(instance: ServerInstance) {
        stopped += instance.id
        setState(instance.id, ServerState.Stopped)
    }

    override fun forceStop(instance: ServerInstance) {
        setState(instance.id, ServerState.Stopped)
    }

    override fun sendCommand(instance: ServerInstance, command: String) {
        sentCommands += instance.id to command
    }

    override fun release(instanceId: String) {
        consoles.remove(instanceId)
    }
}
