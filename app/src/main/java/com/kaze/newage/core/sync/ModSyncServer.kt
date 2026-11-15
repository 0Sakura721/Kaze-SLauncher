package com.kaze.newage.core.sync

import com.kaze.newage.data.model.ServerInstance
import com.kaze.newage.util.LanIp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * 客户端模组同步服务：手机上的一个小型 HTTP 文件服务，玩家在同一局域网里直接拉取模组。
 *
 * 为什么不是"服务端推进客户端"：Minecraft 原版的网络协议没有任何服务端→客户端的文件下发能力，
 * 真正的自动同步要么装 AutoModpack / PackSync 这类**双端都要装**的模组，要么走整合包。
 * 本应用能单方面做到的最实用形态就是：
 *  1. 服务端把"当前启用的模组 + sha1 清单"暴露成 HTTP；
 *  2. 玩家用生成的脚本（或浏览器直接下载 zip / .mrpack）同步；
 *  3. 服务端换模组后，客户端重跑脚本，按 sha1 只下载变化的文件。
 *
 * 安全边界是**访问令牌**（默认必填，可清空表示内网裸奔）：
 * 绑在局域网上的服务如果没有令牌，同网段任何人都能翻你的模组包与内网地址。
 * 令牌只保护文件类端点；`/` 与 `/health` 公开，方便玩家确认"地址对不对"。
 *
 * 生命周期跟随应用进程：实例页面关掉不影响正在进行的下载（服务在进程内跑），
 * 但进程被杀就没了 —— 与 MCP 服务同一个取舍。
 */
