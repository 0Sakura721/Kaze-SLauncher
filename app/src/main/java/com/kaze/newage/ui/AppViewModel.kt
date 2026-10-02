package com.kaze.newage.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kaze.newage.container
import com.kaze.newage.core.addons.AddonKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.kaze.newage.core.addons.AddonManager
import com.kaze.newage.core.addons.ModrinthApi
import com.kaze.newage.core.addons.ModrinthSearchHit
import com.kaze.newage.core.ai.AiClient
import com.kaze.newage.core.ai.AiConfig
import com.kaze.newage.core.ai.AiContext
import com.kaze.newage.core.ai.AiFileTools
import com.kaze.newage.core.ai.AiMessage
import com.kaze.newage.core.ai.AiProfile
import com.kaze.newage.core.ai.AiPrompt
import com.kaze.newage.core.ai.AiReply
import com.kaze.newage.core.ai.AiSanitize
import com.kaze.newage.core.ai.AiSearch
import com.kaze.newage.core.ai.AiSuggestion
import com.kaze.newage.core.ai.AiUsage
import com.kaze.newage.core.ai.AiWebPage
import com.kaze.newage.core.ai.BrowserSearch
import com.kaze.newage.core.ai.NativeArgs
import com.kaze.newage.core.ai.NativeToolCall
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
import com.kaze.newage.core.update.UpdateChecker
import com.kaze.newage.core.update.UpdateInstaller
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
import com.kaze.newage.core.console.ConsoleArchive
import com.kaze.newage.core.console.LineType
import com.kaze.newage.core.console.CONSOLE_DISPLAY_MAX
import com.kaze.newage.core.console.CONSOLE_FLUSH_BATCH
import com.kaze.newage.core.console.CONSOLE_FLUSH_INTERVAL_MS
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import com.kaze.newage.core.console.CONSOLE_EAGER_LIMIT
import com.kaze.newage.core.monitor.ProcessStats

