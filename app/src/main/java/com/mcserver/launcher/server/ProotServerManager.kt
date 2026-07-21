package com.mcserver.launcher.server

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.*
import android.provider.OpenableColumns
import android.util.Log
import com.mcserver.launcher.McApplication
import com.mcserver.launcher.data.ServerConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.*
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Linux 环境状态
 */
enum class LinuxEnvState2 { NOT_READY, READY, JAVA_MISSING }

/**
 * 通过 proot + Ubuntu 环境执行 Java JAR 文件
 * 替代原方案，完全内置运行环境
 */
class ProotServerManager {

    companion object {
        const val TAG = "ProotServerManager"

        /** 共享工作目录 */
        fun serverDir(ctx: Context): File {
            val dir = File(Environment.getExternalStorageDirectory(), "mcserver")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

        /** 检查 Linux 环境是否就绪 */
        fun isLinuxReady(): Boolean {
            return LinuxEnvironmentManager.isEnvironmentReady()
        }
    }

    private val context: Context get() = McApplication.instance
    var scope: CoroutineScope? = null
    private val isRunning = AtomicBoolean(false)
    private var tailJob: Job? = null
    private var logFile: File? = null
    private var serverProcess: java.lang.Process? = null

    private var rconClient: RconClient? = null
    private var rconReady = false

    var onServerExited: (() -> Unit)? = null

    private val onlinePlayers = mutableSetOf<String>()
    private val _players = MutableStateFlow<List<String>>(emptyList())
    val players: StateFlow<List<String>> = _players.asStateFlow()

    private val _consoleOutput = MutableSharedFlow<String>(
        replay = 500, extraBufferCapacity = 100,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val consoleOutput: SharedFlow<String> = _consoleOutput.asSharedFlow()

    private val _stateChanged = MutableSharedFlow<Boolean>(
        replay = 1, extraBufferCapacity = 5,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val stateChanged: SharedFlow<Boolean> = _stateChanged.asSharedFlow()

    val running: Boolean get() = isRunning.get()

    private fun validateShellSafe(s: String): Boolean {
        val dangerous = listOf(";", "`", "$(", "|", ">", "<", "&&", "||", "\n", "\r")
        for (d in dangerous) {
            if (s.contains(d)) return false
        }
        return true
    }

    private fun escapeSingleQuote(s: String): String {
        return s.replace("'", "'\\''")
    }

    fun isServerProcessAlive(): Boolean {
        return try {
            val dir = serverDir(context)
            val pidFile = File(dir, "mcserver.pid")
            if (!pidFile.exists()) return false
            val pid = pidFile.readText().trim().toIntOrNull() ?: return false
            val procDir = File("/proc/$pid")
            if (!procDir.exists() || !procDir.isDirectory) return false
            try {
                val cmdline = File("/proc/$pid/cmdline")
                if (cmdline.exists()) {
                    val content = cmdline.readText()
                    if (content.contains("java", ignoreCase = true)) return true
                }
                val comm = File("/proc/$pid/comm")
                if (comm.exists()) {
                    val content = comm.readText()
                    if (content.contains("java", ignoreCase = true)) return true
                }
            } catch (_: Exception) {}
            true
        } catch (_: Exception) { false }
    }

    fun reconnectToRunningServer(): Boolean {
        return try {
            if (!isServerProcessAlive()) return false
            val dir = serverDir(context)
            val file = File(dir, "server.log")
            if (!file.exists()) {
                logFile = null
                return false
            }
            logFile = file
            isRunning.set(true)
            _stateChanged.tryEmit(true)
            emit("> 检测到服务器仍在运行，正在恢复连接...")
            startTailLog(file)
            emit("> 已恢复与服务器的连接")
            true
        } catch (e: Exception) {
            isRunning.set(false)
            false
        }
    }

    fun checkState(): LinuxEnvState2 {
        if (!LinuxEnvironmentManager.isEnvironmentReady()) return LinuxEnvState2.NOT_READY
        val hasJava = listOf(21, 17, 11, 8).any { LinuxEnvironmentManager.isJdkInstalled(it) }
        if (!hasJava) return LinuxEnvState2.JAVA_MISSING
        return LinuxEnvState2.READY
    }

    private suspend fun ensureLocalJarPath(jarPath: String): String {
        if (!jarPath.startsWith("content://")) return jarPath
        return withContext(Dispatchers.IO) {
            val uri = Uri.parse(jarPath)
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
            var name = "server.jar"
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) name = cursor.getString(idx)
                    }
                }
                val target = File(context.filesDir, "servers/$name")
                target.parentFile?.mkdirs()
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output) }
                } ?: throw SecurityException("无法读取")
                target.absolutePath
            } catch (e: SecurityException) {
                throw SecurityException(
                    "JAR 文件权限已过期，请到「配置」页面重新选择一次 JAR 文件"
                )
            }
        }
    }

    private fun getJavaBinary(): String? {
        val preferred = ServerManager.instance.selectedJreVersion.toIntOrNull()
        if (preferred != null && LinuxEnvironmentManager.isJdkInstalled(preferred)) {
            return LinuxEnvironmentManager.getJavaPath(preferred)
        }
        listOf(21, 17, 11, 8).forEach { ver ->
            if (LinuxEnvironmentManager.isJdkInstalled(ver)) {
                return LinuxEnvironmentManager.getJavaPath(ver)
            }
        }
        return null
    }

    suspend fun startServer(config: ServerConfig, javaPath: String? = null): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                if (!LinuxEnvironmentManager.isEnvironmentReady()) {
                    emit("> Linux 环境未初始化，请先完成环境部署")
                    return@withContext Result.failure(Exception("Linux 环境未就绪"))
                }

                val javaBin = javaPath ?: getJavaBinary()
                if (javaBin == null || !File(javaBin).exists()) {
                    emit("> 未找到可用的 Java 运行环境，请先安装 JDK")
                    return@withContext Result.failure(Exception("Java 未安装"))
                }

                emit("> 准备 Linux 环境...")

                val serverDir = serverDir(context)
                onlinePlayers.clear()
                _players.value = emptyList()
                prepareServerProperties(serverDir, config)
                prepareEula(serverDir)

                val localJarPath: String
                try {
                    localJarPath = ensureLocalJarPath(config.jarPath)
                } catch (e: SecurityException) {
                    emit("> ${e.message}")
                    emit("> 提示：选完 JAR 文件后记得点击「保存配置」")
                    return@withContext Result.failure(e)
                }
                val jarFile = File(localJarPath)
                val targetJar = File(serverDir, jarFile.name)

                if (!targetJar.exists() || jarFile.lastModified() > targetJar.lastModified()) {
                    emit("> 复制 JAR 到工作目录: ${targetJar.absolutePath}")
                    jarFile.copyTo(targetJar, overwrite = true)
                }

                val scriptFile = File(serverDir, "start.sh")
                val logPath = File(serverDir, "server.log").absolutePath
                val pipePath = File(serverDir, "cmdpipe").absolutePath

                val xmx = config.maxRamMB
                val xms = config.minRamMB
                val noguiArg = if (config.nogui) "nogui" else ""
                val args = config.additionalArgs.trim()
                if (args.isNotBlank() && !validateShellSafe(args)) {
                    return@withContext Result.failure(IllegalArgumentException("启动参数包含不安全的 shell 元字符"))
                }

                val pidFile = File(serverDir, "mcserver.pid").absolutePath
                val safeServerDir = escapeSingleQuote(serverDir.absolutePath)
                val safePipePath = escapeSingleQuote(pipePath)
                val safeLogPath = escapeSingleQuote(logPath)
                val safePidFile = escapeSingleQuote(pidFile)
                val safeJarName = escapeSingleQuote(targetJar.name)
                val safeJavaBin = escapeSingleQuote(javaBin)

                val script = buildString {
                    appendLine("#!/bin/sh")
                    appendLine("cd '$safeServerDir'")
                    appendLine("rm -f '$safePipePath'")
                    appendLine("mkfifo '$safePipePath'")
                    appendLine("echo '--- Minecraft Server Started ---' > '$safeLogPath'")
                    appendLine(": > '$safePidFile'")
                    appendLine("$safeJavaBin -Xmx${xmx}M -Xms${xms}M $args -jar '$safeJarName' $noguiArg >> '$safeLogPath' 2>&1 < '$safePipePath' &")
                    appendLine("JAVA_PID=\$!")
                    appendLine("echo \$JAVA_PID > '$safePidFile'")
                    appendLine("wait \$JAVA_PID")
                    appendLine("echo '--- Server Stopped ---' >> '$safeLogPath'")
                    appendLine("rm -f '$safePipePath' '$safePidFile'")
                }
                scriptFile.writeText(script)
                scriptFile.setExecutable(true)

                emit("> 启动脚本: ${scriptFile.absolutePath}")
                emit("> JAR: ${targetJar.name}")
                emit("> 内存: ${xmx}MB / ${xms}MB")
                if (noguiArg.isNotEmpty()) emit("> 模式: 无 GUI (nogui)") else emit("> 模式: 带 GUI")

                logFile = File(logPath)
                logFile?.writeText("")
                logFile?.setWritable(true, false)

                val prootProcessBuilder = LinuxEnvironmentManager.buildProotCommand(
                    command = scriptFile.absolutePath,
                    workDir = serverDir.absolutePath
                )
                serverProcess = prootProcessBuilder
                    .directory(serverDir)
                    .start()

                emit("> 服务器已在 Linux 环境中启动")

                val processWaitScope = scope ?: CoroutineScope(Dispatchers.IO + SupervisorJob())
                processWaitScope.launch {
                    try {
                        serverProcess?.waitFor()
                        handleServerExit()
                    } catch (_: Exception) {
                        handleServerExit()
                    }
                }

                isRunning.set(true)
                _stateChanged.tryEmit(true)
                logFile?.let { startTailLog(it) }

                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "启动失败", e)
                emit("> 错误: ${e.message}")
                _stateChanged.tryEmit(false)
                isRunning.set(false)
                Result.failure(e)
            }
        }

    private fun startTailLog(file: File) {
        tailJob?.cancel()
        val coroutineScope = scope ?: CoroutineScope(Dispatchers.IO + SupervisorJob())
        tailJob = coroutineScope.launch {
            var lastSize = 0L
            var leftover = byteArrayOf()
            while (isActive && isRunning.get()) {
                try {
                    val currentSize = file.length()
                    if (currentSize > lastSize) {
                        val newBytes = ByteArray((currentSize - lastSize).toInt())
                        RandomAccessFile(file, "r").use { raf ->
                            raf.seek(lastSize)
                            raf.readFully(newBytes)
                        }
                        lastSize = currentSize

                        val combined = ByteArray(leftover.size + newBytes.size).apply {
                            System.arraycopy(leftover, 0, this, 0, leftover.size)
                            System.arraycopy(newBytes, 0, this, leftover.size, newBytes.size)
                        }
                        val text = String(combined, StandardCharsets.UTF_8)
                        val parts = text.split("\n")
                        val endsWithNewline = combined.isEmpty() || combined.last() == '\n'.code.toByte()
                        val complete = if (endsWithNewline) parts else parts.dropLast(1)
                        for (line in complete) {
                            if (line == "--- Server Stopped ---") {
                                handleServerExit()
                                return@launch
                            }
                            if (line.isNotBlank()) {
                                _consoleOutput.tryEmit(line)
                                parsePlayerEvent(line)
                                detectStartupIssues(line)
                                PerformanceMonitor.instance.feedLogLine(line)
                            }
                        }
                        leftover = if (endsWithNewline) byteArrayOf()
                        else parts.last().toByteArray(StandardCharsets.UTF_8)
                    } else if (currentSize < lastSize) {
                        lastSize = 0
                        leftover = byteArrayOf()
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "tail error", e)
                }
                delay(300)
            }
        }
    }

    private fun handleServerExit() {
        if (!isRunning.compareAndSet(true, false)) return
        _stateChanged.tryEmit(false)
        emit("> 服务器进程已退出")
        tailJob?.cancel()
        onlinePlayers.clear()
        _players.value = emptyList()
        try { onServerExited?.invoke() } catch (_: Exception) {}
    }

    private fun detectStartupIssues(line: String) {
        when {
            line.contains("Address already in use") ->
                emit("> 警告：端口被占用，请检查 server.properties 或配置页端口设置")
            line.contains("Invalid or corrupt jarfile") ->
                emit("> 错误：JAR 文件损坏，请重新选择/下载服务器核心")
            line.contains("UnsupportedClassVersionError") ->
                emit("> 错误：JAR 要求的 Java 版本高于当前运行环境，请在设置页切到更高版本 JRE")
            line.contains("You need to agree to the EULA") ->
                emit("> 错误：EULA 未被接受，正在自动写入 eula=true 后重试")
            line.contains("java.lang.OutOfMemoryError") ->
                emit("> 错误：内存不足（OOM），请在配置页降低内存分配或关闭其他应用")
        }
    }

    private fun parsePlayerEvent(line: String) {
        val joined = " joined the game"
        val left = " left the game"
        val name = when {
            line.contains(joined) -> line.substringBefore(joined).substringAfterLast("]: ").trim()
            line.contains(left) -> line.substringBefore(left).substringAfterLast("]: ").trim()
            else -> return
        }
        if (name.isBlank()) return
        val changed = if (line.contains(joined)) onlinePlayers.add(name) else onlinePlayers.remove(name)
        if (changed) _players.value = onlinePlayers.toList()
    }

    private fun prepareEula(dir: File) {
        try {
            val eula = File(dir, "eula.txt")
            if (!eula.exists()) {
                eula.writeText(
                    "# Minecraft EULA 由 MCServer Launcher 自动接受\n" +
                    "# 详见 https://aka.ms/MinecraftEULA\n" +
                    "eula=true\n"
                )
                emit("> 已自动接受 EULA (eula=true)")
            }
        } catch (e: Exception) {
            emit("> EULA 写入失败：${e.message}")
        }
    }

    private fun prepareServerProperties(dir: File, config: ServerConfig) {
        try {
            val props = File(dir, "server.properties")
            val rconPwd = config.rconPassword.ifEmpty { RconClient.generatePassword() }
            val desired = linkedMapOf(
                "server-port" to config.serverPort.toString(),
                "motd" to config.motd,
                "max-players" to config.maxPlayers.toString(),
                "gamemode" to config.gamemode,
                "difficulty" to config.difficulty,
                "pvp" to config.pvp.toString(),
                "online-mode" to config.onlineMode.toString(),
                "white-list" to config.whiteList.toString(),
                "spawn-protection" to config.spawnProtection.toString(),
                "view-distance" to config.viewDistance.toString(),
                "enable-command-block" to "true",
                "enable-rcon" to config.rconEnabled.toString(),
                "rcon.port" to config.rconPort.toString(),
                "rcon.password" to rconPwd,
                "broadcast-rcon-to-ops" to "true"
            )

            val lines = if (props.exists()) props.readLines().toMutableList()
            else mutableListOf()

            for ((key, value) in desired) {
                var found = false
                for (i in lines.indices) {
                    if (lines[i].trim().startsWith("$key=")) {
                        lines[i] = "$key=$value"
                        found = true
                        break
                    }
                }
                if (!found) lines.add("$key=$value")
            }
            props.writeText(lines.joinToString("\n") + "\n")
            emit("> 端口: ${config.serverPort}")
            emit("> 模式: ${config.gamemode} / 难度: ${config.difficulty} / 最大玩家: ${config.maxPlayers}")
            emit("> 在线模式: ${if (config.onlineMode) "正版验证" else "离线"} / PVP: ${config.pvp}")
        } catch (e: Exception) {
            emit("> server.properties 设置失败：${e.message}")
        }
    }

    fun sendCommand(cmd: String) {
        if (!isRunning.get() || cmd.isBlank()) return
        emit("> $cmd")

        if (rconReady && rconClient?.isConnected == true) {
            sendCommandViaRcon(cmd)
            return
        }

        writeCommandToPipe(cmd)
    }

    private fun sendCommandViaRcon(cmd: String) {
        val coroutineScope = scope ?: CoroutineScope(Dispatchers.IO + SupervisorJob())
        coroutineScope.launch {
            try {
                val result = rconClient?.sendCommand(cmd)
                if (result?.isSuccess == true) {
                    val response = result.getOrNull() ?: ""
                    if (response.isNotBlank() && response != "Unknown command. Type \"help\" for help.") {
                        if (response.lines().size <= 5) {
                            emit("  $response")
                        }
                    }
                } else {
                    emit("> RCON 不可用，使用管道发送")
                    writeCommandToPipe(cmd)
                }
            } catch (_: Exception) {}
        }
    }

    fun tryConnectRcon(config: ServerConfig) {
        if (!config.rconEnabled) {
            rconReady = false
            return
        }
        val pwd = config.rconPassword.ifEmpty { RconClient.generatePassword() }
        rconClient = RconClient(port = config.rconPort, password = pwd)
        val coroutineScope = scope ?: CoroutineScope(Dispatchers.IO + SupervisorJob())
        coroutineScope.launch {
            try {
                var connected = false
                for (attempt in 1..10) {
                    val result = rconClient?.connect()
                    if (result?.isSuccess == true) {
                        connected = true
                        break
                    }
                    kotlinx.coroutines.delay(2000)
                }
                rconReady = connected
                if (connected) {
                    emit("> RCON 已连接（端口 ${config.rconPort}）")
                } else {
                    emit("> RCON 连接失败，将使用管道发送命令")
                    rconClient?.disconnect()
                }
            } catch (_: Exception) {
                rconReady = false
            }
        }
    }

    fun disconnectRcon() {
        rconReady = false
        rconClient?.disconnect()
        rconClient = null
    }

    fun exportLogs(): String? {
        return try {
            val dir = serverDir(context)
            val exportDir = File(dir, "exports")
            exportDir.mkdirs()
            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
            val exportFile = File(exportDir, "server_logs_$timestamp.txt")

            val sb = StringBuilder()
            sb.appendLine("=== MCServer Launcher 日志导出 ===")
            sb.appendLine("导出时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
            sb.appendLine()

            val serverLog = File(dir, "server.log")
            if (serverLog.exists()) {
                sb.appendLine("=== server.log (最近 5000 行) ===")
                val lines = serverLog.readLines()
                val recent = if (lines.size > 5000) lines.takeLast(5000) else lines
                recent.forEach { sb.appendLine(it) }
                sb.appendLine()
            }

            val latestLog = File(dir, "logs/latest.log")
            if (latestLog.exists()) {
                sb.appendLine("=== logs/latest.log ===")
                latestLog.readLines().takeLast(2000).forEach { sb.appendLine(it) }
                sb.appendLine()
            }

            val crashDir = File(dir, "crash-reports")
            if (crashDir.exists()) {
                crashDir.listFiles()?.sortedByDescending { it.lastModified() }?.take(3)?.forEach { crash ->
                    sb.appendLine("=== 崩溃报告: ${crash.name} ===")
                    crash.readLines().take(100).forEach { sb.appendLine(it) }
                    sb.appendLine("... (截断)")
                    sb.appendLine()
                }
            }

            exportFile.writeText(sb.toString())
            exportFile.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    fun writeCommandToPipe(cmd: String) {
        val pipe = File(serverDir(context), "cmdpipe")
        if (!pipe.exists()) {
            emit("> 命令发送失败：服务器未就绪")
            return
        }
        try {
            val escapedCmd = cmd.replace("'", "'\\''")
            val prootProcessBuilder = LinuxEnvironmentManager.buildProotCommand(
                command = "printf '%s\\n' '$escapedCmd' >> '${escapeSingleQuote(pipe.absolutePath)}'",
                workDir = serverDir(context).absolutePath
            )
            val proc = prootProcessBuilder.start()
            proc.waitFor()
        } catch (e: Exception) {
            emit("> 命令发送失败：${e.message}")
        }
    }

    fun stopServer() {
        if (!isRunning.get()) {
            emit("> 服务器未在运行")
            return
        }
        emit("> 正在停止服务器...")
        onlinePlayers.clear()
        _players.value = emptyList()
        disconnectRcon()

        val dir = serverDir(context)
        val pidFile = File(dir, "mcserver.pid")
        val pipe = File(dir, "cmdpipe")

        if (pipe.exists()) {
            try {
                writeCommandToPipe("stop")
            } catch (_: Exception) {}
        }

        val stopScript = File(dir, "stop.sh")
        val pidPath = pidFile.absolutePath
        val safePidPath = escapeSingleQuote(pidPath)
        stopScript.writeText(
            "#!/bin/sh\n" +
            "sleep 8\n" +
            "if [ -f '$safePidPath' ]; then\n" +
            "  PID=\$(cat '$safePidPath' 2>/dev/null)\n" +
            "  if [ -n \"\$PID\" ] && kill -0 \"\$PID\" 2>/dev/null; then\n" +
            "    kill -TERM \"\$PID\" 2>/dev/null\n" +
            "    sleep 5\n" +
            "    kill -0 \"\$PID\" 2>/dev/null && kill -KILL \"\$PID\" 2>/dev/null\n" +
            "  fi\n" +
            "fi\n" +
            "rm -f '$safePidPath'\n"
        )
        stopScript.setExecutable(true)

        try {
            val prootProcessBuilder = LinuxEnvironmentManager.buildProotCommand(
                command = stopScript.absolutePath,
                workDir = dir.absolutePath
            )
            prootProcessBuilder.start()
        } catch (_: Exception) {}

        val coroutineScope = scope ?: CoroutineScope(Dispatchers.IO + SupervisorJob())
        coroutineScope.launch {
            delay(15000)
            if (isRunning.get()) {
                isRunning.set(false)
                _stateChanged.tryEmit(false)
                emit("> 服务器已停止")
            }
            tailJob?.cancel()
            serverProcess?.destroy()
        }
    }

    fun notifyConsole(msg: String) {
        emit(msg)
    }

    private fun emit(msg: String) {
        _consoleOutput.tryEmit(msg)
    }
}
