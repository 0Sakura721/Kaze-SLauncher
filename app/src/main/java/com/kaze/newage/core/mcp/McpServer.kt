package com.kaze.newage.core.mcp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * MCP 端口策略：**映射端口优先，被占用时按范围顺延**。
 *
 * 「映射端口」是外部 AI 客户端配置里填的那个入口端口（用户指定、尽量稳定）；
 * 它可能被系统随机分配撞掉或被别的应用占着，所以再给一段可自定义的范围：
 * 从起始端口往下扫，取第一个能绑定的。范围也耗尽就明确失败，不悄悄挪到范围外——
 * 悄悄挪走的端口用户配置里没有，外部 AI 反而永远连不上。
 */
object McpPorts {

    /** 端口合法区间：与服务端端口同一约定（1024 以下需要特权） */
    const val PORT_MIN = 1024
    const val PORT_MAX = 65535

    /** 默认监听地址：所有网卡（局域网可访问，外部 AI 的默认用法）；可改 127.0.0.1 仅本机 */
    const val DEFAULT_BIND_ADDRESS = "0.0.0.0"

    /**
     * 绑定一个可用端口：先试 [preferred]，再按 [rangeStart]..[rangeEnd] 顺延。
     *
     * @throws IOException 范围内全部被占用（或配置无效）时抛出，message 里带上原因。
     */
    fun bind(
        preferred: Int,
        rangeStart: Int,
        rangeEnd: Int,
        bindAddress: String = DEFAULT_BIND_ADDRESS,
    ): ServerSocket {
        val tried = linkedSetOf<Int>()
        if (preferred in PORT_MIN..PORT_MAX) tried += preferred
        if (rangeStart in PORT_MIN..PORT_MAX && rangeEnd >= rangeStart) {
            for (p in rangeStart..rangeEnd) tried += p
        }
        var last: IOException? = null
        for (port in tried) {
            try {
                // backlog 16：MCP 客户端同时在飞的请求就几条，够用
                return ServerSocket(port, 16, InetAddress.getByName(bindAddress))
            } catch (e: IOException) {
                last = e
            }
        }
        throw last ?: IOException("端口配置无效：没有可尝试的端口（映射端口 $preferred，范围 $rangeStart..$rangeEnd）")
    }
}

/**
 * MCP 服务端（Streamable HTTP 传输）：让外部 AI 客户端（Claude / Cursor 等）
 * 通过 `POST /mcp` 的 JSON-RPC 2.0 调用 [McpTools] 里的工具，直接控制本机的
 * Minecraft 服务端 —— 读日志、发命令、启停实例、改配置。
 *
 * 安全边界只有一道：**访问令牌**（`Authorization: Bearer <token>`）。
 * 接口默认绑在所有网卡上（0.0.0.0，外部 AI 就是靠局域网进来的；也可改 127.0.0.1 仅本机），
 * 同一 Wi-Fi 下没有令牌的请求一律 401；服务默认关闭，由用户在「设置 → MCP 服务」显式开启
 * （见 [SettingsPrefs][com.kaze.newage.data.prefs.SettingsPrefs]）。
 *
 * 传输选 Streamable HTTP 而不是 stdio：外部客户端与本机是网络两端，且 stdio 服务
 * 需要客户端"拉起"进程 —— 手机上的应用进程由自己生命周期管理，做不了被拉起的一方。
 * HTTP 响应用 `Connection: close` 一请求一连接：实现简单、对客户端没有 keep-alive 依赖。
 *
 * 随应用进程存活：进程死即服务死（服务端实例由前台服务另行守护）。
 */
