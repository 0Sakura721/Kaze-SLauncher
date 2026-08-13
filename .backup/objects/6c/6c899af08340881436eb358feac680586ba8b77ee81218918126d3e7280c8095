package com.mcserver.launcher.data

import com.mcserver.launcher.core.instance.CoreType
import java.util.UUID

/** 服务器运行状态 */
sealed interface ServerState {
    data object Idle : ServerState
    data object Starting : ServerState
    data class Running(val pid: Int) : ServerState
    data object Stopping : ServerState
    data class Crashed(val exitCode: Int) : ServerState
}

/** 实时运行指标 */
data class RuntimeStats(
    val cpuPercent: Float = 0f,
    val memMb: Long = 0L,
    val tps: Float = -1f,        // -1 表示未知
    val uptimeMs: Long = 0L,
    val playerCount: Int = 0,
)

/** 服务器实例（一个实例 = 一个独立服务端目录） */
data class ServerInstance(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "新服务器",
    val coreType: CoreType = CoreType.PAPER,
    val mcVersion: String = "",
    val coreFileName: String = "",      // 实例目录里的服务端 jar 文件名
    val jvmArgs: String = "-Xmx2G",     // JVM 参数（内存等）
    val agreeEula: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
)

/** 运行配置（全局偏好） */
data class AppSettings(
    val themeMode: Int = 0,        // 0=跟随系统 1=浅色 2=深色
    val language: String = "zh",   // zh / en
    val autoBackupOnStart: Boolean = false,
    val keepAwake: Boolean = true, // 运行时保持屏幕常亮
    val maxLogLines: Int = 2000,   // 控制台最多缓存行数
)

/** 下载任务状态 */
sealed interface DownloadState {
    data object Idle : DownloadState
    data class Progress(val doneBytes: Long, val totalBytes: Long) : DownloadState
    data class Done(val filePath: String) : DownloadState
    data class Failed(val message: String) : DownloadState
}