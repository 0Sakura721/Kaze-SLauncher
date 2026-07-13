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

    private var serverJob: Job? = null
    private var uptimeJob: Job? = null
    private var lastConfig: ServerConfig? = null
    /** 区分「用户手动停止」与「崩溃退出」，避免自动重启把手动停止的服务器又拉起来 */
    private val manualStop = AtomicBoolean(false)

    init {
        // 将在线玩家列表同步到状态，供界面展示
        serverScope.launch {
            termuxManager.players.collect { list ->
                _serverStatus.value = _serverStatus.value.copy(
                    playerCount = list.size,
                    players = list
                )
            }
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

        // 服务器进程退出回调：崩溃检测 + 自动重启 + 手动停止收尾
        termuxManager.onServerExited = {
            serverScope.launch {
                if (manualStop.get()) {
                    _serverStatus.value = ServerStatus(state = ServerState.STOPPED)
                    stopUptime()
                    stopForeground()
                } else if (config.autoRestart) {
                    termuxManager.notifyConsole("> 自动重启已开启，正在重新启动服务器...")
                    launchServer(config)
                } else {
                    _serverStatus.value = ServerStatus(state = ServerState.STOPPED)
                    stopUptime()
                    stopForeground()
                }
            }
        }

        serverJob = serverScope.launch {
            _serverStatus.value = _serverStatus.value.copy(
                state = ServerState.RUNNING,
                memoryUsedMB = config.allocatedMemoryMB.toLong()
            )
            startUptime()
            val result = termuxManager.startServer(config)
            if (result.isFailure) {
                stopUptime()
                _serverStatus.value = ServerStatus(state = ServerState.ERROR)
                stopForeground()
                termuxManager.onServerExited = null
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

    private fun stopUptime() { uptimeJob?.cancel(); uptimeJob = null }

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
