package com.mcserver.launcher.server

import android.content.Context
import android.content.Intent
import com.mcserver.launcher.McApplication
import com.mcserver.launcher.data.JreStatus
import com.mcserver.launcher.data.ServerConfig
import com.mcserver.launcher.data.ServerState
import com.mcserver.launcher.data.ServerStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * 服务器管理器：统一管理 JRE、服务器启动/停止、状态跟踪
 */
class ServerManager private constructor() {

    companion object {
        val instance: ServerManager by lazy { ServerManager() }
    }

    private val context: Context get() = McApplication.instance
    private val jreManager = JreManager(context)
    private val jarRunner = JarRunner()

    private val _serverStatus = MutableStateFlow(ServerStatus())
    val serverStatus: StateFlow<ServerStatus> = _serverStatus.asStateFlow()

    val jreInfo: StateFlow<com.mcserver.launcher.data.JreInfo> = jreManager.jreInfo
    val consoleOutput: SharedFlow<String> = jarRunner.consoleOutput
    val isRunning: Boolean get() = jarRunner.running

    /** 当前选择的 Java 版本 */
    val selectedJreVersion: String get() = jreManager.selectedVersion
    /** 当前选择的包类型 (jre/jdk) */
    val selectedJrePackage: String get() = jreManager.selectedPackage
    /** 自定义下载源 */
    val customBaseUrl: String get() = jreManager.customBaseUrl
    /** 当前可用的 Java 路径 */
    val currentJavaPath: String? get() = jreManager.currentJavaPath

    private var serverJob: Job? = null
    private var uptimeJob: Job? = null

    /** 获取可选版本列表 */
    suspend fun fetchAvailableVersions(): Result<List<String>> =
        jreManager.fetchAvailableVersions()

    /** 设置版本和包类型 */
    fun setJreVersion(version: String, pkg: String) =
        jreManager.setVersionAndPackage(version, pkg)

    /** 设置自定义下载源 */
    fun setCustomBaseUrl(url: String) {
        jreManager.customBaseUrl = url
    }

    /** 刷新 JRE 状态 */
    fun refreshJreStatus() {
        jreManager.checkJre().let { /* MutableStateFlow already updated inside */ }
    }

    /** 暂停下载 */
    fun pauseDownload() = jreManager.pauseDownload()
    /** 继续下载 */
    fun resumeDownload() = jreManager.resumeDownload()
    /** 取消下载 */
    fun cancelDownload() = jreManager.cancelDownload()

    /**
     * 启动服务器
     */
    fun startServer(config: ServerConfig, scope: CoroutineScope) {
        if (jarRunner.running) return

        val jre = jreManager.checkJre()
        if (jre.status != JreStatus.INSTALLED) {
            return
        }

        _serverStatus.value = ServerStatus(state = ServerState.STARTING)

        val serviceIntent = Intent(context, ServerForegroundService::class.java)
        context.startForegroundService(serviceIntent)

        serverJob = scope.launch(Dispatchers.IO) {
            try {
                _serverStatus.value = _serverStatus.value.copy(state = ServerState.RUNNING)
                startUptimeCounter(scope)
                jarRunner.start(config, jre.path)
                stopUptimeCounter()
                _serverStatus.value = ServerStatus(state = ServerState.STOPPED)
                context.stopService(Intent(context, ServerForegroundService::class.java))
            } catch (e: Exception) {
                stopUptimeCounter()
                _serverStatus.value = ServerStatus(state = ServerState.ERROR)
                context.stopService(Intent(context, ServerForegroundService::class.java))
            }
        }
    }

    fun stopServer() {
        _serverStatus.value = _serverStatus.value.copy(state = ServerState.STOPPING)
        jarRunner.stop()
    }

    fun sendCommand(cmd: String) {
        jarRunner.sendCommand(cmd)
    }

    suspend fun installJre(onProgress: (Float, Long, Long) -> Unit = { _, _, _ -> }): Result<String> {
        return jreManager.downloadAndInstall(onProgress)
    }

    private fun startUptimeCounter(scope: CoroutineScope) {
        uptimeJob = scope.launch {
            val startTime = System.currentTimeMillis()
            while (isActive) {
                val uptime = (System.currentTimeMillis() - startTime) / 1000
                _serverStatus.value = _serverStatus.value.copy(uptimeSeconds = uptime)
                delay(1000)
            }
        }
    }

    private fun stopUptimeCounter() {
        uptimeJob?.cancel()
        uptimeJob = null
    }
}
