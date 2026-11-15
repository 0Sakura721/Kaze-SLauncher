package com.kaze.newage.core.net

import android.content.Context
import android.content.Intent
import com.kaze.newage.core.env.ProotEnvironment
import com.kaze.newage.util.Downloader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.net.Inet4Address
import java.util.Collections
import kotlin.concurrent.thread

/**
 * 公网访问与远程管理的统一入口：Tailscale 检测 + frpc 运行器 + 自定义 frp 配置。
 *
 * 三者解决的是同一个问题（"朋友怎么连进来"）的三条路：
 *  - **Tailscale**：零配置组网，双方装客户端即可，IP 是固定的 `100.x`；
 *  - **樱花映射**：国内最常用的免费/低价穿透，API 能拿到现成 frpc 配置；
 *  - **自建 frp**：有自己的服务器时最稳，填地址/令牌/端口即可。
 *
 * frpc 是静态 Go 二进制，放在 rootfs 的 `/root/kaze-net/` 里由 proot 执行 ——
 * 这与 Java 走的是同一条"私有目录 + proot"路径，不需要 root 或 Termux。
 */
object NetworkAccess {

    /** Tailscale 的 Android 包名 */
    const val TAILSCALE_PACKAGE = "com.tailscale.ipn"

    data class Tailscale(
        val installed: Boolean,
        val interfaces: List<String>,
        val ips: List<String>,
    ) {
        val connected: Boolean get() = ips.isNotEmpty() || interfaces.isNotEmpty()
        val primaryIp: String? get() = ips.firstOrNull()
    }

    /**
     * 检测 Tailscale 是否安装/在线。
     *
     * Android 上普通应用读不到 VPN 的详细配置，但**接口列表**通常能看到
     * `tailscale0` 或 `tun0` + `100.64.0.0/10` 的地址；读不到也不代表没连上，
     * 所以界面按"已安装 / 检测到地址"两档展示，而不是断言"未连接"。
     */
    fun tailscale(context: Context): Tailscale {
        val installed = runCatching {
            context.packageManager.getPackageInfo(TAILSCALE_PACKAGE, 0)
            true
        }.getOrDefault(false)
        val names = mutableListOf<String>()
        val ips = mutableListOf<String>()
        runCatching {
            Collections.list(java.net.NetworkInterface.getNetworkInterfaces()).forEach { nif ->
                val name = nif.name.orEmpty()
                val isTailscaleName = name.startsWith("tailscale") ||
                    (name.startsWith("tun") && nif.displayName?.contains("tailscale", true) == true)
                val hasCgnat = Collections.list(nif.inetAddresses)
                    .filterIsInstance<Inet4Address>()
                    .any { it.hostAddress?.startsWith("100.") == true }
                if (isTailscaleName || hasCgnat) {
                    if (name.isNotBlank()) names += name
                    Collections.list(nif.inetAddresses)
                        .filterIsInstance<Inet4Address>()
                        .mapNotNull { it.hostAddress }
                        .filter { ip -> ip.startsWith("100.") || ip.startsWith("fd7a:") }
                        .forEach { ips += it }
                }
            }
        }
        return Tailscale(installed, names.distinct(), ips.distinct())
    }

    /** 打开 Tailscale App（未安装返回 false，界面据此引导去安装） */
    fun openTailscale(context: Context): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(TAILSCALE_PACKAGE)
            ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    const val TAILSCALE_DOWNLOAD_URL = "https://tailscale.com/download/android"

    // ── 自定义 frp ──

