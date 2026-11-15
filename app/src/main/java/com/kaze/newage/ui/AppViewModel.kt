package com.kaze.newage.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kaze.newage.container
import com.kaze.newage.core.addons.AddonImporter
import com.kaze.newage.core.addons.AddonKind
import com.kaze.newage.core.addons.ModAudit
import com.kaze.newage.core.addons.ModrinthProject
import com.kaze.newage.core.content.ContentCatalog
import com.kaze.newage.core.content.ContentKind
import com.kaze.newage.core.console.OfflineConsole
import com.kaze.newage.core.sync.ClientPack
import com.kaze.newage.core.sync.ModSyncServer
import kotlinx.serialization.Serializable
import com.kaze.newage.core.addons.AddonRegistry
import com.kaze.newage.core.addons.AddonUpdate
import com.kaze.newage.core.addons.AddonManager
import com.kaze.newage.core.addons.ModrinthApi
import com.kaze.newage.core.addons.ModrinthSearchHit
import com.kaze.newage.core.ai.AiToolStep
import com.kaze.newage.core.ai.AiSkills
import com.kaze.newage.core.ai.SkillWriter
import com.kaze.newage.core.ai.AiProfile
import com.kaze.newage.core.console.ConsoleLine
import com.kaze.newage.core.console.CONSOLE_MAX_LINES
import com.kaze.newage.core.console.ConsoleParser
import com.kaze.newage.core.download.CoreBuild
import com.kaze.newage.core.download.CoreSources
import com.kaze.newage.core.env.ProotEnvironment
import com.kaze.newage.core.server.CoreUpdate
import com.kaze.newage.core.server.ServerProperties
import com.kaze.newage.core.server.ModpackImporter
import com.kaze.newage.core.server.ModpackManifest
import com.kaze.newage.core.server.JarInspector
import com.kaze.newage.core.server.ServerState
import com.kaze.newage.data.model.CoreType
import com.kaze.newage.data.model.GameVersion
import com.kaze.newage.data.model.JavaVersionInference
import com.kaze.newage.data.model.ServerInstance
import com.kaze.newage.core.update.UpdateChecker
import com.kaze.newage.core.update.UpdateInstaller
import com.kaze.newage.ui.ai.AiChatController
import com.kaze.newage.ui.ai.AiChatHost
import com.kaze.newage.util.Downloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
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
import com.kaze.newage.core.i18n.t

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
class AppViewModel(application: Application) : AndroidViewModel(application), AiChatHost {

    private val container = application.container

    // ── AiChatHost：AI 控制器（ui/ai/AiChatController）要的依赖与现场 ──
    // 全部是 getter 转发到**同一个对象**（不是拷贝），所以行为与搬运前完全一致。
    override val appContext: Context get() = container.appContext
    override val appScope: CoroutineScope get() = container.appScope
    override val scope: CoroutineScope get() = viewModelScope

    // ── core 组件 ──
    val env: ProotEnvironment = container.env
    override val serverManager = container.serverManager

    /** 应用日志（设置 → 诊断日志）；container 是私有的，这里给界面一个出口 */
    val appLog get() = container.appLog

    /** proot 环境（诊断页要读它写的自检文件） */
    val prootEnv get() = container.env
    override val instanceStore = container.instanceStore
    override val uiPrefs = container.uiPrefs

    /**
     * 内容中心（搜索 / 推荐 / 资源包 / 地图 / 基岩版）的业务逻辑。
     *
     * 依赖只有 Context、InstanceStore 和协程作用域，跟服务端生命周期无关，
     * 所以单独成类而不是继续堆在 AppViewModel 里（见 ContentController 的注释）。
     */
    val contentController: ContentController = ContentController(
        context = container.appContext,
        instanceStore = container.instanceStore,
        scope = viewModelScope,
        prefs = container.uiPrefs,
        onInstanceCreated = { inst -> selectInstance(inst) },
    )

    /** 公网访问（Tailscale / 樱花映射 / 自建 frp） */
    val networkController: NetworkController = NetworkController(
        context = container.appContext,
        env = container.env,
        prefs = container.uiPrefs,
        scope = viewModelScope,
    )

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
    override val currentInstanceId: StateFlow<String?> = _currentInstanceId.asStateFlow()

    /** 当前实例状态（无选中时为 Idle） */
    override val serverState: StateFlow<ServerState> =
        combine(_currentInstanceId, serverManager.states) { id, m ->
            if (id == null) ServerState.Idle else m[id] ?: ServerState.Idle
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ServerState.Idle)

