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

    /**
     * 崩溃文本的格式化（抽成独立函数便于单测）。
     * 必须自带完整堆栈与 cause 链 —— 这份内容就是"闪退现场"的全部。
     */
    internal fun formatCrash(threadName: String, throwable: Throwable): String {
        val sw = java.io.StringWriter()
        throwable.printStackTrace(java.io.PrintWriter(sw))
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return buildString {
            appendLine("════════ 崩溃 ════════")
            appendLine("时间: $stamp")
            appendLine("线程: $threadName")
            appendLine(sw.toString().trimEnd())
            appendLine("═════════════════════")
        }
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
     *
     * **顺序很重要**：先把上一次会话的尾巴捞回来，再开始实时采集 ——
     * 采集器跑在应用进程里，进程一闪退它就跟着死，系统最后打的那段
     * `FATAL EXCEPTION` 根本来不及写进文件；而 logcat 的环形缓冲在进程死后**仍然在**，
     * 所以下次启动时捞一次就能补上（这正是"闪退也要有日志"的关键一步）。
     */
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            runCatching {
                logDir.mkdirs()
                rotateIfNeeded()
                salvagePreviousSession()
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

    /**
     * 把**上一次运行**遗留的日志尾巴补进今天的文件。
     *
     * 为什么要这么做：采集器活在应用进程里，闪退时它一起没了，崩溃现场（系统打的
     * FATAL EXCEPTION 堆栈、native 崩溃前后的几行）永远写不进文件。但 logcat 的环形缓冲
     * 在进程死后仍在内存里，所以启动时用 `--uid`（上一次的 pid 已经不可知）dump 一次，
     * 就能把上一段的末尾捞回来（800 行：崩溃到下次启动之间系统还会打不少字，窗口太小会把现场挤出缓冲）。
    已经采过的行会重复一次，可接受 —— 诊断文件里重复远好过缺失。
     */
    private fun salvagePreviousSession() {
        val out = runCatching {
            val p = ProcessBuilder("logcat", "-d", "-v", "threadtime", "--uid=${android.os.Process.myUid()}", "-t", "800")
                .redirectErrorStream(true)
                .start()
            val text = p.inputStream.bufferedReader().use { it.readText() }
            p.waitFor()
            text
        }.getOrNull().orEmpty().trim()
        if (out.isEmpty()) return
        appendRaw(
            buildString {
                appendLine("──────── 上一次运行的日志尾巴（闪退现场，启动时补捞）────────")
                appendLine(out)
                appendLine("──────── 本次运行 ────────")
            },
        )
    }

    /**
     * 安装崩溃处理器：未捕获异常时**同步**把堆栈写进日志文件再让系统接管。
     *
     * 采集器是异步的，进程被杀时它可能还没读到那一行；这里在同一个线程上直接落盘，
     * 保证"闪退"也一定留下现场。写文件本身也包在 runCatching 里 ——
     * 崩溃路径上再抛异常会把原始崩溃信息覆盖掉，那才是最糟的结果。
     */
    fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { appendRaw(formatCrash(thread.name, throwable)) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun stop() {
        runCatching { process?.destroy() }
        process = null
        job?.cancel()
        job = null
    }

    /** 直接追加一段原文（崩溃堆栈 / 补捞结果），不做行前缀处理 */
    @Synchronized
    fun appendRaw(text: String) {
        runCatching {
            logDir.mkdirs()
            currentFile().appendText(text + "\n")
            trimOld()
        }
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
