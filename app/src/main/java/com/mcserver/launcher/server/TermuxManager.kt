package com.mcserver.launcher.server

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.*
import android.util.Log
import com.mcserver.launcher.McApplication
import com.mcserver.launcher.data.ServerConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.*
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

    suspend fun startServer(config: ServerConfig, javaPath: String? = null): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                if (!isTermuxInstalled(context)) {
                    emit("> ❌ 需要安装 Termux 来提供 Linux 运行环境")
                    emit("> 请在 F-Droid 下载 Termux: https://f-droid.org/packages/com.termux/")
                    return@withContext Result.failure(Exception("Termux 未安装"))
                }

                emit("> 🔧 准备 Termux 环境...")

                // 1. 复制 JAR 到共享目录
                val serverDir = File("${Environment.getExternalStorageDirectory()}/mcserver")
                if (!serverDir.exists()) serverDir.mkdirs()
                val jarFile = File(config.jarPath)
                val targetJar = File(serverDir, jarFile.name)

                if (!targetJar.exists() || jarFile.lastModified() > targetJar.lastModified()) {
                    emit("> 复制 JAR 到共享目录: ${targetJar.absolutePath}")
                    jarFile.copyTo(targetJar, overwrite = true)
                }

                // 2. 创建启动脚本
                val scriptFile = File(serverDir, "start.sh")
                val logPath = File(serverDir, "server.log").absolutePath

                val termuxJava = "$TERMUX_BIN/java"
                val xmx = config.maxRamMB
                val xms = config.minRamMB
                val script = buildString {
                    appendLine("#!/data/data/com.termux/files/usr/bin/bash")
                    appendLine("cd ${serverDir.absolutePath}")
                    appendLine("echo '--- Minecraft Server Started ---' > $logPath")
                    appendLine("$termuxJava -Xmx${xmx}M -Xms${xms}M ${config.additionalArgs} -jar ${targetJar.name} nogui >> $logPath 2>&1")
                    appendLine("echo '--- Server Stopped ---' >> $logPath")
                }
                scriptFile.writeText(script)
                scriptFile.setExecutable(true)

                emit("> 启动脚本: ${scriptFile.absolutePath}")
                emit("> JAR: ${targetJar.name}")
                emit("> 内存: ${xmx}MB / ${xms}MB")

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

                emit("> ✅ 服务器已在 Termux 中启动")

                // 5. 追踪日志文件输出
                isRunning.set(true)
                _stateChanged.tryEmit(true)
                startTailLog(logFile!!)

                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "启动失败", e)
                emit("> ❌ 错误: ${e.message}")
                _stateChanged.tryEmit(false)
                isRunning.set(false)
                Result.failure(e)
            }
        }

    // ─── 日志追踪 ───

    private fun startTailLog(file: File) {
        tailJob?.cancel()
        tailJob = CoroutineScope(Dispatchers.IO).launch {
            var lastSize = 0L
            while (isActive && isRunning.get()) {
                try {
                    val currentSize = file.length()
                    if (currentSize > lastSize) {
                        RandomAccessFile(file, "r").use { raf ->
                            raf.seek(lastSize)
                            var line: String?
                            while (raf.readLine().also { line = it } != null) {
                                if (!line.isNullOrBlank())
                                    _consoleOutput.tryEmit(line!!)
                            }
                        }
                        lastSize = currentSize
                    } else if (currentSize < lastSize) {
                        // 文件被重置
                        lastSize = 0
                    }
                } catch (_: Exception) {}
                delay(300)
            }
        }
    }

    // ─── 停止 ───

    fun stopServer() {
        emit("> 正在停止服务器...")
        try {
            // 通过 Termux 发送 stop 命令
            val stopScript = File("${Environment.getExternalStorageDirectory()}/mcserver/stop.sh")
            stopScript.writeText(
                "#!/data/data/com.termux/files/usr/bin/bash\n" +
                "pkill -f 'java.*server' || true"
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

        tailJob?.cancel()
        isRunning.set(false)
        _stateChanged.tryEmit(false)
        emit("> 服务器已停止")
    }

    private fun emit(msg: String) {
        _consoleOutput.tryEmit(msg)
    }
}
