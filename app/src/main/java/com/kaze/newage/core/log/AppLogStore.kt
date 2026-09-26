package com.kaze.newage.core.log

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用自身的日志落盘（供「设置 → 诊断日志」查看）。
 *
 * 为什么这么做：Android 的 logcat 只在**内存环形缓冲**里（main/system/crash 各 256KiB），
 * 应用一崩、系统一回收就没了；普通应用也没有 READ_LOGS 权限，读不到别人的日志。
 * 但我们**可以**读自己的：以自身 UID 跑一个 `logcat` 子进程，它输出的正是本应用能看到的
 * 条目 —— 包含系统为我们的崩溃打的 `AndroidRuntime: FATAL EXCEPTION` 堆栈。
 * 把它接进文件即可，不用插电脑、不用 root 就能事后追溯。
 *
 * 保留策略：一天一个文件，最多留 [KEEP_FILES] 天；单文件超过 [MAX_BYTES] 时截断重开，
 * 避免服务端刷屏时把存储写满。
 */
class AppLogStore(private val context: Context) {

    companion object {
        /** 保留最近几天 */
        const val KEEP_FILES = 3
        /** 单个文件上限（超出后轮转，不删旧文件内容） */
        const val MAX_BYTES = 2L * 1024 * 1024
        private const val TAG = "AppLogStore"
        private val STAMP = SimpleDateFormat("yyyyMMdd", Locale.US)
    }

    val logDir: File get() = File(context.filesDir, "logs")

    private var job: Job? = null
    private var process: Process? = null

    /** 今天的日志文件 */
    fun currentFile(): File =
        File(logDir, "app-${STAMP.format(Date())}.log")

    /** 目前保留的日志文件（新的在前） */
    fun files(): List<File> =
        logDir.listFiles { f -> f.isFile && f.name.startsWith("app-") && f.name.endsWith(".log") }
            ?.sortedByDescending { it.name }
            ?: emptyList()

    /** 读最后 [maxLines] 行（跨文件从新到旧拼接） */
    suspend fun tail(maxLines: Int = 800): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        var remaining = maxLines
        for (f in files()) {
            if (remaining <= 0) break
            val lines = runCatching { f.readLines() }.getOrDefault(emptyList())
            val take = lines.takeLast(remaining)
            sb.insert(0, take.joinToString("\n") + "\n")
            remaining -= take.size
        }
        sb.toString()
    }

    /** 清空所有日志 */
    suspend fun clear() = withContext(Dispatchers.IO) {
        files().forEach { runCatching { it.delete() } }
        Unit
    }

    /**
     * 开始采集。重复调用无副作用；采集失败（例如受限设备上 logcat 不可执行）时静默放弃 ——
     * 这是诊断增强，不该影响应用可用性。
     */
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            runCatching {
                logDir.mkdirs()
                rotateIfNeeded()
                // 只取本应用进程的条目：以自身 UID 跑 logcat 本来也只能看到自己的，
                // 但显式 --pid 更干净，也避免系统广播刷屏
                val pid = android.os.Process.myPid()
                val p = ProcessBuilder("logcat", "-v", "threadtime", "--pid=$pid")
                    .redirectErrorStream(true)
                    .start()
                process = p
                p.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line -> appendLine(line) }
                }
            }.onFailure { e ->
                android.util.Log.w(TAG, "日志采集未启动：${e.message}")
            }
        }
    }

    fun stop() {
        runCatching { process?.destroy() }
        process = null
        job?.cancel()
        job = null
    }

    @Synchronized
    private fun appendLine(line: String) {
        runCatching {
            val f = currentFile()
            if (f.length() > MAX_BYTES) {
                // 轮转成带序号的旧文件，保留策略不变
                val rolled = File(logDir, "${f.name}.1")
                runCatching { rolled.delete() }
                f.renameTo(rolled)
            }
            f.appendText(line + "\n")
            trimOld()
        }
    }

    private fun rotateIfNeeded() {
        runCatching { trimOld() }
    }

    /** 只保留最近 [KEEP_FILES] 个文件 */
    private fun trimOld() {
        files().drop(KEEP_FILES).forEach { runCatching { it.delete() } }
    }
}
