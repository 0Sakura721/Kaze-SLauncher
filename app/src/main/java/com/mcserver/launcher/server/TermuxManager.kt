package com.mcserver.launcher.server

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
 * Termux 安装状态
 */
enum class TermuxState { NOT_INSTALLED, INSTALLED, JAVA_MISSING, READY }

/**
 * 通过 Termux 的 Linux 环境执行 Java JAR 文件
 * 解决 Android SELinux 阻止直接 exec 的问题
 */
class TermuxManager {

    companion object {
        const val TAG = "TermuxManager"
        const val TERMUX_PACKAGE = "com.termux"
        const val TERMUX_FILES = "/data/data/com.termux/files"
        const val TERMUX_HOME = "$TERMUX_FILES/home"
        const val TERMUX_USR = "$TERMUX_FILES/usr"
        const val TERMUX_BIN = "$TERMUX_USR/bin"

        /**
         * 获取 Termux bash 路径。
         * 兼容标准安装和非标准路径（某些 ROM 可能修改了 Termux 路径）。
         */
        fun getTermuxBashPath(): String {
            // 标准路径
            val standardBash = File("$TERMUX_BIN/bash")
            if (standardBash.exists() && standardBash.canExecute()) {
                return standardBash.absolutePath
            }
            // 备用：通过 /proc/self/exe 或环境变量推断
            val altPaths = listOf(
                "/data/data/com.termux/files/usr/bin/bash",
                "/data/user/0/com.termux/files/usr/bin/bash",
                "/usr/bin/bash"
            )
            for (path in altPaths) {
                val f = File(path)
                if (f.exists() && f.canExecute()) return f.absolutePath
            }
            // 回退到标准路径（即使不存在，至少 Termux 安装后会出现）
            return "$TERMUX_BIN/bash"
        }

        /** 共享工作目录（应用与 Termux 都能访问） */
        fun serverDir(ctx: Context): File {
            val dir = File(Environment.getExternalStorageDirectory(), "mcserver")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

        /** 检查 Termux 是否已安装 */
        fun isTermuxInstalled(ctx: Context): Boolean {
            return try {
                ctx.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
                // 进一步检查 Termux 文件目录是否存在
                val filesDir = File(TERMUX_FILES)
                if (!filesDir.exists()) {
                    // 尝试备用路径
                    val altDir = File("/data/user/0/com.termux/files")
                    altDir.exists()
                } else true
            } catch (_: Exception) { false }
        }

        /** 打开 Termux 下载页面 */
        fun openTermuxDownload(ctx: Context) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://f-droid.org/packages/com.termux/"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
        }
    }

    private val context: Context get() = McApplication.instance
    private val isRunning = AtomicBoolean(false)
    private var tailJob: Job? = null
    private var logFile: File? = null

    /** RCON 客户端实例（惰性初始化，密码由配置决定） */
    private var rconClient: RconClient? = null
    private var rconReady = false

    /** 服务器进程退出时回调（用于崩溃检测 / 自动重启） */
    var onServerExited: (() -> Unit)? = null

    /** 在线玩家（从日志解析 join/leave） */
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

    // ─── 服务器存活检测（用于应用重启后恢复状态） ───

    /**
     * 检测服务器是否仍在 Termux 中运行。
     * 通过检查 mcserver.pid 文件中的 PID 是否存活来判断。
     * 借鉴 Pterodactyl 的进程存活检测机制。
     */
    fun isServerProcessAlive(): Boolean {
        return try {
            val dir = serverDir(context)
            val pidFile = File(dir, "mcserver.pid")
            if (!pidFile.exists()) return false
            val pid = pidFile.readText().trim().toIntOrNull() ?: return false
            // 检查 /proc/[pid] 是否存在
            val procDir = File("/proc/$pid")
            procDir.exists() && procDir.isDirectory
        } catch (_: Exception) { false }
    }

