package com.mcserver.launcher.server

import android.util.Log
import com.mcserver.launcher.data.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import java.io.*

class JarRunner {

    companion object { private const val TAG = "JarRunner" }

    private var process: Process? = null
    private var isRunning = false

    private val _consoleOutput = MutableSharedFlow<String>(
        replay = 500,
        extraBufferCapacity = 100,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val consoleOutput: SharedFlow<String> = _consoleOutput.asSharedFlow()

    private val _stateChanged = MutableSharedFlow<Boolean>(
        replay = 1,
        extraBufferCapacity = 5,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val stateChanged: SharedFlow<Boolean> = _stateChanged.asSharedFlow()

    val running: Boolean get() = isRunning

    suspend fun start(config: ServerConfig, javaPath: String) =
        withContext(Dispatchers.IO) {
            try {
                val jarFile = File(config.jarPath)
                if (!jarFile.exists()) {
                    val msg = "找不到 JAR 文件: ${config.jarPath}"
                    safeEmit("> $msg")
                    return@withContext Result.failure(Exception(msg))
                }

                val javaFile = File(javaPath)
                if (!javaFile.exists() || !javaFile.canExecute()) {
                    // 尝试恢复权限
                    javaFile.setExecutable(true, false)
                    Runtime.getRuntime().exec(arrayOf("chmod", "755", javaPath)).waitFor()
                }

                if (!javaFile.canExecute()) {
                    val msg = "Java 运行时不可执行（可能被 SELinux 阻止）\n路径: $javaPath\n请在 Termux 中设置环境"
                    safeEmit("> $msg")
                    return@withContext Result.failure(Exception(msg))
                }

                val workDir = jarFile.parentFile ?: File("/")
                safeEmit("> 启动: ${javaPath}")
                safeEmit("> JAR: ${config.jarPath}")
                safeEmit("> 内存: ${config.maxRamMB}MB / ${config.minRamMB}MB")

                val command = mutableListOf<String>().apply {
                    add(javaPath)
                    addAll(config.toCommandArgs())
                }

                val pb = ProcessBuilder(command)
                    .directory(workDir)
                    .redirectErrorStream(true)

                // 设置环境变量
                val env = pb.environment()
                val javaHome = javaFile.parentFile?.parentFile?.absolutePath ?: ""
                env["JAVA_HOME"] = javaHome
                env["HOME"] = workDir.absolutePath
                env["PATH"] = "${javaFile.parentFile?.absolutePath}:${System.getenv("PATH")}"

                safeEmit("> 工作目录: ${workDir.absolutePath}")

                process = pb.start()
                isRunning = true
                _stateChanged.tryEmit(true)

                val reader = BufferedReader(InputStreamReader(process!!.inputStream), 8192)
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    _consoleOutput.tryEmit(line!!)
                }

                val exitCode = process?.waitFor() ?: -1
                isRunning = false; process = null
                safeEmit("> 服务器已停止 (退出码: $exitCode)")
                _stateChanged.tryEmit(false)
                Result.success(Unit)
            } catch (e: Exception) {
                isRunning = false; process = null
                Log.e(TAG, "启动失败", e)
                safeEmit("> 错误: ${e.message}")
                _stateChanged.tryEmit(false)
                Result.failure(e)
            }
        }

    private fun safeEmit(msg: String) {
        try { _consoleOutput.tryEmit(msg) } catch (_: Exception) {}
    }

    fun sendCommand(command: String) {
        try { process?.outputStream?.bufferedWriter()?.apply { write("$command\n"); flush() }; _consoleOutput.tryEmit("> $command") }
        catch (_: Exception) {}
    }

    fun stop() {
        try { sendCommand("stop"); Thread.sleep(5000); if (isRunning) process?.destroy() }
        catch (_: Exception) {}
        try { Thread.sleep(3000); if (isRunning) process?.destroyForcibly() }
        catch (_: Exception) {}
        isRunning = false; process = null
        _stateChanged.tryEmit(false)
    }

    fun forceStop() {
        try { process?.destroyForcibly() } catch (_: Exception) {}
        isRunning = false; process = null
        _consoleOutput.tryEmit("> 服务器已被强制终止")
        _stateChanged.tryEmit(false)
    }
}
