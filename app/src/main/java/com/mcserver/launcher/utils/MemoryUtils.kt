package com.mcserver.launcher.utils

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/**
 * 获取设备内存信息（单位：MB）
 */
class MemoryInfo(
    val totalMB: Long,
    val availableMB: Long,
    val usedMB: Long
)

fun Context.getDeviceMemoryInfo(): MemoryInfo {
    val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        ?: return MemoryInfo(0, 0, 0)
    val memoryInfo = ActivityManager.MemoryInfo()
    activityManager.getMemoryInfo(memoryInfo)

    val totalMB = memoryInfo.totalMem / (1024 * 1024)
    val availableMB = memoryInfo.availMem / (1024 * 1024)
    val usedMB = totalMB - availableMB

    return MemoryInfo(totalMB, availableMB, usedMB)
}
