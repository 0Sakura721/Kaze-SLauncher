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
                true
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

    // ─── 环境检测 ───

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
                // 让配置中的端口生效（写入/更新 server.properties）
                prepareServerProperties(serverDir, config.serverPort)

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

                val script = buildString {
                    appendLine("#!/data/data/com.termux/files/usr/bin/bash")
                    appendLine("cd ${serverDir.absolutePath}")
                    // 清理可能残留的监听进程与管道
                    appendLine("pkill -f 'cmdpipe' 2>/dev/null || true")
                    appendLine("rm -f '$pipePath'")
                    appendLine("mkfifo '$pipePath'")
                    appendLine("echo '--- Minecraft Server Started ---' > '$logPath'")
                    // 通过命名管道把控制台命令喂给 java 的 stdin
                    appendLine("tail -f '$pipePath' | $termuxJava -Xmx${xmx}M -Xms${xms}M $args -jar '${targetJar.name}' $noguiArg >> '$logPath' 2>&1 &")
                    appendLine("JAVA_PID=\$!")
                    // 阻塞直到 java 退出，再写入结束标记（用于崩溃/停止检测）
                    appendLine("wait \$JAVA_PID")
                    appendLine("echo '--- Server Stopped ---' >> '$logPath'")
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

    /** 让配置中的端口生效：写入或更新 server.properties 的 server-port */
    private fun prepareServerProperties(dir: File, port: Int) {
        try {
            val props = File(dir, "server.properties")
            if (props.exists()) {
                val lines = props.readLines().toMutableList()
                var found = false
                for (i in lines.indices) {
                    if (lines[i].trim().startsWith("server-port")) {
                        lines[i] = "server-port=$port"
                        found = true
                    }
                }
                if (!found) lines.add("server-port=$port")
                props.writeText(lines.joinToString("\n"))
            } else {
                props.writeText("server-port=$port\n")
            }
            emit("> 端口: $port")
        } catch (e: Exception) {
            emit("> 端口设置失败：${e.message}")
        }
    }

    // ─── 发送控制台命令 ───

    /**
     * 向运行中的服务器发送命令（如 stop / op / gamemode）
     * 通过命名管道喂给 java 的 stdin
     */
    fun sendCommand(cmd: String) {
        if (!isRunning.get() || cmd.isBlank()) return
        emit("> $cmd")
        try {
            val pipe = File(serverDir(context), "cmdpipe")
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
                context.startForegroundService(intent)
            else context.startService(intent)
        } catch (e: Exception) {
            emit("> 命令发送失败：${e.message}")
        }
    }

    // ─── 停止 ───

    fun stopServer() {
        emit("> 正在停止服务器...")
        onlinePlayers.clear()
        _players.value = emptyList()
        try {
            val stopScript = File(serverDir(context), "stop.sh")
            stopScript.writeText(
                "#!/data/data/com.termux/files/usr/bin/bash\n" +
                "pkill -f 'server.jar' 2>/dev/null || true\n" +
                "pkill -f 'cmdpipe' 2>/dev/null || true\n"
            )
            stopScript.setExecutable(true)
            val intent = Intent("com.termux.RUN_COMMAND")
            intent.setClassName(TERMUX_PACKAGE, "com.termux.app.RunCommandService")
            intent.putExtra("com.termux.RUN_COMMAND_PATH", "$TERMUX_BIN/bash")
            intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf(stopScript.absolutePath))
            intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", TERMUX_HOME)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(intent)
            else context.startService(intent)
        } catch (_: Exception) {}

        // 若 3 秒后仍未退出，强制收尾
        CoroutineScope(Dispatchers.IO).launch {
            delay(3000)
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
