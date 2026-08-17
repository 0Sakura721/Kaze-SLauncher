package com.kaze.newage.core.server

import com.kaze.newage.core.console.ConsoleStream
import com.kaze.newage.core.console.LineType
import com.kaze.newage.core.download.CoreSources
import com.kaze.newage.core.env.LinuxEnvironment
import com.kaze.newage.core.java.JavaManager
import com.kaze.newage.data.model.CoreType
import com.kaze.newage.data.model.ServerInstance
import com.kaze.newage.util.Downloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 服务端管理器实现：完整生命周期编排，支持**多开**。
 * 每个实例一个 RuntimeSlot（独立进程 / 状态 / 控制台 / 运行时长 / 退出监控），互不干扰。
 * 全局共享的只有 Linux 环境与 Java 安装（一次安装，多实例复用）。
 *
 * 启动链路（用户需求核心）：
 *  1. 检查/部署 Linux 环境（proot + rootfs）
 *  2. 检查/安装 Java（按 MC 版本推断或实例指定）
 *  3. eula 处理三段式：
 *     a. eula.txt 已接受（true）→ 直接启动
 *     b. eula.txt 存在且 false → 改写 true 后启动
 *     c. eula.txt 不存在 → 首启（服务端生成 eula.txt 后自动退出）→ 改写 true → 重启
 *  4. 正常运行，实时控制台输出 + 命令注入（进程 stdin）
 *  5. 退出监控：early-exit 判启动失败；稳定后异常退出可自动重启（限次）
 */
