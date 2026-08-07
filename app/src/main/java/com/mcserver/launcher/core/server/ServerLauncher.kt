package com.mcserver.launcher.core.server

import android.content.Context
import com.mcserver.launcher.core.env.EnvManager
import com.mcserver.launcher.data.InstanceStatus
import com.mcserver.launcher.data.ServerInstance
import com.mcserver.launcher.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 服务器启动引擎:实例生命周期 + 控制台 + RCON。
 * 一个实例一个进程,状态独立(多实例版本隔离)。
 */
class ServerLauncher(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _status = MutableStateFlow(InstanceStatus.STOPPED)
    val status: StateFlow<InstanceStatus> = _status.asStateFlow()

    private val _console = MutableSharedFlow<String>(replay = 300, extraBufferCapacity = 200)
    val console: SharedFlow<String> = _console.asSharedFlow()

    private val _uptimeSec = MutableStateFlow(0L)
    val uptimeSec: StateFlow<Long> = _uptimeSec.asStateFlow()

    private val _players = MutableStateFlow<List<String>>(emptyList())
    val players: StateFlow<List<String>> = _players.asStateFlow()

    private var process: Process? = null
    private var tailJob: Job? = null
    private var uptimeJob: Job? = null
    private var processWaitJob: Job? = null
    private var restartCount = 0
    private val isLaunching = AtomicBoolean(false)
    private var currentInstance: ServerInstance? = null
    private var manualStop = false
    private var launchedAtMs = 0L

    val isRunning: Boolean get() = process?.isAlive == true

    /** 控制台日志(供 UI 一次性加载) */
    fun snapshotConsole(): List<String> = emptyList()

    // ── 启动 ──
    suspend fun start(instance: ServerInstance): Result<Unit> {
        if (isRunning || isLaunching.get()) return Result.failure(RuntimeException("服务器已在运行"))
        if (!EnvManager.isEnvironmentReady()) return Result.failure(RuntimeException("Linux 环境未就绪"))
        val javaPath = EnvManager.resolveJavaPath(null)
        if (javaPath == null) return Result.failure(RuntimeException("未安装 Java,请先到设置页安装"))

        currentInstance = instance
        manualStop = false
        isLaunching.set(true)
        launchedAtMs = android.os.SystemClock.elapsedRealtime()
        _status.value = InstanceStatus.STARTING

        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                val dir = instance.dir(InstanceStore.instancesDir)
                dir.mkdirs()

                // 环境自检诊断(控制台可见,便于定位环境问题)
                val jreOk = EnvManager.isJreReady()
                emit("> 环境自检:Java 运行时=${if (jreOk) "OK(${EnvManager.jreHomeDir.name})" else "缺失(请到设置页部署/导入)"}")

                // 1) 核心 jar
                val jarFile = dir.listFiles()?.firstOrNull { it.name.endsWith(".jar") && !it.name.contains("installer") }
                    ?: return@withContext Result.failure(RuntimeException("实例目录中没有服务端核心,请先下载"))
                val jarName = jarFile.name

                // 2) eula + server.properties
                writeEula(dir)
                writeServerProperties(dir, instance)

                // 3) 启动脚本(JRE 直跑:宿主 sh 直接 exec java,不经 proot)
                val logPath = File(dir, "server.log").absolutePath
                // FIFO 必须放 App 私有目录:外部存储(FUSE)不支持 mkfifo
                val pipePath = File(EnvManager.appTmpDir, "cmdpipe-${instance.id}").absolutePath
                val pidFile = File(dir, "mcserver.pid").absolutePath
                val tmpDirPath = EnvManager.appTmpDir.absolutePath
                val script = buildString {
                    appendLine("#!/bin/sh")
                    appendLine("cd '$dir' || exit 1")
                    appendLine("rm -f '$pipePath'")
                    appendLine("mkfifo '$pipePath'")
                    // 保持 FIFO 写端打开:否则 java 的 stdin 读端 open 会阻塞等待写者,
                    // 长时间阻塞易被 Android 信号打断(EINTR,启动失败)。
                    // 后台 sleep 只持有 fd 不写数据,java 的读端 open 立即完成。
                    appendLine("sleep 100000d > '$pipePath' &")
                    appendLine("HOLDER_PID=\$!")
                    // Android linker 不搜 JRE lib 目录,必须显式注入:
                    // 否则 java 动态链接报 libz.so.1 / libandroid-shmem.so not found
                    appendLine("export LD_LIBRARY_PATH='${EnvManager.jreLibDir.absolutePath}'")
                    appendLine("export TMPDIR='$tmpDirPath'")
                    appendLine("echo '--- Server Started ---' > '$logPath'")
                    appendLine(": > '$pidFile'")
                    // 用宿主 linker64 加载 java(而非直接 exec):
                    // vivo/Android 16 对 untrusted_app 直接 exec 应用数据目录 ELF 有限制
                    // (Termux 等白名单 App 不受限),linker64 加载不触发 exec 限制,
                    // 与 proot 的 linker64 回退是同一机制,已验证可用
                    appendLine("/system/bin/linker64 $javaPath -Xmx${instance.config.maxRamMB}M -Xms${(instance.config.maxRamMB / 2).coerceAtLeast(256)}M -Djava.io.tmpdir='$tmpDirPath' -jar '$jarName' ${if (instance.config.nogui) "nogui" else ""} >> '$logPath' 2>&1 < '$pipePath' &")
                    appendLine("JAVA_PID=\$!")
                    appendLine("echo \$JAVA_PID > '$pidFile'")
                    appendLine("wait \$JAVA_PID")
                    appendLine("kill \$HOLDER_PID 2>/dev/null")
                    appendLine("echo '--- Server Stopped ---' >> '$logPath'")
                    appendLine("rm -f '$pipePath' '$pidFile'")
                }
                File(dir, "start.sh").writeText(script)
                File(dir, "start.sh").setExecutable(true)

                // 4) JRE 直跑启动(宿主 sh,不经 proot)
                val pb = EnvManager.startShell(File(dir, "start.sh").absolutePath)
                process = pb
                emit("> 服务器启动中(${instance.name} ${instance.mcVersion} ${instance.coreType.displayName})")

                // 消费启动进程的输出(含 stderr 警告/错误),否则管道缓冲会阻塞
                // 且失败原因无法查看
                val procOut = process
                scope.launch {
                    procOut?.inputStream?.bufferedReader()?.useLines { lines ->
                        lines.forEach { line ->
                            if (line.isNotBlank()) {
                                Logger.w("sh: $line")
                                emit(line)
                            }
                        }
                    }
                }

                // 进程退出监控
                processWaitJob?.cancel()
                processWaitJob = scope.launch {
                    process?.waitFor()
                    handleExit()
                }
                startTail(File(logPath))
                startUptime()

                _status.value = InstanceStatus.RUNNING
                // 保活:服务已常驻(App 启动时拉起),这里只更新通知为实例信息
                try {
                    ServerKeepAliveService.update(
                        com.mcserver.launcher.KazeApp.instance,
                        instance.name,
                        instance.mcVersion
                    )
                } catch (_: Exception) { }
                Result.success(Unit)
            } catch (e: Exception) {
                Logger.e("start failed", e)
                emit("> 启动失败:${e.message}")
                _status.value = InstanceStatus.ERROR
                Result.failure(e)
            } finally {
                isLaunching.set(false)
            }
        }
    }

    // ── 停止 ──
    suspend fun stop() {
        if (!isRunning) { _status.value = InstanceStatus.STOPPED; return }
        manualStop = true
        _status.value = InstanceStatus.STOPPING
        emit("> 正在停止服务器...")
        sendCommand("stop")
        val dir = currentInstance?.dir(InstanceStore.instancesDir)
        if (dir != null) {
            val pidFile = File(dir, "mcserver.pid")
            val script = File(dir, "stop.sh")
            script.writeText(
                "#!/bin/sh\n" +
                "sleep 8\n" +
                "if [ -f '$pidFile' ]; then\n" +
                "  PID=\$(cat '$pidFile' 2>/dev/null)\n" +
                "  if [ -n \"\$PID\" ] && kill -0 \"\$PID\" 2>/dev/null; then\n" +
                "    kill -TERM \"\$PID\" 2>/dev/null; sleep 5; kill -0 \"\$PID\" 2>/dev/null && kill -KILL \"\$PID\" 2>/dev/null\n" +
                "  fi\n" +
                "fi\n"
            )
            script.setExecutable(true)
            EnvManager.startShell(script.absolutePath)
        }
        // 兜底:15 秒后强制收尾
        scope.launch {
            delay(15000)
            if (isRunning) {
                process?.destroy()
                processWaitJob?.cancel()
                finalizeStop()
            }
        }
    }

    // ── 控制台 ──
    /**
     * 发送控制台指令。优先走 RCON(服务器标准指令通道,稳定可靠);
     * RCON 不可用时回退 FIFO(stdin 在非 TTY 下可能不被 JLine 读取,仅作后备)。
     */
    fun sendCommand(cmd: String) {
        if (!isRunning || cmd.isBlank()) return
        emit("> $cmd")
        val dir = currentInstance?.dir(InstanceStore.instancesDir) ?: return
        val cfg = currentInstance?.config
        // 1) RCON 通道(后台线程,避免网络超时阻塞 UI)
        if (cfg?.rconEnabled == true) {
            val rconFile = File(dir, "rcon.txt")
            val pwd = if (rconFile.exists()) rconFile.readText().trim() else cfg.rconPassword
            val port = cfg.rconPort
            Thread {
                try {
                    val client = RconClient(port = port, password = pwd)
                    if (client.connect(2000)) {
                        client.command(cmd)
                        client.close()
                    }
                } catch (_: Exception) { }
            }.start()
            return
        }
        // 2) FIFO 后备
        val pipe = File(EnvManager.appTmpDir, "cmdpipe-${currentInstance?.id}")
        if (pipe.exists()) {
            try {
                pipe.appendText(cmd + "\n")
            } catch (e: Exception) {
                emit("> 命令发送失败:${e.message}")
            }
        }
    }

    private fun emit(line: String) { _console.tryEmit(line) }

    private fun handleExit() {
        Logger.w("handleExit: manualStop=$manualStop launchedAtMs=$launchedAtMs now=${android.os.SystemClock.elapsedRealtime()}")
        if (manualStop) {
            finalizeStop()
            return
        }
        // 自动重启(仅运行稳定后异常退出才重启;启动 30 秒内的退出 = 启动失败,直接提示)
        val instance = currentInstance ?: run { finalizeStop(); return }
        val earlyExit = launchedAtMs > 0 && android.os.SystemClock.elapsedRealtime() - launchedAtMs < 30_000
        if (!earlyExit && instance.config.autoRestart && restartCount < instance.config.maxRestarts) {
            restartCount++
            emit("> 服务器异常退出,第 $restartCount 次自动重启")
            _status.value = InstanceStatus.STARTING
            scope.launch {
                delay(3000)
                start(instance)
            }
        } else {
            emit("> 服务器已退出")
            if (earlyExit) {
                Logger.w("earlyExit -> Toast 启动失败")
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(
                        context,
                        "启动失败:进程异常退出(环境或配置问题),请查看控制台日志",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
            finalizeStop()
        }
    }

    private fun finalizeStop() {
        _status.value = InstanceStatus.STOPPED
        _players.value = emptyList()
        tailJob?.cancel()
        uptimeJob?.cancel()
        process = null
        restartCount = 0
        currentInstance = null
        // 保活服务保持常驻,通知内容重置为默认
        try {
            ServerKeepAliveService.update(com.mcserver.launcher.KazeApp.instance, null)
        } catch (_: Exception) { }
    }

    // ── 日志 tail ──
    private fun startTail(file: File) {
        tailJob?.cancel()
        tailJob = scope.launch {
            var lastSize = 0L
            var leftover = byteArrayOf()
            while (isActive && process?.isAlive == true) {
                try {
                    val size = file.length()
                    if (size > lastSize) {
                        val newBytes = ByteArray((size - lastSize).toInt())
                        RandomAccessFile(file, "r").use { raf ->
                            raf.seek(lastSize)
                            raf.readFully(newBytes)
                        }
                        lastSize = size
                        val combined = leftover + newBytes
                        val text = String(combined, Charsets.UTF_8)
                        val parts = text.split("\n")
                        val endsWithNl = combined.isNotEmpty() && combined.last() == '\n'.code.toByte()
                        val complete = if (endsWithNl) parts else parts.dropLast(1)
                        for (line in complete) {
                            if (line.isBlank()) continue
                            emit(line)
                            parsePlayerEvents(line)
                        }
                        leftover = if (endsWithNl) byteArrayOf() else parts.last().toByteArray(Charsets.UTF_8)
                    } else if (size < lastSize) { lastSize = 0; leftover = byteArrayOf() }
                } catch (_: Exception) {}
                delay(300)
            }
        }
    }

    private fun startUptime() {
        uptimeJob?.cancel()
        uptimeJob = scope.launch {
            val start = System.currentTimeMillis()
            while (isActive) {
                _uptimeSec.value = (System.currentTimeMillis() - start) / 1000
                delay(1000)
            }
        }
    }

    /** 从日志解析玩家进出 */
    private fun parsePlayerEvents(line: String) {
        val joined = Regex("""([A-Za-z0-9_]{3,16}) joined the game""").find(line)
        val left = Regex("""([A-Za-z0-9_]{3,16}) left the game""").find(line)
        if (joined != null) {
            val name = joined.groupValues[1]
            _players.value = (_players.value + name).distinct()
        } else if (left != null) {
            _players.value = _players.value - left.groupValues[1]
        }
    }

    private fun writeEula(dir: File) {
        val eula = File(dir, "eula.txt")
        if (!eula.exists()) {
            eula.writeText("# eula\n# By changing the setting below to TRUE you are indicating your agreement to our EULA (https://aka.ms/MinecraftEULA).\neula=true\n")
            emit("> 已自动接受 EULA")
        }
    }

    private fun writeServerProperties(dir: File, instance: ServerInstance) {
        val cfg = instance.config
        // RCON 密码固定化:空配置时生成随机密码,并写入 rcon.txt 供控制台指令使用
        val rconPassword = cfg.rconPassword.ifEmpty { "kaze" + (100000..999999).random() }
        File(dir, "rcon.txt").writeText(rconPassword)
        val desired = linkedMapOf(
            "server-port" to cfg.serverPort.toString(),
            "motd" to cfg.motd,
            "max-players" to cfg.maxPlayers.toString(),
            "gamemode" to cfg.gamemode,
            "difficulty" to cfg.difficulty,
            "pvp" to cfg.pvp.toString(),
            "online-mode" to cfg.onlineMode.toString(),
            "white-list" to cfg.whiteList.toString(),
            "spawn-protection" to cfg.spawnProtection.toString(),
            "view-distance" to cfg.viewDistance.toString(),
            "enable-command-block" to "true",
            "enable-rcon" to cfg.rconEnabled.toString(),
            "rcon.port" to cfg.rconPort.toString(),
            "rcon.password" to rconPassword
        )
        val file = File(dir, "server.properties")
        val lines = if (file.exists()) file.readLines().toMutableList() else mutableListOf()
        for ((key, value) in desired) {
            var found = false
            for (i in lines.indices) {
                if (lines[i].trim().startsWith("$key=")) { lines[i] = "$key=$value"; found = true; break }
            }
            if (!found) lines.add("$key=$value")
        }
        file.writeText(lines.joinToString("\n") + "\n")
    }
}
