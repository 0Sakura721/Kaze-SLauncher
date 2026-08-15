package com.kaze.newage.core.java

import java.io.File

/** Java 运行时信息 */
data class JavaRuntime(
    val version: String,       // 例如 "17" / "21"
    val home: File,            // JRE 根目录（含 bin/java）
    val architecture: String,  // aarch64 / armv7a
) {
    val javaExecutable: File get() = File(home, "bin/java")
}

/**
 * Java 安装管理器：负责下载/解压适用于 Android ARM 的 JRE。
 *
 * 来源候选：
 *  - droidJRE（FCL 使用，OpenJDK 为 Android bionic 编译，配合 Termux 环境）
 *  - PojavLauncher 的 JRE 构建（aarch64/armv7a）
 *  - 若走 proot rootfs 路线，则可直接用 Ubuntu aarch64 的 OpenJDK（glibc）
 */
interface JavaManager {
    /** 已安装的运行时列表 */
    fun installed(): List<JavaRuntime>

    /** 安装指定主版本（如 17），返回安装后的运行时；已安装则直接返回 */
    suspend fun install(majorVersion: Int, onProgress: (Float, String) -> Unit = { _, _ -> }): JavaRuntime

    /** 删除指定版本 */
    suspend fun uninstall(majorVersion: Int)
}
