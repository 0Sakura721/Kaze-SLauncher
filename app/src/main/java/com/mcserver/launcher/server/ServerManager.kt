package com.mcserver.launcher.server

import android.content.Context
import android.content.Intent
import android.os.Build
import com.mcserver.launcher.McApplication
import com.mcserver.launcher.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

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

        // 检查 Termux
        if (!TermuxManager.isTermuxInstalled(context)) {
            _serverStatus.value = ServerStatus(state = ServerState.ERROR)
            return
        }

        serverJob?.cancel()
        _serverStatus.value = ServerStatus(state = ServerState.STARTING)

        // 启动前台服务
        try {
            val si = Intent(context, ServerForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(si)
            else context.startService(si)
        } catch (_: Exception) {}

        serverJob = serverScope.launch {
            try {
                _serverStatus.value = _serverStatus.value.copy(state = ServerState.RUNNING)
                startUptime()
                termuxManager.startServer(config)
                stopUptime()
                _serverStatus.value = ServerStatus(state = ServerState.STOPPED)
                try { context.stopService(Intent(context, ServerForegroundService::class.java)) } catch (_: Exception) {}
            } catch (e: CancellationException) {
                stopUptime()
            } catch (e: Exception) {
                stopUptime()
                _serverStatus.value = ServerStatus(state = ServerState.ERROR)
                try { context.stopService(Intent(context, ServerForegroundService::class.java)) } catch (_: Exception) {}
            }
        }
    }

    fun stopServer() {
        _serverStatus.value = _serverStatus.value.copy(state = ServerState.STOPPING)
        termuxManager.stopServer()
    }

    fun sendCommand(cmd: String) {
        // 通过 Termux 发送命令到服务器 stdin 较复杂，暂不支持
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
}