class ModSyncServer(
    private val instance: ServerInstance,
    private val cacheDir: File,
    /**
     * 远程管理回调。为 null 时 `/api/…` 一律 404 —— 只做模组同步、不开放远程控制。
     *
     * 刻意不把 ServerManager 直接塞进来：这个类只该知道"怎么把一个动作变成响应"，
     * 具体是启停服务端还是发命令，由 AppViewModel 在构造时用闭包决定（也便于单测）。
     */
    private val remote: RemoteBridge? = null,
) {

    /** 远程管理的最小接口（状态 / 日志 / 命令 / 启停） */
    interface RemoteBridge {
        fun statusJson(): String
        fun logs(maxLines: Int): String
        fun command(cmd: String): String
        fun start(): String
        fun stop(): String
    }

    data class Snapshot(
        val running: Boolean = false,
        val port: Int = 0,
        /** 当前令牌；空串 = 不校验 */
        val token: String = "",
        /** 局域网可访问的基地址（`http://192.168.x.x:端口`） */
        val urls: List<String> = emptyList(),
        val requests: Long = 0,
        val lastRequest: String = "",
        /** 最近一次启动/构建失败的原因 */
        val error: String? = null,
    ) {
        val baseUrl: String? get() = urls.firstOrNull()

        /** 给玩家抄的完整地址（带令牌，直接粘进浏览器/脚本） */
        val shareUrl: String?
            get() = baseUrl?.let { if (token.isBlank()) "$it/mods.txt" else "$it/mods.txt?token=$token" }
    }

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var pool: ExecutorService? = null

    @Volatile
    private var acceptThread: Thread? = null

    @Volatile
    private var token: String = ""

    private var requestCount = 0L

    /** 同一时刻只允许一个请求构建客户端包（几百 MB 的 zip 不能并发生成） */
    private val packLock = Any()

    val isRunning: Boolean get() = serverSocket != null

    /**
     * 启动服务。
     *
     * 端口策略：先试 [preferredPort]，被占用就从这里往后顺延 20 个，再不行交给系统分配
     * （0 = 临时端口）。绝不"悄悄换到别的端口却不告诉用户"——实际端口会写回 [Snapshot.port]，
     * 界面展示的就是真实可连的地址。
     */
    @Synchronized
    fun start(preferredPort: Int, token: String, bindAddress: String = "0.0.0.0"): Boolean {
        if (isRunning) stop()
        this.token = token
        requestCount = 0
        val socket = try {
            bindSocket(preferredPort, bindAddress)
        } catch (e: Exception) {
            _state.value = Snapshot(error = "端口绑定失败：${e.message ?: e.javaClass.simpleName}")
            return false
        }
        if (socket == null) {
            _state.value = Snapshot(error = "没有可用端口（$preferredPort 起 21 个都被占用）")
            return false
        }
        serverSocket = socket
        val executor = Executors.newFixedThreadPool(4) { r ->
            Thread(r, "kaze-sync-worker").apply { isDaemon = true }
        }
        pool = executor
        _state.value = Snapshot(
            running = true,
            port = socket.localPort,
            token = token,
            urls = urlsFor(socket.localPort),
        )
        acceptThread = thread(name = "kaze-sync-accept", isDaemon = true) {
            acceptLoop(socket, executor)
        }
        return true
    }

    /** 停止服务（正在传的文件会中断，客户端重跑脚本即可继续） */
    @Synchronized
    fun stop() {
        val socket = serverSocket
        serverSocket = null
        runCatching { socket?.close() }
        runCatching { pool?.shutdownNow() }
        pool = null
        acceptThread = null
        _state.value = _state.value.copy(running = false)
    }

    /** 模组集合变了：清掉清单缓存（下次请求重新算 sha1） */
    fun invalidateManifest() = ClientPack.invalidate(instance.id)

    // ── 网络 ──

    private fun bindSocket(preferred: Int, bindAddress: String): ServerSocket? {
        val addresses = try {
            InetAddress.getByName(bindAddress)
        } catch (_: Exception) {
            InetAddress.getByName("0.0.0.0")
        }
        val candidates = buildList {
            if (preferred in 1024..65535) {
                for (p in preferred..minOf(65535, preferred + 20)) add(p)
            }
            add(0)
        }
        var lastError: IOException? = null
        for (port in candidates) {
            try {
                return ServerSocket(port, 16, addresses)
            } catch (e: IOException) {
                lastError = e
            }
        }
        if (lastError != null) throw lastError
        return null
    }

    private fun urlsFor(port: Int): List<String> {
        val ips = LanIp.candidates().map { it.address }.take(3)
        val all = if (ips.isEmpty()) listOf("127.0.0.1") else ips + "127.0.0.1"
        return all.distinct().map { "http://$it:$port" }
    }

    private fun acceptLoop(socket: ServerSocket, executor: ExecutorService) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: IOException) {
                return
            }
            runCatching { executor.execute { handle(client) } }
                .onFailure { runCatching { client.close() } }
        }
    }

    private fun handle(client: Socket) {
        client.use { s ->
            runCatching { s.soTimeout = 15_000 }
            val input = BufferedInputStream(s.getInputStream())
            val head = readHead(input) ?: return
            val lines = head.split("\r\n")
            val requestLine = lines.firstOrNull().orEmpty()
            val parts = requestLine.split(' ')
            if (parts.size < 2) {
                respondText(s, 400, "错误的请求")
                return
            }
            val method = parts[0].uppercase(Locale.US)
            if (method != "GET" && method != "HEAD") {
                respondText(s, 405, "只支持 GET（收到 $method）")
                return
            }
            val headOnly = method == "HEAD"
            val target = parts[1]
            val path = target.substringBefore('?')
            val query = parseQuery(target.substringAfter('?', ""))
            val headers = lines.drop(1)

            try {
                route(s, path, query, headers, headOnly)
            } catch (e: Exception) {
                runCatching { respondText(s, 500, "服务端错误：${e.message ?: "未知"}") }
            }
        }
    }

    private fun serveApi(s: Socket, path: String, query: Map<String, String>, headOnly: Boolean) {
        val bridge = remote
        if (bridge == null) {
            record("GET $path 404")
            respondText(s, 404, "这个实例没有开启远程管理")
            return
        }
        try {
            when (path) {
                "/api/status" -> {
                    record("GET /api/status 200")
                    respondText(s, 200, bridge.statusJson(), headOnly, "application/json; charset=utf-8")
                }
                "/api/logs" -> {
                    val n = query["n"]?.toIntOrNull()?.coerceIn(1, 2000) ?: 200
                    record("GET /api/logs 200")
                    respondText(s, 200, bridge.logs(n), headOnly)
                }
                "/api/command" -> {
                    val cmd = query["cmd"].orEmpty()
                    if (cmd.isBlank()) {
                        respondText(s, 400, "缺少 cmd 参数，例如 /api/command?cmd=list")
                        return
                    }
                    record("GET /api/command 200")
                    respondText(s, 200, bridge.command(cmd), headOnly)
                }
                "/api/start" -> {
                    record("GET /api/start 200")
                    respondText(s, 200, bridge.start(), headOnly)
                }
                "/api/stop" -> {
                    record("GET /api/stop 200")
                    respondText(s, 200, bridge.stop(), headOnly)
                }
                else -> {
                    record("GET $path 404")
                    respondText(s, 404, "未知接口：$path")
                }
            }
        } catch (e: Exception) {
            runCatching { respondText(s, 500, "执行失败：${e.message}") }
        }
    }
    private fun route(
        s: Socket,
        path: String,
        query: Map<String, String>,
        headers: List<String>,
        headOnly: Boolean,
    ) {
        // 公开端点：给玩家一个"地址通了"的确认页
        when (path) {
            "/", "/index.html" -> {
                record("GET / 200")
                respondHtml(s, indexHtml(), headOnly)
                return
            }
            "/health" -> {
                record("GET /health 200")
                respondText(s, 200, "ok", headOnly)
                return
            }
        }

        if (!authorized(query, headers)) {
            record("$path 401")
            respondText(
                s, 401,
                "需要访问令牌。\n\n" +
                    "把服务端「客户端同步」页面上的完整地址（带 ?token=...）发给玩家，\n" +
                    "或在脚本里传令牌。",
                headOnly,
            )
            return
        }

        // ── 远程管理 API（受同一个令牌保护）──
        if (path == "/api" || path.startsWith("/api/")) {
            serveApi(s, path, query, headOnly)
            return
        }

        when {
            path == "/manifest.json" -> {
                record("GET /manifest.json 200")
                respondText(s, 200, ClientPack.manifestJsonPretty(instance), headOnly, "application/json; charset=utf-8")
            }
            path == "/mods.txt" -> {
                record("GET /mods.txt 200")
                respondText(s, 200, ClientPack.modsTsv(instance), headOnly, "text/plain; charset=utf-8")
            }
            path == "/mod" -> {
                val name = query["f"].orEmpty()
                serveMod(s, name, headOnly)
            }
            path.startsWith("/mods/") -> {
                serveMod(s, percentDecode(path.removePrefix("/mods/")), headOnly)
            }
            path == "/client-pack.zip" -> {
                serveClientPack(s, headOnly, mrpack = false)
            }
            path == "/client-pack.mrpack" -> {
                serveClientPack(s, headOnly, mrpack = true)
            }
            path == "/sync.sh" -> {
                record("GET /sync.sh 200")
                respondText(s, 200, ClientPack.syncScriptSh(), headOnly, "text/x-shellscript; charset=utf-8")
            }
            path == "/sync.ps1" -> {
                record("GET /sync.ps1 200")
                respondText(s, 200, ClientPack.syncScriptPs1(), headOnly, "text/plain; charset=utf-8")
            }
            else -> {
                record("GET $path 404")
                respondText(s, 404, "没有这个地址：$path")
            }
        }
    }

    /** 只允许发清单里列出的文件名 —— 这样路径穿越（`../`）根本没有落点 */
    private fun serveMod(s: Socket, name: String, headOnly: Boolean) {
        if (name.isBlank()) {
            record("GET /mod 400")
            respondText(s, 400, "缺少文件名参数 f")
            return
        }
        val manifest = ClientPack.manifest(instance)
        val entry = manifest.mods.firstOrNull { it.fileName == name }
        if (entry == null) {
            record("GET /mod $name 404")
            respondText(s, 404, "清单里没有这个模组：$name（服务端可能刚更新，重跑一次同步）")
            return
        }
        val file = File(File(instance.dir, "mods"), entry.fileName)
        if (!file.isFile) {
            record("GET /mod $name 404")
            respondText(s, 404, "文件已经不在服务器上：$name")
            return
        }
        record("GET /mod $name 200")
        respondFile(s, file, "application/java-archive", entry.fileName, headOnly)
    }

    private fun serveClientPack(s: Socket, headOnly: Boolean, mrpack: Boolean) {
        val manifest = ClientPack.manifest(instance)
        val dir = File(cacheDir, "client-packs").apply { mkdirs() }
        val ext = if (mrpack) "mrpack" else "zip"
        val out = File(dir, "${instance.id}-${manifest.fingerprint}.$ext")
        val error = synchronized(packLock) {
            if (out.isFile && out.length() > 0) return@synchronized null
            try {
                val tmp = File(dir, out.name + ".tmp")
                tmp.outputStream().use { stream ->
                    if (mrpack) ClientPack.writeMrpack(instance, stream)
                    else ClientPack.writeZip(instance, stream)
                }
                if (out.exists()) out.delete()
                if (!tmp.renameTo(out)) {
                    tmp.copyTo(out, overwrite = true)
                    tmp.delete()
                }
                // 只保留最近 3 份：每换一次模组就多一个几百 MB 的包，不清理会吃满存储
                dir.listFiles { f ->
                    f.name.startsWith(instance.id) &&
                        (f.name.endsWith(".zip") || f.name.endsWith(".mrpack"))
                }
                    ?.sortedByDescending { it.lastModified() }
                    ?.drop(3)
                    ?.forEach { runCatching { it.delete() } }
                null
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            }
        }
        if (error != null) {
            record("GET /client-pack.$ext 500")
            respondText(s, 500, "生成客户端包失败：$error")
            return
        }
        record("GET /client-pack.$ext 200")
        respondFile(
            s, out,
            if (mrpack) "application/x-modrinth-modpack+zip" else "application/zip",
            "${instance.name}-client-pack.$ext", headOnly,
        )
    }

    // ── 请求/响应工具 ──

    private fun authorized(query: Map<String, String>, headers: List<String>): Boolean {
        val expected = token
        if (expected.isBlank()) return true
        val fromQuery = query["token"]
        if (fromQuery != null && constantTimeEquals(fromQuery, expected)) return true
        val fromHeader = headers.firstOrNull { it.startsWith("X-Kaze-Token:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()
        return fromHeader != null && constantTimeEquals(fromHeader, expected)
    }

    /** 令牌比较走常量时间：普通字符串比较的提前返回会让攻击者按时间猜字符 */
    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    private fun record(line: String) {
        requestCount++
        _state.value = _state.value.copy(
            requests = requestCount,
            lastRequest = line,
        )
    }

    private fun readHead(input: BufferedInputStream): String? {
        val buf = ByteArray(4096)
        val sb = StringBuilder()
        var total = 0
        while (total < 16_384) {
            val n = input.read(buf)
            if (n <= 0) return null
            sb.append(String(buf, 0, n, Charsets.ISO_8859_1))
            total += n
            val idx = sb.indexOf("\r\n\r\n")
            if (idx >= 0) return sb.substring(0, idx)
        }
        return null
    }

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return raw.split('&').mapNotNull { pair ->
            if (pair.isBlank()) return@mapNotNull null
            val k = percentDecode(pair.substringBefore('='))
            val v = percentDecode(pair.substringAfter('=', ""))
            k to v
        }.toMap()
    }

    private fun percentDecode(s: String): String = runCatching {
        java.net.URLDecoder.decode(s, "UTF-8")
    }.getOrDefault(s)

    private fun respondText(
        s: Socket,
        status: Int,
        body: String,
        headOnly: Boolean = false,
        contentType: String = "text/plain; charset=utf-8",
    ) {
        respondBytes(s, status, contentType, body.toByteArray(Charsets.UTF_8), headOnly)
    }

    private fun respondHtml(s: Socket, html: String, headOnly: Boolean) {
        respondBytes(s, 200, "text/html; charset=utf-8", html.toByteArray(Charsets.UTF_8), headOnly)
    }

    private fun respondBytes(
        s: Socket,
        status: Int,
        contentType: String,
        body: ByteArray,
        headOnly: Boolean,
    ) {
        val head = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Connection: close\r\n")
            append("Cache-Control: no-store\r\n")
            append("Accept-Ranges: none\r\n")
            append("\r\n")
        }
        val out = BufferedOutputStream(s.getOutputStream())
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        if (!headOnly) out.write(body)
        out.flush()
    }

    private fun respondFile(s: Socket, file: File, contentType: String, downloadName: String, headOnly: Boolean) {
        val head = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(file.length()).append("\r\n")
            append("Content-Disposition: attachment; filename=\"").append(sanitizeHeader(downloadName)).append("\"\r\n")
            append("Connection: close\r\n")
            append("Cache-Control: no-store\r\n")
            append("Accept-Ranges: none\r\n")
            append("\r\n")
        }
        val out = BufferedOutputStream(s.getOutputStream())
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        if (!headOnly) {
            file.inputStream().use { ins -> ins.copyTo(out, 128 * 1024) }
        }
        out.flush()
    }

    /** 响应头里不能出现换行/非 ASCII：中文文件名会被写成 `?`，但连接不会被破坏 */
    private fun sanitizeHeader(name: String): String =
        name.map { if (it.code in 32..126 && it != '"') it else '_' }.joinToString("")

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        500 -> "Internal Server Error"
        else -> "OK"
    }

    // ── 首页 ──

    private fun indexHtml(): String {
        val manifest = runCatching { ClientPack.manifest(instance) }.getOrNull()
        val count = manifest?.mods?.size ?: 0
        val size = manifest?.totalBytes?.let { "${it / 1024 / 1024} MB" } ?: "—"
        return """
            <!DOCTYPE html>
            <html lang="zh-CN"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>${htmlEscape(instance.name)} · 模组同步</title>
            <style>
            body{font-family:system-ui,sans-serif;margin:0;padding:24px;background:#12121a;color:#e6e6ee}
            h1{font-size:20px;margin:0 0 4px} .sub{color:#9a9ab0;font-size:13px;margin-bottom:20px}
            .card{background:#1c1c28;border-radius:16px;padding:18px;margin-bottom:14px}
            code{background:#2a2a3a;padding:2px 6px;border-radius:6px;font-size:12px;word-break:break-all}
            a{color:#9db4ff} ul{padding-left:18px;margin:8px 0} li{margin:6px 0;font-size:14px}
            </style></head><body>
            <h1>${htmlEscape(instance.name)}</h1>
            <div class="sub">MC ${htmlEscape(instance.mcVersion.ifBlank { "未知" })} ·
              ${htmlEscape(instance.coreType.displayName)} · $count 个模组 · $size</div>
            <div class="card">
              <b>这个地址是通的</b>
              <p>把带令牌的完整地址发给玩家（在服务端的「客户端同步」卡片里复制）：</p>
              <code>http://你的服务端IP:端口/mods.txt?token=令牌</code>
            </div>
            <div class="card">
              <b>怎么同步</b>
              <ul>
                <li>整包下载：<code>/client-pack.zip</code>（解压后把 mods/ 拷进客户端）</li>
                <li>Modrinth 包：<code>/client-pack.mrpack</code>（直接导入 Modrinth App / Prism / HMCL）</li>
                <li>命令行：<code>/sync.sh</code> 或 <code>/sync.ps1</code>（需要令牌）</li>
                <li>清单：<code>/mods.txt</code>（sha1 + 文件名，脚本用它比对）</li>
              </ul>
            </div>
            <div class="sub">由 Kaze SLauncher 提供 · 仅在局域网/内网可信环境中开放</div>
            </body></html>
        """.trimIndent()
    }

    private fun htmlEscape(s: String): String = buildString {
        s.forEach { c ->
            when (c) {
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '&' -> append("&amp;")
                '"' -> append("&quot;")
                else -> append(c)
            }
        }
    }
}
