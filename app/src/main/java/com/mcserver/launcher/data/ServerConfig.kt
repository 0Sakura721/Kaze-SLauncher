package com.mcserver.launcher.data

import java.util.UUID

data class ServerConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Minecraft Server",
    val jarPath: String = "",
    val javaPath: String = "",
    val javaVersion: Int = 21,              // 8 | 11 | 17 | 21 — 当前使用的 Java 版本
    val allocatedMemoryMB: Int = 2048,      // 用户通过滑块控制的统一内存分配
    val serverPort: Int = 25565,
    val additionalArgs: String = "-XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200",
    val autoRestart: Boolean = false,
    val nogui: Boolean = true,
    // 启动方式
    val startupMode: StartupMode = StartupMode.DIRECT_JAR,
    val shScriptPath: String = "",           // .sh 脚本路径（仅 SHELL_SCRIPT 模式）
    val shMaxRamMB: Int = 4096,              // .sh 中的最大内存
    val shMinRamMB: Int = 2048,              // .sh 中的最小内存
    // server.properties 常用项（启动前注入，仿 Pterodactyl 的变量注入）
    val motd: String = "A Minecraft Server",
    val maxPlayers: Int = 20,
    val gamemode: String = "survival",       // survival / creative / adventure / spectator
    val difficulty: String = "easy",         // peaceful / easy / normal / hard
    val pvp: Boolean = true,
    val onlineMode: Boolean = true,          // true=正版验证，false=离线模式
    val whiteList: Boolean = false,
    val spawnProtection: Int = 16,
    val viewDistance: Int = 10,
    // 自动重启保护（仿 Pterodactyl restart policy）
    val maxRestarts: Int = 3,                // 连续崩溃最大自动重启次数，0=不限制
    val restartCooldownSec: Int = 5,         // 两次重启之间的最小冷却时间
    // 备份（仿 MCSManager）
    val backupOnStop: Boolean = false,       // 停止服务器时自动备份一次
    // RCON 远程控制（仿 Pterodactyl / MCSManager 的控制台设计）
    val rconEnabled: Boolean = true,         // 启用 RCON 协议
    val rconPassword: String = "",           // RCON 密码（空则自动生成）
    val rconPort: Int = 25575               // RCON 端口
) {
    // 运行时：最大堆 = 用户分配值，最小堆 = 其一半（不低于 256MB）
    val maxRamMB: Int get() = allocatedMemoryMB
    val minRamMB: Int get() = (allocatedMemoryMB / 2).coerceAtLeast(256)
}

enum class StartupMode {
    DIRECT_JAR,     // java -jar server.jar
    SHELL_SCRIPT    // bash run.sh
}

enum class ServerState {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    ERROR
}

data class ServerStatus(
    val state: ServerState = ServerState.STOPPED,
    val pid: Int = -1,
    val uptimeSeconds: Long = 0,
    val memoryUsedMB: Long = 0,
    val playerCount: Int = 0,
    val players: List<String> = emptyList(),
    val restartCount: Int = 0,
    val maxRestarts: Int = 0
)

enum class JreStatus {
    NOT_INSTALLED,
    DOWNLOADING,
    PAUSED,
    EXTRACTING,
    INSTALLED,
    ERROR
}

data class JreInfo(
    val status: JreStatus = JreStatus.NOT_INSTALLED,
    val version: String = "",
    val path: String = "",
    val downloadProgress: Float = 0f,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val downloadSpeedBytesPerSec: Long = 0,
    val remainingSeconds: Long = 0,
    val installedVersions: List<String> = emptyList(),
    val isPaused: Boolean = false
)
