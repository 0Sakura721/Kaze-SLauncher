package com.mcserver.launcher.core.server

import android.content.Context
import com.mcserver.launcher.data.ServerInstance
import kotlinx.coroutines.flow.StateFlow

/**
 * 全局服务器管理器:同一时刻运行一个实例,负责路由与状态暴露。
 */
object ServerManager {

    private lateinit var appContext: Context
    private lateinit var launcher: ServerLauncher

    @Volatile
    var currentInstanceId: String? = null
        private set

    val status: StateFlow<com.mcserver.launcher.data.InstanceStatus>
        get() = launcher.status

    val console get() = launcher.console
    val players get() = launcher.players
    val uptimeSec get() = launcher.uptimeSec

    fun init(context: Context) {
        appContext = context.applicationContext
        launcher = ServerLauncher(appContext)
    }

    fun isRunningFor(instanceId: String): Boolean =
        currentInstanceId == instanceId && launcher.isRunning

    suspend fun start(instance: ServerInstance): Result<Unit> {
        currentInstanceId = instance.id
        return launcher.start(instance)
    }

    suspend fun stop() {
        launcher.stop()
    }

    fun sendCommand(cmd: String) = launcher.sendCommand(cmd)
}
