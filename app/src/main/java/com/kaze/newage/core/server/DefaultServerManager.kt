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

    /** 需要保活前台服务的生命周期状态（Starting 等阶段 process 尚未创建，不能只看 isAlive） */
    private val guardActiveStates = setOf(
        ServerState.Starting,
        ServerState.FirstRun,
        ServerState.AcceptingEula,
        ServerState.Running,
        ServerState.Stopping,
    )

    /**
     * `start()` 协程正在推进、进程可能尚未创建的状态。
     * 处于这些状态时收到停止请求，必须置取消标志让 start() 自己收尾，
     * 不能直接 finalizeStop（否则 start() 收不到信号，稍后仍会拉起一个停不掉的进程）。
     */
    private val startingStates = setOf(
        ServerState.Starting,
        ServerState.FirstRun,
        ServerState.AcceptingEula,
    )

    /** start() 被用户中止——属于正常收尾，不应记为 Error */
    private class StartCancelled : Exception("用户已请求停止")

    // ── 每实例运行会话 ──
    private inner class RuntimeSlot(val instance: ServerInstance) {
        val console: ConsoleStream = consoles.getOrPut(instance.id) { ConsoleStream() }
        val state = MutableStateFlow(ServerState.Idle)
        /** 与 [uptimeFlows] 共用同一个流：slot 会在停止时被移除，流不能跟着消失（否则重启后时长恒 0） */
        val uptimeSec: MutableStateFlow<Long> get() = uptimeFlows.getOrPut(instance.id) { MutableStateFlow(0L) }
        var process: Process? = null
        var manualStop = false
        var launchedAtMs = 0L
        var restartCount = 0
        var waitJob: Job? = null
        var uptimeJob: Job? = null

        /** 「优雅停止 → 10s 后强杀」的延时任务。重启前必须取消，否则它会杀掉新进程。
         *  见 stop() 的说明。 */
        var stopTimeoutJob: Job? = null

        /** start() 各阶段的取消检查点：用户点停止后尽快中止后续重活（部署/装 Java/首启探测） */
        fun checkCancelled() {
            if (manualStop) throw StartCancelled()
        }

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

        /** \r 覆盖式进度行：控制台替换上一行（文件仍逐行落盘，与服务器原始日志一致） */
        fun logReplace(text: String, type: LineType) {
            console.emitReplace(text, type)
            persistLine(text)
        }

        private fun persistLine(text: String) {
            try {
                synchronized(logLock) {
                    val f = java.io.File(instance.dir, "console-output.log")
                    f.parentFile?.mkdirs()
                    // 日志轮转：超过 8MB 时把旧日志改名保留，避免无限膨胀。
                    // renameTo 的返回值必须检查：失败时（文件被占用/权限）旧写法会继续往超限文件里
                    // append，8MB 上限形同虚设 → 退化为直接截断，保证上限一定生效。
                    if (f.length() > 8L * 1024 * 1024) {
                        val old = java.io.File(instance.dir, "console-output.old.log")
                        old.delete()
                        if (!f.renameTo(old)) {
                            runCatching { f.writeText("") }
                        }
                    }
                    f.appendText(text + "\n")
                }
            } catch (e: Exception) {
                // 不再完全静默：实例目录不可写（SD 卡拔出/空间满）时日志会停止落盘，
                // 至少留下一条线索
                android.util.Log.w("KazeSLauncher", "日志落盘失败: ${e.message}")
            }
        }
    }

    override fun consoleFor(instanceId: String): ConsoleStream =
        consoles.getOrPut(instanceId) { ConsoleStream() }

    /**
     * 运行时长流。
     *
     * 必须**长期持有**每个实例的流：旧实现是 `slots[id]?.uptimeSec ?: MutableStateFlow(0L)`，
     * 而 finalizeStop 会把 slot 从表里移除，同一实例再启动时会换成一个新的 slot 对象；
     * 上层（AppViewModel）用 `flatMapLatest { currentInstanceId }` 订阅，实例 id 没变就不会重订阅，
     * 于是"停止→再启动"之后运行时长永远显示 0 秒。
     */
    private val uptimeFlows = java.util.concurrent.ConcurrentHashMap<String, MutableStateFlow<Long>>()

    override fun uptimeSec(instanceId: String): StateFlow<Long> =
        uptimeFlows.getOrPut(instanceId) { MutableStateFlow(0L) }

    override fun isRunning(instanceId: String): Boolean =
        slots[instanceId]?.process?.isAlive == true

    // ── 启动 ──
    override suspend fun start(instance: ServerInstance) {
        val existing = slots[instance.id]
        // 按生命周期状态防重入：Starting/FirstRun 等部署阶段 process 尚为 null，
        // 只看 isAlive 会漏——重复触发会双进程共写同一世界目录
        if (existing != null && existing.state.value in guardActiveStates) {
            existing.log("> 已处于启动/运行中，忽略重复启动", LineType.Warn)
            return
        }
        val slot = existing ?: RuntimeSlot(instance).also { slots[instance.id] = it }
        slot.manualStop = false
        slot.setState(ServerState.Starting)
        // 一启动就保活：环境部署/Java 安装可能耗时数分钟，期间应用退后台也不能被杀
        startGuard(instance)

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
            slot.checkCancelled()

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
                        // 别的实例在装 Java——可能是不同版本！旧实现只轮询"自己要的版本出现"，
                        // 获胜者装完的是它自己的版本 → 第二实例必然 300s 超时。
                        // 改为：轮询期间只要互斥空闲就自己动手装目标版本
                        var runtime: com.kaze.newage.core.java.JavaRuntime? = null
                        val deadline = System.currentTimeMillis() + 300_000
                        while (System.currentTimeMillis() < deadline) {
                            runtime = javaManager.installed().firstOrNull { it.version == javaMajor.toString() }
                            if (runtime != null) break
                            if (javaInitializing.compareAndSet(false, true)) {
                                try {
                                    javaManager.install(javaMajor, onProgress = { _, message ->
                                        slot.log("> $message", LineType.System)
                                    })
                                } finally {
                                    javaInitializing.set(false)
                                }
                                continue // 再查一遍已装列表
                            }
                            kotlinx.coroutines.delay(1000)
                        }
                        runtime ?: throw RuntimeException("等待 Java 安装超时")
                    }
                }
            } catch (e: Exception) {
                throw RuntimeException("Java $javaMajor 安装失败：${e.message}")
            }
            slot.log("> Java ${runtime.version} 就绪", LineType.System)
            slot.checkCancelled()

            // 3. 服务端核心
            // Forge/NeoForge 下载到的是 installer：必须先 `--installServer` 生成启动入口，
            // 之后才能像其它核心一样启动（安装过程本身会联网下载依赖，可能几分钟）
            val isForgeLike = instance.coreType == CoreType.FORGE || instance.coreType == CoreType.NEOFORGE
            if (isForgeLike) {
                ensureForgeInstalled(slot, instance, runtime.version)
            } else {
                val jar = instance.jarFile
                if (!jar.exists()) throw RuntimeException("实例目录中没有服务端核心 jar：${jar.path}")
            }

            // 3.5 patched 核心（Paper/Purpur）预置原版 jar + 修补 rootfs 结构
            ensureVanillaJar(slot, instance)
            patchRootfs(slot)
            // 取消检查点：ensureVanillaJar 可能要下几十 MB 的原版 jar，这是启动流程里最长的一段。
            // 没有这个检查点的话，用户在下载中原版 jar 时点「停止」只会置上标志，
            // start() 却一路走到 launchServer —— 界面显示"已停止"，服务端照样被拉起来。
            slot.checkCancelled()

            // 4. eula 三段式
            when {
                EulaHandler.isAccepted(instance.dir) -> {
                    slot.log("> eula 已接受", LineType.System)
                    launchServer(slot, runtime.version)
                }
                instance.eulaFile.exists() -> {
                    slot.log("> eula.txt 为 false，正在改写为 true…", LineType.System)
                    acceptEula(slot, instance)
                    launchServer(slot, runtime.version)
                }
                // 导入的自定义核心不一定是 Minecraft 服务端（Velocity / BungeeCord / 自研代理…）：
                // 它们既不读 eula.txt、也不会"生成后自动退出"。走首启探测会永久卡在 FirstRun——
                // 服务其实正常在跑，但控制台不能发命令、也没有任何停止入口。直接常驻启动。
                instance.coreType == CoreType.CUSTOM -> {
                    slot.log("> 自定义核心：跳过 eula 首启探测，直接启动", LineType.System)
                    launchServer(slot, runtime.version)
                }
                else -> {
                    // 首启：生成 eula.txt 后服务端自动退出
                    slot.setState(ServerState.FirstRun)
                    slot.log("> 首次启动：服务端将生成 eula.txt 并自动退出", LineType.System)
                    slot.log("> （等待自动退出后自动改写 eula=true 并重启）", LineType.System)
                    val exitCode = runEulaProbe(slot, runtime.version)
                    slot.log("> 首启进程已退出（exit=$exitCode）", LineType.System)
                    // 首启探测期间用户可能点了停止：此时不应再改写 eula / 重新拉起服务端
                    slot.checkCancelled()
                    if (!EulaHandler.isAccepted(instance.dir)) {
                        slot.log("> 正在改写 eula.txt → true…", LineType.System)
                        acceptEula(slot, instance)
                    }
                    slot.log("> 重新启动服务器…", LineType.System)
                    launchServer(slot, runtime.version)
                }
            }
        } catch (e: StartCancelled) {
            // 用户主动中止：走正常收尾，不置 Error
            slot.log("> 已取消启动", LineType.System)
            finalizeStop(slot)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 协程被取消（进程退出/作用域结束）不是"启动失败"，必须原样抛出：
            // 旧实现让它落到下面的 catch(Exception)，于是界面留下一条永久的
            // "启动失败：Job was cancelled"，还把状态置成 Error、撤下保活前台服务。
            slot.log("> 启动已中断", LineType.Warn)
            finalizeStop(slot)
            throw e
        } catch (e: Exception) {
            slot.log("> 启动失败：${e.message}", LineType.Error)
            slot.setState(ServerState.Error)
            // 启动失败且没有其他实例在跑：撤下保活前台服务
            // （按生命周期状态判断——其他实例处于启动部署阶段时 process 为 null，
            //   用 process.isAlive 会误判"无实例在跑"而提前撤下它的保活）
            if (slots.values.none { it.state.value in guardActiveStates }) {
                com.kaze.newage.core.service.ServerGuardService.stop(appContext)
            }
        }
    }

    /**
     * patched 核心（Paper/Purpur）预置原版 server jar：
     * 旧版 paperclip 启动时读 jar 内 META-INF/download-context（`<sha256>\t<url>\t<fileName>`），
     * 若 `CWD/cache/<fileName>` 不存在或 SHA256 不匹配，则从 url（launcher.mojang.com，国内 DNS
     * 常不可达 → "Failed to download mojang_x.jar / UnknownHostException"）重新下载。
     * 预置正确哈希的原版 jar 到 `实例目录/cache/<fileName>` 即跳过联网。
     * 下载源：官方 piston-meta → piston-data，失败换 BMCLAPI 国内镜像。
     */
    private suspend fun ensureVanillaJar(slot: RuntimeSlot, instance: ServerInstance) {
        if (instance.coreType != CoreType.PAPER && instance.coreType != CoreType.PURPUR) return
        val jar = instance.jarFile
        if (!jar.exists()) return
        val dc: Triple<String, String, String> = try {
            java.util.zip.ZipFile(jar).use { zip ->
                val entry = zip.getEntry("META-INF/download-context") ?: return
                val parts = zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }.trim()
                    .split(Regex("\\s+"))
                if (parts.size < 3) return
                Triple(parts[0].lowercase(), parts[1], parts[2])
            }
        } catch (_: Exception) { return }
        val (hash, _, fileName) = dc
        val mc = paperclipVanillaVersion(instance)
        if (mc.isBlank()) return
        val dest = File(instance.dir, "cache/$fileName")
        if (dest.exists() && sha256Hex(dest) == hash) return

        slot.log("> 预置原版服务端 jar（$mc → cache/$fileName，Paper 启动需要）…", LineType.System)
        val tmp = File(instance.dir, "cache/$fileName.part")
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
                    // 让下载本身也能被「停止」打断，而不是下完这几十 MB 才发现被取消
                    shouldCancel = { slot.manualStop },
                    onProgress = { d, t ->
                        val mb = d / 1024 / 1024
                        if (mb - lastMb >= 10) { // 10MB 一条，避免刷屏
                            lastMb = mb
                            val total = if (t > 0) "/ ${t / 1024 / 1024}MB" else ""
                            slot.log("> 下载原版 $mc：${mb}MB$total", LineType.System)
                        }
                    },
                    validate = { f -> f.length() > 1_000_000 },
                )
                if (sha256Hex(tmp) == hash) {
                    if (!tmp.renameTo(dest)) {
                        tmp.copyTo(dest, overwrite = true)
                        tmp.delete()
                    }
                    slot.log("> 原版服务端预置完成（$mc）", LineType.System)
                    return
                }
                tmp.delete() // 哈希不匹配（镜像内容不一致），换源
                lastErr = RuntimeException("SHA256 不匹配")
            } catch (e: Exception) {
                lastErr = e
            }
        }
        slot.log("> 原版服务端预置失败（$mc）：${lastErr?.message ?: "全部源失败"}，Paper 启动可能受影响", LineType.Warn)
    }

    /** SHA-256 hex（小写） */
    private fun sha256Hex(f: File): String = try {
        java.security.MessageDigest.getInstance("SHA-256").let { md ->
            f.inputStream().use { ins ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }
    } catch (_: Exception) { "" }

    /**
     * paperclip 要找的 vanilla 版本：读核心 jar 根目录 version.json 的 id 字段。
     * Paper 的构建版本号与内部 MC 版本可能不一致（如 paper-1.18.2-217.jar 的
     * version.json id=1.18.1 → paperclip 找 versions/1.18.1/1.18.1.jar）。
     */
    private fun paperclipVanillaVersion(instance: ServerInstance): String {
        val jar = instance.jarFile
        if (jar.exists()) {
            try {
                java.util.zip.ZipFile(jar).use { zip ->
                    val entry = zip.getEntry("version.json") ?: return@use
                    val text = zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
                    val id = Regex(""""id"\s*:\s*"([^"]+)"""").find(text)?.groupValues?.get(1)
                    if (!id.isNullOrBlank()) return id
                }
            } catch (_: Exception) { }
        }
        return instance.mcVersion
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

    /**
     * 首启探测：直接跑一次服务端，输出逐行转发，等它自动退出。
     *
     * 这里用 launch() 而不是 execute()，是为了把探测进程登记到 `slot.process`：
     * 旧实现走 execute()，该进程游离在 slot 之外，用户点停止时它既不进 10 秒强杀路径，
     * 也没有任何代码持有它的引用，只能干等它自己退出（首启探测会跑完整个服务端启动过程）。
     */
    /**
     * 把 eula.txt 改成已接受；改不动就明确报错。
     *
     * `flipToTrue` 是**按正则替换** `eula=false` 这类写法的：用户手改过的 eula.txt
     * （`eula = False`、行尾带注释、混杂 CRLF…）可能匹配不到而原样返回。旧实现不看返回值
     * 就直接拉起服务端 → 服务端立即退出，界面只报「环境或配置问题」，用户重试多少次都一样，
     * 也完全不知道问题出在 eula.txt。
     */
    private fun acceptEula(slot: RuntimeSlot, instance: ServerInstance) {
        if (EulaHandler.flipToTrue(instance.dir).accepted) return
        slot.log("> 未能按常规改写 eula.txt，改为直接覆盖写入", LineType.Warn)
        if (!EulaHandler.accept(instance.dir).accepted) {
            throw RuntimeException(
                "eula.txt 存在但无法自动改写为 eula=true（可能被占用或目录只读），请手动改成 eula=true 后重试"
            )
        }
    }

    private suspend fun runEulaProbe(slot: RuntimeSlot, javaVersion: String): Int? {
        val args = serverArgs(slot.instance, javaVersion) ?: return null
        val proc = env.launch(args, slot.instance.dir) ?: return null
        slot.process = proc
        return try {
            proc.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line -> slot.log(line, classify(line)) }
            }
            proc.waitFor()
            proc.exitValue()
        } catch (e: Exception) {
            proc.destroyForcibly()
            null
        } finally {
            if (slot.process === proc) slot.process = null
        }
    }

    /** 正常启动：进程常驻 + 后台消费输出 + 退出监控 */
    private fun launchServer(slot: RuntimeSlot, javaVersion: String) {
        val args = serverArgs(slot.instance, javaVersion)
            ?: throw RuntimeException("找不到可启动的服务端入口（核心未安装完成？）")
        val proc = env.launch(args, slot.instance.dir)
            ?: throw RuntimeException("无法启动 proot 进程（环境异常）")
        slot.process = proc
        // 上一轮的「10s 强杀」若还挂着，必须取消：它捕获的是旧进程，留着只会误杀本次新进程
        slot.stopTimeoutJob?.cancel()
        slot.stopTimeoutJob = null
        slot.launchedAtMs = System.currentTimeMillis()
        slot.setState(ServerState.Running)
        slot.log("> 服务器启动中", LineType.System)
        startGuard(slot.instance)

        // 消费输出（slot.log 已同步写入运行日志 console-output.log）
        scope.launch {
            try {
                // 逐字符流式读取：\r = 覆盖式进度行（控制台替换上一行，避免几百行刷屏），
                // \n = 普通行。readLine 会把 \r 也当分隔符，无法区分，故手写分割。
                val reader = proc.inputStream.bufferedReader()
                val sb = StringBuilder()
                // 块读取：syscall 从"每字符一次"降到每 8KB 一次，其余行为不变
                val buf = CharArray(8 * 1024)
                fun flushLine(replace: Boolean) {
                    val text = sb.toString().trim()
                    sb.setLength(0)
                    if (text.isNotEmpty()) {
                        if (replace) slot.logReplace(text, classify(text))
                        else slot.log(text, classify(text))
                    }
                }
                while (true) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    for (i in 0 until n) {
                        when (buf[i]) {
                            '\r' -> flushLine(replace = true)
                            '\n' -> if (sb.isNotEmpty()) flushLine(replace = false)
                            else -> sb.append(buf[i])
                        }
                    }
                }
                if (sb.isNotEmpty()) flushLine(replace = false)
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
            // 进程还没创建，但 start() 的协程仍在推进（部署 / 装 Java / 首启探测）：
            // 必须置取消标志让 start() 自己收尾。旧实现在这里直接 finalizeStop ——
            // 它跳过了下一行的 manualStop = true，于是 start() 收不到任何信号、
            // 稍后照常拉起进程，而 slot 已被移除 → UI 显示"已停止"、实际有 java 在跑，
            // 且再点停止只会走 slots[id] == null 分支，进程永远停不掉。
            if (slot.state.value in startingStates) {
                slot.manualStop = true
                slot.setState(ServerState.Stopping)
                slot.log("> 已请求停止：等待当前启动流程收尾…", LineType.System)
                return
            }
            finalizeStop(slot)
            return
        }
        slot.manualStop = true
        slot.setState(ServerState.Stopping)
        slot.log("> 正在停止服务器…", LineType.System)
        sendCommand(instance, "stop")
        // 优雅停止等待 10s，超时强杀。
        // 注意捕获当前的 proc，而不是在延时回调里读 slot.process ——
        // 若用户在 10s 内重新启动，slot.process 已指向新进程，旧写法会把刚启动的服务端杀掉；
        // 同时把 Job 存起来，重启前取消它。
        slot.stopTimeoutJob?.cancel()
        slot.stopTimeoutJob = scope.launch {
            delay(10_000)
            if (proc.isAlive) {
                // 先 SIGTERM，而不是直接 SIGKILL：proot 收到 SIGTERM 才有机会执行 `--kill-on-exit`
                // 的清理，把它 trace 的 guest 进程（java）一起带走。
                // 旧实现直接 destroyForcibly()（SIGKILL）→ proot 被瞬间杀死、来不及清理，
                // 它下面的 java 会脱离继续运行：界面显示"已停止"，实际仍有 java 占着端口
                // 和世界文件；用户再点启动就会在同一个世界目录上拉起第二个 java。
                slot.log("> 停止超时，正在结束进程（SIGTERM，让 proot 清理 guest）…", LineType.Warn)
                proc.destroy()
                delay(5_000)
            }
            if (proc.isAlive) {
                slot.log(
                    "> 进程仍未退出，强制结束。若随后端口仍被占用，说明 guest 里的 java 未被回收",
                    LineType.Warn,
                )
                proc.destroyForcibly()
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
            // 必须先把状态退回 Stopped 再重启：start() 的防重入判定把 Starting 也算作"已在运行"
            // 而直接 return，于是自动重启永远不会真的发生，状态永久卡在 Starting——
            // 四个页面全是禁用态、没有任何停止入口，只能杀掉应用。
            slot.setState(ServerState.Stopped)
            scope.launch {
                delay(3000)
                // 重启窗口内用户可能已删除实例：目录没了就不再拉起（否则重建半成品实例）
                if (!slot.instance.dir.exists()) {
                    slot.log("> 实例目录已删除，取消自动重启", LineType.System)
                    finalizeStop(slot)
                    return@launch
                }
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
        // 全部实例停止后撤下守护前台服务；仍有活跃实例则刷新聚合通知
        // （同上按状态判断：其他实例 Starting 部署中 process 为 null，不能只看 isAlive）
        val remaining = slots.values.filter { it.state.value in guardActiveStates }
        if (remaining.isEmpty()) {
            com.kaze.newage.core.service.ServerGuardService.stop(appContext)
        } else {
            updateGuard(remaining)
        }
    }

    /** 守护通知内容：单实例显示详情，多开聚合成一行 */
    private fun updateGuard(active: List<RuntimeSlot>) {
        if (active.isEmpty()) return
        if (active.size == 1) {
            val s = active.first()
            val port = runCatching {
                ServerProperties.load(s.instance.dir)["server-port"] ?: "25565"
            }.getOrDefault("25565")
            com.kaze.newage.core.service.ServerGuardService.start(
                appContext,
                "Kaze SLauncher · ${s.instance.name}",
                "MC ${s.instance.mcVersion} 服务端运行中 · 端口 $port",
            )
        } else {
            com.kaze.newage.core.service.ServerGuardService.start(
                appContext,
                "Kaze SLauncher · ${active.size} 个实例运行中",
                active.joinToString("、") { it.instance.name },
            )
        }
    }

    /** 启动/更新守护前台服务（防止应用退后台后服务端进程被系统回收） */
    private fun startGuard(instance: ServerInstance) {
        // 以"当前全部活跃实例"为准聚合（本实例已 setState 过，slots 里能查到）
        updateGuard(slots.values.filter { it.state.value in guardActiveStates })
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

    /**
     * JVM 启动参数（移动端 proot 环境实测优化）：
     *  - Xms=Xmx：消除堆扩容停顿（启动阶段反复扩容是拖慢主因之一）
     *  - PerfDisableSharedMem：禁止写 /tmp/hsperfdata_*（proot 映射 /tmp 有 IO 开销且无意义）
     *  - MaxGCPauseMillis：G1 目标停顿，避免长时间 STW
     *  - java.security.egd=urandom：Java 8/17 上 SecureRandom 阻塞读 /dev/random 的规避
     */
    private fun javaArgs(instance: ServerInstance, javaBin: String, jarName: String): List<String> =
        buildList {
            add(javaBin)
            add("-Xmx${instance.memoryMb}M")
            add("-Xms${instance.memoryMb}M")
            add("-XX:+PerfDisableSharedMem")
            add("-XX:MaxGCPauseMillis=200")
            add("-Djava.security.egd=file:/dev/urandom")
            add("-jar")
            add(jarName)
            if (instance.nogui) add("nogui")
        }

    /**
     * 该实例的启动参数。
     *  - 普通核心：`java <jvm 参数> -jar <核心 jar> nogui`
     *  - Forge/NeoForge：走 [forgeArgs]（现代版是 `@unix_args.txt`，旧版是 `-jar forge-*.jar`）
     * 返回 null 表示找不到可启动入口（核心缺失或未安装完成）。
     */
    private fun serverArgs(instance: ServerInstance, javaVersion: String): List<String>? {
        val javaBin = "/usr/lib/jvm/java-$javaVersion-openjdk-${archSuffix()}/bin/java"
        val isForgeLike = instance.coreType == CoreType.FORGE || instance.coreType == CoreType.NEOFORGE
        return if (isForgeLike) {
            forgeArgs(instance, javaBin)
        } else {
            val jar = instance.jarFile
            if (jar.exists()) javaArgs(instance, javaBin, jar.name) else null
        }
    }

    /**
     * Forge / NeoForge 的启动参数。
     *  - 现代版（Forge 1.17+ / NeoForge）：安装生成 `libraries/.../unix_args.txt`，
     *    用 Java 的 @argfile 语法传入；`nogui` 必须由命令行追加（args 文件里没有）
     *  - 旧版（≤1.16.5）：安装生成可直接 `-jar` 的 forge-*.jar
     */
    private fun forgeArgs(instance: ServerInstance, javaBin: String): List<String>? {
        val argsFile = instance.forgeArgsFile()
        val legacyJar = instance.legacyForgeJar()
        if (argsFile == null && legacyJar == null) return null
        return buildList {
            add(javaBin)
            add("-Xmx${instance.memoryMb}M")
            add("-Xms${instance.memoryMb}M")
            add("-XX:+PerfDisableSharedMem")
            add("-XX:MaxGCPauseMillis=200")
            add("-Djava.security.egd=file:/dev/urandom")
            if (argsFile != null) {
                // user_jvm_args.txt 是 Forge 留给用户覆盖 JVM 参数的位置，存在就一并传入
                if (File(instance.dir, "user_jvm_args.txt").isFile) add("@user_jvm_args.txt")
                val rel = argsFile.relativeTo(File(instance.dir, "libraries")).path.replace('\\', '/')
                add("@libraries/$rel")
            } else if (legacyJar != null) {
                add("-jar")
                add(legacyJar.name)
            }
            if (instance.nogui) add("nogui")
        }
    }

    /**
     * Forge / NeoForge 首启安装。
     *
     * 下载到的是 `*-installer.jar`，**不能直接当服务端跑**：必须先执行
     * `java -jar <installer> --installServer` 生成 `libraries/` 与启动入口。
     * 安装过程本身要联网下载 Minecraft 与 Forge 依赖，可能数分钟；
     * 用 [ServerInstance.forgeInstalled] 判断是否已完成，避免每次启动重装。
     */
    private suspend fun ensureForgeInstalled(
        slot: RuntimeSlot,
        instance: ServerInstance,
        javaVersion: String,
    ) {
        if (instance.forgeInstalled()) {
            slot.log("> ${instance.coreType.displayName} 已安装", LineType.System)
            return
        }
        val installer = instance.installerJar ?: throw RuntimeException(
            "实例目录里没有找到 ${instance.coreType.displayName} 安装器（*-installer.jar）"
        )
        slot.log(
            // 有 unix_args.txt 却没有成功标记 = 上次安装中断过（断网/DNS 不通/取消）。
            // installer 幂等，重跑会把缺的依赖库补齐——这也是这类实例唯一的自救路径。
            if (instance.forgeInstallIncomplete()) {
                "> 检测到上次 ${instance.coreType.displayName} 安装未完成（依赖库不全），正在补齐…"
            } else {
                "> 首次启动：正在安装 ${instance.coreType.displayName}（需联网下载依赖，可能数分钟）"
            },
            LineType.System,
        )
        val javaBin = "/usr/lib/jvm/java-$javaVersion-openjdk-${archSuffix()}/bin/java"
        val code = env.execute(
            listOf(javaBin, "-jar", installer.name, "--installServer"),
            instance.dir,
        ) { line -> slot.log(line, classify(line)) }
        // 安装期间用户可能点了停止
        slot.checkCancelled()
        if (code != 0) {
            throw RuntimeException("${instance.coreType.displayName} 安装失败（退出码 $code），请看上方日志")
        }
        // 入口文件必须真的生成了
        if (instance.forgeArgsFile() == null && instance.legacyForgeJar() == null) {
            throw RuntimeException(
                "${instance.coreType.displayName} 安装器已退出，但没有生成启动入口" +
                    "（unix_args.txt / forge-*.jar），请查看上方安装日志"
            )
        }
        // 成功标记必须**在这里**写：forgeInstalled() 认的就是它。
        // 若在安装成功前就写，会在失败时留下"已安装"的假象，之后永远跳过安装、再也修不回来。
        runCatching { instance.forgeMarker.writeText(javaVersion.toString()) }
        slot.log("> ${instance.coreType.displayName} 安装完成", LineType.System)
    }

    /** 玩家聊天行：`... [Server thread/INFO]: <Steve> 内容` */
    private val CHAT_LINE = Regex(""":\s*<[^>]{1,32}>\s""")

    private fun classify(line: String): LineType = when {
        // 本应用的命令回显/系统消息优先：否则 `> say error` 会被下面的 ERROR 子串判成错误
        line.startsWith("> ") -> LineType.System
        // 聊天行永远是普通输出：玩家说一句 "there is an error" 不该让整行变红
        CHAT_LINE.containsMatchIn(line) -> LineType.Info
        line.contains("ERROR", ignoreCase = true) -> LineType.Error
        line.contains("WARN", ignoreCase = true) || line.contains("WARNING", ignoreCase = true) -> LineType.Warn
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
