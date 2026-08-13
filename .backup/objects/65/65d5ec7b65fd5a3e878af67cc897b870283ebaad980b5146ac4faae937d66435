package com.mcserver.launcher.util

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 轻量日志：Logcat + 文件双写 */
object KLog {
    private const val TAG = "KazeSL"
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private var fileWriter: java.io.FileWriter? = null

    fun init(logFile: File) {
        try {
            fileWriter = java.io.FileWriter(logFile, true)
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun i(msg: String) { Log.i(TAG, msg); write("I", msg) }

    @Synchronized
    fun w(msg: String) { Log.w(TAG, msg); write("W", msg) }

    @Synchronized
    fun e(msg: String, t: Throwable? = null) {
        Log.e(TAG, msg, t)
        write("E", msg + (t?.let { " | ${it.message}" } ?: ""))
    }

    private fun write(level: String, msg: String) {
        try {
            fileWriter?.append("${fmt.format(Date())} $level $msg\n")?.flush()
        } catch (_: Exception) {
        }
    }
}