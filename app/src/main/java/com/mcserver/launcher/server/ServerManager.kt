package com.mcserver.launcher.server

import android.content.Context
import android.content.Intent
import android.os.Build
import com.mcserver.launcher.McApplication
import com.mcserver.launcher.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicBoolean

class ServerManager private constructor() {

    companion object {
        val instance: ServerManager by lazy { ServerManager() }
    }

    private val context: Context get() = McApplication.instance
    private val jreManager = JreManager(context)
    private val termuxManager = TermuxManager()
    private val serverScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _serverStatus = MutableStateFlow(ServerStatus())
    val serverStatus: StateFlow<ServerStatus> = _serverStatus.asStateFlow()

    val jreInfo: StateFlow<JreInfo> = jreManager.jreInfo
    val consoleOutput: SharedFlow<String> = termuxManager.consoleOutput
    val isRunning: Boolean get() = termuxManager.running

    val selectedJreVersion: String get() = jreManager.selectedVersion
    val selectedJrePackage: String get() = jreManager.selectedPackage
    val customBaseUrl: String get() = jreManager.customBaseUrl
    val currentJavaPath: String? get() = jreManager.currentJavaPath
    val mirror: String get() = jreManager.mirror

    /** 备份列表（仿 MCSManager） */
    val backups: StateFlow<List<BackupManager.BackupEntry>> get() = BackupManager.backups
    suspend fun refreshBackups() = BackupManager.refresh()
    suspend fun createBackup(label: String = "") =
        BackupManager.createBackup(label).also { refreshBackups() }
    suspend fun restoreBackup(name: String) =
        BackupManager.restoreBackup(name).also { refreshBackups() }
    suspend fun deleteBackup(name: String) =
        BackupManager.deleteBackup(name).also { refreshBackups() }

    /** 状态恢复标记：是否正在尝试恢复之前的服务器连接 */
    private val _isRecovering = MutableStateFlow(false)
    val isRecovering: StateFlow<Boolean> = _isRecovering.asStateFlow()

    private var serverJob: Job? = null
    private var uptimeJob: Job? = null
    private var lastConfig: ServerConfig? = null
    /** 最后一次启动使用的服务器配置（供 ConsoleScreen 等界面使用） */
    val currentConfig: ServerConfig? get() = lastConfig
    /** 区分「用户手动停止」与「崩溃退出」，避免自动重启把手动停止的服务器又拉起来 */
    private val manualStop = AtomicBoolean(false)
    /** 连续崩溃自动重启次数（仿 Pterodactyl restart policy） */
    private var restartCount = 0
    private var lastExitTime = 0L

    /** 累计统计数据（持久化） */
    val stats: ServerStateManager.ServerStats get() = ServerStateManager.getStats()

    init {
        // 加载上次持久化状态
        ServerStateManager.load()
        // 将在线玩家列表同步到状态，供界面展示
        serverScope.launch {
            termuxManager.players.collect { list ->
                _serverStatus.value = _serverStatus.value.copy(
                    playerCount = list.size,
                    players = list
                )
            }
        }
        // 尝试恢复之前的服务器状态（应用被杀后重新打开）
        serverScope.launch {
            tryRecoverServerState()
        }
    }

    /**
     * 尝试恢复之前的服务器运行状态。
     * 应用被杀后重新打开时，检测 Termux 中是否仍有服务器进程运行，
     * 如果有则自动重连日志追踪和性能监控。
     * 借鉴 Pterodactyl 的 server state recovery 机制。
     */
    private suspend fun tryRecoverServerState() {
        try {
            val persistedState = ServerStateManager.state
            if (!persistedState.isRunning) return

            _isRecovering.value = true

            // 检查 Termux 中的进程是否还活着
            if (!termuxManager.isServerProcessAlive()) {
                // 进程已经不在了，清理持久化状态
                ServerStateManager.save(ServerStateManager.PersistentState())
                _isRecovering.value = false
                return
            }

            // 尝试恢复连接
            delay(1000) // 给 Termux 一点时间
            val reconnected = termuxManager.reconnectToRunningServer()
            if (reconnected) {
                _serverStatus.value = ServerStatus(
                    state = ServerState.RUNNING,
                    uptimeSeconds = (System.currentTimeMillis() - persistedState.startTimeMs) / 1000
                )
                PerformanceMonitor.instance.startMonitoring()
                startUptime()
                startForeground()
            } else {
                ServerStateManager.save(ServerStateManager.PersistentState())
            }
            _isRecovering.value = false
        } catch (_: Exception) {
            ServerStateManager.save(ServerStateManager.PersistentState())
            _isRecovering.value = false
        }
    }

    suspend fun fetchAvailableVersions() = jreManager.fetchAvailableVersions()

    fun setJreVersion(version: String, pkg: String) = jreManager.setVersionAndPackage(version, pkg)
    fun setCustomBaseUrl(url: String) { jreManager.customBaseUrl = url }
    fun setMirror(m: String) { jreManager.mirror = m }
    fun refreshJreStatus() = jreManager.checkJre()
    fun pauseDownload() = jreManager.pauseDownload()
    fun resumeDownload() = jreManager.resumeDownload()
    fun cancelDownload() = jreManager.cancelDownload()
    fun deleteInstalledVersion(version: String) = jreManager.deleteInstalledVersion(version)
    suspend fun testMirrorLatency() = testAllMirrors()

    /** Termux 状态 */
    val termuxState: TermuxState get() = termuxManager.checkState()
    fun openTermuxDownload() = TermuxManager.openTermuxDownload(context)
    fun installJavaInTermux() = termuxManager.installJavaInTermux()

