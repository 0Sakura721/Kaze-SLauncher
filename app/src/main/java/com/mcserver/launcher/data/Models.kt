package com.mcserver.launcher.data

import java.io.File

/** 服务端核心类型 */
enum class CoreType(val displayName: String) {
    VANILLA("Vanilla"),
    PAPER("Paper"),
    PURPUR("Purpur"),
    SPIGOT("Spigot"),
    FABRIC("Fabric"),
    FORGE("Forge"),
    NEOFORGE("NeoForge");

    companion object {
        fun fromKey(key: String): CoreType? = entries.firstOrNull { it.name == key }
    }
}

/** 实例运行状态 */
enum class InstanceStatus { STOPPED, STARTING, RUNNING, STOPPING, ERROR }

/** 服务器实例配置(版本隔离:每个实例一份) */
data class InstanceConfig(
    // ── 基础 ──
    val serverPort: Int = 25565,
    val maxPlayers: Int = 20,
    val gamemode: String = "survival",
    val difficulty: String = "normal",
    val pvp: Boolean = true,
    val onlineMode: Boolean = false,
    val whiteList: Boolean = false,
    val motd: String = "A Minecraft Server",
    // ── 世界 ──
    val levelName: String = "world",
    val levelSeed: String = "",
    val levelType: String = "default",
    val hardcore: Boolean = false,
    val allowNether: Boolean = true,
    val allowFlight: Boolean = false,
    val spawnMonsters: Boolean = true,
    val spawnAnimals: Boolean = true,
    val maxWorldSize: Int = 29999984,
    // ── 性能 ──
    val maxRamMB: Int = 2048,
    val viewDistance: Int = 10,
    val spawnProtection: Int = 16,
    val nogui: Boolean = true,
    // ── JVM ──
    val jvmArgs: String = "",
    // ── RCON ──
    val rconEnabled: Boolean = true,
    val rconPassword: String = "",
    val rconPort: Int = 25575,
    // ── 运维 ──
    val autoRestart: Boolean = true,
    val maxRestarts: Int = 3,
    val backupOnStop: Boolean = true,
    val autoBackupHours: Int = 0
)

/** 服务器实例(一个实例 = 一个目录 + 独立配置 + 独立状态) */
data class ServerInstance(
    val id: String,
    val name: String,
    val coreType: CoreType,
    val mcVersion: String,
    val buildId: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val config: InstanceConfig = InstanceConfig()
) {
    /** 实例在 App 私有目录中的根目录 */
    fun dir(baseDir: File): File = File(baseDir, id)
}

/** 下载任务状态 */
enum class DownloadStatus { PENDING, DOWNLOADING, PAUSED, COMPLETED, FAILED, CANCELED }

/** 下载任务(统一下载中心队列项) */
data class DownloadTask(
    val id: String,
    val title: String,
    val urls: List<String>,
    val destFile: File,
    val status: DownloadStatus = DownloadStatus.PENDING,
    val progress: Float = 0f,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val speedBytesPerSec: Long = 0,
    val error: String? = null
) {
    val isActive: Boolean get() = status == DownloadStatus.PENDING || status == DownloadStatus.DOWNLOADING
}
