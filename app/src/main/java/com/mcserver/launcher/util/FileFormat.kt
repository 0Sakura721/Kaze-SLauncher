package com.mcserver.launcher.util

import java.io.File

/**
 * 文件 / 字节相关共享工具。
 * 集中 formatSize 等小函数,避免在多个 Screen 中重复定义。
 */
object FileFormat {

    /** 字节数格式化:KB/MB/GB,带 1 位小数 */
    fun size(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format("%.1f GB", bytes / 1024.0 / 1024 / 1024)
        bytes >= 1024L * 1024 -> String.format("%.1f MB", bytes / 1024.0 / 1024)
        bytes >= 1024L -> String.format("%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    /** 递归计算目录大小 */
    fun dirSize(dir: File): Long =
        dir.listFiles()?.sumOf { if (it.isDirectory) dirSize(it) else it.length() } ?: 0L
}