    fun startServer(config: ServerConfig) {
        if (termuxManager.running) return
        lastConfig = config
        manualStop.set(false)
        // 全新启动，重置崩溃计数
        restartCount = 0
        launchServer(config)
    }

    /** 真正执行一次启动（含自动重启时的重复调用） */
    private fun launchServer(config: ServerConfig) {
        if (!TermuxManager.isTermuxInstalled(context)) {
            _serverStatus.value = ServerStatus(state = ServerState.ERROR)
            return
        }

        serverJob?.cancel()
        _serverStatus.value = ServerStatus(state = ServerState.STARTING)

        startForeground()

        // 记录进程退出时间（在回调触发时立即记录，而非在分支逻辑内）
        val exitTime = System.currentTimeMillis()

        // 服务器进程退出回调：崩溃检测 + 自动重启 + 手动停止收尾
        termuxManager.onServerExited = {
            serverScope.launch {
                if (manualStop.get()) {
                    // 用户主动停止，正常收尾
                    restartCount = 0
                    lastExitTime = 0L
                    if (config.backupOnStop) {
                        termuxManager.notifyConsole("> 正在创建停止前备份...")
                        BackupManager.createBackup("stop").onSuccess {
                            termuxManager.notifyConsole("> 备份完成：$it")
                        }.onFailure {
                            termuxManager.notifyConsole("> 备份失败：${it.message}")
                        }
                    }
                    ServerStateManager.onServerStopped()
                    _serverStatus.value = ServerStatus(state = ServerState.STOPPED)
                    stopUptime()
                    stopForeground()
                } else {
                    // 非手动停止：进程异常退出
                    val max = config.maxRestarts
                    val triggered = max > 0 && restartCount >= max
                    if (config.autoRestart && !triggered) {
                        // 遵守最小冷却时间，避免崩溃循环瞬间刷屏
                        // 冷却基准 = 上次退出时间（非本次），首次崩溃没有冷却限制
                        val cooldownMs = config.restartCooldownSec * 1000L
                        if (lastExitTime > 0) {
                            val elapsed = exitTime - lastExitTime
                            if (elapsed < cooldownMs) {
                                delay(cooldownMs - elapsed)
                            }
                        }
                        lastExitTime = System.currentTimeMillis()
                        restartCount++
                        termuxManager.notifyConsole(
                            "> 检测到服务器退出，第 $restartCount 次自动重启（最多 $max 次）"
                        )
                        launchServer(config)
                    } else {
                        if (triggered) {
                            termuxManager.notifyConsole(
                                "> 已连续崩溃 $restartCount 次，超过上限 $max，停止自动重启。" +
                                "请检查日志排查原因。"
                            )
                        }
                        lastExitTime = 0L
                        restartCount = 0
                        ServerStateManager.onServerCrashed()
                        _serverStatus.value = ServerStatus(state = ServerState.STOPPED)
                        stopUptime()
                        stopForeground()
                    }
                }
            }
        }

        serverJob = serverScope.launch {
            val result = termuxManager.startServer(config)
            if (result.isFailure) {
                stopUptime()
                PerformanceMonitor.instance.stopMonitoring()
                _serverStatus.value = ServerStatus(state = ServerState.ERROR, maxRestarts = config.maxRestarts)
                stopForeground()
                termuxManager.onServerExited = null
                return@launch
            }
            _serverStatus.value = _serverStatus.value.copy(
                state = ServerState.RUNNING,
                memoryUsedMB = config.allocatedMemoryMB.toLong(),
                maxRestarts = config.maxRestarts
            )
            ServerStateManager.onServerStarted(config)
            PerformanceMonitor.instance.startMonitoring()
            startUptime()
            // 启动成功后尝试建立 RCON 连接
            if (config.rconEnabled) {
                termuxManager.tryConnectRcon(config)
            }
            // 启动成功后，进程的实际退出由 onServerExited 回调处理
        }
    }

    fun stopServer() {
        if (!termuxManager.running) {
            _serverStatus.value = ServerStatus(state = ServerState.STOPPED)
            stopUptime()
            stopForeground()
            return
        }
        manualStop.set(true)
        // 保留 onServerExited 回调，由它负责把状态收尾为「已停止」
        _serverStatus.value = _serverStatus.value.copy(state = ServerState.STOPPING)
        serverScope.launch {
            termuxManager.stopServer()
            // 安全兜底：若 4s 内回调未触发，强制收尾
            delay(4000)
            if (termuxManager.running) {
                _serverStatus.value = ServerStatus(state = ServerState.STOPPED)
                stopUptime()
                stopForeground()
            }
        }
    }

    fun sendCommand(cmd: String) {
        termuxManager.sendCommand(cmd)
    }

    suspend fun installJre(onProgress: (Float, Long, Long) -> Unit = { _, _, _ -> }) =
        jreManager.downloadAndInstall(onProgress)

    private fun startUptime() {
        uptimeJob = serverScope.launch {
            val start = System.currentTimeMillis()
            while (isActive) {
                _serverStatus.value = _serverStatus.value.copy(uptimeSeconds = (System.currentTimeMillis() - start) / 1000)
                delay(1000)
            }
        }
    }

    private fun stopUptime() { uptimeJob?.cancel(); uptimeJob = null; PerformanceMonitor.instance.stopMonitoring() }

    private fun startForeground() {
        try {
            val si = Intent(context, ServerForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(si)
            else context.startService(si)
        } catch (_: Exception) {}
    }

    private fun stopForeground() {
        try { context.stopService(Intent(context, ServerForegroundService::class.java)) } catch (_: Exception) {}
    }
}
