package com.mcserver.launcher.core.server

import android.content.Context
import com.mcserver.launcher.data.ServerInstance
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * 全局服务器管理器:同一时刻运行一个实例,负责路由与状态暴露。
 */
object ServerManager {

    private lateinit var appContext: Context
    private lateinit var launcher: ServerLauncher

    val status: StateFlow<com.mcserver.launcher.data.InstanceStatus>
        get() = launcher.status

    val console get() = launcher.console
    val players get() = launcher.players
    val uptimeSec get() = launcher.uptimeSec
    val runningInstanceId get() = launcher.runningInstanceId

    /** 当前运行的实例 ID(停止后自动重置为 null) */
    val currentInstanceId: String? get() = launcher.runningInstanceId.value

    fun init(context: Context) {
        appContext = context.applicationContext
        launcher = ServerLauncher(appContext)
    }

    fun isRunningFor(instanceId: String): Boolean =
        currentInstanceId == instanceId && launcher.isRunning

    /** 是否有服务器在运行 */
    val isRunning: Boolean get() = launcher.isRunning

    /**
     * 孤儿服务器接管:App 被系统杀死后服务器进程仍在运行,
     * 重启时扫描实例 pid 文件,若进程存活则恢复监控与状态。
     * 返回接管的数量。
     */
    fun recoverOrphans(): Int {
        var count = 0
        InstanceStore.instances.value.forEach { inst ->
            val pidFile = File(inst.dir(InstanceStore.instancesDir), "mcserver.pid")
            if (pidFile.exists()) {
                val pid = pidFile.readText().trim().toIntOrNull() ?: return@forEach
                if (launcher.adoptOrphan(inst, pid)) count++
            }
        }
        return count
    }

    /** 是否有孤儿服务器在运行(未接管前查询) */
    fun orphanRunning(inst: ServerInstance): Boolean {
        val pidFile = File(inst.dir(InstanceStore.instancesDir), "mcserver.pid")
        if (!pidFile.exists()) return false
        val pid = pidFile.readText().trim().toIntOrNull() ?: return false
        return File("/proc/$pid").exists()
    }

    suspend fun start(instance: ServerInstance): Result<Unit> {
        return launcher.start(instance)
    }

    suspend fun stop() {
        launcher.stop()
    }

    fun sendCommand(cmd: String) = launcher.sendCommand(cmd)
}