class McpServer(
    private val tools: McpTools,
    private val serverVersion: String,
    /** 令牌即时读取：设置页重新生成令牌后无需重启服务 */
    private val tokenProvider: () -> String,
    private val bindAddress: String = McpPorts.DEFAULT_BIND_ADDRESS,
) {

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    /** 连接处理池：daemon 线程，进程退出不留尾巴；4 条并发对单客户端足够 */
    private val pool = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "mcp-worker").apply { isDaemon = true }
    }

    private val _boundPort = MutableStateFlow<Int?>(null)

    /** 实际监听的端口（映射端口或顺延后的端口）；null = 服务未运行 */
    val boundPort: StateFlow<Int?> = _boundPort.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)

    /** 最近一次启动失败的原因（成功启动后清空） */
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    val isRunning: Boolean get() = serverSocket != null

    /**
     * 启动服务：按 [McpPorts.bind] 的策略绑定端口并开始接受连接。
     *
     * @param bindAddress 监听地址（0.0.0.0 全网卡 / 127.0.0.1 仅本机 / 具体 IPv4）；
     *   缺省用构造时传入的地址。设置页改动后重绑时传入新值。
     * @return 实际绑定的端口；失败返回 null（原因在 [lastError]）。
     */
    @Synchronized
    fun start(
        preferredPort: Int,
        rangeStart: Int,
        rangeEnd: Int,
        bindAddress: String = this.bindAddress,
    ): Int? {
        stop()
        val socket = try {
            McpPorts.bind(preferredPort, rangeStart, rangeEnd, bindAddress)
        } catch (e: IOException) {
            _lastError.value = "端口绑定失败：${e.message}"
            return null
        }
        serverSocket = socket
        _boundPort.value = socket.localPort
        _lastError.value = null
        acceptThread = thread(isDaemon = true, name = "mcp-accept") {
            acceptLoop(socket)
        }
        return socket.localPort
    }

    /** 停止服务并断开所有连接（进行中的工具调用随线程结束丢弃） */
    @Synchronized
    fun stop() {
        val socket = serverSocket
        serverSocket = null
        _boundPort.value = null
        runCatching { socket?.close() }
        // accept 在 close 后抛 SocketException 退出循环；这里不 join，随它自然收尾
        acceptThread = null
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: SocketException) {
                break // 正常停止路径：socket 被 close
            } catch (_: Exception) {
                continue
            }
            pool.execute {
                try {
                    handle(client)
                } catch (_: Exception) {
                    // 单次坏请求不值得让线程带崩服务
                }
            }
        }
    }

    // ── HTTP ──

    private fun handle(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 30_000
            val input = BufferedInputStream(s.getInputStream())
            val request = HttpRequest.read(input) ?: return
            route(request).writeTo(s.getOutputStream())
        }
    }

    private fun route(req: HttpRequest): HttpResponse {
        if (req.method == "OPTIONS") return HttpResponse.preflight()
        // 容忍尾斜杠：/mcp/ 与 /mcp 等价
        val path = req.path.trimEnd('/').ifEmpty { "/" }
        if (path != "/mcp" && path != "/") return HttpResponse.text(404, "not found")
        if (req.method != "POST") return HttpResponse.text(405, "use POST /mcp")
        if (req.contentLength > MAX_BODY_BYTES) return HttpResponse.text(413, "body too large")
        if (!authorized(req)) {
            return HttpResponse(
                status = 401,
                headers = mapOf(
                    "Content-Type" to "text/plain; charset=utf-8",
                    "WWW-Authenticate" to "Bearer",
                ),
                body = "unauthorized: 需要请求头 Authorization: Bearer <访问令牌>".toByteArray(Charsets.UTF_8),
            )
        }
        return jsonRpc(req.body)
    }

    private fun authorized(req: HttpRequest): Boolean {
        val expected = tokenProvider()
        if (expected.isEmpty()) return false // 没有令牌等于完全不设防，宁可全拒
        val header = req.headers["authorization"]?.trim().orEmpty()
        val token = header.removePrefix("Bearer").trim()
        // 恒时比较：令牌短、请求少，这一点开销换掉时序侧信道是值得的
        return constantTimeEquals(token, expected)
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        val x = a.toByteArray(Charsets.UTF_8)
        val y = b.toByteArray(Charsets.UTF_8)
        if (x.size != y.size) return false
        var diff = 0
        for (i in x.indices) diff = diff or (x[i].toInt() xor y[i].toInt())
        return diff == 0
    }

    // ── JSON-RPC 2.0（Streamable HTTP：POST 一个请求 → 一个 JSON 响应）──

    private fun jsonRpc(body: String): HttpResponse {
        val parsed = try {
            json.parseToJsonElement(body)
        } catch (_: SerializationException) {
            return HttpResponse.json(400, errorBody(null, -32700, "解析失败：不是合法 JSON"))
        }
        val obj = parsed as? JsonObject
            ?: return HttpResponse.json(400, errorBody(null, -32600, "无效请求：不是 JSON-RPC 对象"))
        val id = obj["id"]
        if (id != null && id !is JsonPrimitive) {
            return HttpResponse.json(400, errorBody(null, -32600, "无效请求：id 必须是基本类型"))
        }
        // 无 id = 通知：服务端不回应（JSON-RPC 约定），HTTP 层回 202
        val isNotification = id == null
        val method = obj["method"]?.jsonPrimitive?.contentOrNull
            ?: return HttpResponse.json(400, errorBody(id, -32600, "无效请求：缺少 method"))
        val params = obj["params"]?.let { it as? JsonObject } ?: JsonObject(emptyMap())

        val resultElement: JsonElement = try {
            dispatch(method, params)
        } catch (e: RpcError) {
            return if (isNotification) HttpResponse.accepted()
            else HttpResponse.json(200, errorBody(id, e.code, e.message ?: "请求错误"))
        } catch (e: Exception) {
            return if (isNotification) HttpResponse.accepted()
            else HttpResponse.json(200, errorBody(id, -32603, e.message ?: "内部错误"))
        }
        return if (isNotification) HttpResponse.accepted()
        else HttpResponse.json(200, resultBody(id, resultElement))
    }

    private fun dispatch(method: String, params: JsonObject): JsonElement = when (method) {
        "initialize" -> initialize(params)
        "ping" -> buildJsonObject { }
        "tools/list" -> buildJsonObject {
            put("tools", buildJsonArray {
                tools.tools.forEach { t ->
                    add(buildJsonObject {
                        put("name", t.name)
                        put("description", t.description)
                        put("inputSchema", t.inputSchema)
                    })
                }
            })
        }
        "tools/call" -> toolsCall(params)
        // 通知类（initialized / cancelled 等）：无副作用，正常回空结果
        "notifications/initialized", "notifications/cancelled" -> buildJsonObject { }
        else -> throw RpcError(-32601, "方法不存在：$method")
    }

    private fun initialize(params: JsonObject): JsonElement {
        val requested = params["protocolVersion"]?.jsonPrimitive?.contentOrNull
        val version = requested?.takeIf { it in SUPPORTED_PROTOCOLS } ?: LATEST_PROTOCOL
        return buildJsonObject {
            put("protocolVersion", version)
            put("capabilities", buildJsonObject {
                put("tools", buildJsonObject { })
            })
            put("serverInfo", buildJsonObject {
                put("name", SERVER_NAME)
                put("version", serverVersion)
            })
        }
    }

    private fun toolsCall(params: JsonObject): JsonElement {
        val name = params["name"]?.jsonPrimitive?.contentOrNull
            ?: throw RpcError(-32602, "缺少参数：name")
        val args = params["arguments"]?.let { it as? JsonObject } ?: buildJsonObject { }
        val result = kotlinx.coroutines.runBlocking { tools.call(name, args) }
        return buildJsonObject {
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", result.text)
                })
            })
            put("isError", result.isError)
        }
    }

    private fun resultBody(id: JsonElement?, result: JsonElement): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id ?: JsonNull)
        put("result", result)
    }.toString()

    private fun errorBody(id: JsonElement?, code: Int, message: String): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id ?: JsonNull)
        put("error", buildJsonObject {
            put("code", code)
            put("message", message)
        })
    }.toString()

    /** JSON-RPC 错误（方法不存在 / 参数错）：在 jsonRpc 里转成 error 响应 */
    private class RpcError(val code: Int, message: String) : RuntimeException(message)

    companion object {
        /** 对外服务名（initialize 握手时返回，客户端配置里认它） */
        const val SERVER_NAME = "kaze-slauncher-mcp"

        /** 默认映射端口与顺延范围（用户可在设置里改） */
        const val DEFAULT_PORT = 3800
        const val DEFAULT_RANGE_START = 3800
        const val DEFAULT_RANGE_END = 3899

        /** 传输层版本：客户端声明哪个就回哪个（都在支持表里），不认识则回最新 */
        val SUPPORTED_PROTOCOLS = listOf("2024-11-05", "2025-03-26")
        const val LATEST_PROTOCOL = "2025-03-26"

        /** 请求体上限：工具参数是小 JSON，超限必是误用或滥用 */
        const val MAX_BODY_BYTES = 256 * 1024

        private val json = Json { ignoreUnknownKeys = true }
    }
}

