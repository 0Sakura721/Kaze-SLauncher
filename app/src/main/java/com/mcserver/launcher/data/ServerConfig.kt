package com.mcserver.launcher.data

data class ServerConfig(
    val name: String = "Minecraft Server",
    val jarPath: String = "",
    val javaPath: String = "",
    val allocatedMemoryMB: Int = 2048,  // 用户通过滑块控制的统一内存分配
    val serverPort: Int = 25565,
    val additionalArgs: String = "-XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200",
    val autoRestart: Boolean = false,
    val nogui: Boolean = true
) {
    // 运行时：最大堆 = 用户分配值，最小堆 = 其一半（不低于 256MB）
    val maxRamMB: Int get() = allocatedMemoryMB
    val minRamMB: Int get() = (allocatedMemoryMB / 2).coerceAtLeast(256)

    fun toCommandArgs(): List<String> {
        return buildList {
            add("-Xmx${maxRamMB}M")
            add("-Xms${minRamMB}M")
            addAll(additionalArgs.split(" ").filter { it.isNotBlank() })
            add("-jar")
            add(jarPath)
            if (nogui) add("nogui")
        }
    }
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
    val players: List<String> = emptyList()
)

enum class JreStatus {
    NOT_INSTALLED,
    DOWNLOADING,
    EXTRACTING,
    INSTALLED,
    ERROR
}

data class JreInfo(
    val status: JreStatus = JreStatus.NOT_INSTALLED,
    val version: String = "",
    val path: String = "",
    val downloadProgress: Float = 0f,
    val installedVersions: List<String> = emptyList()
)
