package com.kaze.newage.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kaze.newage.container
import com.kaze.newage.core.addons.AddonKind
import com.kaze.newage.core.addons.AddonManager
import com.kaze.newage.core.addons.ModrinthApi
import com.kaze.newage.core.addons.ModrinthSearchHit
import com.kaze.newage.core.console.ConsoleLine
import com.kaze.newage.core.console.CONSOLE_MAX_LINES
import com.kaze.newage.core.console.ConsoleParser
import com.kaze.newage.core.download.CoreBuild
import com.kaze.newage.core.download.CoreSources
import com.kaze.newage.core.env.ProotEnvironment
import com.kaze.newage.core.server.ServerProperties
import com.kaze.newage.core.server.ServerState
import com.kaze.newage.data.model.CoreType
import com.kaze.newage.data.model.GameVersion
import com.kaze.newage.data.model.JavaVersionInference
import com.kaze.newage.data.model.ServerInstance
import com.kaze.newage.util.Downloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlinx.coroutines.withTimeoutOrNull

/** 服务端下载状态 */
data class DownloadState(
    val running: Boolean = false,
    val progress: Float = 0f,
    val message: String = "",
    val done: Boolean = false,
    val error: String? = null,
)

/** Java 安装/卸载任务状态 */
data class JavaTaskState(
    val running: Boolean = false,
    val version: Int? = null,
    val progress: Float = 0f,
    val message: String = "",
    val error: String? = null,
    /** 取消请求标志（下载循环轮询；true 时下载中止并保留断点） */
    val cancelRequested: Boolean = false,
)

/**
 * 服务端核心 jar 的最小可接受体积（64KB）。
 *
 * 只用来兜底"明显不是包"的响应，真正的防伪是 ZIP 魔数与官方哈希。
 * **下限不能定高**：Fabric 的 server launcher jar 实测只有 178KB（181,840 字节），
 * 历史上 1MB 的阈值使 Fabric 每次下载都被判为非法、删文件重试，最终报"所有源不可用"。
 */
private const val MIN_CORE_JAR_BYTES = 64L * 1024L

