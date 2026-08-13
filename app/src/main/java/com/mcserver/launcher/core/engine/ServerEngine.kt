package com.mcserver.launcher.core.engine

import android.content.Context
import com.mcserver.launcher.data.AppPaths
import com.mcserver.launcher.data.RuntimeStats
import com.mcserver.launcher.data.ServerInstance
import com.mcserver.launcher.data.ServerState
import com.mcserver.launcher.util.KLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 服务端进程引擎（单例，全局唯一运行实例）。
 *
 * 设计要点：
 * - ProcessBuilder 直接以 Android 端 JRE 启动服务端（无需 proot/rootfs）
 * - 输出流逐行泵入 MutableSharedFlow，控制台 UI 订阅渲染
 * - 写 stdin 实现命令交互；stop 先优雅关停（发 stop 命令）再强杀
 * - CPU/内存从 /proc/<pid> 读取，TPS 通过定时发送 "tps" 命令解析
 */
object ServerEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<ServerState>(ServerState.Idle)
    val state: StateFlow<ServerState> = _state.asStateFlow()

    private val _logs = MutableSharedFlow<String>(extraBufferCapacity = 4096)
    val logs: SharedFlow<String> = _logs.asSharedFlow()

    private val _stats = MutableStateFlow(RuntimeStats())
    val stats: StateFlow<RuntimeStats> = _stats.asStateFlow()

    /** 环形日志缓冲（UI 首次进入时回放） */
    private val logBuffer = ConcurrentLinkedQueue<String>()
    const val BUFFER_CAP = 2000

    private var process: Process? = null
    private var runningInstanceId: String? = null
    private var startedAtMs = 0L
    private var monitorJob: kotlinx.coroutines.Job? = null
    private var tpsJob: kotlinx.coroutines.Job? = null

    fun runningInstanceId(): String? = runningInstanceId

    fun logHistory(): List<String> = logBuffer.toList()

    fun isRunning(): Boolean = _state.value is ServerState.Running

    /** 启动服务端 */
    fun start(context: Context, inst: ServerInstance, javaPath: String): Boolean {
        if (_state.value !is ServerState.Idle && _state.value !is ServerState.Crashed) {
            KLog.w("引擎忙，忽略启动请求")
            return false
        }
        val dir = AppPaths.instanceDir(inst.id)
        val coreFile = File(dir, inst.coreFileName)
        if (inst.coreFileName.isBlank() || !coreFile.exists()) {
            KLog.e("核心 jar 不存在: ${coreFile.absolutePath}")
            _state.value = ServerState.Crashed(-2)
            return false
        }
        // EULA 检查与自动同意（用户已在 UI 勾选）
        val eula = File(dir, "eula.txt")
        if (!eula.exists() || !eula.readText().contains("eula=true")) {
            eula.writeText("eula=true\n")
        }
        try {
            _state.value = ServerState.Starting
            logBuffer.clear()
            val javaArgs = mutableListOf<String>()
            inst.jvmArgs.split(" ").filter { it.isNotBlank() }.forEach(javaArgs::add)
            val cmd = mutableListOf<String>()
            cmd.add(javaPath)
            cmd.addAll(javaArgs)
            cmd.add("-jar")
            cmd.add(coreFile.absolutePath)
            cmd.add("nogui")

            val pb = ProcessBuilder(cmd)
                .directory(dir)
                .redirectErrorStream(true)
            pb.environment()["HOME"] = dir.absolutePath
            val p = pb.start()
            process = p
            runningInstanceId = inst.id
            startedAtMs = System.currentTimeMillis()
            _state.value = ServerState.Running(p.pidCompat())
            _stats.value = RuntimeStats(uptimeMs = 0L)
            KLog.i("服务端已启动 pid=${p.pidCompat()} cmd=${cmd.joinToString(" ")}")

            // 输出泵
            scope.launch {
                try {
                    BufferedReader(InputStreamReader(p.inputStream)).use { reader ->
                        var line: String?
                        while (isActive) {
                            line = reader.readLine() ?: break
                            pushLine(line)
                        }
                    }
                } catch (e: Exception) {
                    if (isActive) KLog.w("输出流读取结束: ${e.message}")
                }
                // 进程退出
                val code = p.waitFor()
                process = null
                runningInstanceId = null
                monitorJob?.cancel(); tpsJob?.cancel()
                _stats.value = RuntimeStats()
                _state.value = if (code == 0) ServerState.Idle else ServerState.Crashed(code)
                pushLine("[引擎] 进程已退出，代码 $code")
                KLog.i("服务端进程退出 code=$code")
            }

            // 监控循环
            monitorJob = scope.launch {
                val pid = p.pidCompat()
                var lastTotal = 0L; var lastAt = 0L
                while (isActive) {
                    delay(1000)
                    try {
                        val cpu = ProcStats.cpuPercent(pid, lastTotal, lastAt)
                        lastTotal = cpu.total; lastAt = cpu.at
                        val mem = ProcStats.memKb(pid) / 1024
                        _stats.value = _stats.value.copy(
                            cpuPercent = cpu.percent,
                            memMb = mem,
                            uptimeMs = System.currentTimeMillis() - startedAtMs,
                        )
                    } catch (_: Exception) {
                        // 进程已退出等情况
                    }
                }
            }

            // TPS 探测（Paper 系支持 tps 命令）
            tpsJob = scope.launch {
                delay(15_000) // 等服务端完全起来
                while (isActive) {
                    sendCommand("tps")
                    delay(10_000)
                }
            }
            return true
        } catch (e: Exception) {
            KLog.e("启动服务端失败", e)
            process = null
            runningInstanceId = null
            _state.value = ServerState.Crashed(-1)
            pushLine("[引擎] 启动失败: ${e.message}")
            return false
        }
    }

    /** 优雅停止：先发 stop 命令，超时后强杀 */
    fun stop(graceMs: Long = 15_000) {
        val p = process ?: run {
            _state.value = ServerState.Idle
            return
        }
        if (_state.value is ServerState.Stopping) return
        _state.value = ServerState.Stopping
        scope.launch {
            try {
                writeCommand("stop")
                val deadline = System.currentTimeMillis() + graceMs
                while (System.currentTimeMillis() < deadline && p.isAlive) {
                    delay(300)
                }
                if (p.isAlive) {
                    KLog.w("优雅停止超时，强制结束")
                    p.destroyForcibly()
                }
            } catch (e: Exception) {
                KLog.e("停止服务端异常", e)
                p.destroyForcibly()
            }
        }
    }

    /** 发送一条控制台命令（自动补换行） */
    fun sendCommand(cmd: String): Boolean {
        val p = process ?: return false
        if (!p.isAlive) return false
        return try {
            writeCommand(cmd)
            true
        } catch (e: Exception) {
            KLog.w("发送命令失败: ${e.message}")
            false
        }
    }

    private fun writeCommand(cmd: String) {
        process?.outputStream?.use { os ->
            os.write((cmd.trimEnd() + "\n").toByteArray(Charsets.UTF_8))
            os.flush()
        }
    }

    private fun pushLine(line: String) {
        logBuffer.add(line)
        while (logBuffer.size > BUFFER_CAP) logBuffer.poll()
        _logs.tryEmit(line)
        // TPS 解析: "TPS from last 1m, 5m, 15m: 20.0, 19.8, 19.9"
        if (line.contains("TPS from last")) {
            try {
                val nums = line.substringAfter(':')
                    .split(',').mapNotNull { it.trim().toFloatOrNull() }
                if (nums.isNotEmpty()) {
                    _stats.value = _stats.value.copy(tps = nums[0])
                }
            } catch (_: Exception) {
            }
        }
        // 玩家数解析: "There are X of a max of Y players online"
        val m = Regex("There are (\\d+) of a max of (\\d+) players").find(line)
        m?.let {
            _stats.value = _stats.value.copy(playerCount = it.groupValues[1].toIntOrNull() ?: 0)
        }
    }

    fun shutdown() {
        stop(graceMs = 3_000)
        scope.cancel()
    }

    /** Android 的 java.lang.Process 无 pid()（Java9+ API），通过反射读取 */
    private fun Process.pidCompat(): Int = try {
        val f = javaClass.getDeclaredField("pid")
        f.isAccessible = true
        f.getInt(this)
    } catch (e: Exception) {
        KLog.w("无法获取子进程 pid: ${e.message}")
        0
    }
}

/** /proc 读取工具 */
private object ProcStats {
    data class Cpu(val percent: Float, val total: Long, val at: Long)

    private fun readStat(pid: Int): Pair<Long, Long> {
        val text = File("/proc/$pid/stat").readText()
        val close = text.lastIndexOf(')')
        val fields = text.substring(close + 2).trim().split(' ')
        val utime = fields.getOrNull(11)?.toLongOrNull() ?: 0L   // 13 = utime
        val stime = fields.getOrNull(12)?.toLongOrNull() ?: 0L   // 14 = stime
        return utime to stime
    }

    fun cpuPercent(pid: Int, lastTotal: Long, lastAt: Long): Cpu {
        val (u, s) = readStat(pid)
        val total = u + s
        val now = System.currentTimeMillis()
        val percent = if (lastAt > 0 && now > lastAt) {
            ((total - lastTotal) * 1000f / (now - lastAt) / 100f * 100f)
                .coerceIn(0f, 100f)
        } else 0f
        return Cpu(percent, total, now)
    }

    fun memKb(pid: Int): Long {
        val text = File("/proc/$pid/status").readText()
        val m = Regex("VmRSS:\\s+(\\d+)").find(text)
        return m?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    }
}