    data class FrpConfig(
        val serverAddr: String,
        val serverPort: Int = 7000,
        val token: String = "",
        val proxyName: String = "kaze-mc",
        val localIp: String = "127.0.0.1",
        val localPort: Int = 25565,
        val remotePort: Int = 25565,
    ) {
        /** frpc 0.52+ 的 TOML 配置（樱花分发的 0.51-sakura 同样接受） */
        fun toToml(): String = buildString {
            appendLine("# 由 Kaze SLauncher 生成（自建 frp）")
            appendLine("serverAddr = \"$serverAddr\"")
            appendLine("serverPort = $serverPort")
            if (token.isNotBlank()) appendLine("auth.token = \"$token\"")
            appendLine()
            appendLine("[[proxies]]")
            appendLine("name = \"$proxyName\"")
            appendLine("type = \"tcp\"")
            appendLine("localIP = \"$localIp\"")
            appendLine("localPort = $localPort")
            appendLine("remotePort = $remotePort")
        }
    }

    // ── frpc 运行器 ──

    /**
     * frpc 进程管理（单实例：同一时间只跑一条隧道组合）。
     *
     * 配置由调用方给（樱花 API 生成 / 自建 frp 拼装），这里只负责：
     * 落盘配置 → 确保二进制可执行 → 经 proot 启动 → 收集日志 → 停止。
     */
    class FrpcRunner(private val env: ProotEnvironment) {

        data class State(
            val running: Boolean = false,
            val provider: String = "",
            val configPath: String = "",
            val log: List<String> = emptyList(),
            val error: String? = null,
        )

        private val _state = MutableStateFlow(State())
        val state: StateFlow<State> = _state.asStateFlow()

        @Volatile
        private var process: Process? = null

        val isRunning: Boolean get() = process != null

        private val workDir: File get() = File(env.rootfsDir, "root/kaze-net")
        private val binary: File get() = File(workDir, "frpc")
        private val configFile: File get() = File(workDir, "frpc.toml")

        /** 二进制是否已就绪 */
        fun hasBinary(): Boolean = binary.isFile && binary.length() > 1024

        /**
         * 确保 frpc 存在：不存在时按 [url] 下载并校验 md5。
         * 返回是否可用。阻塞式（约 20 MB），调用方放 IO。
         */
        fun ensureBinary(url: String, md5: String, onProgress: (Float, String) -> Unit): Boolean {
            if (hasBinary()) return true
            workDir.mkdirs()
            val client = SakuraFrpcClient(version = "frpc", url = url, md5 = md5, size = 0)
            return runCatching {
                SakuraFrpApi.downloadFrpc(client, binary, onProgress)
                true
            }.getOrElse {
                _state.value = _state.value.copy(error = "下载 frpc 失败：${it.message}")
                false
            }
        }

        /** 用给定配置启动 frpc；返回是否启动成功 */
        fun start(configText: String, provider: String): Boolean {
            stop()
            if (!env.isReady) {
                _state.value = State(error = "Linux 环境还没准备好（先部署环境）")
                return false
            }
            if (!hasBinary()) {
                _state.value = State(error = "frpc 还没下载")
                return false
            }
            workDir.mkdirs()
            configFile.writeText(configText)
            binary.setExecutable(true)
            val proc = env.launch(
                listOf("/root/kaze-net/frpc", "-c", "/root/kaze-net/frpc.toml"),
                workDir = null,
                env = emptyMap(),
            ) ?: run {
                _state.value = State(error = "frpc 启动失败（proot 没起来）")
                return false
            }
            process = proc
            _state.value = State(
                running = true,
                provider = provider,
                configPath = configFile.absolutePath,
                log = listOf("> 正在连接（$provider）…"),
            )
            thread(name = "kaze-frpc-log", isDaemon = true) {
                runCatching {
                    proc.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            _state.value = _state.value.copy(
                                log = (_state.value.log + line).takeLast(200),
                            )
                        }
                    }
                }
                process = null
                _state.value = _state.value.copy(
                    running = false,
                    log = (_state.value.log + "> frpc 已退出").takeLast(200),
                )
            }
            return true
        }

        fun stop() {
            val proc = process
            process = null
            runCatching { proc?.destroy() }
            if (_state.value.running) {
                _state.value = _state.value.copy(running = false, log = (_state.value.log + "> 已停止").takeLast(200))
            }
        }
    }
}
