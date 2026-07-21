package com.mcserver.launcher.server

import android.content.Context
import android.content.Intent
import android.util.Log
import android.os.Build
import com.mcserver.launcher.McApplication
import com.mcserver.launcher.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class ServerManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "ServerManager"
        @Volatile
        private var _instance: ServerManager? = null
        val instance: ServerManager
            get() = _instance ?: throw IllegalStateException("ServerManager not initialized. Call init() first.")

        fun init(context: Context) {
            if (_instance == null) {
                synchronized(this) {
                    if (_instance == null) {
                        _instance = ServerManager(context.applicationContext)
                    }
                }
            }
        }
    }

    private val jreManager = JreManager(context)
    private val preferencesManager = PreferencesManager(context)
    val jreManagerInstance: JreManager get() = jreManager
    /** ProotServerManager 实例（暴露给 ScheduleManager/ConsoleScreen 等模块复用） */
    val prootServerManager = ProotServerManager()
    private val serverScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /** 多服务器列表 */
    val serverList: StateFlow<List<ServerConfig>> = preferencesManager.serverList
        .stateIn(serverScope, SharingStarted.Eagerly, emptyList())
    /** 当前选中的服务器 ID */
    val currentServerId: StateFlow<String> = preferencesManager.currentServerId
        .stateIn(serverScope, SharingStarted.Eagerly, ProotServerManager.DEFAULT_SERVER_ID)

    /** 切换服务器实例（运行中禁止切换） */
    fun switchServer(serverId: String) {
        if (prootServerManager.running) {
            Log.w(TAG, "服务器运行中，禁止切换实例")
            return
        }
        ProotServerManager.activeServerId = serverId
        prootServerManager.currentServerId = serverId
        serverScope.launch {
            preferencesManager.switchServer(serverId)
            // 加载新服务器的配置
            val config = preferencesManager.serverConfig.first()
            lastConfig = config
        }
    }

    /** 创建新服务器实例 */
    suspend fun createServer(name: String): ServerConfig {
        val config = ServerConfig(name = name)
        preferencesManager.createServer(config)
        return config
    }

    /** 删除服务器实例 */
    suspend fun deleteServer(serverId: String) {
        preferencesManager.deleteServer(serverId)
    }

    private val _serverStatus = MutableStateFlow(ServerStatus())
    val serverStatus: StateFlow<ServerStatus> = _serverStatus.asStateFlow()

    val jreInfo: StateFlow<JreInfo> = jreManager.jreInfo
    val consoleOutput: SharedFlow<String> = prootServerManager.consoleOutput
    val isRunning: Boolean get() = prootServerManager.running

    val selectedJreVersion: String get() = jreManager.selectedVersion
    val selectedJrePackage: String get() = jreManager.selectedPackage
    val customBaseUrl: String get() = jreManager.customBaseUrl
    val currentJavaPath: String? get() = jreManager.currentJavaPath
    val mirror: String get() = jreManager.mirror

    /** 备份列表（仿 MCSManager） */
    val backups: StateFlow<List<BackupManager.BackupEntry>> get() = BackupManager.backups
    suspend fun refreshBackups() = BackupManager.refresh()
    suspend fun createBackup(label: String = ""): Result<String> =
        BackupManager.createBackup(label).also { refreshBackups() }
    suspend fun restoreBackup(name: String): Result<Unit> =
        BackupManager.restoreBackup(name).also { refreshBackups() }
    suspend fun deleteBackup(name: String): Result<Unit> =
        BackupManager.deleteBackup(name).also { refreshBackups() }

    /** 状态恢复标记：是否正在尝试恢复之前的服务器连接 */
    private val _isRecovering = MutableStateFlow(false)
    val isRecovering: StateFlow<Boolean> = _isRecovering.asStateFlow()

    private var serverJob: Job? = null
    private var uptimeJob: Job? = null
    @Volatile
    private var lastConfig: ServerConfig? = null
    /** 最后一次启动使用的服务器配置（供 ConsoleScreen 等界面使用） */
    val currentConfig: ServerConfig? get() = lastConfig
    /** 区分「用户手动停止」与「崩溃退出」，避免自动重启把手动停止的服务器又拉起来 */
    private val manualStop = AtomicBoolean(false)
    /** 连续崩溃自动重启次数（仿 Pterodactyl restart policy） */
    private val restartCount = AtomicInteger(0)
    private val lastExitTime = AtomicLong(0L)
    /** 标记退出事件是否已被处理，防止 stopServer 兜底与 onServerExited 回调重复执行 */
    private val exitHandled = AtomicBoolean(false)
    /** 标记服务器是否正在启动中，防止重复启动覆盖 onServerExited 回调 */
    private val isLaunching = AtomicBoolean(false)

    /** 累计统计数据（持久化） */
    val stats: ServerStateManager.ServerStats get() = ServerStateManager.getStats()

    init {
        // 初始化 CurseForgeManager
        serverScope.launch {
            preferencesManager.curseforgeApiKey.collect { apiKey ->
                if (apiKey.isNotBlank()) {
                    CurseForgeManager.initialize(apiKey)
                }
            }
        }
        prootServerManager.scope = serverScope
        // 加载上次持久化状态
        ServerStateManager.load()
        // 将在线玩家列表同步到状态，供界面展示
        serverScope.launch {
            prootServerManager.players.collect { list ->
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
     * 释放 ServerManager 持有的所有资源。
     * 一般用于测试或调试，正常使用无需调用（伴随进程结束自动回收）。
     */
    fun shutdown() {
        serverJob?.cancel()
        uptimeJob?.cancel()
        serverScope.cancel()
        if (prootServerManager.running) {
            prootServerManager.stopServer()
        }
    }

    /**
     * 尝试恢复之前的服务器运行状态。
     * 应用被杀后重新打开时，检测 Linux 环境中是否仍有服务器进程运行，
     * 如果有则自动重连日志追踪和性能监控。
     * 借鉴 Pterodactyl 的 server state recovery 机制。
     */
    private suspend fun tryRecoverServerState() {
        try {
            val persistedState = ServerStateManager.state
            if (!persistedState.isRunning) return

            _isRecovering.value = true

            // 检查 Linux 环境中的进程是否还活着
            if (!prootServerManager.isServerProcessAlive()) {
                // 进程已经不在了，清理持久化状态
                ServerStateManager.save(ServerStateManager.PersistentState())
                _isRecovering.value = false
                return
            }

            // 尝试恢复连接
            delay(1000) // 给 Linux 环境一点时间
            val reconnected = prootServerManager.reconnectToRunningServer()
            if (reconnected) {
                if (transitionState(ServerState.RUNNING)) {
                    _serverStatus.value = ServerStatus(
                        state = ServerState.RUNNING,
                        uptimeSeconds = (System.currentTimeMillis() - persistedState.startTimeMs) / 1000
                    )
                }
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
    suspend fun testMirrorLatency() = jreManager.testAllMirrors()

    /** Linux 环境状态 */
    val linuxEnvState: LinuxEnvState get() = prootServerManager.checkState()

    /**
     * 检查状态转换是否合法。
     * 确保状态机有明确的转换方向，不会出现 STOPPING → RUNNING 等反向转换。
     */
    private fun canTransitionTo(newState: ServerState): Boolean {
        val currentState = _serverStatus.value.state
        return when (newState) {
            ServerState.STARTING -> currentState == ServerState.STOPPED || currentState == ServerState.ERROR
            ServerState.RUNNING -> currentState == ServerState.STARTING
            ServerState.STOPPING -> currentState == ServerState.RUNNING
            ServerState.STOPPED -> currentState == ServerState.STARTING || 
                                   currentState == ServerState.RUNNING || 
                                   currentState == ServerState.STOPPING || 
                                   currentState == ServerState.ERROR
            ServerState.ERROR -> currentState == ServerState.STARTING || currentState == ServerState.RUNNING
        }
    }

    /**
     * 尝试转换状态，如果转换合法则更新状态并返回 true。
     */
    private fun transitionState(newState: ServerState): Boolean {
        if (canTransitionTo(newState)) {
            _serverStatus.value = _serverStatus.value.copy(state = newState)
            return true
        }
        return false
    }

    fun startServer(config: ServerConfig) {
        if (prootServerManager.running || isLaunching.get()) return
        lastConfig = config
        manualStop.set(false)
        // 全新启动，重置崩溃计数
        restartCount.set(0)
        lastExitTime.set(0L)
        exitHandled.set(false)
        launchServer(config)
    }

    /** 真正执行一次启动（含自动重启时的重复调用） */
    private fun launchServer(config: ServerConfig) {
        // ── 环境检查：LinuxEnvironment(proot+Ubuntu) 必须就绪 ──
        val linuxEnvOk = LinuxEnvironmentManager.isEnvironmentReady()
        if (!linuxEnvOk) {
            transitionState(ServerState.ERROR)
            prootServerManager.notifyConsole("> 请先完成运行环境部署（内置 Linux 环境）")
            return
        }

        if (!isLaunching.compareAndSet(false, true)) {
            return
        }

        serverJob?.cancel()
        exitHandled.set(false)
        transitionState(ServerState.STARTING)

        startForeground()

        // 服务器进程退出回调：崩溃检测 + 自动重启 + 手动停止收尾
        prootServerManager.onServerExited = {
            serverScope.launch {
                if (!exitHandled.compareAndSet(false, true)) {
                    return@launch
                }
                isLaunching.set(false)
                if (manualStop.get()) {
                    // 用户主动停止，正常收尾
                    restartCount.set(0)
                    lastExitTime.set(0L)
                    if (config.backupOnStop) {
                        prootServerManager.notifyConsole("> 正在创建停止前备份...")
                        BackupManager.createBackup("stop").onSuccess {
                            prootServerManager.notifyConsole("> 备份完成：$it")
                        }.onFailure {
                            prootServerManager.notifyConsole("> 备份失败：${it.message}")
                        }
                    }
                    ServerStateManager.onServerStopped()
                    transitionState(ServerState.STOPPED)
                    stopUptime()
                    stopForeground()
                    // 发送停止通知
                    McApplication.showServerEventNotification("服务器已停止", "Minecraft 服务器已正常停止")
                } else {
                    // 非手动停止：进程异常退出
                    val max = config.maxRestarts
                    val currentCount = restartCount.get()
                    val triggered = max > 0 && currentCount >= max
                    if (config.autoRestart && !triggered) {
                        // 遵守最小冷却时间，避免崩溃循环瞬间刷屏
                        // 冷却基准 = 上次退出时间（非本次），首次崩溃没有冷却限制
                        val exitTime = System.currentTimeMillis()
                        val cooldownMs = config.restartCooldownSec * 1000L
                        val lastExit = lastExitTime.get()
                        if (lastExit > 0) {
                            val elapsed = exitTime - lastExit
                            if (elapsed < cooldownMs) {
                                delay(cooldownMs - elapsed)
                            }
                        }
                        lastExitTime.set(System.currentTimeMillis())
                        val newCount = restartCount.incrementAndGet()
                        prootServerManager.notifyConsole(
                            "> 检测到服务器退出，第 $newCount 次自动重启（最多 $max 次）"
                        )
                        // 发送崩溃通知
                        McApplication.showServerEventNotification(
                            "服务器异常退出", "正在第 $newCount 次自动重启...", isError = true
                        )
                        exitHandled.set(false)
                        isLaunching.set(false)
                        launchServer(config)
                    } else {
                        if (triggered) {
                            prootServerManager.notifyConsole(
                                "> 已连续崩溃 $currentCount 次，超过上限 $max，停止自动重启。" +
                                "请检查日志排查原因。"
                            )
                        }
                        lastExitTime.set(0L)
                        restartCount.set(0)
                        ServerStateManager.onServerCrashed()
                        transitionState(ServerState.STOPPED)
                        stopUptime()
                        stopForeground()
                        // 发送崩溃通知
                        McApplication.showServerEventNotification(
                            "服务器已崩溃", "服务器异常退出，已停止运行。请检查日志。", isError = true
                        )
                    }
                }
            }
        }

        serverJob = serverScope.launch {
            val result = prootServerManager.startServer(config)
            if (result.isFailure) {
                isLaunching.set(false)
                stopUptime()
                PerformanceMonitor.instance.stopMonitoring()
                transitionState(ServerState.ERROR)
                _serverStatus.value = _serverStatus.value.copy(state = ServerState.ERROR, maxRestarts = config.maxRestarts)
                stopForeground()
                prootServerManager.onServerExited = null
                return@launch
            }
            isLaunching.set(false)
            transitionState(ServerState.RUNNING)
            _serverStatus.value = _serverStatus.value.copy(
                state = ServerState.RUNNING,
                memoryUsedMB = config.allocatedMemoryMB.toLong(),
                maxRestarts = config.maxRestarts
            )
            ServerStateManager.onServerStarted(config)
            PerformanceMonitor.instance.startMonitoring()
            startUptime()
            // 发送启动成功通知
            McApplication.showServerEventNotification("服务器已启动", "Minecraft 服务器启动成功")
            // 启动成功后尝试建立 RCON 连接
            if (config.rconEnabled) {
                prootServerManager.tryConnectRcon(config)
            }
            // 启动成功后，进程的实际退出由 onServerExited 回调处理
        }
    }

    fun stopServer() {
        if (!prootServerManager.running) {
            transitionState(ServerState.STOPPED)
            stopUptime()
            stopForeground()
            return
        }
        manualStop.set(true)
        // 保留 onServerExited 回调，由它负责把状态收尾为「已停止」
        transitionState(ServerState.STOPPING)
        serverScope.launch {
            prootServerManager.stopServer()
            // 安全兜底：若 4s 内回调未触发，强制收尾
            delay(4000)
            if (prootServerManager.running && !exitHandled.get()) {
                if (exitHandled.compareAndSet(false, true)) {
                    isLaunching.set(false)
                    transitionState(ServerState.STOPPED)
                    stopUptime()
                    stopForeground()
                    McApplication.showServerEventNotification("服务器已停止", "Minecraft 服务器已停止")
                }
            }
        }
    }

    fun sendCommand(cmd: String) {
        prootServerManager.sendCommand(cmd)
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
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed", e)
        }
    }

    private fun stopForeground() {
        try { context.stopService(Intent(context, ServerForegroundService::class.java)) } catch (e: Exception) {
            Log.w(TAG, "stopForeground failed", e)
        }
    }
}
