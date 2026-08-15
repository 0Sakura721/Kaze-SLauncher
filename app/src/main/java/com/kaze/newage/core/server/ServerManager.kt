package com.kaze.newage.core.server

import com.kaze.newage.core.console.ConsoleStream
import com.kaze.newage.data.model.ServerInstance
import kotlinx.coroutines.flow.StateFlow

/** 服务端生命周期状态 */
enum class ServerState {
    Idle,        // 未配置/未启动
    Starting,    // 正在启动
    FirstRun,    // 首次启动（正在生成 eula / 等待自动退出）
    AcceptingEula, // 正在改写 eula.txt
    Running,     // 正常运行中
    Stopping,    // 正在停止
    Stopped,     // 已停止
    Error,       // 启动失败
}

/**
 * 服务端管理器：启动流程编排（支持多开：每实例独立进程、独立状态、独立控制台）。
 *
 * 启动流程（用户需求的核心链路）：
 *  1. 检查/安装 Java
 *  2. 首次启动服务端 → 自动生成 eula.txt 后自动退出
 *  3. 改写 eula.txt 中 eula=false → eula=true
 *  4. 再次启动 → 正常运行，实时输出日志
 */
interface ServerManager {

    /** 各实例状态：instanceId -> ServerState */
    val states: StateFlow<Map<String, ServerState>>

    /** 实例控制台（每实例独立日志流；未运行时也保留历史） */
    fun consoleFor(instanceId: String): ConsoleStream

    /** 实例运行时长（秒） */
    fun uptimeSec(instanceId: String): StateFlow<Long>

    /** 该实例是否正在运行 */
    fun isRunning(instanceId: String): Boolean

    suspend fun start(instance: ServerInstance)

    suspend fun stop(instance: ServerInstance)

    /** 发送控制台命令（写入进程 stdin，同步操作） */
    fun sendCommand(instance: ServerInstance, command: String)
}
