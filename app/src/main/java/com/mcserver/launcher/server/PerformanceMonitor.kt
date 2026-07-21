package com.mcserver.launcher.server

import android.os.*
import android.util.Log
import com.mcserver.launcher.McApplication
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.RandomAccessFile

/**
 * 性能监控器 — 实时采集 CPU、内存、TPS、磁盘 I/O 等指标。
 * 借鉴 Pterodactyl 的资源监控面板设计 + MCSManager 的诊断面板。
 *
 * 功能：
 * - 进程级 CPU 使用率（通过 /proc/[pid]/stat）
 * - 进程级内存使用（VmRSS / VmSize）
 * - 系统级 CPU 使用率（/proc/stat 差值）
 * - TPS / MSPT 检测（Spark 插件 + Paper 内置 + Plan 插件）
 * - 磁盘 I/O 监控
 * - 服务器线程数
 * - 历史数据保留（最近 60 个采样点用于图表）
 */
class PerformanceMonitor private constructor() {

    companion object {
        private const val TAG = "PerformanceMonitor"
        val instance: PerformanceMonitor by lazy { PerformanceMonitor() }
        const val HISTORY_SIZE = 60  // 保留最近 60 个采样点（2 分钟）
    }

    data class Metrics(
        val cpuPercent: Float = 0f,
        val processCpuPercent: Float = 0f,  // 服务器进程 CPU 占比
        val memoryUsedMB: Long = 0,
        val memoryTotalMB: Long = 0,
        val tps: Float = 20f,
        val mspt: Float = 50f,
        val tps1m: Float = 20f,   // Spark: 1 分钟 TPS
        val tps5m: Float = 20f,   // Spark: 5 分钟 TPS
        val tps15m: Float = 20f,  // Spark: 15 分钟 TPS
        val playerCount: Int = 0,
        val uptimeSeconds: Long = 0,
        val threadCount: Int = 0,
        val diskReadMB: Long = 0,
        val diskWriteMB: Long = 0
    )

    data class MetricsHistory(
        val timestamps: List<Long> = emptyList(),
        val cpuHistory: List<Float> = emptyList(),
        val memoryHistory: List<Long> = emptyList(),
        val tpsHistory: List<Float> = emptyList()
    )

    private val _metrics = MutableStateFlow(Metrics())
    val metrics: StateFlow<Metrics> = _metrics.asStateFlow()

    private val _history = MutableStateFlow(MetricsHistory())
    val history: StateFlow<MetricsHistory> = _history.asStateFlow()

    private var monitorJob: Job? = null
    private var startTime = 0L

    private val context get() = McApplication.instance

    // 进程 CPU 历史（用于差值计算）
    private var lastProcessCpuTime = 0L
    private var lastProcessCpuSampleTime = 0L