// ── HTTP 读写（最小实现：请求头 + Content-Length 定长体，一请求一连接）──

private class HttpRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: String,
    val contentLength: Int,
) {
    companion object {
        /**
         * 读一个完整请求。头按行读、体按 Content-Length 定长读，
         * 全程只用这一个 [BufferedInputStream]（各自读会把对方的字节缓冲走）。
         */
        fun read(input: BufferedInputStream): HttpRequest? {
            val requestLine = readLine(input) ?: return null
            val parts = requestLine.split(' ')
            if (parts.size < 3) return null
            val headers = LinkedHashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) {
                    headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                }
            }
            val length = headers["content-length"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            val body = readBody(input, length)
            return HttpRequest(
                method = parts[0],
                path = parts[1].substringBefore('?'),
                headers = headers,
                body = body,
                contentLength = length,
            )
        }

        private fun readLine(input: BufferedInputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b == -1) return if (sb.isEmpty()) null else sb.toString()
                if (b == '\n'.code) return sb.toString()
                if (b != '\r'.code) sb.append(b.toChar())
            }
        }

        private fun readBody(input: BufferedInputStream, length: Int): String {
            if (length <= 0) return ""
            // 只取上限内的部分：超长请求体由路由层按 413 拒绝，这里不先把内存吃满
            val want = minOf(length, McpServer.MAX_BODY_BYTES + 1)
            val buf = ByteArray(want)
            var read = 0
            while (read < want) {
                val n = input.read(buf, read, want - read)
                if (n < 0) break
                read += n
            }
            return String(buf, 0, read, Charsets.UTF_8)
        }
    }
}

