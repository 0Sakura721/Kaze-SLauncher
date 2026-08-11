package com.mcserver.launcher.core

import android.os.Build

/**
 * 设备架构感知工具（UI / 体验层使用）。
 * 与 EnvManager 的运行时架构判定逻辑保持一致,但独立封装,
 * 避免核心环境引擎被 UI 改动牵连,也便于在界面层做"各自架构"的体验适配。
 */
object AbiDetector {
    val primaryAbi: String =
        Build.SUPPORTED_64_BIT_ABIS.firstOrNull()
            ?: (Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a")

    /** 是否 64 位设备（aarch64 / x86_64） */
    val is64Bit: Boolean get() = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()

    val isX8664: Boolean get() = primaryAbi.contains("x86_64")

    /** 低端架构:32 位 ARM(armeabi-v7a)等视为需降级动效的设备 */
    val isLowEnd: Boolean
        get() = !is64Bit ||
            primaryAbi.contains("armeabi-v7a") ||
            primaryAbi.contains("armv7") ||
            primaryAbi.contains("armeabi")

    /** 架构展示名（aarch64 / x86_64 / armhf） */
    val archName: String get() = when {
        isX8664 -> "x86_64"
        primaryAbi.contains("arm64") || primaryAbi.contains("aarch64") -> "aarch64"
        else -> "armhf"
    }

    /** JRE 资源后缀（amd64 / arm64 / armhf） */
    val jdkArchSuffix: String get() = when {
        isX8664 -> "amd64"
        primaryAbi.contains("arm64") || primaryAbi.contains("aarch64") -> "arm64"
        else -> "armhf"
    }

    /**
     * 是否应降低动效:v7a 等低端架构默认降级,用户也可在设置中强制开启。
     * @param userPref 用户在设置中的 reduceMotion 偏好
     */
    fun shouldReduceMotion(userPref: Boolean = false): Boolean = userPref || isLowEnd
}