    fun startMonitoring() {
        monitorJob?.cancel()
        startTime = System.currentTimeMillis()
        lastProcessCpuTime = 0L
        lastProcessCpuSampleTime = 0L
        monitorJob = CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                _metrics.value = collectMetrics()
                updateHistory()
                delay(2000)
            }
        }
    }

    fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
        _metrics.value = Metrics()
        _history.value = MetricsHistory()
        lastProcessCpuTime = 0L
    }

    private fun collectMetrics(): Metrics {
        val uptime = (System.currentTimeMillis() - startTime) / 1000
        val memInfo = collectServerMemoryInfo()
        val sysCpu = collectSystemCpuUsage()
        val procCpu = collectProcessCpuUsage()
        val tpsMetrics = collectTpsEstimate()
        val threads = collectThreadCount()
        val diskIO = collectDiskIO()

        return Metrics(
            cpuPercent = sysCpu,
            processCpuPercent = procCpu,
            memoryUsedMB = memInfo.first,
            memoryTotalMB = memInfo.second,
            tps = tpsMetrics.first,
            mspt = tpsMetrics.second,
            tps1m = tps1m,
            tps5m = tps5m,
            tps15m = tps15m,
            playerCount = ServerManager.instance.serverStatus.value.playerCount,
            uptimeSeconds = uptime,
            threadCount = threads,
            diskReadMB = diskIO.first,
            diskWriteMB = diskIO.second
        )
    }

    private fun updateHistory() {
        val current = _history.value
        val now = System.currentTimeMillis()
        val m = _metrics.value

        val newTimestamps = (current.timestamps + now).takeLast(HISTORY_SIZE)
        val newCpu = (current.cpuHistory + m.cpuPercent).takeLast(HISTORY_SIZE)
        val newMem = (current.memoryHistory + m.memoryUsedMB).takeLast(HISTORY_SIZE)
        val newTps = (current.tpsHistory + m.tps).takeLast(HISTORY_SIZE)

        _history.value = MetricsHistory(newTimestamps, newCpu, newMem, newTps)
    }

    // ─── 服务器进程内存 ───

    private fun getServerPid(): Int? {
        return try {
            val serverDir = ProotServerManager.serverDir(context)
            val pidFile = File(serverDir, "mcserver.pid")
            if (!pidFile.exists()) return null
            pidFile.readText().trim().toIntOrNull()
        } catch (e: Exception) {
            Log.d(TAG, "getServerPid failed", e)
            null
        }
    }

    private fun collectServerMemoryInfo(): Pair<Long, Long> {
        try {
            val pid = getServerPid() ?: return fallbackMemoryInfo()
            val statusFile = File("/proc/$pid/status")
            if (!statusFile.exists()) return fallbackMemoryInfo()

            val statusLines = statusFile.readLines()
            val vmRss = statusLines
                .firstOrNull { it.startsWith("VmRSS:") }
                ?.substringAfter(":")
                ?.trim()
                ?.replace("kB", "")
                ?.trim()
                ?.toLongOrNull() ?: return fallbackMemoryInfo()

            val vmSize = statusLines
                .firstOrNull { it.startsWith("VmSize:") }
                ?.substringAfter(":")
                ?.trim()
                ?.replace("kB", "")
                ?.trim()
                ?.toLongOrNull() ?: (vmRss * 2)

            return (vmRss / 1024) to (vmSize / 1024)
        } catch (e: Exception) {
            Log.d(TAG, "collectServerMemoryInfo failed", e)
            return fallbackMemoryInfo()
        }
    }

    private fun fallbackMemoryInfo(): Pair<Long, Long> {
        val runtime = Runtime.getRuntime()
        val usedMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val totalMB = runtime.maxMemory() / (1024 * 1024)
        return usedMB to totalMB
    }

    // ─── 系统 CPU ───

    private fun collectSystemCpuUsage(): Float {
        return try {
            val stat1 = readProcStat()
            Thread.sleep(200) // 缩短采样间隔以更快响应
            val stat2 = readProcStat()
            if (stat1 == null || stat2 == null) return 0f

            val total1 = stat1.total()
            val idle1 = stat1.idle + stat1.iowait
            val total2 = stat2.total()
            val idle2 = stat2.idle + stat2.iowait

            val totalDiff = total2 - total1
            val idleDiff = idle2 - idle1
            if (totalDiff == 0L) return 0f

            ((totalDiff - idleDiff).toFloat() / totalDiff * 100f).coerceIn(0f, 100f)
        } catch (e: Exception) {
            Log.d(TAG, "collectSystemCpuUsage failed", e)
            0f
        }
    }

    private data class CpuStat(
        val user: Long, val nice: Long, val system: Long,
        val idle: Long, val iowait: Long, val irq: Long,
        val softirq: Long, val steal: Long = 0
    ) {
        fun total(): Long = user + nice + system + idle + iowait + irq + softirq + steal
    }

    private fun readProcStat(): CpuStat? {
        return try {
            val line = RandomAccessFile("/proc/stat", "r").use { it.readLine() }
            val parts = line.removePrefix("cpu ").trim().split("\\s+".toRegex())
            if (parts.size < 8) return null
            CpuStat(
                parts[0].toLong(), parts[1].toLong(), parts[2].toLong(),
                parts[3].toLong(), parts[4].toLong(), parts[5].toLong(),
                parts[6].toLong(), if (parts.size > 7) parts[7].toLong() else 0
            )
        } catch (e: Exception) {
            Log.d(TAG, "readProcStat failed", e)
            null
        }
    }

    // ─── 进程级 CPU（借鉴 Pterodactyl 的进程监控） ───

    private fun collectProcessCpuUsage(): Float {
        return try {
            val pid = getServerPid() ?: return 0f
            val statFile = File("/proc/$pid/stat")
            if (!statFile.exists()) return 0f

            val statContent = statFile.readText()
            // /proc/[pid]/stat 格式: pid (comm) state ... utime stime cutime cstime ...
            // 需要正确处理进程名中的空格（括号包裹）
            val closeParen = statContent.lastIndexOf(')')
            if (closeParen < 0) return 0f
            val after = statContent.substring(closeParen + 2).split(" ")
            if (after.size < 14) return 0f

            val utime = after[11].toLongOrNull() ?: return 0f  // 用户态时间
            val stime = after[12].toLongOrNull() ?: return 0f  // 内核态时间
            val cutime = after[13].toLongOrNull() ?: 0         // 子进程用户态
            val cstime = after[14].toLongOrNull() ?: 0         // 子进程内核态

            val totalTime = utime + stime + cutime + cstime
            val now = System.currentTimeMillis()

            if (lastProcessCpuTime > 0 && lastProcessCpuSampleTime > 0) {
                val timeDiff = now - lastProcessCpuSampleTime
                val cpuDiff = totalTime - lastProcessCpuTime
                if (timeDiff > 0) {
                    // CLK_TCK 通常是 100 (Hz)，即每个 tick = 10ms
                    // cpuPercent = (cpuDiff * 1000 / timeDiff) / CLK_TCK * 100
                    val clkTck = 100L // sysconf(_SC_CLK_TCK)
                    val cpuPercent = (cpuDiff * 1000f / timeDiff / clkTck * 100f)
                        .coerceIn(0f, 100f * Runtime.getRuntime().availableProcessors())
                    lastProcessCpuTime = totalTime
                    lastProcessCpuSampleTime = now
                    return cpuPercent
                }
            }
            lastProcessCpuTime = totalTime
            lastProcessCpuSampleTime = now
            return 0f
        } catch (e: Exception) {
            Log.d(TAG, "collectProcessCpuUsage failed", e)
            0f
        }
    }

    // ─── 线程数 ───

    private fun collectThreadCount(): Int {
        return try {
            val pid = getServerPid() ?: return 0
            val statusFile = File("/proc/$pid/status")
            if (!statusFile.exists()) return 0
            statusFile.readLines()
                .firstOrNull { it.startsWith("Threads:") }
                ?.substringAfter(":")
                ?.trim()
                ?.toIntOrNull() ?: 0
        } catch (e: Exception) {
            Log.d(TAG, "collectThreadCount failed", e)
            0
        }
    }

    // ─── 磁盘 I/O ───

    private var lastIoReadBytes = 0L
    private var lastIoWriteBytes = 0L
    private var lastIoSampleTime = 0L

    private fun collectDiskIO(): Pair<Long, Long> {
        return try {
            val pid = getServerPid() ?: return 0L to 0L
            val ioFile = File("/proc/$pid/io")
            if (!ioFile.exists()) return 0L to 0L

            val lines = ioFile.readLines()
            val readBytes = lines.firstOrNull { it.startsWith("read_bytes:") }
                ?.substringAfter(":")
                ?.trim()
                ?.toLongOrNull() ?: 0L
            val writeBytes = lines.firstOrNull { it.startsWith("write_bytes:") }
                ?.substringAfter(":")
                ?.trim()
                ?.toLongOrNull() ?: 0L

            val now = System.currentTimeMillis()
            val elapsed = if (lastIoSampleTime > 0) (now - lastIoSampleTime) / 1000f else 0f

            val readRate = if (elapsed > 0 && lastIoReadBytes > 0)
                ((readBytes - lastIoReadBytes) / (1024f * 1024f) / elapsed).toLong().coerceAtLeast(0)
            else 0L
            val writeRate = if (elapsed > 0 && lastIoWriteBytes > 0)
                ((writeBytes - lastIoWriteBytes) / (1024f * 1024f) / elapsed).toLong().coerceAtLeast(0)
            else 0L

            lastIoReadBytes = readBytes
            lastIoWriteBytes = writeBytes
            lastIoSampleTime = now

            readRate to writeRate
        } catch (e: Exception) {
            Log.d(TAG, "collectDiskIO failed", e)
            0L to 0L
        }
    }

    // ─── TPS / MSPT ───

    private var estimatedTps = 20f
    private var estimatedMspt = 50f
    private var tps1m = 20f
    private var tps5m = 20f
    private var tps15m = 20f

    private fun collectTpsEstimate(): Pair<Float, Float> {
        return estimatedTps to estimatedMspt
    }

    /**
     * 从日志行解析 TPS/MSPT 信息。
     * 支持:
     * - Spark 插件: "TPS from last 1m, 5m, 15m: 20.0, 19.8, 19.5"
     * - Paper 内置: "Mean TPS: 19.85" / "Mean tick time: 1.234 ms" / "[Paper] TPS: 19.8"
     * - Plan 插件: "[Plan] Analysis results"
     * - Purpur: "Current TPS = 20.0"
     * - Fabric/Carpet: "TPS: 20.0 MSPT: 50.0"
     * - 通用格式: 任何含 "TPS:" + "ms" 的行
     */
    fun feedLogLine(line: String) {
        try {
            // Spark 插件 TPS（多时间段）
            if (line.contains("TPS from last")) {
                val match = Regex("""(\d+\.\d+)[,\s]*(\d+\.\d+)[,\s]*(\d+\.\d+)""").find(line)
                match?.let {
                    tps1m = it.groupValues[1].toFloatOrNull()?.coerceIn(0f, 20f) ?: tps1m
                    tps5m = it.groupValues[2].toFloatOrNull()?.coerceIn(0f, 20f) ?: tps5m
                    tps15m = it.groupValues[3].toFloatOrNull()?.coerceIn(0f, 20f) ?: tps15m
                    estimatedTps = tps1m  // 优先用 1 分钟 TPS
                }
            }

            // Paper 内置 TPS（含 [Paper] TPS: 格式）
            if (line.contains("Mean TPS") || line.contains("TPS:")) {
                val match = Regex("""(?:Mean\s+)?TPS[:\s]*(\d+\.\d+)""").find(line)
                match?.let {
                    estimatedTps = it.groupValues[1].toFloatOrNull()?.coerceIn(0f, 20f) ?: estimatedTps
                }
            }

            // Purpur TPS
            if (line.contains("Current TPS")) {
                val match = Regex("""Current TPS\s*=\s*(\d+\.\d+)""").find(line)
                match?.let {
                    estimatedTps = it.groupValues[1].toFloatOrNull()?.coerceIn(0f, 20f) ?: estimatedTps
                }
            }

            // Fabric/Carpet mod TPS: "TPS: 20.0 MSPT: 50.0"
            if (line.contains("TPS:") && line.contains("MSPT:") || line.contains("mspt")) {
                val tpsMatch = Regex("""TPS:\s*(\d+\.\d+)""").find(line)
                tpsMatch?.let {
                    estimatedTps = it.groupValues[1].toFloatOrNull()?.coerceIn(0f, 20f) ?: estimatedTps
                }
                val msptMatch = Regex("""MSPT:\s*(\d+\.?\d*)""").find(line)
                msptMatch?.let {
                    estimatedMspt = it.groupValues[1].toFloatOrNull()?.coerceIn(0f, 1000f) ?: estimatedMspt
                }
            }

            // Paper MSPT
            if (line.contains("MSPT") || line.contains("Mean tick time")) {
                val match = Regex("""(?:MSPT|Mean tick time)[:\s]*(\d+\.?\d*)""").find(line)
                match?.let {
                    estimatedMspt = it.groupValues[1].toFloatOrNull()?.coerceIn(0f, 1000f) ?: estimatedMspt
                }
            }

            // 通用 TPS + ms 格式：任何包含 "TPS: XX ms" 的行（自定义插件）
            if (line.contains("TPS:") && line.contains("ms")) {
                val match = Regex("""TPS:\s*(\d+\.\d+)""").find(line)
                match?.let {
                    estimatedTps = it.groupValues[1].toFloatOrNull()?.coerceIn(0f, 20f) ?: estimatedTps
                }
            }

            // Server tick 完成时间
            if (line.contains("Done (") && (line.contains("s)!") || line.contains("ms)"))) {
                val match = Regex("""Done\s*\(\d+\.?\d*[sm]s?!.*?(\d+\.?\d*)ms""").find(line)
                match?.let {
                    val ms = it.groupValues[1].toFloatOrNull()
                    if (ms != null && ms < 1000f) {
                        estimatedMspt = ms
                        estimatedTps = (1000f / ms).coerceAtMost(20f)
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "feedLogLine failed", e)
        }
    }
}