/** 服务端下载状态 */
data class DownloadState(
    val running: Boolean = false,
    val progress: Float = 0f,
    val message: String = "",
    val done: Boolean = false,
    val error: String? = null,
    /**
     * 附加组件专用：解析出目标文件与已装文件同名，正等用户确认是否替换。
     * 此时 [running] 为 false（没有任务在跑），界面据此弹确认框而不是显示进度条。
     */
    val replacePending: Boolean = false,
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
 * 应用自身更新的状态机。
 *
 * 必须活在 ViewModel 里，不能像原来那样放在设置页的 `remember` + `rememberCoroutineScope`：
 * 更新包几十 MB、下载要几分钟，用户点完「下载并安装」去别的页面看看、或转一下屏幕，
 * 组合被销毁 → 协程作用域取消 → 下载静默中止，而界面不会有任何提示
 *（回到设置页时状态已经重置成 Idle，看起来像"我根本没点过"）。
 */
sealed interface AppUpdateState {
    data object Idle : AppUpdateState
    data object Checking : AppUpdateState
    data class Error(val msg: String) : AppUpdateState
    data object Latest : AppUpdateState
    data class Found(val info: UpdateChecker.ReleaseInfo) : AppUpdateState
    data class Downloading(
        val info: UpdateChecker.ReleaseInfo,
        val progress: Float,
        val message: String,
    ) : AppUpdateState
}

/**
 * 服务端核心 jar 的最小可接受体积（64KB）。
 *
 * 只用来兜底"明显不是包"的响应，真正的防伪是 ZIP 魔数与官方哈希。
 * **下限不能定高**：Fabric 的 server launcher jar 实测只有 178KB（181,840 字节），
 * 历史上 1MB 的阈值使 Fabric 每次下载都被判为非法、删文件重试，最终报"所有源不可用"。
 */
private const val MIN_CORE_JAR_BYTES = 64L * 1024L

/** 占用采样节拍（毫秒）。CPU 靠两次求差，窗口越短越跟得上突发负载；配合 EMA 平滑防跳。 */
private const val PROC_STATS_INTERVAL_MS = 1_000L

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
                _consoleLines.value = stream.snapshot().takeLast(CONSOLE_DISPLAY_MAX)
                // 切实例时重置"回读更早日志"的游标（每个实例一份日志文件）
                instanceStore.get(id)?.dir?.let { d -> _olderCursor.value = ConsoleArchive.start(d) }
                _hasMoreOlder.value = _olderCursor.value != null
                // 批量发布的缓冲与定时器（见下面 collect 里的说明）。
                // 定时器是 collectLatest 的子协程 → 切实例时自动取消。
                synchronized(consoleLock) { consolePending.clear() }
                val consoleFlusher = launch {
                    while (isActive) {
                        delay(CONSOLE_FLUSH_INTERVAL_MS)
                        synchronized(consoleLock) { publishConsoleLocked() }
                    }
                }
                try {
                stream.lines.collect { line ->
                    // ⚠️ 这里**不能**每行都做 `(cur + line).takeLast(N)`。
                    //
                    // 真机反馈："Forge 开机时日志到 1.4 万行左右卡住了"。那个写法是 O(N)/行 ——
                    // Forge 启动每秒几百上千行、列表已有一万多条，等于每秒上千万次元素拷贝，
                    // 还附带同样次数的 Compose 重组合，主线程直接被拖死。
                    //
                    // 现在：行只往缓冲里塞（O(1)），由定时器每 80ms 发布一次。
                    // 用定时器而不是"等下一行来了再发"，是为了让**最后一批**也能出现 ——
                    // 否则服务端安静下来时，最关键的 "Done (3.2s)! For help, type help" 反而没了。
                    synchronized(consoleLock) {
                        consolePending.add(line)
                        // 小列表逐行即时发布（手感与改造前一致）；只有大列表才批量，
                        // 省掉的正是 O(N)/行 在万行级别上的代价。
                        // replaceLast 的结算全在 publishConsoleLocked 里逐条做，这里不额外记账。
                        if (consolePending.size >= CONSOLE_FLUSH_BATCH ||
                            _consoleLines.value.size < CONSOLE_EAGER_LIMIT
                        ) publishConsoleLocked()
                    }
                    ConsoleParser.parseOnlinePlayers(line.text)?.let { _onlinePlayers.value = it }
                    ConsoleParser.parseJoin(line.text)?.let { name ->
                        if (name !in _onlinePlayers.value) _onlinePlayers.value = _onlinePlayers.value + name
                    }
                    ConsoleParser.parseLeave(line.text)?.let { name ->
                        _onlinePlayers.value = _onlinePlayers.value - name
                    }
                }
                } finally {
                    consoleFlusher.cancel()
                    synchronized(consoleLock) { publishConsoleLocked() }
                }
            }        }
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
            // 等它**真的停稳**再启动；超时就放弃本次启动，而不是硬启。
            //
            // 优雅停止现在会一直等服务器把世界存完（不再 10 秒自动强杀），世界大时
            // 超过 60 秒很正常。硬启会在同一个世界目录上拉起第二个 java（旧版的
            // 10 秒强杀恰好掩盖了这一点），所以宁可这次不启动，也要先把话说明白。
            val stopped = withTimeoutOrNull(120_000) {
                serverManager.states.first { m ->
                    val s = m[instance.id] ?: ServerState.Idle
                    s != ServerState.Running && !s.isBusy()
                }
            }
            if (stopped == null) {
                serverManager.consoleFor(instance.id).emit(
                    "> 仍在停止中（多半还在保存世界）：已取消本次启动。要立即终止请再点一次「强制停止」",
                    LineType.Warn,
                )
                return@launch
            }
            serverManager.start(instance)
        }
    }

    /**
     * 停止实例（**优雅**：发 `stop` 命令，然后一直等它把世界存完，不自动强杀）。
     *
     * 停止中再调它**不会**强制 —— 强制必须经过确认框，由界面确认后调 [forceStopInstance]
     * （真机要求："点强制关闭按钮，要提示确定强制关闭"）。这里保留一个兜底：
     * 状态已是 Stopping 时直接忽略，避免任何入口绕过确认就把进程砍掉。
     */
    fun stopInstance(instance: ServerInstance) {
        val state = serverManager.states.value[instance.id] ?: ServerState.Idle
        if (state == ServerState.Stopping) return
        container.appScope.launch {
            serverManager.stop(instance)
        }
    }

    /** 强制停止（**界面确认后**才调用）：SIGTERM 让 proot 把 guest 的 java 一起带走 → 5 秒 → SIGKILL */
    fun forceStopInstance(instance: ServerInstance) {
        serverManager.forceStop(instance)
    }

    /**
     * 改实例的内存分配（真机需求："创建完实例，还是可以像创建时那样编辑内存分配"）。
     *
     * 内存是启动参数（`-Xmx`/`-Xms`），**不会**作用到正在跑的进程：运行中改的话，
     * 明确往控制台写一句"重启后生效"，别让用户以为当前的上限已经变了。
     */
    fun setInstanceMemory(instance: ServerInstance, memoryMb: Int) {
        val mb = com.kaze.newage.core.server.MemoryLimits.clamp(memoryMb)
        instanceStore.setMemory(instance.id, mb)
        val running = serverManager.states.value[instance.id] == ServerState.Running
        serverManager.consoleFor(instance.id).emit(
            "> 内存上限已改为 $mb MB" +
                if (running) "（当前进程仍按旧值运行，重启后生效）" else "（下次启动生效）",
            LineType.System,
        )
    }

    fun sendCommand(command: String) {
        val id = _currentInstanceId.value ?: return
        instanceStore.get(id)?.let { serverManager.sendCommand(it, command) }
    }

    /** 请求服务端刷新在线玩家列表（发送 list 命令，结果经日志解析回填） */
    fun refreshPlayers() {
        sendCommand("list")
    }

    // ── AI 助手（P0：只读诊断 + 建议命令；命令必须用户点「执行」才真正发送）──

    /**
     * 待用户确认的 AI 文件写入请求（内容全文随卡片展示，确认后才落盘）。
     *
     * 确认卡必须让用户看清"写到哪个实例的哪个文件"：只显示模型给的相对路径时，
     * 同名文件（每个实例都有一份 server.properties）在用户眼里是完全一样的，
     * 而 AI 的上下文又可能被玩家聊天/网页结果污染 —— 所以要带上实例名与解析后的绝对路径。
     */
    @Serializable
    data class AiWriteRequest(
        val path: String,
        val content: String,
        val bytes: Int,
        /** 解析后的绝对路径（显示用；解析失败时为原始相对路径） */
        val absPath: String = "",
        /** 目标实例名（显示用） */
        val instanceName: String = "",
        /** 高风险文件的红字警示（脚本类；null = 普通文本文件） */
        val warning: String? = null,
    )

    /** 一条对话气泡。assistant 消息额外携带建议命令、原始回复、工具动作与写入请求 */
    @Serializable
    data class AiChatMessage(
        val id: Long,
        val isUser: Boolean,
        val text: String,
        /** 建议命令（仅助手消息、且模型给出了可用命令时非空；已经过 AiSuggestion 清洗） */
        val command: String? = null,
        /** 该建议是否已执行（防重复发送） */
        val commandSent: Boolean = false,
        val isError: Boolean = false,
        /** 模型的原始回复（多轮历史回喂用；错误消息为 null） */
        val rawReply: String? = null,
        /** 联网搜索用的查询词（null = 本轮未搜索） */
        val searchQuery: String? = null,
        /** 搜索拿到的结果条数 */
        val searchCount: Int = 0,
        /** 搜索失败原因（有它时按本地信息回答，UI 明示） */
        val searchError: String? = null,
        /** 工具动作提示（读取/列出/写入了哪个文件），轻量展示行 */
        val toolNote: String? = null,
        /** 待确认的文件写入请求（确认/拒绝后卡片保留供回看，状态见 [writeState]） */
        val writeRequest: AiWriteRequest? = null,
        /** 写入请求状态：0=待确认 1=已允许 2=已拒绝 */
        val writeState: Int = 0,
        /**
         * 本条消息对应的实例。命令执行与文件写入都按这里记录的实例走，
         * 而不是"当前选中的实例" —— 用户在 AI 回答后切换实例再点按钮，
         * 不能把命令/写入落到另一个实例上。
         */
        val instanceId: String? = null,
        /** 模型的思考过程（reasoning_content 或正文 <think> 段）；null = 无 */
        val reasoning: String? = null,
        /** 开了深度思考但没拿到思考内容的诊断提示（模型不支持/服务商未开启） */
        val thinkingNote: String? = null,
        /** token 用量摘要（如 "输入 1.2K（缓存命中 800）· 输出 3.4K（思考 2.1K）"） */
        val usageSummary: String? = null,
    )

    private val _aiMessages = MutableStateFlow<List<AiChatMessage>>(emptyList())
    val aiMessages: StateFlow<List<AiChatMessage>> = _aiMessages.asStateFlow()

    /**
     * AI 输入框草稿：存在 ViewModel 里，切页面 / 关掉面板 / 转屏都不丢 ——
     * 打了一半没发出去的字是最不该丢的东西。
     */
    private val _aiDraft = MutableStateFlow("")
    val aiDraft: StateFlow<String> = _aiDraft.asStateFlow()

    fun setAiDraft(text: String) {
        _aiDraft.value = text
        // 草稿防丢持久化：逐键落盘太浪费，800ms 防抖
        if (!draftSavePending) {
            draftSavePending = true
            container.appScope.launch {
                delay(800)
                draftSavePending = false
                persistAiChat()
            }
        }
    }

    @Volatile
    private var draftSavePending = false

    private val _aiBusy = MutableStateFlow(false)
    val aiBusy: StateFlow<Boolean> = _aiBusy.asStateFlow()

    private val aiIdCounter = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * 一轮提问的工具会话：多轮循环期间（含挂起等待写入确认）持有 API 消息序列。
     * 写入确认/拒绝后从断点继续；新提问或清空对话时作废。
     */
    private class AiTurnSession(
        val config: AiConfig,
        val apiMessages: MutableList<AiMessage>,
        var roundsLeft: Int,
        val searchQuery: String?,
        val searchCount: Int,
        val searchError: String?,
        /** 本轮提问发起时的实例：命令/写入绑定到它（见 [AiChatMessage.instanceId]） */
        val instanceId: String?,
    )

    @Volatile
    private var aiSession: AiTurnSession? = null

    /** 用户请求取消本轮 AI 提问：在工具循环的每个轮次边界生效（进行中的 HTTP 调用会自然超时结束） */
    @Volatile
    private var aiCancelRequested = false

    /** 取消当前 AI 提问（正在思考/工具循环中时有效） */
    fun cancelAiTurn() {
        if (_aiBusy.value) aiCancelRequested = true
    }

    // ── 流式实况（思考/正文逐 token 更新；null = 没有进行中的流）──

    data class AiLiveStream(
        val id: Long,
        val thinking: Boolean,
        val reasoning: String = "",
        val content: String = "",
    )

    private val _aiLiveStream = MutableStateFlow<AiLiveStream?>(null)
    val aiLiveStream: StateFlow<AiLiveStream?> = _aiLiveStream.asStateFlow()

    private fun updateLiveStream(id: Long, reasoningDelta: String?, contentDelta: String?) {
        val cur = _aiLiveStream.value ?: return
        if (cur.id != id) return
        _aiLiveStream.value = cur.copy(
            reasoning = if (reasoningDelta != null) (cur.reasoning + reasoningDelta).take(AI_MAX_REASONING_CHARS) else cur.reasoning,
            content = if (contentDelta != null) (cur.content + contentDelta).take(120_000) else cur.content,
        )
    }

    /** 是否对当前配置发送 DeepSeek 原生参数/工具（官方端点 + 官方模型名才发） */
    private fun includeNativeThinking(config: AiConfig): Boolean =
        AiClient.supportsNativeThinkingParam(config.requestModel, config.baseUrl)

    private val aiJson = Json { ignoreUnknownKeys = true }

    // ── 对话持久化：进程被杀后恢复最近一次会话与草稿 ──

    private val aiChatFile by lazy { File(container.appContext.filesDir, "ai_chat.json") }

    @Serializable
    private data class AiChatSnapshot(
        val messages: List<AiChatMessage> = emptyList(),
        val draft: String = "",
    )

    private fun persistAiChat() {
        runCatching {
            aiChatFile.writeText(
                aiJson.encodeToString(
                    AiChatSnapshot(messages = _aiMessages.value.takeLast(200), draft = _aiDraft.value)
                )
            )
        }
    }

    init {
        // 进程被杀后的恢复：对话与草稿回来；挂起中的写入卡片没有会话了，
        // 但「重试」仍会执行写入本身（写入不依赖会话，见 approveAiWrite）
        runCatching {
            if (aiChatFile.isFile) {
                val snap = aiJson.decodeFromString<AiChatSnapshot>(aiChatFile.readText())
                if (snap.messages.isNotEmpty()) _aiMessages.value = snap.messages
                if (snap.draft.isNotEmpty() && _aiDraft.value.isEmpty()) _aiDraft.value = snap.draft
            }
        }
    }

    // ── 模型配置档案 + 联网搜索（存取在 SettingsPrefs，这里只做转发）──

    fun saveAiProfile(profile: AiProfile) = uiPrefs.saveAiProfile(profile)

    fun deleteAiProfile(id: String) = uiPrefs.deleteAiProfile(id)

    /** 功能分配：把「对话」指向某个档案 */
    fun setChatAiProfile(id: String) = uiPrefs.setChatAiProfile(id)

    fun setAiSearch(providerId: String, key: String) = uiPrefs.setAiSearch(providerId, key)

    fun setAiSearchOn(v: Boolean) = uiPrefs.setAiSearchOn(v)

    /** 思考强度开关（面板头部随时可切；DeepSeek = chat/reasoner 模型切换） */
    fun setAiThinking(v: Boolean) {
        uiPrefs.setAiThinking(v)
    }

    /** 是否已授予悬浮窗权限（本机浏览器搜索走真实窗口的前提） */
    fun canDrawOverlays(): Boolean = android.provider.Settings.canDrawOverlays(container.appContext)

    /** 引导去系统设置授予悬浮窗权限（一次性手动操作，与电池白名单同一套做法） */
    fun requestOverlayPermission() {
        try {
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${container.appContext.packageName}"),
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            container.appContext.startActivity(intent)
        } catch (_: Exception) {
        }
    }

    /** 清空 AI 对话：忙时不允许（正在生成的回复会找不到落点）；挂起中的写入会话一并作废 */
    fun clearAiChat() {
        if (_aiBusy.value) return
        aiSession = null
        _aiMessages.value = emptyList()
        persistAiChat()
    }

    /**
     * 提问 → 重新采集现场快照 → 调 OpenAI 兼容接口 → 解析出分析与建议命令。
     *
     * 每轮都重新采集上下文（玩家、日志、占用都是新的），历史只保留最近几轮文本 ——
     * 这样长会话不会把上下文撑爆，模型看到的也永远是最新的现场。
     */
    fun askAi(question: String) {
        val q = question.trim()
        if (q.isEmpty() || _aiBusy.value) return
        val config = uiPrefs.aiConfig()
        if (!config.isConfigured) {
            appendAiMessage(
                AiChatMessage(nextAiId(), isUser = false, text = "尚未配置 AI 接口：请先填写 API Key。", isError = true)
            )
            return
        }
        appendAiMessage(AiChatMessage(nextAiId(), isUser = true, text = q))
        _aiBusy.value = true
        // 新提问作废可能挂起的旧会话与取消标志
        aiSession = null
        aiCancelRequested = false
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // ── 联网搜索（开启才走；API 源需已配 Key，本机浏览器源零 Key）──
                // 失败只降级不阻塞：搜不到就按本地信息回答，气泡上注明原因
                var searchQuery: String? = null
                var searchCount = 0
                var searchError: String? = null
                var searchBlock = ""
                val provider = AiSearch.Provider.byId(uiPrefs.aiSearchProviderId.value)
                val searchKey = uiPrefs.aiSearchKey.value
                if (uiPrefs.aiSearchOn.value && (!provider.needsKey || searchKey.isNotBlank())) {
                    try {
                        // 生成查询词是小事：强制用标准模型（省掉思考模式的等待），小 max_tokens
                        val queryRaw = callAiWithTimeout(
                            config.copy(thinking = false),
                            listOf(
                                AiMessage(AiMessage.ROLE_SYSTEM, AiPrompt.searchQuerySystem()),
                                AiMessage(AiMessage.ROLE_USER, q),
                            ),
                            AI_QUERY_TIMEOUT_MS,
                            maxTokens = 128,
                        )
                        val query = AiSearch.extractQuery(queryRaw.content)
                        if (query.isNotEmpty()) {
                            val results = when (provider) {
                                // 本机浏览器：WebView 在主线程自起自收，结果解析是纯函数
                                AiSearch.Provider.BING_LOCAL ->
                                    BrowserSearch.searchBing(container.appContext, query)
                                else -> AiSearch.searchCached(provider, searchKey, query)
                            }
                            if (results.isNotEmpty()) {
                                searchQuery = query
                                searchCount = results.size
                                searchBlock = AiSearch.formatResults(query, results)
                            } else {
                                searchError = "搜索没有返回结果"
                            }
                        }
                    } catch (e: Exception) {
                        searchError = e.message ?: "未知错误"
                    }
                }

                // 前缀缓存优化：system 只放不变规则，每轮变化的现场快照走独立用户消息，
                // 多轮对话的稳定前缀才能命中 DeepSeek 的自动上下文缓存
                val snapshot = buildString {
                    append("【现场快照（启动器采集，可信）】\n")
                    append(buildAiContext())
                    if (searchBlock.isNotEmpty()) append("\n\n").append(searchBlock)
                    append("\n\n【问题】").append(q)
                }
                val history = _aiMessages.value
                    .dropLast(1) // 刚追加的本轮提问，最后单独加
                    .filter { !it.isError }
                    .mapNotNull { m ->
                        when {
                            m.isUser -> AiMessage(AiMessage.ROLE_USER, m.text)
                            m.rawReply != null -> AiMessage(AiMessage.ROLE_ASSISTANT, m.rawReply)
                            else -> null
                        }
                    }
                    .takeLast(AI_HISTORY_MESSAGES)
                val session = AiTurnSession(
                    config = config,
                    apiMessages = mutableListOf(AiMessage(AiMessage.ROLE_SYSTEM, AiPrompt.rules())).also {
                        it += history
                        it += AiMessage(AiMessage.ROLE_USER, snapshot)
                    },
                    roundsLeft = AI_MAX_TOOL_ROUNDS,
                    searchQuery = searchQuery,
                    searchCount = searchCount,
                    searchError = searchError,
                    instanceId = _currentInstanceId.value,
                )
                // 工具多轮循环：读/列自动执行回喂，写挂起等用户确认（见 runTurn）
                runTurn(session)
            } catch (e: Exception) {
                appendAiMessage(
                    AiChatMessage(nextAiId(), isUser = false, text = e.message ?: "AI 请求失败", isError = true)
                )
            } finally {
                _aiBusy.value = false
            }
        }
    }

    /**
     * 工具多轮循环：模型每轮要么返回最终回答，要么调用一个工具。
     * 双协议：官方 DeepSeek 走**原生 function calling**（tools 参数 + role=tool 回喂），
     * 其它服务商走 JSON 夹带协议（tool 字段 + 【工具结果】用户消息回喂）——
     * 两者共用同一套执行与审批逻辑，只是回喂格式不同。
     * read/list/fetch 自动执行；write_file 挂起等用户确认（会话存入 [aiSession]）。
     * [AI_MAX_TOOL_ROUNDS] 轮内没收敛就按已有分析收尾 —— 防止失控循环烧 token。
     */
    private suspend fun runTurn(session: AiTurnSession) {
        var lastAnalysis = ""
        while (session.roundsLeft > 0) {
            // 取消在轮次边界生效：进行中的 HTTP 调用让它自然结束（线程有超时兜底）
            if (aiCancelRequested) {
                aiCancelRequested = false
                aiSession = null
                appendAiMessage(
                    AiChatMessage(nextAiId(), isUser = false, text = "已取消本轮提问。", toolNote = "已取消")
                )
                return
            }
            session.roundsLeft--
            val deadline = System.currentTimeMillis() +
                if (session.config.thinking) AI_THINKING_REPLY_TIMEOUT_MS else AI_REPLY_TIMEOUT_MS
            // 实况流：思考/正文逐 token 回调进 [_aiLiveStream]，完成后以正式气泡落榜
            val live = AiLiveStream(nextAiId(), thinking = session.config.thinking)
            val aiReply = try {
                _aiLiveStream.value = live
                AiClient.chatStream(
                    session.config,
                    session.apiMessages,
                    // write_file 可能携带整个配置文件；思考模式推理 Token 计入 max_tokens
                    //（官方：思考模式默认输出 64K），给足预算防截断
                    maxTokens = if (session.config.thinking) 16_384 else 4_096,
                    extraJson = session.config.extraBody,
                    thinkingEnabled = session.config.thinking,
                    includeNativeThinkingParam = includeNativeThinking(session.config),
                    includeTools = includeNativeThinking(session.config),
                    includeTools = includeNativeThinking(session.config),
                    shouldStop = { aiCancelRequested || System.currentTimeMillis() > deadline },
                    onDelta = { r, c -> updateLiveStream(live.id, r, c) },
                )
            } finally {
                _aiLiveStream.value = null
            }
            if (aiReply.aborted) {
                val userCancelled = aiCancelRequested
                aiCancelRequested = false
                aiSession = null
                val partial = aiReply.content.trim()
                appendAiMessage(
                    AiChatMessage(
                        nextAiId(),
                        isUser = false,
                        text = when {
                            partial.isNotEmpty() -> "（已中止）${partial.take(1000)}…"
                            userCancelled -> "已取消本轮提问。"
                            else -> "AI 响应超时，已停止本轮。可重试或换个问法。"
                        },
                        toolNote = if (userCancelled) "已取消" else "响应超时",
                    )
                )
                return
            }
            // ── 原生工具调用（官方 DeepSeek）──
            if (aiReply.toolCalls.isNotEmpty()) {
                session.apiMessages += AiMessage(
                    AiMessage.ROLE_ASSISTANT,
                    aiReply.content,
                    toolCallsRaw = aiReply.rawToolCallsJson,
                )
                if (execNativeToolCalls(session, aiReply.toolCalls)) return // 挂起等写入确认
                continue
            }
            // ── JSON 夹带协议（其它服务商）──
            // 剥掉混在正文里的 <think> 推理段；历史回喂与展示都用干净文本，
            // 推理过程（reasoning_content 或 <think> 段）单独保存供 UI 折叠展示
            val (cleaned, inlineThink) = AiSuggestion.splitThinking(aiReply.content)
            val reasoning = (aiReply.reasoning ?: inlineThink)
                ?.take(AI_MAX_REASONING_CHARS)
            // 开了深度思考却没拿到思考内容：给可见诊断，而不是无声无息让用户以为坏了
            val thinkingNote = if (session.config.thinking && reasoning == null) {
                "已开启深度思考，但该模型没有返回思考过程：DeepSeek 官方 deepseek-flash " +
                    "会自动返回；其它服务商可能不支持思考输出，或需要显式参数" +
                    "（在 AI 设置「附加请求参数」里填写，如 Qwen 的 {\"enable_thinking\":true}）"
            } else {
                null
            }
            val parsed = AiSuggestion.parse(cleaned)
            lastAnalysis = parsed.analysis
            val tool = parsed.tool
            if (tool == null) {
                appendFinalAiMessage(session, parsed, cleaned, reasoning, thinkingNote, aiReply.usage)
                return
            }
            when (tool.name) {
                "read_file", "list_dir", "fetch_page" -> {
                    val note = when (tool.name) {
                        "read_file" -> "读取 ${tool.path}"
                        "list_dir" -> "列出 ${tool.path}"
                        else -> "抓取网页 ${AiSanitize.displayOneLine(tool.path)}"
                    }
                    val result = runCatching { executeAiReadTool(tool.name, tool.path) }
                        .getOrElse { "工具执行失败：${it.message}" }
                    appendAiMessage(
                        AiChatMessage(
                            id = nextAiId(),
                            isUser = false,
                            text = parsed.analysis.ifBlank { note },
                            toolNote = note,
                        )
                    )
                    session.apiMessages += AiMessage(
                        AiMessage.ROLE_USER,
                        "【工具结果】${tool.name} \"${tool.path}\"\n$result",
                    )
                }
                "write_file" -> {
                    if (tool.content.isEmpty()) {
                        session.apiMessages += AiMessage(
                            AiMessage.ROLE_USER,
                            "【工具结果】write_file \"${tool.path}\" → 失败：content 为空，请给出完整新文件内容",
                        )
                        continue
                    }
                    // 结构性禁止的文件（JVM 参数 / op 名单 / 数据包函数 / EULA…）：
                    // 直接拒绝并回喂，**不给**"允许写入"按钮 —— 这类文件的写入等于把代码执行
                    // 或权限授予交出去，任何一次点击确认都不该放行（用户想看的是"AI 想干什么"，
                    // 而不是被迫在"允许"和"放弃"之间二选一）。
                    val target = session.instanceId?.let { instanceStore.get(it) }
                    val policy = AiFileTools.writePolicyFor(target?.dir, tool.path)
                    if (policy.forbidden != null) {
                        appendAiMessage(
                            AiChatMessage(
                                id = nextAiId(),
                                isUser = false,
                                text = "已阻止一次文件写入\n${tool.path}\n原因：${policy.forbidden}",
                                toolNote = "已阻止写入 ${tool.path}",
                                isError = true,
                                instanceId = session.instanceId,
                            )
                        )
                        session.apiMessages += AiMessage(
                            AiMessage.ROLE_USER,
                            "【工具结果】write_file \"${tool.path}\" → 被启动器策略拒绝：${policy.forbidden}。" +
                                "该文件不允许通过 AI 修改，请改用其它方案，或直接告诉用户应该怎么改。",
                        )
                        continue
                    }
                    // 挂起等用户确认：卡片上展示完整内容，批准/拒绝后从这轮继续
                    aiSession = session
                    appendAiMessage(
                        AiChatMessage(
                            id = nextAiId(),
                            isUser = false,
                            text = parsed.analysis.ifBlank { "我准备写入文件。" },
                            writeRequest = AiWriteRequest(
                                path = tool.path,
                                content = tool.content,
                                bytes = tool.content.toByteArray(Charsets.UTF_8).size,
                                absPath = policy.resolvedPath,
                                instanceName = target?.name.orEmpty(),
                                warning = policy.warning,
                            ),
                            instanceId = session.instanceId,
                        )
                    )
                    return
                }
                else -> {
                    appendFinalAiMessage(session, parsed, cleaned, reasoning, thinkingNote, aiReply.usage)
                    return
                }
            }
        }
        aiSession = null
        appendAiMessage(
            AiChatMessage(
                id = nextAiId(),
                isUser = false,
                text = "本轮工具调用已达上限（$AI_MAX_TOOL_ROUNDS 次）。基于已获得的信息：\n" +
                    lastAnalysis.ifBlank { "（模型没有给出更多分析，请换个问法继续）" },
            )
        )
    }

    /**
     * 执行原生工具调用批次。返回 true = 已挂起等写入确认（本轮暂停）。
     * 结果以 role=tool 回喂（OpenAI 工具协议），与 JSON 协议共用同一套执行/审批逻辑。
     */
    private suspend fun execNativeToolCalls(session: AiTurnSession, calls: List<NativeToolCall>): Boolean {
        for (tc in calls) {
            val args = runCatching { aiJson.decodeFromString<NativeArgs>(tc.arguments) }.getOrNull()
            if (args == null) {
                session.apiMessages += AiMessage(
                    AiMessage.ROLE_TOOL,
                    "工具参数解析失败（arguments 不是合法 JSON）：${tc.arguments.take(200)}",
                    toolCallId = tc.id,
                )
                continue
            }
            val name = tc.name.trim().lowercase()
            if (name == "write_file") {
                if (args.content.isEmpty()) {
                    session.apiMessages += AiMessage(
                        AiMessage.ROLE_TOOL,
                        "失败：content 为空，请给出完整新文件内容",
                        toolCallId = tc.id,
                    )
                    continue
                }
                val target = session.instanceId?.let { instanceStore.get(it) }
                val policy = AiFileTools.writePolicyFor(target?.dir, args.path)
                if (policy.forbidden != null) {
                    appendAiMessage(
                        AiChatMessage(
                            id = nextAiId(), isUser = false,
                            text = "已阻止一次文件写入\n${args.path}\n原因：${policy.forbidden}",
                            toolNote = "已阻止写入 ${args.path}",
                            isError = true,
                            instanceId = session.instanceId,
                        )
                    )
                    session.apiMessages += AiMessage(
                        AiMessage.ROLE_TOOL,
                        "被启动器策略拒绝：${policy.forbidden}。该文件不允许通过 AI 修改，请改用其它方案，或直接告诉用户应该怎么改。",
                        toolCallId = tc.id,
                    )
                    continue
                }
                aiSession = session
                appendAiMessage(
                    AiChatMessage(
                        id = nextAiId(), isUser = false,
                        text = "我准备写入文件。",
                        writeRequest = AiWriteRequest(
                            path = args.path,
                            content = args.content,
                            bytes = args.content.toByteArray(Charsets.UTF_8).size,
                            absPath = policy.resolvedPath,
                            instanceName = target?.name.orEmpty(),
                            warning = policy.warning,
                        ),
                        instanceId = session.instanceId,
                    )
                )
                return true
            }
            if (name !in setOf("read_file", "list_dir", "fetch_page")) {
                session.apiMessages += AiMessage(AiMessage.ROLE_TOOL, "未知工具：$name", toolCallId = tc.id)
                continue
            }
            // fetch_page 的参数名是 url，其余是 path
            val targetPath = if (name == "fetch_page") args.url.ifBlank { args.path } else args.path
            val note = when (name) {
                "read_file" -> "读取 $targetPath"
                "list_dir" -> "列出 $targetPath"
                else -> "抓取网页 ${AiSanitize.displayOneLine(targetPath)}"
            }
            val result = runCatching { executeAiReadTool(name, targetPath) }
                .getOrElse { "工具执行失败：${it.message}" }
            appendAiMessage(
                AiChatMessage(nextAiId(), isUser = false, text = note, toolNote = note)
            )
            session.apiMessages += AiMessage(AiMessage.ROLE_TOOL, result, toolCallId = tc.id)
        }
        return false
    }

    private fun appendFinalAiMessage(
        session: AiTurnSession,
        parsed: AiSuggestion.Parsed,
        cleaned: String,
        reasoning: String?,
        thinkingNote: String? = null,
        usage: AiUsage? = null,
    ) {
        aiSession = null
        appendAiMessage(
            AiChatMessage(
                id = nextAiId(),
                isUser = false,
                text = parsed.analysis.ifBlank { "（模型没有给出分析文本）" },
                command = parsed.command.takeIf { it.isNotEmpty() },
                rawReply = cleaned,
                searchQuery = session.searchQuery,
                searchCount = session.searchCount,
                searchError = session.searchError,
                instanceId = session.instanceId,
                reasoning = reasoning,
                thinkingNote = thinkingNote,
                usageSummary = usage?.summary(),
            )
        )
    }

    /** 执行只读工具（读文件 / 列目录 / 抓网页）：实例目录为主根，app: 前缀走应用私有目录 */
    private fun executeAiReadTool(name: String, path: String): String {
        // 抓网页不需要实例目录
        if (name == "fetch_page") return AiWebPage.fetch(path)
        val dir = _currentInstanceId.value?.let { instanceStore.get(it) }?.dir
            ?: return "当前未选择实例，无法访问文件"
        return if (name == "read_file") {
            AiFileTools.readFile(dir, container.appContext.filesDir, path)
        } else {
            AiFileTools.listDir(dir, container.appContext.filesDir, path)
        }
    }

    /** 用户允许 AI 写入：执行写入（内部已有 .bak 备份），结果回喂并继续本轮 */
    fun approveAiWrite(messageId: Long) {
        if (_aiBusy.value) return
        val msg = _aiMessages.value.firstOrNull { it.id == messageId } ?: return
        val req = msg.writeRequest ?: return
        // 「已拒绝」是终态；失败(3)允许重试，成功(1)不重复执行。
        // 状态在写入结果出来后才置位 —— 乐观置"已写入"会把失败伪装成成功。
        if (msg.writeState != 0 && msg.writeState != 3) return
        val session = aiSession
        _aiBusy.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 写入绑定到请求发起时的实例（消息里记录的），不是当前选中的实例
                val dir = (msg.instanceId?.let { instanceStore.get(it) }
                    ?: _currentInstanceId.value?.let { instanceStore.get(it) })?.dir
                val result = if (dir == null) "失败：实例不存在或已被删除"
                else runCatching { AiFileTools.writeFile(dir, req.path, req.content) }
                    .getOrElse { "失败：${it.message}" }
                // 失败路径的 result 一律以"失败"开头（writeFile 与上方 dir 判空共同约定）
                val success = !result.startsWith("失败")
                setAiWriteState(messageId, if (success) 1 else 3)
                appendAiMessage(
                    AiChatMessage(
                        nextAiId(),
                        isUser = false,
                        text = if (success) "写入了 ${req.path}" else "写入 ${req.path} 失败",
                        toolNote = if (success) "写入 ${req.path}" else "写入 ${req.path} 失败",
                    )
                )
                // 会话还在（挂起写入的正常路径）：结果回喂，模型继续收尾；
                // 会话已结束（对旧卡片点重试）：只执行写入本身，不再回喂
                if (session != null) {
                    session.apiMessages += AiMessage(
                        AiMessage.ROLE_USER,
                        "【工具结果】write_file \"${req.path}\" → $result",
                    )
                    runTurn(session)
                }
            } catch (e: Exception) {
                aiSession = null
                setAiWriteState(messageId, 3)
                appendAiMessage(
                    AiChatMessage(nextAiId(), isUser = false, text = e.message ?: "写入流程失败", isError = true)
                )
            } finally {
                _aiBusy.value = false
            }
        }
    }

    /** 用户拒绝写入：不执行任何落盘，把拒绝结果回喂让 AI 收尾 */
    fun denyAiWrite(messageId: Long) {
        if (_aiBusy.value) return
        val msg = _aiMessages.value.firstOrNull { it.id == messageId } ?: return
        val req = msg.writeRequest ?: return
        // 失败(3)的卡片上也有「放弃」：语义是放弃这次写入请求
        if (msg.writeState != 0 && msg.writeState != 3) return
        setAiWriteState(messageId, 2)
        val session = aiSession
        if (session == null) {
            appendAiMessage(
                AiChatMessage(
                    nextAiId(), isUser = false,
                    text = "本轮会话已结束，已拒绝本次写入。",
                    toolNote = "拒绝写入 ${req.path}",
                )
            )
            return
        }
        _aiBusy.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                session.apiMessages += AiMessage(
                    AiMessage.ROLE_USER,
                    "【工具结果】write_file \"${req.path}\" → 用户拒绝了本次写入。请不要重试，直接基于已有信息给出建议或改用其它方案",
                )
                runTurn(session)
            } catch (e: Exception) {
                aiSession = null
                appendAiMessage(
                    AiChatMessage(nextAiId(), isUser = false, text = e.message ?: "流程失败", isError = true)
                )
            } finally {
                _aiBusy.value = false
            }
        }
    }

    private fun setAiWriteState(messageId: Long, state: Int) {
        _aiMessages.value = _aiMessages.value.map {
            if (it.id == messageId) it.copy(writeState = state) else it
        }
        persistAiChat()
    }

    /**
     * 执行 AI 建议的命令：与用户手输命令完全同一条路（[sendCommand] → 服务端 stdin）。
     *
     * P0 的安全边界：命令在解析时已清洗（单条、无换行），这里**再清洗一次**防绕过；
     * 服务端未运行时直接拒绝（stdin 不存在，发了也没人收，界面上按钮同样禁用）。
     */
    fun executeAiSuggestion(messageId: Long) {
        val msg = _aiMessages.value.firstOrNull { it.id == messageId } ?: return
        val cmd = AiSuggestion.sanitizeCommand(msg.command) ?: return
        // 命令属于生成它的那个实例，不是"当前选中的实例"（用户可能已切换）
        val targetId = msg.instanceId ?: _currentInstanceId.value ?: return
        val inst = instanceStore.get(targetId) ?: return
        if (serverManager.states.value[targetId] != ServerState.Running) return
        serverManager.sendCommand(inst, cmd)
        _aiMessages.value = _aiMessages.value.map {
            if (it.id == messageId) it.copy(commandSent = true) else it
        }
    }

    /** 组装 AI 上下文快照（当前实例的状态 / 玩家 / 占用 / 控制台日志窗口） */
    private fun buildAiContext(): String {
        val inst = _currentInstanceId.value?.let { instanceStore.get(it) }
        return AiContext.build(
            instanceName = inst?.name ?: "（未选择实例）",
            mcVersion = inst?.mcVersion ?: "",
            coreType = inst?.coreType?.displayName ?: "未知",
            javaMajor = inst?.javaMajor ?: 0,
            memoryMb = inst?.memoryMb ?: 0,
            stateLabel = serverState.value.toLabel(),
            uptimeSec = uptimeSec.value,
            players = _onlinePlayers.value,
            stats = _procStats.value,
            lines = _consoleLines.value,
        )
    }

    /**
     * 在独立守护线程上跑阻塞的 HTTP 调用，并给出**界面可见的超时**。
     *
     * HttpURLConnection 的 DNS 解析不受 readTimeout 约束，离线时可能长时间不返回；
     * 与 [blockingWithTimeout] 同一套做法：超时后界面先恢复，线程自己跑完自行丢弃。
     */
    private suspend fun callAiWithTimeout(
        config: AiConfig,
        messages: List<AiMessage>,
        timeoutMs: Long,
        maxTokens: Int = 1024,
    ): AiReply {
        val deferred = kotlinx.coroutines.CompletableDeferred<AiReply>()
        kotlin.concurrent.thread(isDaemon = true, name = "kaze-ai") {
            try {
                deferred.complete(AiClient.chat(config, messages, maxTokens))
            } catch (t: Throwable) {
                deferred.completeExceptionally(t)
            }
        }
        return withTimeoutOrNull(timeoutMs) { deferred.await() }
            ?: throw RuntimeException("AI 在 ${timeoutMs / 1000} 秒内没有响应：请检查网络后重试")
    }

    private fun appendAiMessage(message: AiChatMessage) {
        _aiMessages.value = _aiMessages.value + message
        persistAiChat()
    }

    private fun nextAiId(): Long = aiIdCounter.incrementAndGet()

    // ── 服务端进程的 CPU / 内存占用 ──
    private val _procStats = MutableStateFlow<ProcessStats.Reading?>(null)
    val procStats: StateFlow<ProcessStats.Reading?> = _procStats.asStateFlow()

    // 启动采样轮询。**必须放在 `_procStats` 声明之后**：Kotlin 的 init 块按声明顺序执行，
    // 若把它写进文件上方那个 init（第 179 行附近），执行到那里时 `_procStats` 还是 null ——
    // 协程被 Dispatchers.IO 立刻调度后读到 null 就抛 NPE，采样循环当场死掉，界面依旧是"采样中"。
    // 这个顺序坑在 CI 上表现为 12 个 UI 测试报 UncaughtExceptionsBeforeTest（真机上同样会死，
    // 只是异常没人看见，还以为是 /proc 读不到）。
    init { startProcStatsPolling() }

    /**
     * 每秒采一次服务端进程的 CPU / 内存，以及整机可用内存。
     *
     * 服务端是 proot 的孙进程（app → proot → java），拿不到 Process 句柄，只能按 pid 读
     * `/proc`；pid 由 [ProcessStats.pickServer] 在候选里挑（踩过"选中 proot 包装进程"的坑）。
     * CPU 必须两次采样求差，所以第一拍只记基线、不出数；读数再过一次 EMA 平滑。
     *
     * 节拍从 2 秒缩到 1 秒：窗口越短越跟得上突发负载（8 核上一个核忙 0.2 秒，
     * 摊到 2 秒窗口里几乎看不见），代价只是每秒两次很小的 /proc 读。
     *
     * 由 [init] 启动 —— **这一环曾经是漏的**：函数写好了却没有任何调用点，
     * 于是 `_procStats` 恒为 null，界面永远显示「CPU 采样中…」。
     * 另外只在当前实例处于 Running 时才扫 /proc（空闲时不白扫一遍整机）。
     */
    fun startProcStatsPolling() {
        viewModelScope.launch(Dispatchers.IO) {
            val appCtx = container.appContext
            var pid: Int? = null
            var lastTicks = 0L
            var lastAt = 0L
            // CPU 平滑值（单位：核）。单拍求差的原始值很跳（GC、区块生成都是突发的）
            var smoothCores = -1f
            // 连续读失败的拍数：连着丢几拍才认定"读不到"，避免瞬时失败把整行闪回"采样中"
            var missCount = 0
            while (true) {
                val id = _currentInstanceId.value
                val dirName = id?.let { instanceStore.get(it)?.dir?.name }
                // 只在「当前实例正在运行」时才扫描 /proc：不运行时也扫整机 /proc
                // （几百次文件读）纯属浪费电，而这一行本来就只在 Running 时渲染。
                val running = id != null && serverManager.states.value[id] == ServerState.Running
                if (dirName == null || !running) {
                    _procStats.value = null
                    pid = null; lastTicks = 0L; lastAt = 0L; smoothCores = -1f; missCount = 0
                    delay(PROC_STATS_INTERVAL_MS)
                    continue
                }
                // 每拍只读一次 /proc。旧实现写成 `if (pid == null || sample(pid) == null) …`
                // 之后又 `sample(pid)`，同一拍读两遍，白花一倍系统调用。
                var cur = pid
                var got = cur?.let { ProcessStats.sample(it) }
                if (got == null) {
                    // 传实例目录名：兜底分支只认 cmdline 里带它的候选，
                    // 免得"一个后代都没扫到"时把别的实例/别的应用的 java 当成自己的
                    val fresh = ProcessStats.findServerPid(dirName)
                    if (fresh != pid) {
                        // 认到了别的进程（或刚认到）：基线作废，重新等两拍再出数
                        pid = fresh; lastTicks = 0L; lastAt = 0L; smoothCores = -1f
                    }
                    // fresh == pid：同一个进程，只是这一拍没读到 —— **保留基线**，
                    // 下一拍用更长的时间窗继续求差（旧实现在这里清零，数字会一顿一顿）
                    cur = fresh
                    got = fresh?.let { ProcessStats.sample(it) }
                }
                if (got == null || cur == null) {
                    missCount++
                    if (missCount >= 3) _procStats.value = null   // ≈3 秒都读不到才清空
                    delay(PROC_STATS_INTERVAL_MS)
                    continue
                }
                missCount = 0
                val now = System.currentTimeMillis()
                if (lastAt > 0L) {
                    val (_, rawCores) = ProcessStats.cpuFrom(lastTicks, got.first, now - lastAt, ProcessStats.cores)
                    smoothCores = ProcessStats.smoothCores(smoothCores, rawCores)
                    val total = ProcessStats.cores.coerceAtLeast(1)
                    val percent = (smoothCores / total * 100f).coerceIn(0f, 100f)
                    val (availKb, totalMemKb, lowMem) = ProcessStats.memorySnapshot(appCtx)
                    _procStats.value = ProcessStats.Reading(
                        cpuPercent = percent,
                        coresUsed = smoothCores,
                        rssKb = got.second,
                        pid = cur,
                        availMemKb = availKb,
                        totalMemKb = totalMemKb,
                        lowMemory = lowMem,
                    )
                }
                lastTicks = got.first
                lastAt = now
                delay(PROC_STATS_INTERVAL_MS)
            }
        }
    }

    // ── 控制台行的批量发布 ──
    // 逐行 `(cur + line).takeLast(N)` 是 O(N)/行：Forge 启动时每秒几百上千行、列表上万条，
    // 就是每秒上千万次元素拷贝 + 同样次数的重组合 → 主线程卡死（真机反馈的"1.4 万行卡住"）。
    private val consoleLock = Any()
    private val consolePending = ArrayList<ConsoleLine>(CONSOLE_FLUSH_BATCH)

    /**
     * 发布一批待写行。调用方必须持有 [consoleLock]。
     *
     * `replaceLast` 必须**逐条**结算，不能整批只记一个布尔。
     * 旧实现用一个 `consolePendingReplace` 标记 + 发布时 `dropLast(1)`：一批里可能有 k 条
     * 覆盖行（服务端的 `\r` 进度行正是这种，一批能攒到 CONSOLE_FLUSH_BATCH=256 条），
     * 却只删掉一条 → 每批净增 k-1 行，Forge 刷屏几秒就能把控制台行数冲爆。
     *
     * 这里用一个只装本批行的局部栈模拟"弹掉最后一行、再压入自己"：
     * 栈非空时弹出的必然是本批的行；批内第一条就是覆盖行时，它顶掉的是上一次发布留下的
     * 最后一行（所以 base 再少一条）。结果与"小列表逐行即时发布"那条路径完全一致 ——
     * 批内被覆盖掉的中间行随之消失。
     */
    private fun publishConsoleLocked() {
        if (consolePending.isEmpty()) return
        val cur = _consoleLines.value
        val out = ArrayList<ConsoleLine>(consolePending.size)
        for (line in consolePending) {
            if (line.replaceLast && out.isNotEmpty()) out.removeAt(out.lastIndex)
            out.add(line)
        }
        val base =
            if (consolePending.first().replaceLast && cur.isNotEmpty()) cur.dropLast(1) else cur
        _consoleLines.value = (base + out).takeLast(CONSOLE_DISPLAY_MAX)
        consolePending.clear()
    }

    // ── 回读更早的日志（内存窗口之外的历史，来自 console-output.log / .old.log）──
    private val _olderCursor = MutableStateFlow<ConsoleArchive.Cursor?>(null)
    private val _hasMoreOlder = MutableStateFlow(false)
    val hasMoreOlder: StateFlow<Boolean> = _hasMoreOlder.asStateFlow()
    private val _loadingOlder = MutableStateFlow(false)
    val loadingOlder: StateFlow<Boolean> = _loadingOlder.asStateFlow()

    /**
     * 往前读一段更早的日志并**前插**到列表顶部。
     *
     * 界面用 LazyColumn 的 key（按 seq）保持滚动位置：前插后索引会整体后移，
     * 没有 key 的话视图会跳到别处。
     */
    fun loadOlderConsole() {
        val id = _currentInstanceId.value ?: return
        if (_loadingOlder.value) return
        val dir = instanceStore.get(id)?.dir ?: return
        _loadingOlder.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val chunk = ConsoleArchive.readOlder(dir, _olderCursor.value)
                if (chunk.lines.isNotEmpty()) {
                    val older = chunk.lines.map { ConsoleLine(it, LineType.Info) }
                    _consoleLines.value = (older + _consoleLines.value).takeLast(CONSOLE_DISPLAY_MAX)
                }
                _olderCursor.value = chunk.cursor
                _hasMoreOlder.value = chunk.hasMore
            } catch (e: Exception) {
                android.util.Log.w("KazeSLauncher", "回读更早日志失败", e)
                _hasMoreOlder.value = false
            } finally {
                _loadingOlder.value = false
            }
        }
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
        _olderCursor.value = null
        _hasMoreOlder.value = false
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
            // 控制台缓冲 / 运行时长流 / 状态表条目都是按 id 长期持有的，实例没了就再没有任何
            // 入口能到达它们 —— 不回收就是纯泄漏（控制台缓冲是按实例上限存行的）
            runCatching { serverManager.release(instance.id) }
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
        // 每次创建都从**干净的空槽位**开始：`download` 是跨页面共享的，上一次安装留下的
        // error/done 会被配置页读出来 —— 用户刚进向导就看到一条过期的「下载失败」，
        // 按钮还写着"重试下载（断点续传）"，而这次什么都没下载过。
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
    /** 搜索结果请求序号：只有"最新一次搜索"有权写结果与复位 loading（快速连续搜索会乱序返回） */
    @Volatile
    private var addonSearchRequestId = 0

    /** 搜索失败信息（**独立槽位**，见 [searchAddons]） */
    private val _addonSearchError = MutableStateFlow<String?>(null)
    val addonSearchError: StateFlow<String?> = _addonSearchError.asStateFlow()

    fun clearAddonSearchError() {
        _addonSearchError.value = null
    }

    /**
     * 搜索 Modrinth。
     *
     * 两条守卫：
     *  1. **请求序号**：搜索是同步网络请求（几秒都正常），用户打完一个词没出结果又改一个词回车，
     *     两次请求并发在 IO 线程池上，先发的后回来就会用旧结果覆盖新结果 —— 输入框写着 A、
     *     列表却是 B 的结果，点安装就装错东西。
     *  2. 失败写**自己的**槽位，不能写进 [addonInstall]：那个槽位是"安装"的状态，
     *     列表用 `installState.error == null` 判断要不要显示空状态，安装页也用它的
     *     error/done 决定横幅 —— 一次搜索失败会污染安装流程的空态判断。
     */
    fun searchAddons(query: String, kind: AddonKind) {
        if (query.isBlank()) return
        val requestId = ++addonSearchRequestId
        _addonSearching.value = true
        _addonSearchError.value = null
        _addonResults.value = emptyList()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val hits = ModrinthApi.search(query, kind)
                if (requestId == addonSearchRequestId) _addonResults.value = hits
            } catch (e: Exception) {
                if (requestId == addonSearchRequestId) {
                    _addonSearchError.value = "搜索失败：${e.message ?: e.javaClass.simpleName}"
                }
            } finally {
                // 带序号：旧请求的 finally 可能晚于新请求置位，无条件复位会把新请求的
                // loading 提前关掉（版本列表加载里踩过同一个坑）
                if (requestId == addonSearchRequestId) _addonSearching.value = false
            }
        }
    }

    /**
     * 安装前的同名检查：文件名与已装插件/模组相同时先停下来问一句。
     *
     * 同名替换本身是对的（Bukkit/Fabric 的惯例：同名 = 同一份插件的新版本，
     * 改名会让两份 jar 被同时加载），但它会**直接覆盖用户已经下好的文件**，
     * 属于不可逆动作 —— 和本页的删除一样必须先确认。目标文件名的解析要走网络，
     * 所以整个检查放在 ViewModel 的 IO 协程里做，不依赖调用方的组合作用域。
     */
    fun installAddonChecked(instance: ServerInstance, kind: AddonKind, hit: ModrinthSearchHit) {
        if (_addonInstall.value.running) return
        _addonInstall.value = DownloadState(running = true, message = "检查 ${hit.title} 的适配版本…")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val target = AddonManager.targetFile(instance, kind, hit.project_id, instance.mcVersion)
                val existing = AddonManager.existingCollision(instance, kind, target)
                if (existing != null) {
                    // running=false + replacePending=true：界面据此弹确认，取消时直接清掉
                    _addonInstall.value = DownloadState(
                        message = "同名文件已存在：${existing.name}",
                        replacePending = true,
                    )
                    return@launch
                }
            } catch (e: Exception) {
                // 解析失败（没有适配当前 MC 版本 / 文件名非法）：直接把原因说出来，
                // 继续走 install 只会拿到同一句错误、白白多一次网络请求
                _addonInstall.value = DownloadState(error = e.message ?: "解析适配版本失败")
                return@launch
            }
            _addonInstall.value = DownloadState()
            installAddon(instance, kind, hit)
        }
    }

    /** 同名替换确认：继续安装（[installAddon] 内部会原子换入新文件） */
    fun confirmReplaceAddon(instance: ServerInstance, kind: AddonKind, hit: ModrinthSearchHit) {
        if (!_addonInstall.value.replacePending) return
        _addonInstall.value = DownloadState()
        installAddon(instance, kind, hit)
    }

    /** 同名替换取消：把待确认状态清掉，不动磁盘上的原文件 */
    fun cancelReplaceAddon() {
        if (_addonInstall.value.replacePending) _addonInstall.value = DownloadState()
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

    // ── 动作：应用自身更新（设置页与启动弹窗共用同一份状态）──
    private val _appUpdate = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val appUpdate: StateFlow<AppUpdateState> = _appUpdate.asStateFlow()

    /** 启动检查发现新版本时，用户点了「以后再说」——本次启动不再弹窗 */
    private var updatePostponed = false

    /** 取消标志：由界面置位，下载循环轮询（断点保留） */
    @Volatile
    private var updateCancelRequested = false

    fun updatePostpone() {
        updatePostponed = true
    }

    /** 当前版本号（GitHub tag 比较用） */
    fun currentVersionName(): String = runCatching {
        container.appContext.packageManager
            .getPackageInfo(container.appContext.packageName, 0).versionName ?: ""
    }.getOrDefault("")

    /**
     * 启动时的自动检查（仅一次）。
     *
     * 网络必须切到 IO 线程：LaunchedEffect 跑在**主线程**，而 UpdateChecker.check 是同步
     * HttpURLConnection（Downloader.downloadText）。旧实现直接在主线程调用，抛出的
     * NetworkOnMainThreadException 又被 catch 吞掉 —— 「启动自动检查更新」在真机上从未成功过。
     */
    fun checkUpdateOnStart(autoUpdate: Boolean) {
        if (!autoUpdate) return
        container.appScope.launch {
            try {
                val info = withContext(Dispatchers.IO) { UpdateChecker.check(container.uiPrefs.updateChannel.value) }
                if (info != null && UpdateChecker.isNewer(info.tag, currentVersionName())) {
                    _appUpdate.value = AppUpdateState.Found(info)
                }
            } catch (_: Exception) { /* 静默：启动检查失败不影响使用 */ }
        }
    }

    /** 手动检查更新（设置页） */
    fun checkUpdate() {
        if (_appUpdate.value is AppUpdateState.Checking) return
        _appUpdate.value = AppUpdateState.Checking
        container.appScope.launch {
            try {
                val info = withContext(Dispatchers.IO) { UpdateChecker.check(container.uiPrefs.updateChannel.value) }
                _appUpdate.value = if (info == null || !UpdateChecker.isNewer(info.tag, currentVersionName())) {
                    AppUpdateState.Latest
                } else {
                    AppUpdateState.Found(info)
                }
            } catch (e: Exception) {
                _appUpdate.value = AppUpdateState.Error(e.message ?: "检查失败（网络不可达？）")
            }
        }
    }

    /** 下载并安装更新。跑在 appScope 上：离开设置页 / 转屏都不会中断 */
    fun downloadAndInstallUpdate(info: UpdateChecker.ReleaseInfo) {
        if (_appUpdate.value is AppUpdateState.Downloading) return
        updateCancelRequested = false
        _appUpdate.value = AppUpdateState.Downloading(info, 0f, "准备下载…")
        container.appScope.launch {
            val file = UpdateInstaller.download(
                context = container.appContext,
                info = info,
                onProgress = { done, total, p ->
                    val msg = "下载中 $done MB" + (if (total > 0) " / $total MB" else "")
                    _appUpdate.value = AppUpdateState.Downloading(info, p, msg)
                },
                shouldCancel = { updateCancelRequested },
                allowPatch = container.uiPrefs.updateMode.value == "patch",
            )
            if (file == null) {
                _appUpdate.value = if (updateCancelRequested) AppUpdateState.Idle
                else AppUpdateState.Error("下载失败：所有线路不可用，请稍后重试")
                return@launch
            }
            val installed = withContext(Dispatchers.Main) {
                UpdateInstaller.install(container.appContext, file)
            }
            _appUpdate.value = if (installed) AppUpdateState.Idle
            else AppUpdateState.Error("无法打开安装器，请到设置中开启「安装未知应用」权限")
        }
    }

    fun cancelUpdateDownload() {
        if (_appUpdate.value is AppUpdateState.Downloading) updateCancelRequested = true
    }

    /**
     * 是否已请求取消更新下载。
     *
     * 用一个普通的 getter 而不是 State：调用点只在**下载中**读它，
     * 而下载中每收到一次进度回调都会重发 [appUpdate]（进而重组），所以不需要额外的可观察状态。
     */
    fun updateCancelRequestedFlag(): Boolean = updateCancelRequested

    /** 清掉「发现新版本 / 已是最新 / 失败」的卡片状态，让用户可以重新检查 */
    fun resetUpdateState() {
        if (_appUpdate.value is AppUpdateState.Downloading) return
        _appUpdate.value = AppUpdateState.Idle
        updatePostponed = true
    }

    /** 导入 jar 的连点守卫（见 [importJar]） */
    @Volatile
    private var importJarRunning = false

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
     *
     * 连点守卫与 [downloadAndCreate] 同理：两个并发导入会各自 createInstanceDir + add，
     * 复制几十 MB 的期间界面上的按钮还是可点的（enabled 要等重组）。
     */
    fun importJar(uri: android.net.Uri, name: String, memoryMb: Int) {
        if (importJarRunning) return
        importJarRunning = true
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
            } finally {
                importJarRunning = false
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

/** AI 历史携带的最大消息数（约 4 轮问答；更早的上下文由每轮重新采集的系统消息承担） */
private const val AI_HISTORY_MESSAGES = 8

/**
 * AI 回复的**界面可见超时**：AiClient 的 readTimeout 是 120s，加上连接与模型排队余量。
 * 深度思考（reasoner 类模型）先出推理再出答案，放宽一倍。
 * 超时后界面立即恢复，底层线程跑完自行丢弃（与 [VERSION_FETCH_TIMEOUT_MS] 同一套思路）。
 */
private const val AI_REPLY_TIMEOUT_MS = 150_000L
private const val AI_THINKING_REPLY_TIMEOUT_MS = 300_000L

/** 搜索查询词生成（小请求）的界面可见超时 */
private const val AI_QUERY_TIMEOUT_MS = 60_000L

/**
 * 单轮提问允许的模型调用次数上限（含最终回答那一次）。
 * 文件工具是多轮循环，设上限防止模型无限读文件烧 token；正常诊断 2~3 轮足够。
 */
private const val AI_MAX_TOOL_ROUNDS = 4

/** 保存的思考过程上限（reasoner 的推理可能非常长，展示端有滚动条，但不能无限占内存） */
private const val AI_MAX_REASONING_CHARS = 20_000