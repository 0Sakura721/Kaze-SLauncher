package com.kaze.newage.ui

import android.content.Context
import com.kaze.newage.core.env.ProotEnvironment
import com.kaze.newage.core.net.NetworkAccess
import com.kaze.newage.core.net.SakuraFrpApi
import com.kaze.newage.core.net.SakuraTunnel
import com.kaze.newage.core.net.SakuraUser
import com.kaze.newage.core.server.ServerProperties
import com.kaze.newage.data.prefs.SettingsPrefs
import com.kaze.newage.data.model.ServerInstance
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 公网访问控制器：Tailscale 状态、樱花映射登录/隧道、自建 frp、frpc 进程。
 *
 * 设计取舍：**只跑一个 frpc 实例**。同一条公网出口同时跑多条隧道没有意义，
 * 反而会出现"两个 frpc 抢同一个本地端口/同一条连接"的怪问题。切换线路就是先停再启。
 */
class NetworkController(
    private val context: Context,
    private val env: ProotEnvironment,
    private val prefs: SettingsPrefs,
    private val scope: CoroutineScope,
) {

    private val _tailscale = MutableStateFlow(NetworkAccess.Tailscale(false, emptyList(), emptyList()))
    val tailscale: StateFlow<NetworkAccess.Tailscale> = _tailscale.asStateFlow()

    data class SakuraState(
        val token: String = "",
        val user: SakuraUser? = null,
        val loading: Boolean = false,
        val tunnels: List<SakuraTunnel> = emptyList(),
        val selectedId: Long? = null,
        val error: String? = null,
        val message: String = "",
        /** 正在用本地保存的访问密钥自动恢复登录 */
        val restoring: Boolean = false,
    )

    private val _sakura = MutableStateFlow(
        // 密钥持久化在 SharedPreferences（加密）里：这里先把"已保存登录"的状态摆出来，
        // 网络回来后再由 [ensureLogin] 自动刷新隧道列表 —— 用户不用每次重新登录。
        prefs.sakuraToken.value.takeIf { it.isNotBlank() }?.let { saved ->
            SakuraState(
                token = saved,
                user = SakuraUser(prefs.sakuraUserName.value.ifBlank { "已保存登录" }, "", JsonObject(emptyMap())),
                selectedId = prefs.sakuraTunnelId.value.takeIf { it > 0 },
                restoring = true,
                message = "正在恢复登录…",
            )
        } ?: SakuraState(),
    )
    val sakura: StateFlow<SakuraState> = _sakura.asStateFlow()

    /** frpc 进程状态（日志也在这里） */
    val frpc: NetworkAccess.FrpcRunner = NetworkAccess.FrpcRunner(env)

    /** 自建 frp 的操作反馈 */
    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message.asStateFlow()

    /** 下载 frpc 的进度（0..1） */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    /** frps 连通性探测结果（自建 frp 卡片的「检测/刷新」用） */
    data class FrpProbe(
        val checking: Boolean = false,
        val reachable: Boolean? = null,
        val latencyMs: Long? = null,
        val checkedAt: Long = 0L,
        val detail: String = "",
    )

    private val _frpProbe = MutableStateFlow(FrpProbe())
    val frpProbe: StateFlow<FrpProbe> = _frpProbe.asStateFlow()

    fun refreshTailscale() {
        _tailscale.value = NetworkAccess.tailscale(context)
    }

    /** 刷新樱花：强制重新拉账号信息与隧道列表（保留已选隧道） */
    fun refreshSakura() {
        val token = _sakura.value.token.ifBlank { prefs.sakuraToken.value }
        if (token.isBlank()) {
            _sakura.value = _sakura.value.copy(error = "还没有登录（缺少访问密钥）")
            return
        }
        loginSakura(token, silent = true)
    }

    /**
     * 检测 frps 是否可达（TCP 连接 + 计时）。
     *
     * 只做一次 connect，不发送任何数据 —— frp 的端口是纯 TCP 转发，
     * 能建立连接就说明地址/端口/网络路径是通的；失败则给出具体原因。
     */
    fun probeFrp(server: String, port: Int) {
        val host = server.trim()
        if (host.isBlank()) {
            _frpProbe.value = FrpProbe(detail = "先填 frps 地址")
            return
        }
        _frpProbe.value = FrpProbe(checking = true)
        scope.launch(Dispatchers.IO) {
            val started = System.currentTimeMillis()
            val result = runCatching {
                java.net.Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress(host, port), 4000)
                }
            }
            val cost = System.currentTimeMillis() - started
            _frpProbe.value = if (result.isSuccess) {
                FrpProbe(reachable = true, latencyMs = cost, checkedAt = System.currentTimeMillis(), detail = "frps 可达")
            } else {
                FrpProbe(
                    reachable = false,
                    latencyMs = cost,
                    checkedAt = System.currentTimeMillis(),
                    detail = result.exceptionOrNull()?.message ?: "连接失败",
                )
            }
        }
    }

    /**
     * 一键刷新：Tailscale 检测 + 樱花隧道 + frps 连通性，各自独立，互不阻塞。
     */
    fun refreshAll(serverAddr: String = prefs.frpServer.value, serverPort: Int = prefs.frpServerPort.value) {
        refreshTailscale()
        if (_sakura.value.token.isNotBlank() || prefs.sakuraToken.value.isNotBlank()) refreshSakura()
        if (serverAddr.isNotBlank()) probeFrp(serverAddr, serverPort)
        _message.value = "已刷新（Tailscale / 樱花 / frps）"
    }

    fun openTailscale(): Boolean = NetworkAccess.openTailscale(context)

    // ── 樱花映射 ──

    /**
     * "登录"= 用访问密钥换账号信息 + 隧道列表。
     *
     * 为什么不是用户名密码：樱花开放 API 只支持 Bearer 访问密钥（官网「账户 → 访问密钥」生成），
     * 密码登录带 2FA/风控，官方没有对第三方开放。把密钥加密存在本机，等价于"已登录"。
     */
    fun loginSakura(token: String, silent: Boolean = false) {
        val t = token.trim().ifBlank { prefs.sakuraToken.value }
        if (t.isBlank()) {
            _sakura.value = _sakura.value.copy(error = "先填访问密钥（樱花面板 → 账户 → 访问密钥）")
            return
        }
        _sakura.value = _sakura.value.copy(loading = true, error = null, message = if (silent) "正在恢复登录…" else "登录中…")
        scope.launch(Dispatchers.IO) {
            try {
                val user = SakuraFrpApi.userInfo(t)
                val tunnels = SakuraFrpApi.tunnels(t)
                // 先把会话元数据落盘，再更新界面：中途被杀进程也不会丢"保持登录"
                val remembered = prefs.sakuraTunnelId.value
                val selected = tunnels.firstOrNull { it.id == remembered }?.id ?: tunnels.firstOrNull()?.id
                prefs.setSakuraToken(t)
                prefs.setSakuraSession(user.name, selected)
                _sakura.value = SakuraState(
                    token = t,
                    user = user,
                    loading = false,
                    tunnels = tunnels,
                    selectedId = selected,
                    message = if (silent) {
                        "已恢复登录：${user.name}（${tunnels.size} 条隧道）"
                    } else {
                        "已登录：${user.name}（${tunnels.size} 条隧道）"
                    },
                )
            } catch (e: Exception) {
                val message = e.message ?: "登录失败"
                // 密钥被撤销/过期：清掉本地会话，避免每次进页面都拿一个坏令牌重试
                val authFailed = message.contains("访问密钥") || message.contains("401") || message.contains("403")
                if (authFailed) {
                    prefs.clearSakuraSession()
                    _sakura.value = SakuraState(
                        error = "登录已失效（$message），请重新填写访问密钥",
                    )
                } else {
                    _sakura.value = _sakura.value.copy(
                        loading = false,
                        restoring = false,
                        message = "",
                        error = message,
                    )
                }
            }
        }
    }

    /**
     * 进页面时调用：已经存着访问密钥就直接恢复登录（不再让用户输一遍）。
     *
     * 只在"有密钥但还没隧道列表"时触发；网络失败保留密钥，用户可点重试。
     */
    fun ensureLogin() {
        val state = _sakura.value
        if (state.token.isBlank() || state.loading) return
        if (state.tunnels.isNotEmpty()) return
        loginSakura(state.token, silent = true)
    }

    fun logoutSakura() {
        prefs.clearSakuraSession()
        _sakura.value = SakuraState()
        frpc.stop()
    }

    fun selectTunnel(id: Long) {
        _sakura.value = _sakura.value.copy(selectedId = id, error = null)
        // 记住选择：重启后还是这条隧道
        prefs.setSakuraSession(_sakura.value.user?.name.orEmpty(), id)
    }

    fun startSakura(instance: ServerInstance) {
        val state = _sakura.value
        val id = state.selectedId
        if (state.token.isBlank() || id == null) {
            _sakura.value = state.copy(error = "先登录并选择一条隧道")
            return
        }
        if (_busy.value) return
        _busy.value = true
        _progress.value = 0f
        _sakura.value = state.copy(loading = true, error = null, message = "准备 frpc…")
        scope.launch(Dispatchers.IO) {
            try {
                ensureFrpc()
                _sakura.value = _sakura.value.copy(message = "生成隧道配置…")
                val config = SakuraFrpApi.tunnelConfig(state.token, id.toString())
                val ok = frpc.start(config, "樱花映射 #$id")
                _sakura.value = _sakura.value.copy(
                    loading = false,
                    message = if (ok) "已启动隧道 #$id（下方看日志）" else "",
                    error = if (ok) null else "frpc 启动失败（看下方日志）",
                )
            } catch (e: Exception) {
                _sakura.value = _sakura.value.copy(
                    loading = false,
                    message = "",
                    error = e.message ?: "启动失败",
                )
            } finally {
                _busy.value = false
            }
        }
    }

    // ── 自建 frp ──

    fun startCustomFrp(
        instance: ServerInstance,
        serverAddr: String,
        serverPort: Int,
        token: String,
        remotePort: Int,
    ) {
        if (serverAddr.isBlank()) {
            _message.value = "先填 frps 地址"
            return
        }
        if (_busy.value) return
        _busy.value = true
        _progress.value = 0f
        _message.value = "准备 frpc…"
        scope.launch(Dispatchers.IO) {
            try {
                ensureFrpc()
                val localPort = localPortOf(instance)
                val cfg = NetworkAccess.FrpConfig(
                    serverAddr = serverAddr.trim(),
                    serverPort = serverPort,
                    token = token,
                    proxyName = "kaze-" + instance.id.take(6),
                    localIp = "127.0.0.1",
                    localPort = localPort,
                    remotePort = remotePort,
                )
                prefs.setFrp(serverAddr, serverPort, token, remotePort)
                val ok = frpc.start(cfg.toToml(), "自建 frp $serverAddr:$serverPort")
                _message.value = if (ok) {
                    "已启动：服务器 ${serverAddr}:${serverPort} → 本地 ${localPort}（远程端口 $remotePort）"
                } else {
                    "frpc 启动失败（看下方日志）"
                }
            } catch (e: Exception) {
                _message.value = e.message ?: "启动失败"
            } finally {
                _busy.value = false
            }
        }
    }

    fun stopTunnel() {
        frpc.stop()
        _message.value = "已停止"
        _sakura.value = _sakura.value.copy(message = "已停止")
    }

    /** 本地服务端口：Java 读 server.properties，基岩版默认 19132 */
    fun localPortOf(instance: ServerInstance): Int =
        ServerProperties.load(instance.dir)["server-port"]?.toIntOrNull()
            ?: if (instance.bedrock) 19132 else 25565

    private fun ensureFrpc() {
        if (frpc.hasBinary()) return
        val client = SakuraFrpApi.frpcClient()
        val ok = frpc.ensureBinary(client.url, client.md5) { p, m ->
            _progress.value = p.coerceIn(0f, 1f)
            _message.value = m
        }
        if (!ok) throw RuntimeException("frpc 下载失败（检查网络）")
    }
}