    /** 当前实例运行时长（秒） */
    override val uptimeSec: StateFlow<Long> = _currentInstanceId
        .flatMapLatest { id -> if (id == null) flowOf(0L) else serverManager.uptimeSec(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    private val _consoleLines = MutableStateFlow<List<ConsoleLine>>(emptyList())
    override val consoleLines: StateFlow<List<ConsoleLine>> = _consoleLines.asStateFlow()

    /** 当前实例的在线玩家（来自 list 响应与加入/离开事件） */
    private val _onlinePlayers = MutableStateFlow<List<String>>(emptyList())
    override val onlinePlayers: StateFlow<List<String>> = _onlinePlayers.asStateFlow()

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
                        // update{} 而不是读-改-写：控制台行是并发喂进来的，
                    // 两个人几乎同时进服时旧写法会丢掉其中一个
                    _onlinePlayers.update { cur -> if (name in cur) cur else cur + name }
                    }
                    ConsoleParser.parseLeave(line.text)?.let { name ->
                        // 与上面"加入"保持一致用 update{}：读-改-写在并发时后写覆盖前写，
                        // 名单里会少一个人/多一个人，而只有 list 命令能纠回来
                        _onlinePlayers.update { cur -> cur - name }
                    }
                }
                } finally {
                    consoleFlusher.cancel()
                    synchronized(consoleLock) { publishConsoleLocked() }
                }
            }        }
        refreshJava()
    }

    // ── 技能（本地 md，用户放在 <实例目录>/skills/<名字>/SKILL.md）──

    /**
     * 当前实例可用技能的提示词段（只含清单，不含全文）。
     *
     * 技能是用户放在实例目录 `skills/<名字>/SKILL.md` 的本地 md 文档；
     * AI 需要时用 read_file 自己去读全文（不新增工具，读文件仍受既有策略约束）。
     */
    override fun skillsPromptBlock(): String =
        AiSkills.promptBlock(installedSkills(), uiPrefs.aiDisabledSkills.value)

    /** 当前实例已安装的技能 */
    fun installedSkills(): AiSkills.Scan =
        AiSkills.scan(currentInstanceId.value?.let { instanceStore.get(it) }?.dir)

    /** 指定实例的技能（详情页用：不一定等于当前选中实例） */
    fun skillsOf(instanceId: String): AiSkills.Scan =
        AiSkills.scan(instanceStore.get(instanceId)?.dir)

    fun setSkillEnabled(skillId: String, enabled: Boolean) = uiPrefs.setAiSkillEnabled(skillId, enabled)

    fun isSkillEnabled(skillId: String): Boolean = skillId !in uiPrefs.aiDisabledSkills.value

    // ── 技能的写入（新建 / 导入 / 删除）──
    //
    // 之前只能让用户自己把 md 拷进 <实例目录>/skills/<名字>/SKILL.md，
    // 手机上没有趁手的 md 编辑器，还得记住目录结构与 frontmatter 格式。
    // 这里给出应用内入口，格式由 SkillWriter 保证（也负责防止越界与覆盖）。

    /** 当前实例的目录（写技能用）；没有选中实例时返回 null */
    private fun currentInstanceDir(): java.io.File? =
        currentInstanceId.value?.let { instanceStore.get(it) }?.dir

    /**
     * 新建技能。
     *
     * @return 失败原因（可直接展示）；成功返回 null
     */
    fun createSkill(name: String, description: String, body: String): String? {
        val dir = currentInstanceDir() ?: return "请先选中一个实例"
        return when (val r = SkillWriter.create(dir, name, description, body)) {
            is SkillWriter.Result.Ok -> {
                // 新建的技能默认启用：从禁用名单里摘掉（同名可能被删过又重建）
                uiPrefs.setAiSkillEnabled(r.id, true)
                null
            }
            is SkillWriter.Result.Failed -> r.reason
        }
    }

    /** 从文件文本导入技能；返回失败原因或 null */
    fun importSkill(fileName: String, text: String): String? {
        val dir = currentInstanceDir() ?: return "请先选中一个实例"
        return when (val r = SkillWriter.importText(dir, fileName, text)) {
            is SkillWriter.Result.Ok -> {
                uiPrefs.setAiSkillEnabled(r.id, true)
                null
            }
            is SkillWriter.Result.Failed -> r.reason
        }
    }

    /** 删除技能；返回失败原因或 null */
    fun deleteSkill(id: String): String? {
        val dir = currentInstanceDir() ?: return "请先选中一个实例"
        return when (val r = SkillWriter.delete(dir, id)) {
            is SkillWriter.Result.Ok -> {
                // 清掉禁用名单里的残留，免得同名重建后被当成"已禁用"
                uiPrefs.setAiSkillEnabled(r.id, true)
                null
            }
            is SkillWriter.Result.Failed -> r.reason
        }
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
        _javaTask.value = JavaTaskState(running = true, version = version, message = t("准备安装 Java %s…", version))
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
                _javaTask.value = JavaTaskState(version = version, progress = 1f, message = t("Java %s 安装完成", version))
            } catch (e: InterruptedException) {
                _javaTask.value = JavaTaskState(version = version, message = t("已取消（已下载部分保留，可继续）"))
            } catch (e: Exception) {
                _javaTask.value = JavaTaskState(version = version, error = e.message ?: t("安装失败"))
            }
        }
    }

    /** 请求取消正在进行的 Java 任务（下载中止，断点保留） */
    fun cancelJavaTask() {
        if (_javaTask.value.running) {
            _javaTask.value = _javaTask.value.copy(cancelRequested = true, message = t("正在取消…"))
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
                t("实例「%s」正在使用 Java %s，请先停止它再卸载", inUse.name, version),
                android.widget.Toast.LENGTH_LONG,
            ).show()
            return
        }
        // 同步置位，否则连点会并发卸载同一个版本
        _javaTask.value = JavaTaskState(running = true, version = version, message = t("卸载 Java %s…", version))
        // 同上：换 appScope
        container.appScope.launch {
            try {
                container.javaManager.uninstall(version)
                refreshJava()
                _javaTask.value = JavaTaskState(message = t("Java %s 已卸载", version))
            } catch (e: Exception) {
                _javaTask.value = JavaTaskState(version = version, error = e.message ?: t("卸载失败"))
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
        _envTask.value = DownloadState(running = true, progress = 0f, message = t("准备部署…"))
        // 部署要跑几分钟（下载 rootfs + apt），退出界面不应中断
        container.appScope.launch {
            try {
                env.setup { progress, message ->
                    _envTask.value = DownloadState(running = true, progress = progress, message = message)
                }
                refreshJava()
                _envTask.value = DownloadState(done = true, message = t("环境部署完成"))
            } catch (e: Exception) {
                _envTask.value = DownloadState(error = e.message ?: t("部署失败"))
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
        } catch (e: Exception) { android.util.Log.w("KazeApp", t("请求忽略电池优化失败"), e) }
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
                    t("> 仍在停止中（多半还在保存世界）：已取消本次启动。要立即终止请再点一次「强制停止」"),
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

    // ── MCP 服务（外部 AI 控制接口，「设置 → MCP 服务」配置）──

    /** MCP 服务（暴露给设置页读绑定端口 / 错误状态） */
    val mcpServer get() = container.mcpServer

    /** 开关 MCP 服务（关 = 断开所有外部连接；开 = 按当前端口配置拉起） */
    fun setMcpEnabled(v: Boolean) {
        uiPrefs.setMcpEnabled(v)
        if (v) startMcp() else container.mcpServer.stop()
    }

    /** 端口/范围改动后重绑（设置页保存时调用；服务没开就只记配置） */
    fun rebindMcp() {
        if (uiPrefs.mcpEnabled.value) {
            container.mcpServer.stop()
            startMcp()
        }
    }

    private fun startMcp() {
        container.mcpServer.start(
            preferredPort = uiPrefs.mcpPort.value,
            rangeStart = uiPrefs.mcpPortRangeStart.value,
            rangeEnd = uiPrefs.mcpPortRangeEnd.value,
            bindAddress = uiPrefs.mcpBindAddress.value,
        )
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
            t("> 内存上限已改为 %s MB", mb) +
                if (running) t("（当前进程仍按旧值运行，重启后生效）") else t("（下次启动生效）"),
            LineType.System,
        )
    }

    /** 自定义 JVM 附加参数（空格分隔；启动时拼进 java 命令行） */
    fun setExtraJvmArgs(instance: ServerInstance, args: String) {
        instanceStore.setExtraJvmArgs(instance.id, args)
        val running = serverManager.states.value[instance.id] == ServerState.Running
        serverManager.consoleFor(instance.id).emit(
            t("> JVM 附加参数已更新") +
                if (running) t("（当前进程仍按旧参数运行，重启后生效）") else t("（下次启动生效）"),
            LineType.System,
        )
    }

    /**
     * 改实例的 Java 大版本。
     *
     * 说明生效时机（与内存/参数一致）：正在跑的服务端仍用旧 Java，重启才换。
     * 顺带提示"该版本 Java 是否已安装"由界面负责，这里只管落盘。
     */
    fun setInstanceJava(instance: ServerInstance, javaMajor: Int) {
        instanceStore.setJavaMajor(instance.id, javaMajor)
        val running = serverManager.states.value[instance.id] == ServerState.Running
        serverManager.consoleFor(instance.id).emit(
            t("> Java 版本已改为 Java %s", javaMajor) +
                if (running) t("（当前进程仍按旧版本运行，重启后生效）") else t("（下次启动生效）"),
            LineType.System,
        )
    }

    /** nogui 开关（启动参数，同上只落盘） */
    fun setNogui(instance: ServerInstance, nogui: Boolean) {
        instanceStore.setNogui(instance.id, nogui)
    }

    /** 实例置顶（服务端列表排最前；纯展示排序，不影响任何运行逻辑） */
    fun setInstancePinned(instance: ServerInstance, pinned: Boolean) {
        instanceStore.setPinned(instance.id, pinned)
    }

    /** 停服自动备份开关（生效时机 = 下一次服务端进程退出，见 BackupManager.autoBackup） */
    fun setInstanceAutoBackup(instance: ServerInstance, enabled: Boolean) {
        instanceStore.setAutoBackup(instance.id, enabled)
    }

    /**
     * 崩溃自动重启开关。
     *
     * 逻辑本来就在 DefaultServerManager.handleExit 里（连续次数、成功启动清零都有），
     * 但一直没有界面入口，用户根本开不了。这里只在控制台写一句生效说明。
     */
    fun setInstanceAutoRestart(instance: ServerInstance, enabled: Boolean) {
        instanceStore.setAutoRestart(instance.id, enabled)
        serverManager.consoleFor(instance.id).emit(
            if (enabled) {
                t("> 已开启崩溃自动重启（最多连续 %s 次，成功启动后计数清零）", instance.maxRestarts)
            } else {
                t("> 已关闭崩溃自动重启")
            },
            LineType.System,
        )
    }

    fun setInstanceMaxRestarts(instance: ServerInstance, value: Int) {
        instanceStore.setMaxRestarts(instance.id, value)
        serverManager.consoleFor(instance.id).emit(
            t("> 自动重启上限已改为 %s 次", value.coerceIn(1, 20)),
            LineType.System,
        )
    }

    // ── 模组体检：客户端专用模组 ──

    data class ModAuditState(
        val running: Boolean = false,
        val progress: Float = 0f,
        val message: String = "",
        val items: List<ModAudit.Item> = emptyList(),
        /** 勾选中的文件绝对路径 */
        val selected: Set<String> = emptySet(),
        val error: String? = null,
        val doneMessage: String? = null,
    )

    private val _modAudit = MutableStateFlow(ModAuditState())
    val modAudit: StateFlow<ModAuditState> = _modAudit.asStateFlow()

    /**
     * 检测 mods/ 里有没有"客户端专用"模组。
     *
     * 结果默认**全选**客户端专用的那些，用户仍可以逐项取消勾选 —— 这就是需求里的
     * "手动一键停用和选择停用"：一键指的是默认选择，不是不给选择。
     */
    fun auditMods(instance: ServerInstance) {
        if (_modAudit.value.running) return
        _modAudit.value = ModAuditState(running = true, message = t("扫描 mods/ …"))
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val items = ModAudit.audit(instance) { p, m ->
                    _modAudit.value = _modAudit.value.copy(progress = p, message = m)
                }
                _modAudit.value = ModAuditState(
                    running = false,
                    progress = 1f,
                    message = "",
                    items = items,
                    selected = items.filter { it.clientOnly && it.enabled }.map { it.file.absolutePath }.toSet(),
                    doneMessage = if (items.none { it.clientOnly }) {
                        t("没有发现客户端专用模组（共检查 %s 个）", items.size)
                    } else {
                        t("发现 %s 个客户端专用模组，已默认勾选，可逐项取消", items.count { it.clientOnly })
                    },
                )
            } catch (e: Exception) {
                _modAudit.value = ModAuditState(
                    error = t("检测失败：%s", e.message ?: e.javaClass.simpleName),
                )
            }
        }
    }

    fun toggleAuditSelection(path: String) {
        val current = _modAudit.value
        if (current.running) return
        val selected = current.selected.toMutableSet()
        if (!selected.add(path)) selected.remove(path)
        _modAudit.value = current.copy(selected = selected)
    }

    /** 停用勾选项（不删文件，随时能在列表里再启用） */
    fun disableAuditSelected(instance: ServerInstance) {
        val current = _modAudit.value
        if (current.running) return
        val targets = current.items.filter { it.file.absolutePath in current.selected && it.enabled }
        if (targets.isEmpty()) {
            _modAudit.value = current.copy(doneMessage = t("没有勾选可停用的模组"))
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val count = ModAudit.disable(targets)
            _modAudit.value = current.copy(
                selected = emptySet(),
                items = current.items.map { item ->
                    if (item.file.absolutePath in current.selected) item.copy(file = refreshedAuditFile(item.file)) else item
                },
                doneMessage = t("已停用 %s 个模组（重启服务端生效）", count),
            )
        }
    }

    /** 一键停用全部客户端专用模组 */
    fun disableAllAuditClientOnly(instance: ServerInstance) {
        val current = _modAudit.value
        val targets = current.items.filter { it.clientOnly && it.enabled }
        if (targets.isEmpty() || current.running) return
        viewModelScope.launch(Dispatchers.IO) {
            val count = ModAudit.disable(targets)
            _modAudit.value = current.copy(
                selected = emptySet(),
                items = current.items.map { item ->
                    if (item.clientOnly) item.copy(file = refreshedAuditFile(item.file)) else item
                },
                doneMessage = t("已停用 %s 个客户端专用模组（重启服务端生效）", count),
            )
        }
    }

    fun clearModAudit() {
        _modAudit.value = ModAuditState()
    }

    /** 停用后把条目指到磁盘上真实存在的那个文件（`x.jar` 或 `x.jar.disabled`） */
    private fun refreshedAuditFile(file: File): File {
        val base = file.absolutePath.removeSuffix(".disabled")
        val disabled = File("$base.disabled")
        val enabled = File(base)
        return when {
            disabled.isFile -> disabled
            enabled.isFile -> enabled
            else -> file
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

    /**
     * 待确认的**敏感文件读取**（P2-4）。读取不改磁盘，但内容会发到模型服务商那边，
     * 而诱导模型去读凭据文件的素材是现成的（控制台里的玩家聊天、联网搜索到的网页）。
     */
    @Serializable
    data class AiReadRequest(
        val path: String,
        /** 解析后的绝对路径（显示用；解析失败时为原始相对路径） */
        val absPath: String = "",
        /** 目标实例名（显示用） */
        val instanceName: String = "",
        /** 为什么判定为敏感（显示在卡片上，用户才知道自己在批准什么） */
        val reason: String = "",
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
        /** 待确认的敏感文件读取请求（P2-4；状态见 [readState]） */
        val readRequest: AiReadRequest? = null,
        /** 读取请求状态：0=待确认 1=已允许 2=已拒绝 */
        val readState: Int = 0,
        /**
         * 本条消息的工具执行步骤（Operit 式"执行过程可见"）。
         *
         * 改造前工具执行只有一行 `· 读取 xxx ·` 小字，**成功与失败长得一样** ——
         * 失败恰恰是最需要被看见的（否则用户会以为回答是基于数据给出的）。
         * 空列表 = 这条消息不是工具执行结果。
         */
        val steps: List<AiToolStep> = emptyList(),
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

    // ── 流式实况（思考/正文逐 token 更新；null = 没有进行中的流）──

    data class AiLiveStream(
        val id: Long,
        val thinking: Boolean,
        val reasoning: String = "",
        val content: String = "",
    )

    // ── AI 控制器：AI 的状态与逻辑都在 ui/ai/AiChatController（纯搬运，行为未改）──
    // 本类只留两样东西：
    //   1) 上面的嵌套数据类（调用点写 `AppViewModel.AiChatMessage` / `AppViewModel.AiLiveStream`，
    //      类型留在这里，界面代码就一个字都不用改）；
    //   2) 下面这组**同名薄转发**（类型与签名和搬运前完全一致）。

    private val ai = AiChatController(this)

    /** AI 对话消息（转发；见 [AiChatController.aiMessages]） */
    val aiMessages: StateFlow<List<AiChatMessage>> get() = ai.aiMessages

    /** AI 输入框草稿（转发；切页面 / 转屏不丢，逐键 800ms 防抖落盘） */
    val aiDraft: StateFlow<String> get() = ai.aiDraft

    /** 是否有本轮提问在进行（转发） */
    val aiBusy: StateFlow<Boolean> get() = ai.aiBusy

    /** 流式实况（思考/正文逐 token；null = 没有进行中的流）（转发） */
    val aiLiveStream: StateFlow<AiLiveStream?> get() = ai.aiLiveStream

    fun setAiDraft(text: String) = ai.setAiDraft(text)

    /** 取消当前 AI 提问（正在思考/工具循环中时有效） */
    fun cancelAiTurn() = ai.cancelAiTurn()

    /** 离开 AI 页面：软停止本轮工具循环（挂起中的写入确认卡片仍留在会话里） */
    fun onAiScreenLeft() = ai.onAiScreenLeft()

    /** 清空 AI 对话：忙时不允许；挂起中的写入会话一并作废 */
    fun clearAiChat() = ai.clearAiChat()

    /** 提问 → 重新采集现场快照 → 调 OpenAI 兼容接口 → 多轮工具循环 */
    fun askAi(question: String) = ai.askAi(question)

    /** 用户允许 AI 写入：执行写入，结果回喂并继续本轮 */
    fun approveAiWrite(messageId: Long) = ai.approveAiWrite(messageId)

    /** 用户拒绝写入：不落盘，把拒绝结果回喂让 AI 收尾 */
    fun denyAiWrite(messageId: Long) = ai.denyAiWrite(messageId)

    /** 用户允许读取敏感文件：真的读一次，结果回喂并继续本轮 */
    fun approveAiRead(messageId: Long) = ai.approveAiRead(messageId)

    /** 用户拒绝读取敏感文件：不读、不外发，把拒绝结果回喂让 AI 收尾 */
    fun denyAiRead(messageId: Long) = ai.denyAiRead(messageId)

    /** 执行 AI 建议的命令（与用户手输命令完全同一条路：再清洗一次 → 服务端 stdin） */
    fun executeAiSuggestion(messageId: Long) = ai.executeAiSuggestion(messageId)

    // ── 模型配置档案 + 联网搜索（存取在 SettingsPrefs，这里只做转发）──

    fun saveAiProfile(profile: AiProfile) = uiPrefs.saveAiProfile(profile)

    fun deleteAiProfile(id: String) = uiPrefs.deleteAiProfile(id)

    /** 功能分配：把「对话」指向某个档案 */
    fun setChatAiProfile(id: String) = uiPrefs.setChatAiProfile(id)


    /** 设置页的「清除损坏提示」入口（原文转存一并清掉，见 SettingsPrefs.aiProfilesCorrupt） */
    fun clearCorruptAiProfiles() = uiPrefs.clearCorruptAiProfiles()


    /** 思考强度开关（面板头部随时可切；DeepSeek = chat/reasoner 模型切换） */
    fun setAiThinking(v: Boolean) {
        uiPrefs.setAiThinking(v)
    }

    /** 控制台命令执行档位（仅建议 / 白名单自动 / 全部自动） */
    fun setAiCommandMode(modeId: String) = uiPrefs.setAiCommandMode(modeId)



    // ── 服务端进程的 CPU / 内存占用 ──
    private val _procStats = MutableStateFlow<ProcessStats.Reading?>(null)
    override val procStats: StateFlow<ProcessStats.Reading?> = _procStats.asStateFlow()

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
                    // 与实时发布（publishConsoleLocked）共用同一把锁：两处都是对 _consoleLines
                    // 的「读-改-写」，不加锁会互相覆盖、丢行。
                    synchronized(consoleLock) {
                        _consoleLines.value = prependOlderLines(
                            older = older,
                            current = _consoleLines.value,
                            max = CONSOLE_DISPLAY_MAX,
                        )
                    }
                }
                _olderCursor.value = chunk.cursor
                _hasMoreOlder.value = chunk.hasMore
            } catch (e: Exception) {
                android.util.Log.w("KazeSLauncher", t("回读更早日志失败"), e)
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
        synchronized(consoleLock) {
            _consoleLines.value = emptyList()
            // 待发布的行也要一起丢：否则定时器下一拍就把它们补回列表，
            // 用户看到"清空后日志自己又冒出来"，以为清空没生效。
            consolePending.clear()
        }
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
                // 必须显式判 null：`openOutputStream` 在 SAF 授权失效/目标卷卸载时返回
                // null，老写法 `?.use{}` 会静默跳过写入且不抛异常 —— 用户以为存了，
                // 打开一看是空文件或根本没有文件。LogsScreen 里已有同样的正确写法。
                val out = container.appContext.contentResolver.openOutputStream(uri)
                    ?: throw IllegalStateException(t("无法写入所选位置（授权可能已失效）"))
                out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            } catch (e: Exception) {
                android.util.Log.w("KazeApp", t("导出控制台日志失败"), e)
                // 静默失败会让用户以为"已经保存了"。导出是明确的一次用户操作，失败必须说出来
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        container.appContext,
                        t("保存失败：%s", e.message ?: e.javaClass.simpleName),
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    /** 复制当前实例控制台显示的完整内容到剪贴板（与屏幕所见一致，含实时流与清空后状态） */
    fun copyConsoleLog() {
        val text = _consoleLines.value.joinToString("\n") { it.text }
        val ctx = container.appContext
        if (text.isBlank()) {
            android.widget.Toast.makeText(ctx, t("暂无日志"), android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        viewModelScope.launch(Dispatchers.Main) {
            try {
                val cm = ctx.getSystemService(
                    android.content.Context.CLIPBOARD_SERVICE
                ) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText(t("Kaze 日志"), text))
                android.widget.Toast.makeText(ctx, t("已复制完整日志"), android.widget.Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                android.util.Log.w("KazeApp", t("复制日志到剪贴板失败"), e)
                // 本协程已在 Main 上，可直接弹提示：复制失败却提示"已复制"是明确的错误反馈
                android.widget.Toast.makeText(
                    ctx,
                    t("复制失败：%s", e.message ?: e.javaClass.simpleName),
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
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
                            t("实例仍在停止中（可能正在安装或部署），请稍后再试删除"),
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    }
                    return@launch
                }
            }
            instanceStore.remove(instance.id)
            // 同步服务与客户端包缓存跟着实例一起释放：端口不关会一直占着，
            // 缓存目录里的 zip 也不再有任何入口能再生成/命中
            runCatching {
                syncServers.remove(instance.id)?.stop()
                _modSyncStates.update { it - instance.id }
                ClientPack.invalidate(instance.id)
            }
            // 目录删不掉就**必须告诉用户**：InstanceStore.load() → recoverFromDirs()
            // 会扫到残留目录里的核心 jar，把这条实例"原地复活"（并且 id 变成目录名）。
            // 用户删了服务端、重启应用它又回来了，存档还在 —— 而 runCatching 把
            // IOException/SecurityException 全吞了，界面上一个字都没有。
            // SD 卡/FUSE 上这很常见：console-output.log 仍被进程句柄持有、只读挂载、
            // 权限变更，都会让 deleteRecursively 返回 false。
            val dirRemoved = runCatching { instance.dir.deleteRecursively() }.getOrDefault(false)
            if (!dirRemoved && instance.dir.exists()) {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        container.appContext,
                        t("实例已从列表移除，但目录未能删除（%s），重启后可能被重新识别", instance.dir.name),
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            }
            // AI 的每实例长期记忆笔记在应用私有目录（不在实例目录里），一并清理防孤儿文件
            runCatching { File(container.appContext.filesDir, "ai_memory_${instance.id}.md").delete() }
            // 备份目录在实例目录之外，删除实例后无任何入口再能访问——一并清理防死数据
            runCatching {
                com.kaze.newage.core.server.BackupManager.deleteAllBackups(
                    instance,
                    instanceStore.instances.value,
                )
            }
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

    // ── 核心更新（已建实例的核心 jar 升级）──

    /** 核心更新检查的状态 */
    sealed interface CoreUpdateState {
        data object Idle : CoreUpdateState
        data class Checking(val instanceId: String) : CoreUpdateState
        /** 有更新可用（含下载地址，界面可直接展示） */
        data class Available(val instanceId: String, val info: CoreUpdate.Result.Available) : CoreUpdateState
        data class UpToDate(val instanceId: String, val current: String) : CoreUpdateState
        data class Unsupported(val instanceId: String, val reason: String) : CoreUpdateState
        data class Failed(val instanceId: String, val reason: String) : CoreUpdateState
        data class Upgrading(val instanceId: String, val progress: Float, val message: String) : CoreUpdateState
        data class Upgraded(val instanceId: String, val note: String) : CoreUpdateState
    }

    private val _coreUpdate = MutableStateFlow<CoreUpdateState>(CoreUpdateState.Idle)
    val coreUpdate: StateFlow<CoreUpdateState> = _coreUpdate.asStateFlow()

    /** 升级中的连点守卫（与 importJar/downloadAndCreate 同理） */
    private var coreUpgradeRunning = false

    /**
     * 检查某个实例的核心有没有新版本。
     *
     * 判定分两条路（见 [CoreUpdate]）：
     *  - 有构建列表的核心（Paper / Fabric）→ 比 `coreBuild`；
     *  - 其余 → 比对下载地址（地址里带版本号，变了就是新包）。
     */
    fun checkCoreUpdate(instance: ServerInstance) {
        if (_coreUpdate.value is CoreUpdateState.Checking) return
        if (!CoreUpdate.supports(instance.coreType)) {
            _coreUpdate.value = CoreUpdateState.Unsupported(
                instance.id,
                t("这是手动导入的 jar，没有上游可以对比 —— 需要你自己下载新版覆盖"),
            )
            return
        }
        _coreUpdate.value = CoreUpdateState.Checking(instance.id)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val builds = CoreSources.fetchBuilds(instance.coreType, instance.mcVersion).getOrNull().orEmpty()
                if (builds.isNotEmpty()) {
                    // 有构建列表：精确比构建号
                    val newer = CoreUpdate.pickNewer(builds, instance.coreBuild)
                    if (newer == null) {
                        _coreUpdate.value = CoreUpdateState.UpToDate(
                            instance.id, instance.coreBuild.ifBlank { t("最新") },
                        )
                        return@launch
                    }
                    val dl = CoreSources.resolveDownload(instance.coreType, instance.mcVersion, newer.id)
                        .getOrThrow()
                    _coreUpdate.value = CoreUpdateState.Available(
                        instance.id,
                        CoreUpdate.Result.Available(
                            buildId = newer.id,
                            label = newer.name,
                            current = instance.coreBuild.ifBlank { t("未知（旧版本未记录）") },
                            downloadUrl = dl.url,
                            fileName = dl.fileName,
                            sha1 = dl.sha1,
                        ),
                    )
                } else {
                    // 没有构建列表：比对下载地址
                    val dl = CoreSources.resolveDownload(instance.coreType, instance.mcVersion)
                        .getOrThrow()
                    val r = CoreUpdate.compareByUrl(
                        currentUrl = instance.coreDownloadUrl,
                        latestUrl = dl.url,
                        label = dl.fileName,
                        fileName = dl.fileName,
                        sha1 = dl.sha1,
                    )
                    _coreUpdate.value = when (r) {
                        is CoreUpdate.Result.Available -> CoreUpdateState.Available(instance.id, r)
                        is CoreUpdate.Result.UpToDate -> CoreUpdateState.UpToDate(instance.id, r.current)
                        is CoreUpdate.Result.Unsupported -> CoreUpdateState.Unsupported(instance.id, r.reason)
                    }
                }
            } catch (e: Exception) {
                _coreUpdate.value = CoreUpdateState.Failed(
                    instance.id, e.message ?: e.javaClass.simpleName,
                )
            }
        }
    }

    /** 清掉检查结果（界面消费完调用，避免重进页面又看到旧结论） */
    fun clearCoreUpdateState() {
        _coreUpdate.value = CoreUpdateState.Idle
    }

    /**
     * 下载并替换核心 jar。
     *
     * ## 安全前提（每条都对应一种真实的数据丢失）
     * 1. **必须停服**：运行中的进程还占着 jar（Linux 上能删但进程仍跑旧代码，
     *    用户会以为升级成功、其实跑的是旧的）。
     * 2. **先把旧 jar 改名备份**，新的下完并校验通过才删备份 —— 下载中断时能被还原，
     *    绝不会出现"jar 没了、服也起不来"。
     * 3. **校验 SHA-1**（上游给了就必须对）：镜像返回 HTML 错误页时靠它拦下。
     * 4. **Forge/NeoForge 的 installer 例外**：那种 jar 要跑 `--installServer` 生成
     *    libraries，直接替换会把已装好的库变成"半装"状态（见 ServerInstance.forgeInstalled
     *    的注释）。所以这两类核心只做**提示**，不自动替换。
     */
    fun upgradeCore(instance: ServerInstance, info: CoreUpdate.Result.Available) {
        if (coreUpgradeRunning) return
        val state = serverManager.states.value[instance.id] ?: ServerState.Idle
        if (state == ServerState.Running || state.isBusy()) {
            _coreUpdate.value = CoreUpdateState.Failed(instance.id, t("请先停止服务端再升级核心"))
            return
        }
        if (instance.coreType == CoreType.FORGE || instance.coreType == CoreType.NEOFORGE) {
            _coreUpdate.value = CoreUpdateState.Failed(
                instance.id,
                t("Forge / NeoForge 的新版要重跑安装器才能生成依赖库，自动替换会让实例变成「半装」状态。") +
                    t("请新建一个同版本实例并把 world / config 搬过去。"),
            )
            return
        }
        coreUpgradeRunning = true
        _coreUpdate.value = CoreUpdateState.Upgrading(instance.id, 0f, t("准备下载…"))
        viewModelScope.launch(Dispatchers.IO) {
            val target = File(instance.dir, info.fileName)
            val part = File(instance.dir, info.fileName + ".part")
            val backup = File(instance.dir, info.fileName + ".old")
            var replaced = false
            try {
                runCatching { part.delete() }
                val used = Downloader.downloadFromSources(
                    listOf(info.downloadUrl),
                    part,
                    onProgress = { done, total ->
                        _coreUpdate.value = CoreUpdateState.Upgrading(
                            instance.id,
                            if (total > 0) done.toFloat() / total else -1f,
                            t("下载 %s…", info.fileName),
                        )
                    },
                    validate = { f ->
                        f.length() > 0 && Downloader.isZip(f) &&
                            (info.sha1 == null ||
                                Downloader.sha1Of(f)?.equals(info.sha1, ignoreCase = true) == true)
                    },
                ) ?: throw RuntimeException(t("下载失败（网络或源站问题）"))

                _coreUpdate.value = CoreUpdateState.Upgrading(instance.id, 1f, t("替换核心 jar…"))
                // 旧 jar 改名备份（同目录 rename 是原子的）
                val oldJar = instance.jarFile
                if (oldJar.exists()) {
                    runCatching { backup.delete() }
                    if (!oldJar.renameTo(backup)) {
                        throw RuntimeException(t("无法移开旧核心（可能被占用）：%s", oldJar.name))
                    }
                }
                if (!part.renameTo(target)) {
                    // 换入失败：立刻把旧 jar 放回去
                    runCatching { backup.renameTo(oldJar) }
                    throw RuntimeException(t("无法写入新核心（可用空间不足？）"))
                }
                replaced = true
                runCatching { backup.delete() }
                _coreUpdate.value = CoreUpdateState.Upgraded(
                    instance.id,
                    t("已升级到 %s（来源 %s）。建议先看一下控制台的启动日志确认能起来。", info.label, used),
                )
            } catch (e: Exception) {
                // 未成功换入 → 还原旧 jar，避免"jar 没了"
                if (!replaced) runCatching { if (backup.exists()) backup.renameTo(File(instance.dir, info.fileName)) }
                runCatching { part.delete() }
                _coreUpdate.value = CoreUpdateState.Failed(
                    instance.id, e.message ?: e.javaClass.simpleName,
                )
            } finally {
                coreUpgradeRunning = false
            }
        }
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
        _download.value = DownloadState(running = true, progress = 0f, message = t("解析下载地址…"))
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
                _download.value = DownloadState(running = true, progress = 0f, message = t("下载 %s", dl.fileName))
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
                        _download.value = DownloadState(message = t("已取消（已下载部分保留，可重试续传）"))
                        withContext(Dispatchers.Main) { onComplete(null) }
                        return@launch
                    }
                    throw RuntimeException(t("下载失败：所有源不可用（请检查网络后重试）"))
                }
                if (!part.renameTo(target)) {
                    part.copyTo(target, overwrite = true)
                    part.delete()
                }
                val instance = ServerInstance(
                    // 记下构建信息，供日后「检查核心更新」判断是否落后
                    coreBuild = buildId,
                    coreDownloadUrl = dl.url,
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
                _download.value = DownloadState(done = true, message = t("下载完成"))
                // 导航/UI 回调必须回主线程（否则 Compose 报 setCurrentState 或静默失败不跳转）
                withContext(Dispatchers.Main) { onComplete(instance) }
            } catch (e: Exception) {
                // 保留 dir 与 .part 半成品：重试时断点续传；目录扫描不会把 .part 误识别为实例
                _download.value = DownloadState(error = e.message ?: t("下载失败"))
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

    // ── 推荐列表（模组页顶部，PCL2 那种"没下载过也看得到图标"的列表）──

    /**
     * 推荐状态。
     *
     * [expanded] 是界面上的"展开/收起"，放在这里而不是 composition 里：
     * 用户切到别的页面再回来，展开状态不该被重置（列表一收一开很跳）。
     */
    data class AddonRecommendState(
        val loading: Boolean = false,
        val error: String? = null,
        val items: List<ModrinthProject> = emptyList(),
        val expanded: Boolean = true,
    )

    private val _addonRecommend = MutableStateFlow(AddonRecommendState())
    val addonRecommend: StateFlow<AddonRecommendState> = _addonRecommend.asStateFlow()

    /** 已经为哪个实例/类型加载过推荐，避免每次重组都打网络 */
    private var recommendKey: String? = null

    fun loadRecommendedAddons(instance: ServerInstance, kind: AddonKind, force: Boolean = false) {
        val key = "${instance.id}|${kind.name}"
        if (!force) {
            val current = _addonRecommend.value
            if (recommendKey == key && (current.items.isNotEmpty() || current.loading)) return
        }
        recommendKey = key
        _addonRecommend.value = _addonRecommend.value.copy(loading = true, error = null)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val contentKind = if (kind == AddonKind.MOD) ContentKind.MOD else ContentKind.PLUGIN
                val list = ContentCatalog.recommended(instance, contentKind)
                _addonRecommend.value = AddonRecommendState(
                    loading = false,
                    items = list,
                    error = if (list.isEmpty()) t("没有可推荐的条目（网络或区域限制？）") else null,
                    expanded = _addonRecommend.value.expanded,
                )
            } catch (e: Exception) {
                _addonRecommend.value = _addonRecommend.value.copy(
                    loading = false,
                    error = t("推荐列表加载失败：%s", e.message ?: e.javaClass.simpleName),
                )
            }
        }
    }

    fun setRecommendedExpanded(expanded: Boolean) {
        _addonRecommend.value = _addonRecommend.value.copy(expanded = expanded)
    }

    /**
     * 从推荐列表安装（与搜索安装共用同一个进度槽位 [addonInstall]，
     * 界面上的进度条/错误/完成提示一处就够）。
     */
    fun installRecommendedAddon(instance: ServerInstance, kind: AddonKind, project: ModrinthProject) {
        if (_addonInstall.value.running) return
        _addonInstall.value = DownloadState(running = true, message = t("安装 %s…", project.title))
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val contentKind = if (kind == AddonKind.MOD) ContentKind.MOD else ContentKind.PLUGIN
                val installed = ContentCatalog.install(instance, contentKind, project) { p, m ->
                    _addonInstall.value = DownloadState(running = true, progress = p, message = m)
                }
                _addonInstall.value = DownloadState(
                    done = true,
                    message = t("已安装 %s（重启服务端生效）", installed.file.name),
                )
            } catch (e: Exception) {
                _addonInstall.value = DownloadState(error = e.message ?: t("安装失败"))
            }
        }
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
                    _addonSearchError.value = t("搜索失败：%s", e.message ?: e.javaClass.simpleName)
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
        _addonInstall.value = DownloadState(running = true, message = t("检查 %s 的适配版本…", hit.title))
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val target = AddonManager.targetFile(instance, kind, hit.project_id, instance.mcVersion)
                val existing = AddonManager.existingCollision(instance, kind, target)
                if (existing != null) {
                    // running=false + replacePending=true：界面据此弹确认，取消时直接清掉
                    _addonInstall.value = DownloadState(
                        message = t("同名文件已存在：%s", existing.name),
                        replacePending = true,
                    )
                    return@launch
                }
            } catch (e: Exception) {
                // 解析失败（没有适配当前 MC 版本 / 文件名非法）：直接把原因说出来，
                // 继续走 install 只会拿到同一句错误、白白多一次网络请求
                _addonInstall.value = DownloadState(error = e.message ?: t("解析适配版本失败"))
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
        _addonInstall.value = DownloadState(running = true, message = t("解析 %s 版本…", hit.title))
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = AddonManager.install(instance, kind, hit.project_id, instance.mcVersion) { p, m ->
                    _addonInstall.value = DownloadState(running = true, progress = p, message = m)
                }
                _addonInstall.value = DownloadState(done = true, message = t("已安装 %s（重启服务端生效）", file.name))
            } catch (e: Exception) {
                _addonInstall.value = DownloadState(error = e.message ?: t("安装失败"))
            }
        }
    }

    fun clearAddonInstallState() {
        _addonInstall.value = DownloadState()
    }

    // ── 动作：本地导入（SAF 选文件）──

    /**
     * 本地导入的进度与结果。
     *
     * 与 [addonInstall] 分开两个槽位：一边在从 Modrinth 下载、一边在导入本地文件时，
     * 共用状态会让界面上的进度条互相覆盖（看起来像下载卡住）。
     */
    data class AddonImportState(
        val running: Boolean = false,
        val progress: Float = 0f,
        val message: String = "",
        val report: AddonImporter.Report? = null,
        /** > 0 表示有同名文件冲突，界面可以给「覆盖并导入」 */
        val conflictCount: Int = 0,
        val error: String? = null,
    )

    private val _addonImport = MutableStateFlow(AddonImportState())
    val addonImport: StateFlow<AddonImportState> = _addonImport.asStateFlow()

    /** 冲突重试要重新打开这些 URI（SAF 授权只在当前界面会话内有效） */
    private var pendingImportUris: List<Uri> = emptyList()
    private var pendingImportKind: AddonKind? = null
    private var pendingImportInstanceId: String? = null

    fun clearAddonImportState() {
        _addonImport.value = AddonImportState()
        pendingImportUris = emptyList()
        pendingImportKind = null
        pendingImportInstanceId = null
    }

    /**
     * 导入用户从文件管理器 / 浏览器选中的 jar（可多选）。
     *
     * 流程：
     *  1. 逐个打开 SAF 流交给 [AddonImporter]（它负责校验、去重、原子落盘、兼容性提示）；
     *  2. 有同名冲突时**不自动覆盖**，把来源留在 [pendingImportUris]，由界面确认后
     *     调 [confirmImportOverwrite] 用同一批文件重跑；
     *  3. 导入完成后清掉客户端包清单缓存 —— 否则玩家拿到的还是旧清单，
     *     同步脚本会认为"没有变化"。
     *
     * @param overwrite 允许覆盖同名文件（仅由确认框触发）
     */
    fun importAddonsFromUris(
        instance: ServerInstance,
        kind: AddonKind,
        uris: List<Uri>,
        overwrite: Boolean = false,
    ) {
        if (uris.isEmpty() || _addonImport.value.running) return
        if (!overwrite) {
            pendingImportUris = uris
            pendingImportKind = kind
            pendingImportInstanceId = instance.id
        }
        _addonImport.value = AddonImportState(
            running = true,
            message = t("准备导入 %s 个文件…", uris.size),
        )
        viewModelScope.launch(Dispatchers.IO) {
            var report = AddonImporter.Report()
            try {
                uris.forEachIndexed { index, uri ->
                    val name = displayNameOf(uri) ?: "import-${index + 1}.jar"
                    _addonImport.value = _addonImport.value.copy(
                        progress = index.toFloat() / uris.size,
                        message = t("导入 %s…", name),
                    )
                    val input = runCatching { appContext.contentResolver.openInputStream(uri) }.getOrNull()
                    if (input == null) {
                        report = report.merge(
                            AddonImporter.Report(
                                failed = listOf(
                                    AddonImporter.Skipped(name, "打不开这个文件（权限被收回或文件已被移动）"),
                                ),
                            ),
                        )
                        return@forEachIndexed
                    }
                    input.use { ins ->
                        report = report.merge(
                            AddonImporter.importFromStream(instance, kind, name, ins, overwrite) { _, _ -> },
                        )
                    }
                }
                ClientPack.invalidate(instance.id)
                _addonImport.value = AddonImportState(
                    running = false,
                    progress = 1f,
                    message = importSummary(report),
                    report = report,
                    conflictCount = if (report.hasConflict) uris.size else 0,
                )
            } catch (e: Exception) {
                _addonImport.value = AddonImportState(
                    error = t("导入失败：%s", e.message ?: e.javaClass.simpleName),
                )
            }
        }
    }

    /** 确认「覆盖同名文件」后，用同一批来源重跑导入 */
    fun confirmImportOverwrite() {
        val id = pendingImportInstanceId ?: return
        val kind = pendingImportKind ?: return
        val instance = instanceStore.get(id) ?: return
        val uris = pendingImportUris
        if (uris.isEmpty()) return
        importAddonsFromUris(instance, kind, uris, overwrite = true)
    }

    private fun importSummary(r: AddonImporter.Report): String = buildString {
        append(t("导入完成：成功 %s 个", r.okCount))
        if (r.skipped.isNotEmpty()) append(t("，跳过 %s 个", r.skipped.size))
        if (r.failed.isNotEmpty()) append(t("，失败 %s 个", r.failed.size))
    }

    /** SAF 的展示名（可能为空：有些来源只给流不给名字） */
    private fun displayNameOf(uri: Uri): String? = runCatching {
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()?.takeIf { it.isNotBlank() }

    // ── 动作：客户端模组同步（服务端把模组分发给玩家）──

    /** 每个实例一个 HTTP 同步服务（多开时互不影响） */
    private val syncServers = java.util.concurrent.ConcurrentHashMap<String, ModSyncServer>()

    private val _modSyncStates = MutableStateFlow<Map<String, ModSyncServer.Snapshot>>(emptyMap())
    val modSyncStates: StateFlow<Map<String, ModSyncServer.Snapshot>> = _modSyncStates.asStateFlow()

    /** 取（必要时创建）实例的同步服务 */
    fun modSyncServer(instance: ServerInstance): ModSyncServer = syncServers.getOrPut(instance.id) {
        val server = ModSyncServer(
            instance = instance,
            cacheDir = appContext.cacheDir,
            remote = object : ModSyncServer.RemoteBridge {
                override fun statusJson(): String {
                    val state = serverManager.states.value[instance.id] ?: ServerState.Idle
                    val uptime = serverManager.uptimeSec(instance.id).value
                    val mods = runCatching {
                        AddonManager.installed(instance, AddonKind.MOD).count { AddonManager.isEnabled(it) }
                    }.getOrDefault(0)
                    val name = instance.name.replace("\\", "\\\\").replace("\"", "\\\"")
                    return "{\"instance\":\"$name\",\"state\":\"${state.name}\",\"uptimeSec\":$uptime," +
                        "\"mods\":$mods,\"mcVersion\":\"${instance.mcVersion}\",\"bedrock\":${instance.bedrock}}"
                }

                override fun logs(maxLines: Int): String =
                    serverManager.consoleFor(instance.id).snapshot()
                        .takeLast(maxLines)
                        .joinToString("\n") { it.text }

                override fun command(cmd: String): String {
                    serverManager.sendCommand(instance, cmd)
                    return "ok: $cmd"
                }

                override fun start(): String {
                    viewModelScope.launch { serverManager.start(instance) }
                    return "starting"
                }

                override fun stop(): String {
                    viewModelScope.launch { serverManager.stop(instance) }
                    return "stopping"
                }
            },
        )
        viewModelScope.launch {
            server.state.collect { snap ->
                _modSyncStates.update { it + (instance.id to snap) }
            }
        }
        server
    }

    /**
     * 打开客户端同步。
     *
     * 端口与令牌来自设置（端口被占用时服务会顺延，实际端口以返回的状态为准）。
     * 启动前清一次清单缓存：用户刚导入/删掉模组时，第一个请求必须看到最新文件。
     */
    fun startModSync(instance: ServerInstance) {
        val server = modSyncServer(instance)
        ClientPack.invalidate(instance.id)
        viewModelScope.launch(Dispatchers.IO) {
            server.start(uiPrefs.modSyncPort.value, uiPrefs.modSyncToken.value)
        }
    }

    fun stopModSync(instance: ServerInstance) {
        syncServers[instance.id]?.stop()
    }

    // ── 动作：导出客户端整合包 ──

    data class PackExportState(
        val running: Boolean = false,
        val message: String = "",
        val done: Boolean = false,
        val error: String? = null,
    )

    private val _packExport = MutableStateFlow(PackExportState())
    val packExport: StateFlow<PackExportState> = _packExport.asStateFlow()

    fun clearPackExport() {
        _packExport.value = PackExportState()
    }

    /**
     * 把当前启用中的模组导出成客户端整合包（[mrpack] = true 时用 Modrinth 格式）。
     *
     * 输出直接写进用户通过 SAF 选的位置：手机上"先写到缓存再让用户去文件管理器找"
     * 是行不通的（Android/data 里的文件玩家看不到）。
     */
    fun exportClientPack(instance: ServerInstance, uri: Uri, mrpack: Boolean = false) {
        if (_packExport.value.running) return
        _packExport.value = PackExportState(running = true, message = t("准备打包…"))
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val out = appContext.contentResolver.openOutputStream(uri)
                    ?: throw RuntimeException(t("打不开所选位置"))
                out.use { stream ->
                    val progress: (Float, String) -> Unit = { p, m ->
                        _packExport.value = PackExportState(
                            running = true,
                            // 百分比直接拼，不走 t()：t() 只处理 %s/%d，`%%` 会原样吐出来
                            message = if (p > 0f) "$m（${(p * 100).toInt()}%）" else m,
                        )
                    }
                    if (mrpack) {
                        ClientPack.writeMrpack(instance, stream, progress)
                    } else {
                        ClientPack.writeZip(instance, stream, includeConfig = false, onProgress = progress)
                    }
                }
                _packExport.value = PackExportState(done = true, message = t("已导出客户端整合包"))
            } catch (e: Exception) {
                _packExport.value = PackExportState(
                    error = t("导出失败：%s", e.message ?: e.javaClass.simpleName),
                )
            }
        }
    }

    // ── 动作：离线控制台（服务端未运行时也能用）──

    /**
     * 执行一行控制台输入。
     *
     * 运行中 → 照旧写进服务端 stdin；未运行 → 交给 [OfflineConsole] 直接操作实例文件。
     * 这样控制台输入框在**任何状态**下都有意义，而不是停止时整块禁用
     *（用户想改 server.properties / 加白名单 / 停用模组，本来就不该被迫先启动服务端）。
     */
    fun runOfflineCommand(input: String) {
        val id = _currentInstanceId.value ?: return
        val instance = instanceStore.get(id) ?: return
        val text = input.trim()
        if (text.isEmpty()) return
        if (text.equals("clear", ignoreCase = true) || text.equals("cls", true)) {
            clearConsole()
            return
        }
        if (serverManager.states.value[id] == ServerState.Running) {
            sendCommand(text)
            return
        }
        val stream = serverManager.consoleFor(id)
        stream.emit("> $text", LineType.Command)
        viewModelScope.launch(Dispatchers.IO) {
            val shell: (suspend (String) -> Result<String>)? = if (env.isReady) {
                { cmd ->
                    val sb = StringBuilder()
                    val exit = withTimeoutOrNull(120_000) {
                        env.execute(
                            command = listOf("/usr/bin/sh", "-c", cmd),
                            workDir = instance.dir,
                            env = emptyMap(),
                            onLine = { line -> if (sb.length < 200_000) sb.appendLine(line) },
                        )
                    }
                    val body = sb.toString()
                    when {
                        exit == null -> Result.failure(RuntimeException("命令超时（120 秒）"))
                        exit == 0 -> Result.success(body)
                        else -> Result.success(body.trimEnd() + "\n（退出码 $exit）")
                    }
                }
            } else {
                null
            }
            val lines = runCatching { OfflineConsole.run(instance, text, shell) }
                .getOrElse {
                    listOf(
                        OfflineConsole.Line(
                            t("离线命令执行失败：%s", it.message ?: "未知错误"),
                            LineType.Error,
                        ),
                    )
                }
            lines.forEach { stream.emit(it.text, it.type) }
        }
    }

    // ── 插件 / 模组更新（已装附加组件的升级）──

    /** 附加组件更新检查的状态 */
    sealed interface AddonUpdateState {
        data object Idle : AddonUpdateState
        data class Checking(val done: Int, val total: Int, val current: String) : AddonUpdateState
        /** 检查完成；summary 里有可更新列表 / 已是最新 / 认不出来源 / 上游无兼容版本 */
        data class Done(
            val instanceId: String,
            val kind: AddonKind,
            val summary: AddonUpdate.Summary,
        ) : AddonUpdateState
        data class Failed(val reason: String) : AddonUpdateState
        data class Upgrading(val done: Int, val total: Int, val current: String) : AddonUpdateState
        data class Upgraded(val okCount: Int, val failed: List<String>) : AddonUpdateState
    }

    private val _addonUpdate = MutableStateFlow<AddonUpdateState>(AddonUpdateState.Idle)
    val addonUpdate: StateFlow<AddonUpdateState> = _addonUpdate.asStateFlow()

    /** 升级中的连点守卫 */
    private var addonUpgradeRunning = false

    fun clearAddonUpdateState() {
        _addonUpdate.value = AddonUpdateState.Idle
    }

    /**
     * 检查某个实例的插件 / 模组有没有新版本。
     *
     * 识别来源有两条路（见 [AddonRegistry]）：台账里有的直接读；台账里没有的**算 SHA-512
     * 去 Modrinth 回查** —— 于是老用户不重装也能被纳入管理。回查失败的文件如实报
     * "认不出来源"，而不是默默跳过（跳过会让用户以为全都检查过了）。
     *
     * 每个文件的网络请求是串行的：并发几十个请求会被 Modrinth 限流（429），
     * 反而更慢，而且进度条也没法给出有意义的位置。
     */
    fun checkAddonUpdate(instance: ServerInstance, kind: AddonKind) {
        if (_addonUpdate.value is AddonUpdateState.Checking) return
        if (!AddonManager.supports(instance, kind)) {
            _addonUpdate.value = AddonUpdateState.Failed(
                // 必须走 t()：原来这里是裸字符串模板，英文用户看到整句中文；
                // 而「插件 / 模组」也是**拼进去的词**，得各自可翻译 —— 所以拆成两个 %s。
                t("%s 不支持%s", instance.coreType.displayName, if (kind == AddonKind.PLUGIN) t("插件") else t("模组")),
            )
            return
        }
        // 读取失败必须与"确实一个都没装"区分开：老写法和空目录走同一条分支，
        // 于是目录不可读（FAT 损坏、权限丢失）时 UI 显示 Done（全部最新），
        // 用户点"检查更新"得到"没有可更新的" —— 其实一个都没检查。
        val files = runCatching { AddonManager.installed(instance, kind) }.getOrElse { err ->
            _addonUpdate.value = AddonUpdateState.Failed(
                t("读取插件目录失败：%s", err.message ?: err.javaClass.simpleName),
            )
            return
        }
        if (files.isEmpty()) {
            _addonUpdate.value = AddonUpdateState.Done(
                instance.id, kind, AddonUpdate.summarize(emptyList()),
            )
            return
        }
        _addonUpdate.value = AddonUpdateState.Checking(0, files.size, "")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 先清掉磁盘上已不存在的台账记录，免得把删掉的插件报出来
                val registry = AddonRegistry.prune(instance, kind).associateBy { it.fileName }
                val loader = AddonManager.loaderFor(instance, kind)
                val verdicts = mutableListOf<AddonUpdate.Verdict>()
                files.forEachIndexed { idx, f ->
                    _addonUpdate.value = AddonUpdateState.Checking(idx, files.size, f.name)
                    // 台账 → 哈希回查 → 认不出来
                    val entry = registry[f.name] ?: runCatching {
                        AddonRegistry.resolveByHash(instance, kind, f)
                    }.getOrNull()
                    if (entry == null) {
                        verdicts += AddonUpdate.Verdict.Unknown(f.name, t("认不出来源（不是从 Modrinth 装的，或被改过）"))
                        return@forEachIndexed
                    }
                    val versions = runCatching {
                        ModrinthApi.versions(entry.projectId, loader, instance.mcVersion.ifBlank { null })
                    }.getOrElse {
                        // 单个项目查询失败不该让整次检查崩掉：如实记成"查不到"，
                        // 用户至少知道这一个没检查成
                        verdicts += AddonUpdate.Verdict.Unknown(f.name, "查询失败：${it.message ?: "网络问题"}")
                        return@forEachIndexed
                    }
                    verdicts += AddonUpdate.judge(f.name, entry, versions)
                }
                _addonUpdate.value = AddonUpdateState.Done(
                    instance.id, kind, AddonUpdate.summarize(verdicts),
                )
            } catch (e: Exception) {
                _addonUpdate.value = AddonUpdateState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /**
     * 把检查出的更新逐个装上。
     *
     * ## 为什么逐个装而不是并发
     * 每个都要写同一个目录、还可能撞同名文件（用户装了两个版本）；串行能保证
     * "一个失败不影响后面"，也让进度条有意义。
     *
     * ## 失败不中断
     * 某个插件下载失败（上游删了文件 / 网络抖动）时记下名字继续下一个，
     * 最后一起汇报 —— 否则用户点了"全部更新"只成功一半还不知道哪几个没成。
     */
    fun upgradeAddons(
        instance: ServerInstance,
        kind: AddonKind,
        updates: List<AddonUpdate.Verdict.Update>,
    ) {
        if (addonUpgradeRunning || updates.isEmpty()) return
        addonUpgradeRunning = true
        _addonUpdate.value = AddonUpdateState.Upgrading(0, updates.size, "")
        viewModelScope.launch(Dispatchers.IO) {
            val failed = mutableListOf<String>()
            var ok = 0
            try {
                updates.forEachIndexed { idx, u ->
                    _addonUpdate.value = AddonUpdateState.Upgrading(idx, updates.size, u.fileName)
                    val r = runCatching {
                        AddonManager.installVersion(instance, kind, u.projectId, u.next) { _, _ -> }
                    }
                    if (r.isSuccess) {
                        ok++
                        // 旧文件可能换了名字（上游改过 filename）：删掉旧的，避免同时加载两份
                        val old = File(AddonManager.addonDir(instance, kind), u.fileName)
                        val newFile = r.getOrNull()
                        if (newFile != null && old.exists() && old.name != newFile.name) {
                            runCatching { old.delete() }
                            AddonRegistry.forget(instance, kind, u.fileName)
                        }
                    } else {
                        failed += "${u.fileName}：${r.exceptionOrNull()?.message ?: "下载失败"}"
                    }
                }
                _addonUpdate.value = AddonUpdateState.Upgraded(ok, failed)
            } finally {
                addonUpgradeRunning = false
            }
        }
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
                val info = withContext(Dispatchers.IO) {
                    UpdateChecker.check(container.uiPrefs.updateChannel.value, currentVersionName(), installedAtMs())
                }
                // check() 内部已按发布日期判定：返回非 null 就是「比当前运行的 Release 新」
                if (info != null) {
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
                val info = withContext(Dispatchers.IO) {
                    UpdateChecker.check(container.uiPrefs.updateChannel.value, currentVersionName(), installedAtMs())
                }
                _appUpdate.value = if (info == null) AppUpdateState.Latest else AppUpdateState.Found(info)
            } catch (e: Exception) {
                _appUpdate.value = AppUpdateState.Error(e.message ?: t("检查失败（网络不可达？）"))
            }
        }
    }

    /**
     * 当前安装包的安装时间（epoch 毫秒）。
     *
     * 更新判定改用发布日期后的兜底基线：当前 versionName 匹配不到任何 Release
     * （本地构建 / tag 改名）时，用「什么时候装的」代替「装的是哪个」——
     * 发布日期晚于安装时间的 Release 才提示，本地构建不会被旧 Release 骚扰。
     */
    fun installedAtMs(): Long = runCatching {
        container.appContext.packageManager
            .getPackageInfo(container.appContext.packageName, 0).lastUpdateTime
    }.getOrDefault(0L)

    /** 下载并安装更新。跑在 appScope 上：离开设置页 / 转屏都不会中断 */
    fun downloadAndInstallUpdate(info: UpdateChecker.ReleaseInfo) {
        if (_appUpdate.value is AppUpdateState.Downloading) return
        updateCancelRequested = false
        _appUpdate.value = AppUpdateState.Downloading(info, 0f, t("准备下载…"))
        container.appScope.launch {
            val file = UpdateInstaller.download(
                context = container.appContext,
                info = info,
                onProgress = { done, total, p ->
                    val msg = t("下载中 %s MB", done) + (if (total > 0) " / $total MB" else "")
                    _appUpdate.value = AppUpdateState.Downloading(info, p, msg)
                },
                shouldCancel = { updateCancelRequested },
                allowPatch = container.uiPrefs.updateMode.value == "patch",
            )
            if (file == null) {
                _appUpdate.value = if (updateCancelRequested) AppUpdateState.Idle
                else AppUpdateState.Error(t("下载失败：所有线路不可用，请稍后重试"))
                return@launch
            }
            val installed = withContext(Dispatchers.Main) {
                UpdateInstaller.install(container.appContext, file)
            }
            _appUpdate.value = if (installed) AppUpdateState.Idle
            else AppUpdateState.Error(t("无法打开安装器，请到设置中开启「安装未知应用」权限"))
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

    /** 导入 jar 的进度（界面显示用；Idle = 没有进行中的导入） */
    sealed interface ImportJarProgress {
        data object Idle : ImportJarProgress
        /** [fraction] < 0 表示总量未知（SAF 流常见），界面只显示已复制大小 */
        data class Running(val fraction: Float, val detail: String) : ImportJarProgress
        data class Done(val instanceId: String, val name: String) : ImportJarProgress
        data class Failed(val reason: String) : ImportJarProgress
    }

    private val _importJarProgress = MutableStateFlow<ImportJarProgress>(ImportJarProgress.Idle)
    val importJarProgress: StateFlow<ImportJarProgress> = _importJarProgress.asStateFlow()

    /** 导入 jar 的连点守卫（见 [importJar]） */
    @Volatile
    private var importJarRunning = false

    /**
     * 导入 jar：把用户选中的文件复制进一个新实例目录并登记实例。
     *
     * ## 这个流程里最要紧的四件事（都是"做错了用户很难自己发现"的）
     * 1. **复制前先体检**：选错文件（插件 jar、世界 zip、随便一个文档）如果等到复制完
     *    几十 MB 之后再报错，用户只会觉得"导入坏了"。这里先用 [JarInspector.quickCheck]
     *    读 zip 目录（不读全包，很快）判断它到底像不像服务端，不像就直接拒绝并说明原因。
     * 2. **自动填好名字与版本**：原来一律叫「导入-MMdd-HHmmss」、版本空着、核心一律 CUSTOM
     *    —— 实例列表里根本认不出是哪个服。现在用识别结果拼「Paper 1.21.4」这样的名字
     *    （重名自动加序号），MC 版本与核心类型一并写进实例。
     * 3. **进度要看得见**：几十 MB 的 copyTo 期间界面必须显示进度 [importJarProgress]，
     *    否则用户会以为卡死而强退（强退会留下半截目录）。
     * 4. **失败说清原因**：Toast 只给一行，够用但不该是「未知错误」——异常信息里通常
     *    已有可读原因（空间不足、SAF 授权失效、文件被删），保留它而不是吞掉。
     *
     * 复制必须在 IO 线程做：几十 MB 的 jar 在主线程 copy 会 ANR。
     * Toast 一律回主线程发：`Toast.show()` 需要当前线程有 Looper，
     * 在 Dispatchers.IO 上直接调用会抛 "Can't create handler inside thread that has not
     * called Looper.prepare()" —— 成功/失败两条路径都会走到 Toast，等于每次导入都崩。
     * 连点守卫与 [downloadAndCreate] 同理：复制期间按钮仍是可点的（enabled 要等重组）。
     */
    fun importJar(uri: android.net.Uri, name: String, memoryMb: Int) {
        if (importJarRunning) return
        importJarRunning = true
        _importJarProgress.value = ImportJarProgress.Running(0f, t("准备导入…"))
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = container.appContext
            var dir: File? = null
            // 同 importModpack：登记之后就不能再删这个目录了（见那里的说明）
            var registered = false
            try {
                // ── ① 先落到临时文件，体检通过才建实例目录 ──
                // 直接往实例目录写的话，被拒绝的文件也会留下一个半成品目录
                val tmpJar = File(ctx.cacheDir, "import-probe-${System.currentTimeMillis()}.jar")
                _importJarProgress.value = ImportJarProgress.Running(0f, t("读取所选文件…"))
                copyWithProgress(ctx, uri, tmpJar) { done, total ->
                    _importJarProgress.value = ImportJarProgress.Running(
                        if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f,
                        t("复制中… %s MB", done / 1024 / 1024) +
                            if (total > 0) " / ${total / 1024 / 1024} MB" else "",
                    )
                }
                if (!tmpJar.isFile || tmpJar.length() == 0L) {
                    throw RuntimeException(t("所选文件为空，或没有被真正读取（授权可能已失效）"))
                }

                _importJarProgress.value = ImportJarProgress.Running(1f, t("检查文件…"))
                val info = JarInspector.quickCheck(tmpJar)
                if (!info.looksLikeServerJar) {
                    // 体检不通过：明确告诉用户"为什么不是"，而不是让他导入之后再撞墙
                    throw RuntimeException(
                        t("选择的文件看起来不是 Minecraft 服务端（%s）。", info.evidence) +
                            t("请选 server.jar 本身：Paper/Purpur 选 paper-x.jar，Forge 选安装器生成的 ") +
                            t("forge-*-universal.jar 或 run.jar。"),
                    )
                }

                // ── ② 名字与版本：尽量取有意义的信息 ──
                val finalName = importNameOf(name, info, instanceStore.instances.value.map { it.name }.toSet())
                dir = instanceStore.createInstanceDir(finalName)
                val target = File(dir, "server.jar")
                tmpJar.renameTo(target)
                if (!target.isFile) {
                    // rename 失败（跨分区/被占用）就退回复制
                    tmpJar.copyTo(target, overwrite = true)
                    tmpJar.delete()
                }
                if (!target.isFile || target.length() == 0L) {
                    throw RuntimeException(t("把文件放进实例目录失败（可用空间不足？）"))
                }

                val coreType = runCatching { CoreType.valueOf(info.coreTypeName) }
                    .getOrDefault(CoreType.CUSTOM)
                val instance = ServerInstance(
                    name = finalName,
                    coreType = coreType,
                    // 优先用识别到的 MC 版本；识别不到就留空（界面显示"未知"），
                    // 绝不编一个 —— 版本填错会让插件过滤与备份命名全错
                    mcVersion = info.mcVersion,
                    javaMajor = info.javaMajor,
                    memoryMb = memoryMb,
                    dir = dir,
                )
                instanceStore.add(instance)
                registered = true
                ServerProperties.ensureInitial(instance, instanceStore.instances.value)
                _currentInstanceId.value = instance.id
                _importJarProgress.value = ImportJarProgress.Done(instance.id, instance.name)
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        ctx,
                        t("已导入：%s", instance.name) +
                            if (info.mcVersion.isNotEmpty()) "（MC ${info.mcVersion}）" else "",
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
            } catch (e: Exception) {
                // 只清理**本次新建的半成品目录**；用户的源文件是 SAF 只读流，不会被改动。
                // 已经登记过的实例绝不能删目录（那是用户的数据）。
                if (!registered) runCatching { dir?.deleteRecursively() }
                val reason = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                _importJarProgress.value = ImportJarProgress.Failed(reason)
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(ctx, t("导入失败：%s", reason), android.widget.Toast.LENGTH_LONG).show()
                }
            } finally {
                importJarRunning = false
                // 临时文件无论成败都清掉（体检用的副本没有保留价值）
                runCatching {
                    ctx.cacheDir.listFiles()
                        ?.filter { it.name.startsWith("import-probe-") && it.name.endsWith(".jar") }
                        ?.forEach { it.delete() }
                }
            }
        }
    }

    /**
     * 导入整合包 zip：解压 → 摊平目录 → 识别启动方式 → 登记实例（issue #15）。
     *
     * ## 为什么不能只"解压完就完事"
     * 整合包有三个坑，任意一个没处理好，用户得到的都是一个**启动不起来**的实例：
     *  1. **多一层壳 或 overrides 目录**：`server.jar` 不在实例根目录 → 启动器找不到它；
     *  2. **Forge 库可能不全**：这种包装完直接启动会报 `Missing required libraries`，
     *     而且一旦被误标成"已安装"就永远跳过安装（见 ServerInstance.forgeInstalled 注释）；
     *  3. **只有 mods、没有核心**：必须明确告诉用户去补核心，不能假装导入成功。
     *
     * 这些都交给 [ModpackImporter] 判断（纯逻辑，可单测），这里只负责：
     * IO 线程解压 + 进度上报 + 落库 + Toast。
     *
     * ## 与 importJar 的关系
     * 共用同一套进度状态 [ImportJarProgress]（界面上是同一块进度条），也共用连点守卫
     * —— 两者都是"往新实例目录里写东西"，并发跑会争用同一块磁盘与同一个实例列表。
     */
    fun importModpack(uri: android.net.Uri, name: String) {
        if (importJarRunning) return
        importJarRunning = true
        _importJarProgress.value = ImportJarProgress.Running(0f, t("准备导入整合包…"))
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = container.appContext
            var dir: File? = null
            // 实例一旦登记进列表，就**不能再删它的目录** —— 删了会留下"列表里有这个实例、
            // 目录却没了"的空壳（用户点进去处处报错，而数据已经没了）。
            // 这个标记用来区分"本次新建的半成品目录（该清）"与"已登记的实例目录（不能动）"。
            var registered = false
            try {
                // ① 解压到临时目录（不直接往实例目录解，失败时不留半成品）
                val tmpDir = File(ctx.cacheDir, "modpack-${System.currentTimeMillis()}").apply { mkdirs() }
                try {
                    val input = ctx.contentResolver.openInputStream(uri)
                        ?: throw RuntimeException(t("无法打开所选文件（授权可能已失效，请重新选择）"))
                    _importJarProgress.value = ImportJarProgress.Running(0f, t("解压中…"))
                    val result = input.use {
                        // 必须用具名参数：extract 的最后一个参数是 shouldCancel，
                        // 尾随 lambda 会被绑到它上面（报 "actual type is Function1<..., Boolean>"）
                        ModpackImporter.extract(
                            input = it,
                            dest = tmpDir,
                            onProgress = { done ->
                                _importJarProgress.value = ImportJarProgress.Running(
                                    // 压缩包总大小未知（SAF 常常给不出），只报已解压字节
                                    -1f,
                                    t("解压中… %s MB", done / 1024 / 1024),
                                )
                            },
                        )
                    }
                    if (result.entryCount == 0) {
                        throw RuntimeException(t("压缩包里没有任何文件"))
                    }

                    // ② 摊平 + 识别
                    _importJarProgress.value = ImportJarProgress.Running(1f, t("整理目录…"))
                    val flattened = ModpackImporter.flatten(tmpDir)
                    // var：装完加载器/下载完模组后要**重新识别一次**（目录形态变了）
                    var plan = ModpackImporter.inspect(tmpDir)

                    // ── 判断这个包属于哪种格式、以及要不要联网 ──
                    //  1. 通用懒人包：文件已实体在包里，什么都不用下
                    //  2. Modrinth .mrpack：只有引用清单，要按 URL 下模组；加载器也要装
                    //  3. CurseForge：只有平台 ID，免密钥拿不到下载地址（见下）
                    val manifest = plan.format
                    val loaderInstallable = plan.mcVersion.isNotEmpty() && when (plan.loader) {
                        ModpackManifest.Loader.FORGE,
                        ModpackManifest.Loader.NEOFORGE,
                        ModpackManifest.Loader.FABRIC,
                        ModpackManifest.Loader.VANILLA -> true
                        else -> false
                    }
                    // 包里已经有能跑的东西（可直接 -jar 的 jar，或装好的 Forge）→ 不必再装加载器
                    val alreadyRunnable = plan.launch == ModpackImporter.Launch.JAR ||
                        (plan.launch == ModpackImporter.Launch.FORGE_ARGS && plan.missingForgeLibs.isEmpty())
                    val needsLoaderInstall = loaderInstallable && !alreadyRunnable

                    // CurseForge 的"只带 ID"包：如实拒绝，不假装导入成功。
                    // 实测 api.curseforge.com 无 key 返回 403，网站那个 download 接口被
                    // Cloudflare 拦成 403 —— 不是"懒得做"，是真的拿不到地址。
                    val curse = manifest as? ModpackManifest.Format.Curse
                    if (curse != null && curse.manifest.refs.isNotEmpty() && !hasPhysicalMods(tmpDir)) {
                        throw RuntimeException(
                            t("这是 CurseForge 格式的整合包：清单只记录了 %s 个模组的", curse.manifest.refs.size) +
                                t("平台 ID，包里不含模组文件，而 CurseForge 的下载接口需要官方 API Key") +
                                "（本应用没有），所以这类包无法自动安装。\n" +
                                t("可行的做法：① 改用它的「服务端包」（Server Pack，模组是实体文件，可直接导入）；") +
                                t("② 用 CurseForge App 装好后，把 mods / config 等打包成 zip 再导入。"),
                        )
                    }
                    if (plan.launch == ModpackImporter.Launch.MODS_ONLY && !loaderInstallable) {
                        // 只有 mods，清单也没说要装哪个加载器：不建立实例 ——
                        // 否则用户会得到一个必然启动失败的实例，还以为是自己操作错了。
                        throw RuntimeException(plan.warnings.firstOrNull() ?: t("整合包里没有可用的服务端核心"))
                    }

                    // ③ 搬到实例目录
                    val existing = instanceStore.instances.value.map { it.name }.toSet()
                    val finalName = uniqueName(modpackNameOf(name, plan), existing)
                    dir = instanceStore.createInstanceDir(finalName)
                    _importJarProgress.value = ImportJarProgress.Running(1f, t("写入实例目录…"))
                    if (!tmpDir.renameTo(dir)) {
                        // 跨分区（cacheDir 与实例目录可能不同卷）→ 退回逐文件搬
                        moveDirChecked(tmpDir, dir)
                    }

                    // ④ 联网步骤（通用懒人包这里什么都不做）
                    val netNotes = mutableListOf<String>()
                    if (needsLoaderInstall) {
                        installLoaderFor(dir, plan) { msg ->
                            _importJarProgress.value = ImportJarProgress.Running(-1f, msg)
                        }?.let { netNotes += it }
                    }
                    if (manifest is ModpackManifest.Format.Mrpack) {
                        downloadManifestFiles(dir, manifest.manifest, netNotes) { done, total, msg ->
                            _importJarProgress.value = ImportJarProgress.Running(
                                if (total > 0) done.toFloat() / total else -1f, msg,
                            )
                        }
                    }
                    // 重新识别：加载器/模组装上之后，目录里的启动入口与库完整性都变了。
                    // 后面的 Forge 安装标记判断必须基于**新**状态，用旧结果会写错标记。
                    if (needsLoaderInstall || manifest !is ModpackManifest.Format.Plain) {
                        plan = ModpackImporter.inspect(dir)
                    }

                    // ⑤ 登记。Forge 系：库齐全才写安装标记，否则留给启动时补
                    val instance = ServerInstance(
                        name = finalName,
                        coreType = runCatching { CoreType.valueOf(plan.coreTypeName) }
                            .getOrDefault(CoreType.CUSTOM),
                        mcVersion = plan.mcVersion,
                        javaMajor = plan.javaMajor,
                        memoryMb = suggestedModpackMemoryMb(),   // 见该函数：整合包给得多，但不能超过设备可用内存
                        dir = dir,
                    )
                    if (plan.launch == ModpackImporter.Launch.FORGE_ARGS &&
                        plan.missingForgeLibs.isEmpty()
                    ) {
                        // 库是齐的：直接认作"已安装"，首次启动不必再跑一遍安装器
                        runCatching { instance.forgeMarker.writeText(instance.javaMajor.toString()) }
                    }
                    instanceStore.add(instance)
                    registered = true
                    ServerProperties.ensureInitial(instance, instanceStore.instances.value)
                    _currentInstanceId.value = instance.id

                    // ⑤ 说明性日志：让用户在控制台就能看懂这个包被抓成了什么
                    val console = serverManager.consoleFor(instance.id)
                    console.emit(
                        "> 已导入整合包（${result.entryCount} 个条目，" +
                            "${result.totalBytes / 1024 / 1024} MB" +
                            if (flattened) t("，已摊平目录") else "" + "）",
                        LineType.System,
                    )
                    if (plan.evidence.isNotEmpty()) {
                        console.emit("> 识别依据：${plan.evidence}", LineType.System)
                    }
                    when (plan.launch) {
                        ModpackImporter.Launch.FORGE_ARGS -> console.emit(
                            t("> 启动方式：Forge/NeoForge（@unix_args.txt）") +
                                if (plan.missingForgeLibs.isEmpty()) {
                                    t("；依赖库齐全")
                                } else {
                                    "；依赖库缺 ${plan.missingForgeLibs.size} 个，首次启动会自动补齐"
                                },
                            LineType.System,
                        )
                        ModpackImporter.Launch.JAR -> console.emit(
                            "> 启动方式：java -jar ${plan.launchJar?.name ?: "server.jar"}",
                            LineType.System,
                        )
                        else -> Unit
                    }
                    plan.warnings.forEach { console.emit("> 注意：$it", LineType.Warn) }
                    netNotes.forEach { console.emit("> $it", LineType.System) }
                    if (result.skipped.isNotEmpty()) {
                        console.emit(
                            "> 有 ${result.skipped.size} 个条目被跳过（路径非法或超限），" +
                                t("例如：%s", result.skipped.first()),
                            LineType.Warn,
                        )
                    }

                    _importJarProgress.value = ImportJarProgress.Done(instance.id, instance.name)
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(
                            ctx,
                            t("已导入整合包：%s", instance.name) +
                                if (plan.warnings.isNotEmpty()) t("（有 %s 条提示，见控制台）", plan.warnings.size) else "",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                } finally {
                    runCatching { tmpDir.deleteRecursively() }
                }
            } catch (e: Exception) {
                // 只清理**本次新建的半成品目录**。实例一旦登记进列表就不能再删它的目录 ——
                // 删了会留下"列表里有这个实例、目录却没了"的空壳（用户点进去处处报错，
                // 而数据已经没了）。跟 importJar 同一套处理。
                if (!registered) runCatching { dir?.deleteRecursively() }
                val reason = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                _importJarProgress.value = ImportJarProgress.Failed(reason)
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(ctx, t("导入失败：%s", reason), android.widget.Toast.LENGTH_LONG).show()
                }
            } finally {
                importJarRunning = false
            }
        }
    }

    /**
     * 给整合包装加载器（联网）。返回一句说明性文字（写进控制台），失败原因也在里面。
     *
     * 三种加载器三条路：
     *  - **Forge / NeoForge**：下 maven 上的 installer jar（按清单里的**确切版本**，不是取最新）
     *    放进实例目录 —— 已有的启动流程会在首次启动时跑 `--installServer` 完成安装。
     *  - **Fabric**：Fabric 没有 installer，改为「原版 `server.jar` + 官方现成的服务端启动器 jar」，
     *    启动器会自己在同目录找 `server.jar`。
     *  - **原版**：只下 `server.jar`。
     *
     * 刻意不在这里"装完就标记已安装"：Forge 的安装标记必须由真正的安装器跑完才写，
     * 否则依赖库不全的包会被当成装好了，之后每次启动都跳过安装、永远修不回来。
     */
    private suspend fun installLoaderFor(
        dir: File,
        plan: ModpackImporter.Plan,
        onStatus: (String) -> Unit,
    ): String? {
        val mc = plan.mcVersion
        val loaderName = plan.loader.name
        return try {
            when (plan.loader) {
                ModpackManifest.Loader.FORGE, ModpackManifest.Loader.NEOFORGE -> {
                    val pair = ModpackImporter.loaderInstallerUrl(plan.loader, mc, plan.loaderVersion)
                    if (pair == null) {
                        // 走 t()：原来这里是裸字符串模板，英文用户整句看不懂。
                        // 两个可变部分都拆成 %s，加载器名与「空」各自可翻译。
                        t(
                            "%s 的版本信息不全（清单只给了 %s），",
                            loaderName,
                            plan.loaderVersion.ifEmpty { t("空") },
                        ) +
                            t("未能自动下载安装器；请手动放入 %s-*-installer.jar 后启动一次", loaderName)
                    } else {
                        val (url, fileName) = pair
                        onStatus(t("下载 %s 安装器…", loaderName))
                        val dest = File(dir, fileName)
                        val used = Downloader.downloadFromSources(
                            listOf(url), dest,
                            onProgress = { done, total ->
                                if (total > 0) {
                                    onStatus(t("下载 %s 安装器… %s%", loaderName, done * 100 / total))
                                }
                            },
                            validate = { it.length() > 0 && Downloader.isZip(it) },
                        )
                        if (used == null) {
                            t("%s 安装器下载失败（网络问题？）。首次启动前请手动放入 %s", loaderName, fileName)
                        } else {
                            t("已下载 %s 安装器（%s）：首次启动会自动完成安装，需要联网", loaderName, fileName)
                        }
                    }
                }

                ModpackManifest.Loader.FABRIC -> {
                    onStatus(t("下载原版服务端（Fabric 需要）…"))
                    val vanilla = runCatching { CoreSources.getVanillaDownload(mc) }
                        .getOrNull()?.getOrNull()
                    if (vanilla == null) {
                        t("原版服务端下载地址获取失败，Fabric 启动器需要它；请手动放入 server.jar")
                    } else {
                        val serverJar = File(dir, "server.jar")
                        val okServer = Downloader.downloadFromSources(
                            listOf(vanilla.url), serverJar,
                            onProgress = { done, total ->
                                if (total > 0) onStatus(t("下载原版服务端… %s%", done * 100 / total))
                            },
                            validate = { it.length() > 0 && Downloader.isZip(it) },
                        ) != null
                        if (!okServer) {
                            t("原版 server.jar 下载失败；Fabric 启动器需要同目录有一份 server.jar")
                        } else {
                            onStatus(t("下载 Fabric 启动器…"))
                            val fab = runCatching { CoreSources.getFabricDownload(mc, plan.loaderVersion) }
                                .getOrNull()?.getOrNull()
                            if (fab == null) {
                                t("Fabric 启动器地址获取失败；可稍后在「新建」里用 Fabric 建一个实例再把这些文件拷过去")
                            } else {
                                val fabJar = File(dir, fab.fileName)
                                val okFab = Downloader.downloadFromSources(
                                    listOf(fab.url), fabJar,
                                    validate = { it.length() > 0 && Downloader.isZip(it) },
                                ) != null
                                if (!okFab) {
                                    t("Fabric 启动器下载失败；server.jar 已就位，可手动补一个 Fabric 启动器")
                                } else {
                                    t("Fabric 已就绪（%s + server.jar），可直接启动", fab.fileName)
                                }
                            }
                        }
                    }
                }

                ModpackManifest.Loader.VANILLA -> {
                    onStatus(t("下载原版服务端…"))
                    val vanilla = runCatching { CoreSources.getVanillaDownload(mc) }
                        .getOrNull()?.getOrNull()
                    if (vanilla == null) {
                        t("原版服务端下载地址获取失败；请手动放入 server.jar")
                    } else {
                        val serverJar = File(dir, "server.jar")
                        val ok = Downloader.downloadFromSources(
                            listOf(vanilla.url), serverJar,
                            onProgress = { done, total ->
                                if (total > 0) onStatus(t("下载原版服务端… %s%", done * 100 / total))
                            },
                            validate = { it.length() > 0 && Downloader.isZip(it) },
                        ) != null
                        if (ok) t("原版服务端已就绪") else t("原版服务端下载失败")
                    }
                }

                else -> t("%s 加载器本项目暂不支持（如 Quilt），已保留包内文件；", loaderName) +
                    t("请在「新建」里建一个同版本的服务端，或手动放入可运行的核心")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // 联网步骤失败不该整包导入失败：模组与配置已经铺好，缺的只是加载器，
            // 用户手动补一个就能用。所以这里返回说明而不是抛错。
            t("%s 安装未完成：%s", loaderName, e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * 按 Modrinth 清单下载文件（模组等）。失败**不中断**整个导入 —— 把失败数量记进
     * [warnings] 让用户知道可以稍后在「模组」页单独补装。
     *
     * 校验策略：清单有 sha512 就校验 sha512（Modrinth 的主哈希），否则退 sha1，
     * 都没有就只要求"下到了非空文件"。**包已存在且大小与清单一致时跳过**，
     * 这样重复导入或断点续传后不会重复下载。
     */
    private suspend fun downloadManifestFiles(
        dir: File,
        manifest: ModpackManifest.Modrinth,
        warnings: MutableList<String>,
        onProgress: (done: Int, total: Int, msg: String) -> Unit,
    ) {
        val files = manifest.serverFiles
        if (files.isEmpty()) {
            if (manifest.skippedClientOnly > 0) {
                warnings += t("这个包里没有服务端要用的文件（%s 个纯客户端模组已跳过）", manifest.skippedClientOnly)
            }
            return
        }
        var done = 0
        var failed = 0
        var unsafe = 0
        for (f in files) {
            val dest = ModpackImporter.resolveRemoteDest(dir, f.path)
            if (dest == null) {
                // 清单路径来自远端（不可信），越界的一律不写
                unsafe++
                continue
            }
            onProgress(done, files.size, t("下载模组 %s/%s：%s", done + 1, files.size, dest.name))
            // 清单给了哈希时**必须校验哈希**才能复用：只看"大小一致"会永久放过
            // 长度恰好相同的损坏文件（见 ModpackImporter.canReuseExisting 的说明）。
            val hashOk: (File) -> Boolean = { candidate ->
                when {
                    f.sha512 != null -> Downloader.sha512Of(candidate)?.equals(f.sha512, ignoreCase = true) == true
                    f.sha1 != null -> Downloader.sha1Of(candidate)?.equals(f.sha1, ignoreCase = true) == true
                    else -> candidate.length() > 0
                }
            }
            if (ModpackImporter.canReuseExisting(dest, f, hashOk)) {
                done++
                continue
            }
            // 复用了但校验没过：把坏文件删掉，避免"下载失败"之后它还留着被下次复用
            if (dest.isFile) runCatching { dest.delete() }
            if (f.urls.isEmpty()) {
                failed++
                continue
            }
            val validate = hashOk
            val used = Downloader.downloadFromSources(f.urls, dest, validate = validate)
            if (used == null) {
                failed++
                runCatching { dest.delete() }
            } else {
                done++
            }
        }
        if (manifest.skippedClientOnly > 0) {
            warnings += t("%s 个纯客户端模组已跳过（服务端装了会崩或没用）", manifest.skippedClientOnly)
        }
        if (unsafe > 0) warnings += t("%s 个文件的路径非法，已跳过", unsafe)
        if (failed > 0) {
            warnings += t("%s 个模组下载失败（断网或源站删档），可在实例的「模组」页搜索重新安装", failed)
        }
    }

    /** 包里是否已经有实体的模组文件（用来判断 CurseForge 包是不是"服务端包"形态） */
    private fun hasPhysicalMods(root: File): Boolean =
        File(root, "mods").listFiles()?.any { it.isFile && it.name.endsWith(".jar", ignoreCase = true) } == true

    /**
     * 整合包实例的初始内存上限。
     *
     * 整合包通常比原版吃内存（mod 多、加载期峰值高），所以想给得多一点；
     * 但**不能写死 2048** —— 2GB 机型上刚建好实例就会 OOM，比"内存小一点"更糟。
     * 所以走与「新建」页同一套建议逻辑（可用内存的一半，下限 1024），
     * 只在设备确实宽裕（可用 ≥ 4GB）时才抬到 2048；最后统一 clamp 到合法区间。
     *
     * 读不到 /proc/meminfo 时按 0 处理 → 建议值落到下限 1024，仍是安全的选择。
     */
    private fun suggestedModpackMemoryMb(): Int {
        val availMb = runCatching {
            java.io.File("/proc/meminfo").useLines { lines ->
                lines.firstOrNull { it.startsWith("MemAvailable") }
                    ?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull()?.div(1024f)
                    ?: 0f
            }
        }.getOrDefault(0f)
        val suggested = com.kaze.newage.core.server.MemoryLimits.suggestFrom(availMb)
        val desired = if (availMb >= 4096f) 2048f else suggested
        return com.kaze.newage.core.server.MemoryLimits.clamp(maxOf(suggested, desired).toInt())
    }

    /**
     * 整合包实例名：用户可在导入对话框里填，留空就用识别到的「核心 + MC 版本」。
     */
    private fun modpackNameOf(rawName: String, plan: ModpackImporter.Plan): String {
        if (rawName.isNotBlank()) return rawName.trim()
        val core = runCatching { CoreType.valueOf(plan.coreTypeName).displayName }
            .getOrDefault(t("整合包"))
        return if (plan.mcVersion.isNotEmpty()) t("%s %s 整合包", core, plan.mcVersion) else t("%s 整合包", core)
    }

    /** 重名自动加序号（与 importNameOf 同一套规则） */
    private fun uniqueName(base: String, existing: Set<String>): String {
        if (base !in existing) return base
        var i = 2
        while ("$base ($i)" in existing) i++
        return "$base ($i)"
    }

    /** 跨分区搬目录（rename 失败时用）：逐文件 copy，可中断 */
    private fun moveDirChecked(src: File, dst: File) {
        src.walkTopDown().forEach { f ->
            val out = File(dst, f.relativeTo(src).path)
            if (f.isDirectory) out.mkdirs() else {
                out.parentFile?.mkdirs()
                f.copyTo(out, overwrite = true)
            }
        }
    }

    /**
     * 带进度的复制。
     *
     * SAF 的流通常给不出长度（total=0），这时 [onProgress] 只报已复制字节数（分母留空），
     * 界面显示「复制中… 12 MB」而不是一个永远 0% 的进度条。
     */
    private fun copyWithProgress(
        ctx: android.content.Context,
        uri: android.net.Uri,
        dest: File,
        onProgress: (done: Long, total: Long) -> Unit,
    ) {
        val total = runCatching {
            ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull() ?: -1L
        val ins = ctx.contentResolver.openInputStream(uri)
            ?: throw RuntimeException(t("无法打开所选文件（授权可能已失效，请重新选择）"))
        ins.use { input ->
            dest.outputStream().use { out ->
                val buf = ByteArray(256 * 1024)
                var done = 0L
                var lastReport = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    done += n
                    // 限流：每 512 KB 报一次，避免高频状态更新把主线程刷爆
                    if (done - lastReport >= 512 * 1024) {
                        lastReport = done
                        onProgress(done, total)
                    }
                }
                onProgress(done, total)
            }
        }
    }

    /**
     * 生成有意义的实例名：
     *  - 用户自己填了名字（下载流程会传）就用；
     *  - 导入流程传的是「导入-MMdd-HHmmss」这种占位，改用识别到的「核心 + MC 版本」；
     *  - 重名自动加序号（`Paper 1.21.4`、`Paper 1.21.4 (2)`）。
     */
    private fun importNameOf(rawName: String, info: JarInspector.Info, existing: Set<String>): String {
        val placeholder = rawName.startsWith(t("导入-"))
        val coreLabel = runCatching { CoreType.valueOf(info.coreTypeName).displayName }
            .getOrDefault(t("服务端"))
        val base = if (!placeholder) {
            rawName
        } else {
            buildString {
                append(coreLabel)
                if (info.mcVersion.isNotEmpty()) append(' ').append(info.mcVersion)
            }
        }
        if (base !in existing) return base
        var i = 2
        while ("$base ($i)" in existing) i++
        return "$base ($i)"
    }

    /** 取消/忽略导入进度（界面消费完调用，避免下次进来还看到上次的结果） */
    fun clearImportJarProgress() {
        _importJarProgress.value = ImportJarProgress.Idle
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

/**
 * 把回读的更早历史**前插**到控制台显示列表。
 *
 * 列表已满（≥ [max]）时必须让**尾部**（实时最新）让位，而不是丢前插的历史：
 * 旧实现用 `(older + current).takeLast(max)`，在满列表下丢掉的恰恰是排在最前面的、
 * 刚读回来的那段历史 —— 用户点了「加载更早日志」却什么都没出现（游标还在推进，
 * 所以反复点也一直"没反应"）。
 *
 * 抽成纯函数是为了能单测：AppViewModel 需要 Android 运行时，这条规则却不需要。
 */
internal fun prependOlderLines(
    older: List<ConsoleLine>,
    current: List<ConsoleLine>,
    max: Int,
): List<ConsoleLine> {
    if (max <= 0) return emptyList()
    val history = if (older.size > max) older.takeLast(max) else older
    if (history.size >= max) return history
    return history + current.takeLast(max - history.size)
}