private class HttpResponse(
    val status: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0),
) {
    fun writeTo(out: OutputStream) {
        val head = StringBuilder()
        head.append("HTTP/1.1 $status ${reason(status)}\r\n")
        CORS_HEADERS.forEach { (k, v) -> head.append("$k: $v\r\n") }
        headers.forEach { (k, v) -> head.append("$k: $v\r\n") }
        head.append("Content-Length: ${body.size}\r\n")
        head.append("Connection: close\r\n\r\n")
        out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        if (body.isNotEmpty()) out.write(body)
        out.flush()
    }

    companion object {
        /**
         * CORS 头全量带上：非浏览器客户端无所谓，浏览器插件类的调试客户端
         * （MCP Inspector 等）没它就发不出带 Authorization 的请求。
         */
        val CORS_HEADERS = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Allow-Methods" to "POST, GET, OPTIONS",
            "Access-Control-Allow-Headers" to "Authorization, Content-Type, Accept, Mcp-Protocol-Version",
        )

        fun json(status: Int, text: String) = HttpResponse(
            status = status,
            headers = mapOf("Content-Type" to "application/json; charset=utf-8"),
            body = text.toByteArray(Charsets.UTF_8),
        )

        fun text(status: Int, text: String) = HttpResponse(
            status = status,
            headers = mapOf("Content-Type" to "text/plain; charset=utf-8"),
            body = text.toByteArray(Charsets.UTF_8),
        )

        /** 通知请求的回应：202 无响应体（JSON-RPC 约定服务端不应答通知） */
        fun accepted() = HttpResponse(status = 202)

        fun preflight() = HttpResponse(status = 204)

        private fun reason(status: Int): String = when (status) {
            200 -> "OK"
            202 -> "Accepted"
            204 -> "No Content"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            413 -> "Payload Too Large"
            else -> ""
        }
    }
}