    /**
     * 重新连接到正在运行的服务器进程。
     * 恢复日志追踪和状态监控。
     * @return 是否成功重连
     */
    fun reconnectToRunningServer(): Boolean {
        return try {
            if (!isServerProcessAlive()) return false
            val dir = serverDir(context)
            logFile = File(dir, "server.log")
            if (!logFile!!.exists()) {
                logFile = null
                return false
            }
            isRunning.set(true)
            _stateChanged.tryEmit(true)
            emit("> 检测到服务器仍在运行，正在恢复连接...")
            startTailLog(logFile!!)
            emit("> 已恢复与服务器的连接")
            true
        } catch (e: Exception) {
            isRunning.set(false)
            false
        }
    }

    // ─── 环境检测 ───

    /** 检查 Termux 中是否安装了 Java */
    fun checkState(): TermuxState {
        if (!isTermuxInstalled(context)) return TermuxState.NOT_INSTALLED
        val java = File("$TERMUX_BIN/java")
        if (!java.exists()) return TermuxState.JAVA_MISSING
        return TermuxState.READY
    }

    /** 在 Termux 中安装 Java */
    fun installJavaInTermux() {
        val intent = Intent("com.termux.RUN_COMMAND")
        intent.setClassName(TERMUX_PACKAGE, "com.termux.app.RunCommandService")
        intent.putExtra("com.termux.RUN_COMMAND_PATH", "$TERMUX_BIN/bash")
        intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", "pkg update -y && pkg install openjdk-21 -y"))
        intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", TERMUX_HOME)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            context.startForegroundService(intent)
        else context.startService(intent)
    }

    // ─── 启动服务器 ───

    private suspend fun ensureLocalJarPath(jarPath: String): String {
        if (!jarPath.startsWith("content://")) return jarPath
        // content:// URI → 复制到内部存储
        return withContext(Dispatchers.IO) {
            val uri = Uri.parse(jarPath)
            // 尝试持有持久权限
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

    suspend fun startServer(config: ServerConfig, javaPath: String? = null): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                if (!isTermuxInstalled(context)) {
                    emit("> 需要安装 Termux 来提供 Linux 运行环境")
                    emit("> 请在 F-Droid 下载 Termux: https://f-droid.org/packages/com.termux/")
                    return@withContext Result.failure(Exception("Termux 未安装"))
                }

                emit("> 准备 Termux 环境...")

                // 1. 复制 JAR 到共享目录（处理 content:// URI）
                val serverDir = serverDir(context)
                // 重置在线玩家统计
                onlinePlayers.clear()
                _players.value = emptyList()
                // 让配置中的 server.properties 项生效（端口/模式/难度/最大玩家等）
                prepareServerProperties(serverDir, config)
                // 自动接受 EULA（Minecraft 首次启动会因 eula=false 直接退出）
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
                    emit("> 复制 JAR 到共享目录: ${targetJar.absolutePath}")
                    jarFile.copyTo(targetJar, overwrite = true)
                }

                // 2. 创建启动脚本（使用命名管道接收控制台命令）
                val scriptFile = File(serverDir, "start.sh")
                val logPath = File(serverDir, "server.log").absolutePath
                val pipePath = File(serverDir, "cmdpipe").absolutePath

                val termuxJava = "$TERMUX_BIN/java"
                val xmx = config.maxRamMB
                val xms = config.minRamMB
                val noguiArg = if (config.nogui) "nogui" else ""
                val args = config.additionalArgs.trim()

                val pidFile = File(serverDir, "mcserver.pid").absolutePath
                val script = buildString {
                    appendLine("#!/data/data/com.termux/files/usr/bin/bash")
                    appendLine("cd ${serverDir.absolutePath}")
                    // 清理可能残留的管道
                    appendLine("rm -f '$pipePath'")
                    appendLine("mkfifo '$pipePath'")
                    appendLine("echo '--- Minecraft Server Started ---' > '$logPath'")
                    appendLine(": > '$pidFile'")
                    // 关键：让 java 直接从命名管道读取 stdin（而非 tail -f | java）。
                    // 这样停止时只需 kill java 进程，管道写端关闭，java 读到 EOF 自然退出，
                    // 不会再遗留 tail 进程，也无需 pkill 整条命令（仿 Pterodactyl 的进程分组管理）。
                    appendLine("$termuxJava -Xmx${xmx}M -Xms${xms}M $args -jar '${targetJar.name}' $noguiArg >> '$logPath' 2>&1 < '$pipePath' &")
                    appendLine("JAVA_PID=\$!")
                    // 记录 java 进程 PID，供精确停止（不再依赖写死的 jar 名做 pkill）
                    appendLine("echo \$JAVA_PID > '$pidFile'")
                    // 阻塞直到 java 退出，再写入结束标记（用于崩溃/停止检测）
                    appendLine("wait \$JAVA_PID")
                    appendLine("echo '--- Server Stopped ---' >> '$logPath'")
                    appendLine("rm -f '$pipePath' '$pidFile'")
                }
                scriptFile.writeText(script)
                scriptFile.setExecutable(true)

                emit("> 启动脚本: ${scriptFile.absolutePath}")
                emit("> JAR: ${targetJar.name}")
                emit("> 内存: ${xmx}MB / ${xms}MB")
                if (noguiArg.isNotEmpty()) emit("> 模式: 无 GUI (nogui)") else emit("> 模式: 带 GUI")

                // 3. 清空日志
                logFile = File(logPath)
                logFile?.writeText("")
                logFile?.setWritable(true, false)

                // 4. 通过 Termux RunCommandService 执行脚本
                val intent = Intent("com.termux.RUN_COMMAND")
                intent.setClassName(TERMUX_PACKAGE, "com.termux.app.RunCommandService")
                intent.putExtra("com.termux.RUN_COMMAND_PATH", "$TERMUX_BIN/bash")
                intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf(scriptFile.absolutePath))
                intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", serverDir.absolutePath)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    context.startForegroundService(intent)
                else context.startService(intent)

