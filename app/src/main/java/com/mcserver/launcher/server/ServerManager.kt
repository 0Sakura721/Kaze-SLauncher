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
    private val jarRunner = JarRunner()
    private val serverScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _serverStatus = MutableStateFlow(ServerStatus())
    val serverStatus: StateFlow<ServerStatus> = _serverStatus.asStateFlow()

    val jreInfo: StateFlow<JreInfo> = jreManager.jreInfo
    val consoleOutput: SharedFlow<String> = jarRunner.consoleOutput
    val isRunning: Boolean get() = jarRunner.running

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

    fun startServer(config: ServerConfig) {
        if (jarRunner.running) return

        val jre = jreManager.checkJre()
        if (jre.status != JreStatus.INSTALLED) {
            _serverStatus.value = ServerStatus(state = ServerState.ERROR)
            return
        }

        serverJob?.cancel()
        _serverStatus.value = ServerStatus(state = ServerState.STARTING)

        // 启动前台服务（try-catch 防止权限不足崩溃）
        try {
            val si = Intent(context, ServerForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(si)
            else context.startService(si)
        } catch (e: Exception) {
            // 前台服务启动失败不阻断
        }

        serverJob = serverScope.launch {
            try {
                _serverStatus.value = _serverStatus.value.copy(state = ServerState.RUNNING)
                startUptime()
                jarRunner.start(config, jre.path)
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
        jarRunner.stop()
    }

    fun sendCommand(cmd: String) = jarRunner.sendCommand(cmd)

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
