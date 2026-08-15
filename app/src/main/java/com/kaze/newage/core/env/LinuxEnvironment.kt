package com.kaze.newage.core.env

import java.io.File

/**
 * Linux 运行环境抽象。
 *
 * 目标：在非 root Android 设备上营造可执行 `java -jar server.jar` 的 Linux 环境。
 * 当前实现：ProotEnvironment（proot + Ubuntu rootfs，自包含，不使用 Termux）。
 */
interface LinuxEnvironment {
    /** 环境是否已就绪（rootfs 已解压、proot 可执行） */
    val isReady: Boolean

    /** 环境根目录（rootfs 挂载根） */
    val rootfsDir: File

    /** 安装/初始化环境（解压 rootfs、设置权限）。耗时操作，应在后台协程中调用。 */
    suspend fun setup(onProgress: (Float, String) -> Unit = { _, _ -> })

    /**
     * 启动一个进程（不阻塞），返回进程句柄；失败返回 null。
     * 进程 stdin 为 PIPE（可通过 process.outputStream 注入命令），stdout/stderr 合并为一行行输出。
     * @param command 环境内命令，例如 ["/usr/lib/jvm/java-17-openjdk-arm64/bin/java", "-Xmx1024M", "-jar", "server.jar"]
     * @param workDir 工作目录（host 路径，自动绑定进 proot）
     */
    fun launch(
        command: List<String>,
        workDir: File? = null,
        env: Map<String, String> = emptyMap(),
    ): Process?

    /**
     * 在环境内执行命令并实时回调 stdout/stderr 行（便捷方法）。
     * @return 进程退出码；null 表示未能启动
     */
    suspend fun execute(
        command: List<String>,
        workDir: File? = null,
        env: Map<String, String> = emptyMap(),
        onLine: (String) -> Unit,
    ): Int?
}