                emit("> 服务器已在 Termux 中启动")

                // 5. 追踪日志文件输出
                isRunning.set(true)
                _stateChanged.tryEmit(true)
                startTailLog(logFile!!)

                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "启动失败", e)
                emit("> 错误: ${e.message}")
                _stateChanged.tryEmit(false)
                isRunning.set(false)
                Result.failure(e)
            }
        }

    // ─── 日志追踪（UTF-8 安全） ───

    private fun startTailLog(file: File) {
        tailJob?.cancel()
        tailJob = CoroutineScope(Dispatchers.IO).launch {
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

                        // 拼接上次残留的不完整字节，保证 UTF-8 多字节字符不被截断
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
                                // 服务器进程已退出
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
                        // 日志被重置
                        lastSize = 0
                        leftover = byteArrayOf()
                    }
                } catch (_: Exception) {}
                delay(300)
            }
        }
    }

    /** 服务器进程退出：重置状态并通知上层（用于自动重启 / 崩溃检测） */
    private fun handleServerExit() {
        if (!isRunning.compareAndSet(true, false)) return
        _stateChanged.tryEmit(false)
        emit("> 服务器进程已退出")
        tailJob?.cancel()
        onlinePlayers.clear()
        _players.value = emptyList()
        try { onServerExited?.invoke() } catch (_: Exception) {}
    }

    /** 识别常见启动失败原因，给出友好提示（健康检查，仿 MCSManager 的诊断） */
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

    /** 从日志行解析玩家加入/离开，维护在线列表 */
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

    /**
     * 自动接受 Minecraft EULA。
     * 服务器首次启动会生成 eula.txt 并写入 eula=false，然后直接退出，
     * 导致用户看到「启动失败」。这里在启动前主动写入 eula=true，
     * 与 PufferPanel / MCSManager 等成熟面板的做法一致。
     */
    private fun prepareEula(dir: File) {
        try {
            val eula = File(dir, "eula.txt")
            // 仅在不存在时写入，尊重用户后续手动修改（如接受更新后的 EULA）
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

    /** 让配置中的 server.properties 项生效：写入或更新（仿 Pterodactyl 的变量注入） */
    private fun prepareServerProperties(dir: File, config: ServerConfig) {
        try {
            val props = File(dir, "server.properties")
            // key -> value，仅处理本项目支持的常用项；其余项保持原样
            // 若 RCON 密码为空，自动生成一个
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
                // RCON 配置（仿 Pterodactyl 自动注入）
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

    // ─── 发送控制台命令 ───

    /**
     * 向运行中的服务器发送命令（如 stop / op / gamemode）
     *
     * 优先通过 RCON 协议发送（可获取命令返回值），
     * 若 RCON 不可用则回退到命名管道方式。
     *
     * 注意：Android 8+ 对后台启动 Service 有严格限制，应用切到后台后
     * 直接 startForegroundService 会抛异常导致命令丢失。因此这里优先
     * 通过已运行的 ServerForegroundService 转发（它本身就是前台服务，
     * 不受后台限制）；服务未运行时才回退到直接调用 Termux。
     */
    fun sendCommand(cmd: String) {
        if (!isRunning.get() || cmd.isBlank()) return
        emit("> $cmd")

        // 优先尝试 RCON（可获取命令返回值，延迟更低）
        if (rconReady && rconClient?.isConnected == true) {
            sendCommandViaRcon(cmd)
            return
        }

        // 回退：管道方式
        try {
            if (ServerForegroundService.isRunning) {
                val broadcast = Intent(ServerForegroundService.ACTION_SEND_COMMAND).apply {
                    setPackage(context.packageName)
                    putExtra(ServerForegroundService.EXTRA_COMMAND, cmd)
                }
                context.sendBroadcast(broadcast)
            } else {
                writeCommandToPipe(context, cmd)
            }
        } catch (e: Exception) {
            emit("> 命令发送失败：${e.message}")
        }
    }

    /**
     * 通过 RCON 发送命令。
     * 在后台协程中执行，不阻塞 UI。
     */
    private fun sendCommandViaRcon(cmd: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = rconClient?.sendCommand(cmd)
                if (result?.isSuccess == true) {
                    val response = result.getOrNull() ?: ""
                    if (response.isNotBlank() && response != "Unknown command. Type \"help\" for help.") {
                        // 某些命令（如 list、tps）有意义的返回值，显示在控制台
                        // 但 stop 等命令的返回值可能为空或无用
                        if (response.lines().size <= 5) {
                            emit("  $response")
                        }
                    }
                } else {
                    // RCON 失败，回退到管道
                    emit("> RCON 不可用，使用管道发送")
                    try {
                        if (ServerForegroundService.isRunning) {
                            val broadcast = Intent(ServerForegroundService.ACTION_SEND_COMMAND).apply {
                                setPackage(context.packageName)
                                putExtra(ServerForegroundService.EXTRA_COMMAND, cmd)
                            }
                            context.sendBroadcast(broadcast)
                        } else {
                            writeCommandToPipe(context, cmd)
                        }
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * 尝试建立 RCON 连接（在服务器完全启动后调用）。
     * 在日志中检测到 "Done" 消息后由上层触发。
     */
    fun tryConnectRcon(config: ServerConfig) {
        if (!config.rconEnabled) {
            rconReady = false
            return
        }
        val pwd = config.rconPassword.ifEmpty { RconClient.generatePassword() }
        rconClient = RconClient(port = config.rconPort, password = pwd)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 服务器启动后 RCON 端口可能需要几秒才能就绪，重试几次
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

    /** 断开 RCON 连接 */
    fun disconnectRcon() {
        rconReady = false
        rconClient?.disconnect()
        rconClient = null
    }

    /**
     * 导出服务器日志到文件。
     * 包含 server.log、latest.log（如果存在）、以及崩溃报告。
     * @return 导出文件路径，失败返回 null
     */
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

            // server.log
            val serverLog = File(dir, "server.log")
            if (serverLog.exists()) {
                sb.appendLine("=== server.log (最近 5000 行) ===")
                val lines = serverLog.readLines()
                val recent = if (lines.size > 5000) lines.takeLast(5000) else lines
                recent.forEach { sb.appendLine(it) }
                sb.appendLine()
            }

            // logs/latest.log
            val latestLog = File(dir, "logs/latest.log")
            if (latestLog.exists()) {
                sb.appendLine("=== logs/latest.log ===")
                latestLog.readLines().takeLast(2000).forEach { sb.appendLine(it) }
                sb.appendLine()
            }

            // 崩溃报告摘要
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

    /**
     * 直接把命令写入命名管道（不启动额外 Service）。
     * 由 ServerForegroundService 在收到广播后调用，也可作为前台回退。
     */
    fun writeCommandToPipe(ctx: Context, cmd: String) {
        val pipe = File(serverDir(ctx), "cmdpipe")
        if (!pipe.exists()) {
            emit("> 命令发送失败：服务器未就绪")
            return
        }
        // 经由 Termux 写入管道（命令作为参数传入，避免 shell 注入）
        val intent = Intent("com.termux.RUN_COMMAND")
        intent.setClassName(TERMUX_PACKAGE, "com.termux.app.RunCommandService")
        intent.putExtra("com.termux.RUN_COMMAND_PATH", "$TERMUX_BIN/bash")
        intent.putExtra(
            "com.termux.RUN_COMMAND_ARGUMENTS",
            arrayOf("-c", "timeout 5 printf '%s\\n' \"\$1\" >> \"\$2\"", "sh", cmd, pipe.absolutePath)
        )
        intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", TERMUX_HOME)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            ctx.startForegroundService(intent)
        else ctx.startService(intent)
    }

    // ─── 停止 ───

    /**
     * 停止运行中的服务器。
     *
     * 停止策略（与 Pterodactyl / MCSManager 等成熟面板一致）：
     *   1) 优先通过命名管道发送 `stop` 命令，让服务器优雅关闭并保存存档；
     *   2) 等待一段时间后，若进程仍在，按 mcserver.pid 记录的精确 PID 发送 SIGTERM；
     *   3) 若仍存活，再发送 SIGKILL 强制结束。
     *
     * 不再使用写死的 `pkill -f 'server.jar'`，因此 Paper / Spigot / Forge 等
     * 任意名字的 JAR 都能被正确停止。
     */
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

        // 步骤 1：先尝试优雅关闭（向命名管道写入 stop 命令，java 读到后保存并退出）
        if (pipe.exists()) {
            try {
                writeCommandToPipe(context, "stop")
            } catch (_: Exception) {}
        }

        // 步骤 2 / 3：等待停止命令生效后，按精确 PID 先 TERM 后 KILL。
        // 由于 java 直接以管道为 stdin，kill 后管道写端亦随之关闭，java 收到 EOF 自然退出，
        // 不会遗留任何喂数据的辅助进程，因此无需 pkill。
        val stopScript = File(dir, "stop.sh")
        val pidPath = pidFile.absolutePath
        stopScript.writeText(
            "#!/data/data/com.termux/files/usr/bin/bash\n" +
            // 给 stop 命令 8s 优雅保存时间
            "sleep 8\n" +
            "if [ -f '$pidPath' ]; then\n" +
            "  PID=\$(cat '$pidPath' 2>/dev/null)\n" +
            "  if [ -n \"\$PID\" ] && kill -0 \"\$PID\" 2>/dev/null; then\n" +
            "    kill -TERM \"\$PID\" 2>/dev/null\n" +
            // 再给 5s 优雅退出，仍存活则强杀
            "    sleep 5\n" +
            "    kill -0 \"\$PID\" 2>/dev/null && kill -KILL \"\$PID\" 2>/dev/null\n" +
            "  fi\n" +
            "fi\n" +
            "rm -f '$pidPath'\n"
        )
        stopScript.setExecutable(true)
        try {
            val intent = Intent("com.termux.RUN_COMMAND")
            intent.setClassName(TERMUX_PACKAGE, "com.termux.app.RunCommandService")
            intent.putExtra("com.termux.RUN_COMMAND_PATH", "$TERMUX_BIN/bash")
            intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf(stopScript.absolutePath))
            intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", TERMUX_HOME)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(intent)
            else context.startService(intent)
        } catch (_: Exception) {}

        // 收尾兜底：若 15 秒后仍未收到退出标记，强制标记为已停止
        CoroutineScope(Dispatchers.IO).launch {
            delay(15000)
            if (isRunning.get()) {
                isRunning.set(false)
                _stateChanged.tryEmit(false)
                emit("> 服务器已停止")
            }
            tailJob?.cancel()
        }
    }

    /** 供上层向控制台写入一行提示（如自动重启通知） */
    fun notifyConsole(msg: String) {
        emit(msg)
    }

    private fun emit(msg: String) {
        _consoleOutput.tryEmit(msg)
    }
}
