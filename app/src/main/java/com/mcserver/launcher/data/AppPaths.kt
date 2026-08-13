package com.mcserver.launcher.data

import android.content.Context
import java.io.File

/**
 * 全局路径约定：全部落在应用私有目录（免存储权限、支持任意安卓版本），
 * 备份等对外产物通过 SAF / MediaStore 导出。
 */
object AppPaths {
    lateinit var root: File
    lateinit var instancesDir: File
    lateinit var runtimeDir: File
    lateinit var linuxDir: File
    lateinit var logsDir: File
    lateinit var backupsDir: File
    lateinit var downloadsDir: File

    fun init(context: Context) {
        root = context.filesDir
        instancesDir = File(root, "instances").apply { mkdirs() }
        runtimeDir = File(root, "runtime").apply { mkdirs() }     // 导入的 JRE
        linuxDir = File(root, "linux").apply { mkdirs() }         // 内置 Linux 环境（proot + rootfs + JDK）
        logsDir = File(root, "logs").apply { mkdirs() }
        backupsDir = File(root, "backups").apply { mkdirs() }
        downloadsDir = File(root, "downloads").apply { mkdirs() }
    }

    fun instanceDir(id: String): File = File(instancesDir, id).apply { mkdirs() }
    fun javaBinary(runtime: File): String {
        val bin = File(runtime, "bin/java")
        if (bin.exists()) return bin.absolutePath
        val bin2 = File(runtime, "bin/java.sh")
        return if (bin2.exists()) bin2.absolutePath else "java"
    }
    fun instancesJson(): File = File(root, "instances.json")
}