/** 共享 ViewModel：接线 core 各组件与 UI（多开：每实例独立状态/控制台） */
class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val container = application.container

    // ── core 组件 ──
    val env: ProotEnvironment = container.env
    val serverManager = container.serverManager

    /** 应用日志（设置 → 诊断日志）；container 是私有的，这里给界面一个出口 */
    val appLog get() = container.appLog

    /** proot 环境（诊断页要读它写的自检文件） */
    val prootEnv get() = container.env
    val instanceStore = container.instanceStore
    val uiPrefs = container.uiPrefs

    // ── 状态暴露 ──
    val envState: StateFlow<ProotEnvironment.State> = env.state
    val envItems: StateFlow<List<ProotEnvironment.SetupItem>> = env.items
    val envLog: StateFlow<List<String>> = env.log
    val envJavaVersions: StateFlow<List<Int>> =
        MutableStateFlow(emptyList()) // 刷新见 refreshJava()

    /**
     * 主版本 → release 文件里的完整版本号（如 17 → "17.0.20.1"）。
     * 设置页显示「已安装 · 17.0.20.1」，比只写「已安装」更能说明检测到了什么。
     */
    val envJavaVersionDetails: StateFlow<Map<Int, String>> =
        MutableStateFlow(emptyMap()) // 刷新见 refreshJava()

    val instances = instanceStore.instances

    /** 所有实例状态：instanceId -> ServerState */
    val serverStates: StateFlow<Map<String, ServerState>> = serverManager.states

    /** 运行中的实例数 */
    val runningCount: StateFlow<Int> = serverManager.states
        .map { m -> m.values.count { it == ServerState.Running } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _currentInstanceId = MutableStateFlow<String?>(null)
    val currentInstanceId: StateFlow<String?> = _currentInstanceId.asStateFlow()

    /** 当前实例状态（无选中时为 Idle） */
    val serverState: StateFlow<ServerState> =
        combine(_currentInstanceId, serverManager.states) { id, m ->
            if (id == null) ServerState.Idle else m[id] ?: ServerState.Idle
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ServerState.Idle)

    /** 当前实例运行时长（秒） */
    val uptimeSec: StateFlow<Long> = _currentInstanceId
        .flatMapLatest { id -> if (id == null) flowOf(0L) else serverManager.uptimeSec(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    private val _consoleLines = MutableStateFlow<List<ConsoleLine>>(emptyList())
    val consoleLines: StateFlow<List<ConsoleLine>> = _consoleLines.asStateFlow()

    /** 当前实例的在线玩家（来自 list 响应与加入/离开事件） */
    private val _onlinePlayers = MutableStateFlow<List<String>>(emptyList())
    val onlinePlayers: StateFlow<List<String>> = _onlinePlayers.asStateFlow()

    private val _download = MutableStateFlow(DownloadState())
    val download: StateFlow<DownloadState> = _download.asStateFlow()

    /** 环境部署的独立进度（与核心下载分开，见 [setupEnv]） */
    private val _envTask = MutableStateFlow(DownloadState())
    val envTask: StateFlow<DownloadState> = _envTask.asStateFlow()

    private val _versions = MutableStateFlow<List<GameVersion>>(emptyList())
    val versions: StateFlow<List<GameVersion>> = _versions.asStateFlow()

    private val _versionsLoading = MutableStateFlow(false)
    val versionsLoading: StateFlow<Boolean> = _versionsLoading.asStateFlow()

    /** 选定 MC 版本后的可选构建（目前仅 Paper 提供；FCL「加载器版本」的对应物） */
    private val _builds = MutableStateFlow<List<CoreBuild>>(emptyList())
    val builds: StateFlow<List<CoreBuild>> = _builds.asStateFlow()

    private val _buildsLoading = MutableStateFlow(false)
    val buildsLoading: StateFlow<Boolean> = _buildsLoading.asStateFlow()

    /** 版本列表加载竞态：快速切换核心类型时丢弃过期的旧请求（collectLatest 语义） */
    private var versionsJob: kotlinx.coroutines.Job? = null

    /** 构建列表加载竞态：快速切换版本时丢弃过期请求 */
    private var buildsJob: kotlinx.coroutines.Job? = null

    /** Java 安装/卸载任务（用户可选下载/删除） */
    private val _javaTask = MutableStateFlow(JavaTaskState())
    val javaTask: StateFlow<JavaTaskState> = _javaTask.asStateFlow()

    /** 附加组件（插件/模组）搜索 */
    private val _addonResults = MutableStateFlow<List<ModrinthSearchHit>>(emptyList())
    val addonResults: StateFlow<List<ModrinthSearchHit>> = _addonResults.asStateFlow()
    private val _addonSearching = MutableStateFlow(false)
    val addonSearching: StateFlow<Boolean> = _addonSearching.asStateFlow()
    private val _addonInstall = MutableStateFlow(DownloadState())
    val addonInstall: StateFlow<DownloadState> = _addonInstall.asStateFlow()

    init {
        // 每次启动写一份环境自检报告到外部目录（files/diagnostics.txt）：
        // 真机上"部署失败/启动失败"往往只有一句笼统提示，用户可以直接把这个文件发出来。
        container.appScope.launch { runCatching { container.env.dumpDiagnostics() } }

        // 跟随当前实例切换控制台（每实例独立日志流），并跟踪在线玩家
        viewModelScope.launch(Dispatchers.IO) {
            _currentInstanceId.collectLatest { id ->
                _onlinePlayers.value = emptyList()
                if (id == null) {
                    _consoleLines.value = emptyList()
                    return@collectLatest
                }
                val stream = serverManager.consoleFor(id)
                // 先用环形缓冲回填历史：ConsoleStream 的实时流是 replay=0，
                // 不预填的话切到一个**已经在运行**的实例会看到空控制台
                // （snapshot() 之前定义了却没有任何调用点）
                _consoleLines.value = stream.snapshot().takeLast(CONSOLE_MAX_LINES)
                stream.lines.collect { line ->
                    // 覆盖行（服务端 \r 原地进度）要替换上一行而不是追加：
                        // 只让环形缓冲去替换的话，实时视图仍会把每个百分比都追加成一行
                        val cur = _consoleLines.value
                        _consoleLines.value = if (line.replaceLast && cur.isNotEmpty()) {
                            (cur.dropLast(1) + line).takeLast(CONSOLE_MAX_LINES)
                        } else {
                            (cur + line).takeLast(CONSOLE_MAX_LINES)
                        }
                    ConsoleParser.parseOnlinePlayers(line.text)?.let { _onlinePlayers.value = it }
                    ConsoleParser.parseJoin(line.text)?.let { name ->
                        if (name !in _onlinePlayers.value) _onlinePlayers.value = _onlinePlayers.value + name
                    }
                    ConsoleParser.parseLeave(line.text)?.let { name ->
                        _onlinePlayers.value = _onlinePlayers.value - name
                    }
                }
            }
        }
        refreshJava()
    }

    fun refreshJava() {
        // 扫描 rootfs 的 usr/lib/jvm 得出实际安装情况（装了什么就报什么，
        // 不再按写死的 8/11/17/21/25 列表逐个猜）
        (envJavaVersions as MutableStateFlow).value = env.installedJdkVersions()
        (envJavaVersionDetails as MutableStateFlow).value = env.installedJdkFullVersions()
    }

    /** 可选下载：安装指定 Java 版本（8/17/21/25）；失败可再次调用重试（断点续传） */
    fun installJava(version: Int) {
        if (_javaTask.value.running) return // 任务进行中（含取消中）：等其退出后再点即续传
        // 状态必须在这里同步置位，不能放进协程体：launch 只是把协程体派发出去、不会立即执行，
        // 而 UI 的 enabled 还要等下一次重组才更新 —— 这段窗口内再点一次就会并发跑两个任务。
        _javaTask.value = JavaTaskState(running = true, version = version, message = "准备安装 Java $version…")
        // 下载 JDK 是长任务：退出界面后仍应装完（成果在磁盘上）
        container.appScope.launch {
            try {
                if (!container.env.isReady) {
                    container.env.setup { p, m ->
                        // 必须 copy 而非整体 new：整体替换会把 cancelRequested 冲回 false，
                        // 用户在下载期间点取消即刻失效（下一个进度 tick ≤150ms 就洗掉标志）
                        _javaTask.value = _javaTask.value.copy(
                            running = true, progress = p * 0.3f, message = "准备环境：$m",
                        )
                    }
                }
                container.javaManager.install(
                    version,
                    { p, m ->
                        _javaTask.value = _javaTask.value.copy(
                            running = true, progress = 0.3f + p * 0.7f, message = m,
                        )
                    },
                    { _javaTask.value.cancelRequested },
                )
                refreshJava()
                _javaTask.value = JavaTaskState(version = version, progress = 1f, message = "Java $version 安装完成")
            } catch (e: InterruptedException) {
                _javaTask.value = JavaTaskState(version = version, message = "已取消（已下载部分保留，可继续）")
            } catch (e: Exception) {
                _javaTask.value = JavaTaskState(version = version, error = e.message ?: "安装失败")
            }
        }
    }

    /** 请求取消正在进行的 Java 任务（下载中止，断点保留） */
    fun cancelJavaTask() {
        if (_javaTask.value.running) {
            _javaTask.value = _javaTask.value.copy(cancelRequested = true, message = "正在取消…")
        }
    }

    /** 删除指定 Java 版本 */
    fun uninstallJava(version: Int) {
        if (_javaTask.value.running) return
        // 正在运行/启动、且用这个 Java 版本的实例：拒绝卸载。
        // 运行中的 JVM 被摘掉 jmods/rt 后按需加载类会失败（服务端可能中途崩），
        // 而且 apt purge 会与被运行进程使用的同一 rootfs 并发写。
        val busy = setOf(
            ServerState.Starting, ServerState.FirstRun, ServerState.AcceptingEula,
            ServerState.Running, ServerState.Stopping,
        )
        val inUse = instanceStore.instances.value.firstOrNull {
            it.javaMajor == version && serverManager.states.value[it.id] in busy
        }
        if (inUse != null) {
            android.widget.Toast.makeText(
                container.appContext,
                "实例「${inUse.name}」正在使用 Java $version，请先停止它再卸载",
                android.widget.Toast.LENGTH_LONG,
            ).show()
            return
        }
        // 同步置位，否则连点会并发卸载同一个版本
        _javaTask.value = JavaTaskState(running = true, version = version, message = "卸载 Java $version…")
        // 同上：换 appScope
        container.appScope.launch {
            try {
                container.javaManager.uninstall(version)
                refreshJava()
                _javaTask.value = JavaTaskState(message = "Java $version 已卸载")
            } catch (e: Exception) {
                _javaTask.value = JavaTaskState(version = version, error = e.message ?: "卸载失败")
            }
        }
    }

    // ── 动作：环境 ──
    /**
     * 部署 Linux 环境。
     *
     * 进度写到**独立的** [envTask] 而不是 [download]：两者共用同一个状态字段时，
     * 「部署中进新建向导」会让向导把部署进度当成核心下载进度显示、并禁用创建按钮；
     * 反过来「下载核心时点部署」又会静默早退、按钮毫无反馈。
     */
    fun setupEnv() {
        // 部署是长任务（下载 rootfs + apt）：连点两次会同时跑两套部署、互相踩文件
        if (_envTask.value.running) return
        // 同步置位，理由同 installJava
        _envTask.value = DownloadState(running = true, progress = 0f, message = "准备部署…")
        // 部署要跑几分钟（下载 rootfs + apt），退出界面不应中断
        container.appScope.launch {
            try {
                env.setup { progress, message ->
                    _envTask.value = DownloadState(running = true, progress = progress, message = message)
                }
                refreshJava()
                _envTask.value = DownloadState(done = true, message = "环境部署完成")
            } catch (e: Exception) {
                _envTask.value = DownloadState(error = e.message ?: "部署失败")
            }
        }
    }

    /** 重新扫描实例目录（切换自定义目录后调用：直接识别所选目录中的既有服务端） */
    fun rescanInstances() {
        instanceStore.rescan()
        if (_currentInstanceId.value?.let { id -> instanceStore.get(id) == null } == true) {
            _currentInstanceId.value = null
        }
    }

    // ── 动作：服务端（多开）──
    /** 重命名实例（软件显示名；不影响 server.properties / MOTD，两者独立修改） */
    fun renameInstance(id: String, newName: String) {
        instanceStore.rename(id, newName)
    }
    fun selectInstance(instance: ServerInstance) {
        _currentInstanceId.value = instance.id
    }

    fun startInstance(instance: ServerInstance) {
        _currentInstanceId.value = instance.id
        // 电池优化白名单：首次启动服务端时自动弹系统请求（防止后台被杀）；拒绝可去设置页重试
        requestBatteryWhitelistOnce()
        container.appScope.launch {
            serverManager.start(instance)
        }
    }

    /** 未忽略电池优化时，弹系统对话框请求加入白名单（只自动弹一次，vivo 等厂商后台管理需手动引导） */
    private fun requestBatteryWhitelistOnce() {
        try {
            if (container.uiPrefs.batteryPrompted.value) return
            val pm = container.appContext.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            if (pm.isIgnoringBatteryOptimizations(container.appContext.packageName)) return
            container.uiPrefs.setBatteryPrompted(true)
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                android.net.Uri.parse("package:${container.appContext.packageName}"),
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            container.appContext.startActivity(intent)
        } catch (_: Exception) { }
    }

    /**
     * 重启实例：停止 → 等它真的停下 → 启动。
     *
     * 首页那个「重启」按钮原来直接调 [startInstance]，而服务端正在跑时 `start()` 会被
     * guardActiveStates 挡掉（只往日志写一行"已处于启动/运行中，忽略重复启动"），
     * 用户点了**没有任何反应** —— 按钮实际只在停止状态下可用，与「启动服务端」重复。
     * 停止是异步的，必须等状态离开 Running/Stopping 再启动，否则同样会被挡。
     */
    fun restartInstance(instance: ServerInstance) {
        _currentInstanceId.value = instance.id
        container.appScope.launch {
            val cur = serverManager.states.value[instance.id] ?: ServerState.Idle
            if (cur.isBusy()) return@launch
            serverManager.stop(instance)
            // 有上限地等：卡住时也不能让按钮看起来永远没反应
            withTimeoutOrNull(60_000) {
                serverManager.states.first { m ->
                    val s = m[instance.id] ?: ServerState.Idle
                    s != ServerState.Running && !s.isBusy()
                }
            }
            serverManager.start(instance)
        }
    }

    fun stopInstance(instance: ServerInstance) {
        container.appScope.launch {
            serverManager.stop(instance)
        }
    }

    fun sendCommand(command: String) {
        val id = _currentInstanceId.value ?: return
        instanceStore.get(id)?.let { serverManager.sendCommand(it, command) }
    }

    /** 请求服务端刷新在线玩家列表（发送 list 命令，结果经日志解析回填） */
    fun refreshPlayers() {
        sendCommand("list")
    }

    /**
     * 清空当前实例控制台显示。
     *
     * 必须**同时**清后端环形缓冲：只清 UI 列表的话，切走再切回时订阅会重建并
     * `snapshot()` 回填历史，刚清掉的日志整段复活（用户以为清空没生效）；
     * 而此时「复制/导出」用的是清空后的短列表，与屏幕上看到的内容也对不上。
     */
    fun clearConsole() {
        _currentInstanceId.value?.let { id -> serverManager.consoleFor(id).clear() }
        _consoleLines.value = emptyList()
    }

    /**
     * 当前实例的运行日志文件 —— 控制台详情里要回答两个问题：
     * "精确多少行"（内存窗口）+ "完整日志多大 / 在哪"（磁盘上的 append-only 文件）。
     */
    fun consoleLogFile(): java.io.File? =
        _currentInstanceId.value
            ?.let { id -> instanceStore.get(id) }
            ?.let { inst -> java.io.File(inst.dir, "console-output.log") }

    /** 控制台因超限被丢掉的行数（详情对话框里的精确数字） */
    fun consoleDroppedCount(): Int =
        _currentInstanceId.value?.let { id -> serverManager.consoleFor(id).droppedCount } ?: 0

    /** 导出当前实例控制台显示内容到用户选择的目录（SAF 一次性保存，与复制一致 = 控制台所见） */
    fun saveConsoleLog(uri: android.net.Uri) {
        val text = _consoleLines.value.joinToString("\n") { it.text }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                container.appContext.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(text.toByteArray(Charsets.UTF_8))
                }
            } catch (_: Exception) { }
        }
    }

    /** 复制当前实例控制台显示的完整内容到剪贴板（与屏幕所见一致，含实时流与清空后状态） */
    fun copyConsoleLog() {
        val text = _consoleLines.value.joinToString("\n") { it.text }
        val ctx = container.appContext
        if (text.isBlank()) {
            android.widget.Toast.makeText(ctx, "暂无日志", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        viewModelScope.launch(Dispatchers.Main) {
            try {
                val cm = ctx.getSystemService(
                    android.content.Context.CLIPBOARD_SERVICE
                ) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Kaze 日志", text))
                android.widget.Toast.makeText(ctx, "已复制完整日志", android.widget.Toast.LENGTH_SHORT).show()
            } catch (_: Exception) { }
        }
    }

    fun removeInstance(instance: ServerInstance) {
        // 删除要先等实例停稳（最长 20s），不能因为用户切页就被取消在半途
        container.appScope.launch {
            // 运行中先停止（按生命周期状态等待彻底退出：Starting/Running 部署中也要先撤下）
            val activeStates = setOf(
                ServerState.Starting, ServerState.FirstRun, ServerState.AcceptingEula,
                ServerState.Running, ServerState.Stopping,
            )
            val now = serverManager.states.value[instance.id]
            if (now in activeStates) {
                serverManager.stop(instance)
                val deadline = System.currentTimeMillis() + 20_000
                while (System.currentTimeMillis() < deadline) {
                    val s = serverManager.states.value[instance.id]
                    if (s == null || s !in activeStates) break
                    kotlinx.coroutines.delay(500)
                }
                // 还没停下来就**不能删目录**：启动流程可能仍在推进（Forge 安装器要跑几分钟），
                // 删掉目录后它会立刻把目录重新建出来；而旧版 Forge 安装器落下的顶层 forge-*.jar
                // 还会在下次扫描时被当成新实例"复活"。宁可拒绝删除、让用户稍后重试。
                if (serverManager.states.value[instance.id] in activeStates) {
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            container.appContext,
                            "实例仍在停止中（可能正在安装或部署），请稍后再试删除",
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    }
                    return@launch
                }
            }
            instanceStore.remove(instance.id)
            runCatching { instance.dir.deleteRecursively() }
            // 备份目录在实例目录之外，删除实例后无任何入口再能访问——一并清理防死数据
            runCatching { com.kaze.newage.core.server.BackupManager.deleteAllBackups(instance) }
            if (_currentInstanceId.value == instance.id) _currentInstanceId.value = null
        }
    }

    // ── 动作：版本列表 ──

    /**
     * 在独立线程上跑"不可中断的阻塞调用"，并给出**界面可见的超时**。
     *
     * [CoreSources] 的抓取是阻塞式 `HttpURLConnection`：虽然设了 connect/read 超时，
     * 但 **DNS 解析不受 connectTimeout 约束**，设备离线时可能长时间不返回；
     * 而阻塞调用不会在挂起点让出，`withTimeout` 拦不住它（超时只在挂起点生效，
     * 它仍然会一直等到阻塞调用自己返回）。
     *
     * 所以把阻塞调用放到独立线程，用 `deferred.await()` 参与协程取消：超时后界面立即恢复，
     * 那个线程跑完自行丢弃（抓取本身有超时兜底，不会无限积累线程）。
     */
    private suspend fun <T> blockingWithTimeout(timeoutMs: Long, fallback: T, block: suspend () -> T): T {
        val deferred = kotlinx.coroutines.CompletableDeferred<T>()
        kotlin.concurrent.thread(isDaemon = true, name = "kaze-fetch") {
            // 抓取是阻塞实现（内部不挂起），所以在这个独立线程里用 runBlocking 跑即可；
            // 关键是这个线程不在 viewModelScope 上，超时后协程能正常结束。
            deferred.complete(runCatching { kotlinx.coroutines.runBlocking { block() } }.getOrDefault(fallback))
        }
        return withTimeoutOrNull(timeoutMs) { deferred.await() } ?: fallback
    }

    /** 版本列表加载：用递增序号标识"最新一次请求"，只有它有权复位 loading */
    @Volatile
    private var versionsRequestId = 0

    @Volatile
    private var buildsRequestId = 0

    fun loadVersions(type: CoreType) {
        versionsJob?.cancel()
        val requestId = ++versionsRequestId
        versionsJob = viewModelScope.launch(Dispatchers.IO) {
            _versionsLoading.value = true
            _versions.value = emptyList()
            try {
                _versions.value = blockingWithTimeout(VERSION_FETCH_TIMEOUT_MS, emptyList()) {
                    CoreSources.fetchVersions(type).getOrDefault(emptyList())
                }
            } finally {
                // finally 而不是顺序执行：任务被 cancel（快速换核心类型）或抛异常时
                // 也要复位，否则 loading 永久为 true → 版本页那个进度条常驻。
                // 带序号是因为：旧任务的 finally 可能晚于新任务置位，无条件复位会把
                // 新任务的进度条提前关掉。
                if (requestId == versionsRequestId) _versionsLoading.value = false
            }
        }
    }

    /** 拉取选定版本的可选构建（无构建列表的核心会立刻返回空列表） */
    fun loadBuilds(type: CoreType, mcVersion: String) {
        buildsJob?.cancel()
        val requestId = ++buildsRequestId
        buildsJob = viewModelScope.launch(Dispatchers.IO) {
            _buildsLoading.value = true
            _builds.value = emptyList()
            try {
                _builds.value = blockingWithTimeout(VERSION_FETCH_TIMEOUT_MS, emptyList()) {
                    CoreSources.fetchBuilds(type, mcVersion).getOrDefault(emptyList())
                }
            } finally {
                if (requestId == buildsRequestId) _buildsLoading.value = false
            }
        }
    }

    // ── 动作：下载并创建实例 ──
    /** 下载取消标志（downloadAndCreate 内部轮询；取消保留 .part 断点） */
    @Volatile
    private var downloadCancelRequested = false

    /** 取消进行中的实例下载（仅在 download.running 时有效） */
    fun cancelDownload() {
        downloadCancelRequested = true
    }

    fun downloadAndCreate(
        name: String,
        type: CoreType,
        mcVersion: String,
        memoryMb: Int,
        javaMajorOverride: Int = 0,
        /** 安装时用户选定的 server.properties 覆盖项（端口 / 最大玩家 / 在线模式 / 游戏模式）。
         *  参考 FCL 安装页的"可选项"：建服时就把关键参数定下来，省得建完再进实例详情改。 */
        propsOverride: Map<String, String> = emptyMap(),
        /** 指定的核心构建 id（Paper 用；空 = 最新构建） */
        buildId: String = "",
        onComplete: (ServerInstance?) -> Unit,
    ) {
        // 连点守卫 + 同步置位：两个并发下载会写同一个 .part 文件，把包写坏。
        // （UI 上的 enabled 要等重组才更新，靠它拦不住快速双击或脚本连续点击）
        if (_download.value.running) return
        _download.value = DownloadState(running = true, progress = 0f, message = "解析下载地址…")
        downloadCancelRequested = false
        // 核心 jar 可能几十 MB：用户点返回键去干别的，下载应继续完成并建出实例
        container.appScope.launch {
            var dir: File? = null
            try {
                val dl = CoreSources.resolveDownload(type, mcVersion, buildId).getOrThrow()
                dir = instanceStore.createInstanceDir(name)
                // 半成品用 .part 后缀：断点续传保留，且不会被实例目录扫描误识别为已装 jar
                val part = File(dir, dl.fileName + ".part")
                val target = File(dir, dl.fileName)
                _download.value = DownloadState(running = true, progress = 0f, message = "下载 ${dl.fileName}")
                // 多源回退 + 断网自动重试（断点保留，失败可再次点下载续传）；可取消
                val used = Downloader.downloadFromSources(
                    listOf(dl.url),
                    part,
                    onProgress = { done, total ->
                        val progress = if (total > 0) done.toFloat() / total else 0f
                        _download.value = DownloadState(
                            running = true,
                            progress = progress,
                            message = "下载中 ${(done / 1024 / 1024)}MB${if (total > 0) " / ${(total / 1024 / 1024)}MB" else ""}",
                        )
                    },
                    shouldCancel = { downloadCancelRequested },
                    // 重试/换源期间给出文案：不接线的话弱网下进度条会一直冻在"下载中 X MB"，
                    // 最长静默约 9 分钟（4 轮 × 3 次 × (15s 连接 + 30s 读)），用户只能看着不动
                    onSourceError = { _, msg ->
                        _download.value = DownloadState(running = true, progress = 0f, message = msg)
                    },
                    // 官方清单给了 sha1 就必须校验（旧实现完全不校验 → 镜像的 200+HTML
                    // 错误页会被当作 jar 重命名入库，直到启动时才报"没有核心 jar"）
                    validate = { f ->
                        // 大小 + ZIP 魔数：服务端核心都是 jar，魔数能挡住镜像的 HTML 错误页
                        // （Spigot/Fabric/Forge 没有官方哈希可对，此前只查大小）
                        //
                        // 下限必须放得很低：Fabric 的 server launcher jar 实测只有 178KB
                        // （181,840 字节），原来 1MB 的阈值让 Fabric **永远**校验失败——
                        // 文件被删、重试 4 轮后报"所有源不可用"。防伪交给魔数与官方哈希。
                        f.length() > MIN_CORE_JAR_BYTES &&
                            Downloader.isZip(f) &&
                            (dl.sha1 == null ||
                                Downloader.sha1Of(f)?.equals(dl.sha1, ignoreCase = true) == true)
                    },
                )
                if (used == null) {
                    if (downloadCancelRequested) {
                        _download.value = DownloadState(message = "已取消（已下载部分保留，可重试续传）")
                        withContext(Dispatchers.Main) { onComplete(null) }
                        return@launch
                    }
                    throw RuntimeException("下载失败：所有源不可用（请检查网络后重试）")
                }
                if (!part.renameTo(target)) {
                    part.copyTo(target, overwrite = true)
                    part.delete()
                }
                val instance = ServerInstance(
                    name = name,
                    coreType = type,
                    mcVersion = mcVersion,
                    javaMajor = if (javaMajorOverride > 0) javaMajorOverride
                    else if (type == CoreType.CUSTOM) 17
                    else JavaVersionInference.infer(mcVersion),
                    memoryMb = memoryMb,
                    dir = dir,
                )
                instanceStore.add(instance)
                ServerProperties.ensureInitial(instance, instanceStore.instances.value)
                if (propsOverride.isNotEmpty()) {
                    // 读回再覆盖：ensureInitial 写下的其它键（view-distance 等）不能被丢掉
                    val props = ServerProperties.load(dir)
                    props.putAll(propsOverride)
                    ServerProperties.save(dir, props)
                }
                _currentInstanceId.value = instance.id
                _download.value = DownloadState(done = true, message = "下载完成")
                // 导航/UI 回调必须回主线程（否则 Compose 报 setCurrentState 或静默失败不跳转）
                withContext(Dispatchers.Main) { onComplete(instance) }
            } catch (e: Exception) {
                // 保留 dir 与 .part 半成品：重试时断点续传；目录扫描不会把 .part 误识别为实例
                _download.value = DownloadState(error = e.message ?: "下载失败")
                withContext(Dispatchers.Main) { onComplete(null) }
            }
        }
    }

    // ── 动作：插件/模组（Modrinth）──
    fun searchAddons(query: String, kind: AddonKind) {
        if (query.isBlank()) return
        _addonSearching.value = true
        _addonResults.value = emptyList()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _addonResults.value = ModrinthApi.search(query, kind)
            } catch (e: Exception) {
                _addonInstall.value = DownloadState(error = "搜索失败：${e.message}")
            } finally {
                _addonSearching.value = false
            }
        }
    }

    fun installAddon(instance: ServerInstance, kind: AddonKind, hit: ModrinthSearchHit) {
        if (_addonInstall.value.running) return
        // 同步置位，理由同 installJava：否则连点会并发装同一个插件
        _addonInstall.value = DownloadState(running = true, message = "解析 ${hit.title} 版本…")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = AddonManager.install(instance, kind, hit.project_id, instance.mcVersion) { p, m ->
                    _addonInstall.value = DownloadState(running = true, progress = p, message = m)
                }
                _addonInstall.value = DownloadState(done = true, message = "已安装 ${file.name}（重启服务端生效）")
            } catch (e: Exception) {
                _addonInstall.value = DownloadState(error = e.message ?: "安装失败")
            }
        }
    }

    fun clearAddonInstallState() {
        _addonInstall.value = DownloadState()
    }

    /**
     * 导入 jar：把用户选中的文件复制进一个新实例目录并登记实例。
     *
     * 复制必须在这里（IO）做，不能让调用方在主线程 `copyTo`：几十 MB 的 jar 会阻塞 UI（ANR 风险），
     * 而且调用方原来用 `catch (_: Exception) { }` 把失败全吞了——用户选完文件毫无反应，
     * 目录里可能留下半截 server.jar。
     *
     * Toast 一律回到主线程发：`Toast.show()` 需要当前线程有 Looper，
     * 在 Dispatchers.IO 上直接调用会抛 "Can't create handler inside thread that has not called Looper.prepare()"
     * ——导入成功/失败两条路径都会走到 Toast，等于每次导入都崩。
     */
    fun importJar(uri: android.net.Uri, name: String, memoryMb: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = container.appContext
            var dir: File? = null
            try {
                dir = instanceStore.createInstanceDir(name)
                val target = File(dir, "server.jar")
                ctx.contentResolver.openInputStream(uri)?.use { ins ->
                    target.outputStream().use { outs -> ins.copyTo(outs) }
                } ?: throw RuntimeException("无法读取所选文件")
                if (!target.isFile || target.length() == 0L) throw RuntimeException("所选文件为空")
                val instance = ServerInstance(
                    name = name,
                    coreType = CoreType.CUSTOM,
                    // 按 jar 内 class 文件版本推断（导入路径没法从 MC 版本号推）：
                    // 原来硬编码 17，导入 1.20.5+ 的服务端会在启动时 UnsupportedClassVersionError
                    javaMajor = inferJavaFromJar(target),
                    memoryMb = memoryMb,
                    dir = dir,
                )
                instanceStore.add(instance)
                ServerProperties.ensureInitial(instance, instanceStore.instances.value)
                _currentInstanceId.value = instance.id
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(ctx, "已导入：${instance.name}", android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                // 只清理本次新建的半成品目录；用户的源文件是 SAF 只读流，不会被改动
                runCatching { dir?.deleteRecursively() }
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        ctx, "导入失败：${e.message ?: "未知错误"}", android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /**
     * 从 jar 内的 class 文件推断所需 Java 主版本（导入自定义核心用）。
     *
     * class 文件的第 6-7 字节是 major version：52=Java 8、61=Java 17、65=Java 21、69=Java 25。
     * 导入路径无法从 MC 版本号推断，读 class 版本是最直接的依据；
     * 读不出来时退回 17（覆盖面最广，用户也可以在实例详情里看到实际推断值）。
     */
    private fun inferJavaFromJar(jar: File): Int = runCatching {
        java.util.zip.ZipFile(jar).use { zip ->
            val entry = zip.entries().asSequence().firstOrNull {
                it.name.endsWith(".class") && !it.name.startsWith("META-INF/")
            } ?: return@use 17
            zip.getInputStream(entry).use { ins ->
                val head = ByteArray(8)
                if (ins.read(head) < 8) return@use 17
                val major = ((head[6].toInt() and 0xFF) shl 8) or (head[7].toInt() and 0xFF)
                when {
                    major >= 69 -> 25
                    major >= 65 -> 21
                    major >= 61 -> 17
                    else -> 8
                }
            }
        }
    }.getOrDefault(17)
}

/**
 * 版本 / 构建列表抓取的**界面可见超时**。
 *
 * 抓取本身有 connect 15s + read 30s，但 DNS 解析不受 connectTimeout 约束，
 * 设备离线时可能长时间不返回。超过这个时间就放弃等待、复位状态，
 * 让用户看到"加载失败 + 重试"，而不是一个永远转的进度条。
 */
private const val VERSION_FETCH_TIMEOUT_MS = 45_000L