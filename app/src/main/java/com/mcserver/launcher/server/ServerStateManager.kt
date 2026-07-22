package com.mcserver.launcher.server

import android.content.Context
import com.mcserver.launcher.McApplication
import com.mcserver.launcher.utils.L
import com.mcserver.launcher.data.ServerConfig
import com.mcserver.launcher.data.ServerState
import org.json.JSONObject
import java.io.File

/**
 * 服务器状态持久化管理器。
 * 借鉴 Pterodactyl 的 server state 持久化机制，
 * 确保应用重启 / 崩溃后能恢复服务器状态。
 *
 * 存储内容：
 * - 服务器运行状态（是否正在运行）
 * - 启动时间（用于计算 uptime）
 * - 最近一次使用的配置快照
 * - 崩溃/重启统计
 */
object ServerStateManager {

    private const val TAG = "ServerStateManager"
    private val context: Context get() = McApplication.instance
    private val stateFile: File get() = File(context.filesDir, "server_state.json")

    data class PersistentState(
        val isRunning: Boolean = false,
        val startTimeMs: Long = 0L,
        val configSnapshot: String = "",     // JSON 格式的 ServerConfig 快照
        val crashCount: Int = 0,
        val lastCrashTimeMs: Long = 0L,
        val totalUptimeSeconds: Long = 0L,   // 累计运行时间
        val totalRestarts: Int = 0,          // 累计重启次数
        val lastSessionId: String = ""       // 会话 ID，用于区分不同启动周期
    )

    private var _state = PersistentState()
    val state: PersistentState get() = _state

    /** 加载持久化状态 */
    fun load(): PersistentState {
        return try {
            if (!stateFile.exists()) {
                _state = PersistentState()
                return _state
            }
            val json = JSONObject(stateFile.readText())
            _state = PersistentState(
                isRunning = json.optBoolean("isRunning", false),
                startTimeMs = json.optLong("startTimeMs", 0L),
                configSnapshot = json.optString("configSnapshot", ""),
                crashCount = json.optInt("crashCount", 0),
                lastCrashTimeMs = json.optLong("lastCrashTimeMs", 0L),
                totalUptimeSeconds = json.optLong("totalUptimeSeconds", 0L),
                totalRestarts = json.optInt("totalRestarts", 0),
                lastSessionId = json.optString("lastSessionId", "")
            )
            _state
        } catch (e: Exception) {
            L.w(TAG, "load state failed", e)
            _state = PersistentState()
            _state
        }
    }

    /** 保存当前状态 */
    fun save(state: PersistentState) {
        try {
            _state = state
            val json = JSONObject().apply {
                put("isRunning", state.isRunning)
                put("startTimeMs", state.startTimeMs)
                put("configSnapshot", state.configSnapshot)
                put("crashCount", state.crashCount)
                put("lastCrashTimeMs", state.lastCrashTimeMs)
                put("totalUptimeSeconds", state.totalUptimeSeconds)
                put("totalRestarts", state.totalRestarts)
                put("lastSessionId", state.lastSessionId)
            }
            stateFile.writeText(json.toString())
        } catch (e: Exception) {
            L.w(TAG, "save state failed", e)
        }
    }

    /** 服务器启动时调用 */
    fun onServerStarted(config: ServerConfig) {
        val sessionId = java.util.UUID.randomUUID().toString()
        val snapshot = try {
            JSONObject().apply {
                put("name", config.name)
                put("jarPath", config.jarPath)
                put("allocatedMemoryMB", config.allocatedMemoryMB)
                put("serverPort", config.serverPort)
            }.toString()
        } catch (_: Exception) { "" }

        save(PersistentState(
            isRunning = true,
            startTimeMs = System.currentTimeMillis(),
            configSnapshot = snapshot,
            crashCount = 0,  // 成功启动，重置崩溃计数
            lastCrashTimeMs = 0L,
            totalUptimeSeconds = _state.totalUptimeSeconds,
            totalRestarts = _state.totalRestarts,
            lastSessionId = sessionId
        ))
    }

    /** 服务器正常停止时调用 */
    fun onServerStopped() {
        val uptime = if (_state.startTimeMs > 0)
            (System.currentTimeMillis() - _state.startTimeMs) / 1000
        else 0L

        save(PersistentState(
            isRunning = false,
            startTimeMs = 0L,
            configSnapshot = _state.configSnapshot,
            crashCount = _state.crashCount,
            lastCrashTimeMs = 0L,
            totalUptimeSeconds = _state.totalUptimeSeconds + uptime,
            totalRestarts = _state.totalRestarts,
            lastSessionId = _state.lastSessionId
        ))
    }

    /** 服务器崩溃时调用 */
    fun onServerCrashed() {
        save(PersistentState(
            isRunning = false,
            startTimeMs = 0L,
            configSnapshot = _state.configSnapshot,
            crashCount = _state.crashCount + 1,
            lastCrashTimeMs = System.currentTimeMillis(),
            totalUptimeSeconds = _state.totalUptimeSeconds,
            totalRestarts = _state.totalRestarts + 1,
            lastSessionId = _state.lastSessionId
        ))
    }

    /** 检查是否有上次异常退出的服务器（应用被杀死等） */
    fun wasRunningBeforeKill(): Boolean {
        val s = load()
        // 如果之前标记为运行中，且没有正常停止记录，则说明应用被意外杀死
        return s.isRunning && s.startTimeMs > 0
    }

    /** 获取累计统计信息 */
    fun getStats(): ServerStats {
        return ServerStats(
            totalUptimeSeconds = _state.totalUptimeSeconds,
            totalRestarts = _state.totalRestarts,
            totalCrashes = _state.crashCount,
            lastCrashTimeMs = _state.lastCrashTimeMs
        )
    }

    data class ServerStats(
        val totalUptimeSeconds: Long = 0,
        val totalRestarts: Int = 0,
        val totalCrashes: Int = 0,
        val lastCrashTimeMs: Long = 0
    )
}