class DefaultServerManager(
    private val env: LinuxEnvironment,
    private val javaManager: JavaManager,
    private val systemConsole: ConsoleStream,
    private val appContext: android.content.Context,
) : ServerManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 运行中的会话槽：instanceId -> slot */
    private val slots = ConcurrentHashMap<String, RuntimeSlot>()

    /** 每实例控制台（保留历史，即使实例停止） */
    private val consoles = ConcurrentHashMap<String, ConsoleStream>()

    private val _states = MutableStateFlow<Map<String, ServerState>>(emptyMap())
    override val states: StateFlow<Map<String, ServerState>> = _states.asStateFlow()

    /** 环境/Java 初始化互斥（多实例同时启动时只部署一次） */
    private val envInitializing = AtomicBoolean(false)
    private val javaInitializing = AtomicBoolean(false)

    // ── 每实例运行会话 ──
    private inner class RuntimeSlot(val instance: ServerInstance) {
        val console: ConsoleStream = consoles.getOrPut(instance.id) { ConsoleStream() }
        val state = MutableStateFlow(ServerState.Idle)
        val uptimeSec = MutableStateFlow(0L)
        var process: Process? = null
        var manualStop = false
        var launchedAtMs = 0L
        var restartCount = 0
        var waitJob: Job? = null
        var uptimeJob: Job? = null

        /** 日志落盘锁：系统消息（slot.log）与服务器 stdout 可能并发写同一文件 */
        private val logLock = Any()

        fun setState(s: ServerState) {
            state.value = s
            _states.value = _states.value + (instance.id to s)
        }

        /** 系统消息（部署/Java/启动/报错，带 "> " 前缀）也持久化：
         *  进程退出/重启后仍可在「日志」页查看完整过程与报错 */
        fun log(text: String, type: LineType = LineType.System) {
            console.emit(text, type)
            persistLine(text)
        }

        private fun persistLine(text: String) {
            try {
                synchronized(logLock) {
                    val f = java.io.File(instance.dir, "console-output.log")
                    f.parentFile?.mkdirs()
                    // 日志轮转：超过 8MB 时把旧日志改名保留，避免无限膨胀
                    if (f.length() > 8L * 1024 * 1024) {
                        val old = java.io.File(instance.dir, "console-output.old.log")
                        old.delete()
                        f.renameTo(old)
                    }
                    f.appendText(text + "\n")
                }
            } catch (_: Exception) { }
        }
    }

    override fun consoleFor(instanceId: String): ConsoleStream =
        consoles.getOrPut(instanceId) { ConsoleStream() }

    override fun uptimeSec(instanceId: String): StateFlow<Long> =
        slots[instanceId]?.uptimeSec ?: MutableStateFlow(0L)

    override fun isRunning(instanceId: String): Boolean =
        slots[instanceId]?.process?.isAlive == true

    // ── 启动 ──
    override suspend fun start(instance: ServerInstance) {
        val existing = slots[instance.id]
        if (existing?.process?.isAlive == true) {
            existing.log("> 服务器已在运行", LineType.Warn)
            return
        }
        val slot = existing ?: RuntimeSlot(instance).also { slots[instance.id] = it }
        slot.manualStop = false
        slot.setState(ServerState.Starting)

        try {
            // 1. 环境（多实例互斥，只部署一次）
            slot.log("> 检查 Linux 环境…", LineType.System)
            if (!env.isReady) {
                if (envInitializing.compareAndSet(false, true)) {
                    try {
                        slot.log("> 环境未就绪，开始部署…", LineType.System)
                        env.setup { progress, message ->
                            slot.log("> 部署 ${(progress * 100).toInt()}%：$message", LineType.System)
                        }
                    } finally {
                        envInitializing.set(false)
                    }
                } else {
                    // 另一个实例正在部署，等待其完成
                    val waited = waitFor { env.isReady }
                    if (!waited) throw RuntimeException("等待环境部署超时")
                }
                if (!env.isReady) throw RuntimeException("Linux 环境部署失败，请查看部署日志")
            }
            slot.log("> Linux 环境就绪", LineType.System)

            // 2. Java（多实例互斥）
            val javaMajor = instance.javaMajor
            slot.log("> 检查 Java $javaMajor（实例指定）…", LineType.System)
            val runtime = try {
                val already = javaManager.installed().firstOrNull { it.version == javaMajor.toString() }
                when {
                    already != null -> already
                    javaInitializing.compareAndSet(false, true) -> {
                        try {
                            javaManager.install(javaMajor, onProgress = { _, message ->
                                slot.log("> $message", LineType.System)
                            })
                        } finally {
                            javaInitializing.set(false)
                        }
                    }
                    else -> {
                        val waited = waitFor { javaManager.installed().any { it.version == javaMajor.toString() } }
                        if (!waited) throw RuntimeException("等待 Java 安装超时")
                        javaManager.installed().first { it.version == javaMajor.toString() }
                    }
                }
            } catch (e: Exception) {
                throw RuntimeException("Java $javaMajor 安装失败：${e.message}")
            }
            slot.log("> Java ${runtime.version} 就绪", LineType.System)

            // 3. 服务端 jar
            val jar = instance.jarFile
            if (!jar.exists()) throw RuntimeException("实例目录中没有服务端核心 jar：${jar.path}")

            // 3.5 patched 核心（Paper/Purpur）预置原版 jar + 修补 rootfs 结构
            ensureVanillaJar(slot, instance)
            patchRootfs(slot)

            // 4. eula 三段式
            when {
                EulaHandler.isAccepted(instance.dir) -> {
                    slot.log("> eula 已接受", LineType.System)
                    launchServer(slot, runtime.version, jar.name)
                }
                instance.eulaFile.exists() -> {
                    slot.log("> eula.txt 为 false，正在改写为 true…", LineType.System)
                    EulaHandler.flipToTrue(instance.dir)
                    launchServer(slot, runtime.version, jar.name)
                }
                else -> {
                    // 首启：生成 eula.txt 后服务端自动退出
                    slot.setState(ServerState.FirstRun)
                    slot.log("> 首次启动：服务端将生成 eula.txt 并自动退出", LineType.System)
                    slot.log("> （等待自动退出后自动改写 eula=true 并重启）", LineType.System)
                    val exitCode = runEulaProbe(slot, runtime.version, jar.name)
                    slot.log("> 首启进程已退出（exit=$exitCode）", LineType.System)
                    if (!EulaHandler.isAccepted(instance.dir)) {
                        slot.log("> 正在改写 eula.txt → true…", LineType.System)
                        EulaHandler.flipToTrue(instance.dir)
                    }
                    slot.log("> 重新启动服务器…", LineType.System)
                    launchServer(slot, runtime.version, jar.name)
                }
            }
        } catch (e: Exception) {
            slot.log("> 启动失败：${e.message}", LineType.Error)
            slot.setState(ServerState.Error)
        }
    }

    /**
     * patched 核心（Paper/Purpur）预置原版 server jar：
     * paperclip 启动时需从 launcher.mojang.com 下载对应版本 vanilla jar（国内 DNS 常不可达，
     * 报 "Failed to download mojang_x.jar / UnknownHostException"）。预置到
     * `实例目录/versions/<mc>/<mc>.jar` 后 paperclip 检测到文件存在即跳过联网。
     * 下载源：官方 piston-meta → piston-data，失败换 BMCLAPI 国内镜像。
     */
    private suspend fun ensureVanillaJar(slot: RuntimeSlot, instance: ServerInstance) {
        if (instance.coreType != CoreType.PAPER && instance.coreType != CoreType.PURPUR) return
        val mc = instance.mcVersion
        if (mc.isBlank()) return
        val dest = File(instance.dir, "versions/$mc/$mc.jar")
        if (dest.exists() && dest.length() > 1_000_000) return

        slot.log("> 预置原版服务端 jar（$mc，Paper/Purpur 启动需要）…", LineType.System)
        val tmp = File(instance.dir, "versions/$mc/$mc.jar.part")
        val sources = buildList {
            CoreSources.getVanillaDownload(mc).getOrNull()?.let { add(it.url) }
            add("https://bmclapi2.bangbang93.com/download/$mc/server")
            add("https://bmclapi2.bangbang93.com/version/$mc/server")
        }.distinct()

        var lastErr: Exception? = null
        for (url in sources) {
            try {
                var lastMb = 0L
                Downloader.download(
                    url,
                    tmp,
                    onProgress = { d, t ->
                        val mb = d / 1024 / 1024
                        if (mb - lastMb >= 10) { // 10MB 打一条，避免刷屏
                            lastMb = mb
                            val total = if (t > 0) "/ ${t / 1024 / 1024}MB" else ""
                            slot.log("> 下载原版 $mc：${mb}MB$total", LineType.System)
                        }
                    },
                    validate = { f -> f.length() > 1_000_000 },
                )
                if (!tmp.renameTo(dest)) {
                    tmp.copyTo(dest, overwrite = true)
                    tmp.delete()
                }
                slot.log("> 原版服务端预置完成（$mc）", LineType.System)
                return
            } catch (e: Exception) {
                lastErr = e
            }
        }
        slot.log("> 原版服务端预置失败（$mc）：${lastErr?.message ?: "全部源失败"}，Paper 启动可能受影响", LineType.Warn)
    }

    /** 修补 rootfs 结构缺失：sys/.empty 丢失会产生 proot sanitize 警告（无害，顺带补齐） */
    private fun patchRootfs(slot: RuntimeSlot) {
        runCatching {
            val se = File(env.rootfsDir, "sys/.empty")
            if (!se.exists()) {
                se.parentFile?.mkdirs()
                se.writeText("")
            }
        }
    }

    /** 首启探测：直接跑一次服务端，输出逐行转发，等它自动退出 */
    private suspend fun runEulaProbe(slot: RuntimeSlot, javaVersion: String, jarName: String): Int? {
        val javaBin = "/usr/lib/jvm/java-$javaVersion-openjdk-${archSuffix()}/bin/java"
        val args = javaArgs(slot.instance, javaBin, jarName)
        return env.execute(args, slot.instance.dir) { line ->
            slot.log(line, classify(line))
        }
    }

    /** 正常启动：进程常驻 + 后台消费输出 + 退出监控 */
    private fun launchServer(slot: RuntimeSlot, javaVersion: String, jarName: String) {
        val javaBin = "/usr/lib/jvm/java-$javaVersion-openjdk-${archSuffix()}/bin/java"
        val args = javaArgs(slot.instance, javaBin, jarName)
        val proc = env.launch(args, slot.instance.dir)
            ?: throw RuntimeException("无法启动 proot 进程（环境异常）")
        slot.process = proc
        slot.launchedAtMs = System.currentTimeMillis()
        slot.setState(ServerState.Running)
        slot.log("> 服务器启动中", LineType.System)
        startGuard(slot.instance)

        // 消费输出（slot.log 已同步写入运行日志 console-output.log）
        scope.launch {
            try {
                proc.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (line.isNotBlank()) {
                            slot.log(line, classify(line))
                        }
                    }
                }
            } catch (_: Exception) { }
        }

        // 退出监控
        slot.waitJob?.cancel()
        slot.waitJob = scope.launch {
            val code = proc.waitFor()
            slot.log("> 服务器进程退出（exit=$code）", LineType.System)
            handleExit(slot)
        }
        startUptime(slot)
    }

    // ── 停止 ──
    override suspend fun stop(instance: ServerInstance) {
        val slot = slots[instance.id] ?: run {
            _states.value = _states.value + (instance.id to ServerState.Stopped)
            return
        }
        val proc = slot.process
        if (proc == null || !proc.isAlive) {
            finalizeStop(slot)
            return
        }
        slot.manualStop = true
        slot.setState(ServerState.Stopping)
        slot.log("> 正在停止服务器…", LineType.System)
        sendCommand(instance, "stop")
        // 优雅停止等待 10s，超时强杀
        scope.launch {
            delay(10_000)
            if (slot.process?.isAlive == true) {
                slot.log("> 停止超时，强制结束进程", LineType.Warn)
                slot.process?.destroyForcibly()
            }
        }
    }

    /** 命令注入：写入进程 stdin */
    override fun sendCommand(instance: ServerInstance, command: String) {
        if (command.isBlank()) return
        val slot = slots[instance.id] ?: return
        slot.log("> $command", LineType.Command)
        val proc = slot.process ?: return
        try {
            proc.outputStream.write((command + "\n").toByteArray(Charsets.UTF_8))
            proc.outputStream.flush()
        } catch (e: Exception) {
            slot.log("> 命令发送失败：${e.message}", LineType.Error)
        }
    }

    // ── 退出处理 ──
    private fun handleExit(slot: RuntimeSlot) {
        if (slot.manualStop) {
            finalizeStop(slot)
            return
        }
        val earlyExit = System.currentTimeMillis() - slot.launchedAtMs < 30_000
        if (!earlyExit && slot.instance.autoRestart && slot.restartCount < slot.instance.maxRestarts) {
            slot.restartCount++
            slot.log("> 服务器异常退出，第 ${slot.restartCount} 次自动重启", LineType.Warn)
            slot.setState(ServerState.Starting)
            scope.launch {
                delay(3000)
                start(slot.instance)
            }
        } else {
            if (earlyExit) {
                slot.log("> 启动失败：进程 30 秒内退出（环境或配置问题）", LineType.Error)
            } else {
                slot.log("> 服务器已退出", LineType.System)
            }
            finalizeStop(slot)
        }
    }

    private fun finalizeStop(slot: RuntimeSlot) {
        slot.process = null
        slot.waitJob?.cancel()
        slot.uptimeJob?.cancel()
        slot.uptimeSec.value = 0L
        slot.restartCount = 0
        slot.setState(ServerState.Stopped)
        slots.remove(slot.instance.id)
        // 全部实例停止后撤下守护前台服务
        if (slots.values.none { it.process?.isAlive == true }) {
            com.kaze.newage.core.service.ServerGuardService.stop(appContext)
        }
    }

    /** 启动/更新守护前台服务（防止应用退后台后服务端进程被系统回收） */
    private fun startGuard(instance: ServerInstance) {
        val port = ServerProperties.load(instance.dir)["server-port"] ?: "25565"
        com.kaze.newage.core.service.ServerGuardService.start(
            appContext,
            "Kaze SLauncher · ${instance.name}",
            "MC ${instance.mcVersion} 服务端运行中 · 端口 $port",
        )
    }

    // ── 内部工具 ──
    private suspend fun waitFor(timeoutMs: Long = 300_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            delay(1000)
        }
        return condition()
    }

    private fun javaArgs(instance: ServerInstance, javaBin: String, jarName: String): List<String> =
        buildList {
            add(javaBin)
            add("-Xmx${instance.memoryMb}M")
            add("-Xms${(instance.memoryMb / 2).coerceAtLeast(256)}M")
            add("-jar")
            add(jarName)
            if (instance.nogui) add("nogui")
        }

    private fun classify(line: String): LineType = when {
        line.contains("ERROR", ignoreCase = true) -> LineType.Error
        line.contains("WARN", ignoreCase = true) || line.contains("WARNING", ignoreCase = true) -> LineType.Warn
        line.startsWith("> ") -> LineType.System
        else -> LineType.Info
    }

    private fun archSuffix(): String =
        if (android.os.Build.SUPPORTED_ABIS.any { it.contains("arm64-v8a", true) || it.contains("aarch64", true) }) "arm64" else "armhf"

    private fun startUptime(slot: RuntimeSlot) {
        slot.uptimeJob?.cancel()
        slot.uptimeJob = scope.launch {
            while (isActive) {
                delay(1000)
                if (slot.process?.isAlive == true) slot.uptimeSec.value += 1
            }
        }
    }
}
