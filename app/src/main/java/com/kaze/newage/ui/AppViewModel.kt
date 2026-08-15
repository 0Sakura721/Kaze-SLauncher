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
import com.kaze.newage.core.console.ConsoleParser
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

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
)

/** 共享 ViewModel：接线 core 各组件与 UI（多开：每实例独立状态/控制台） */
class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val container = application.container

    // ── core 组件 ──
    val env: ProotEnvironment = container.env
    val serverManager = container.serverManager
    val instanceStore = container.instanceStore
    val uiPrefs = container.uiPrefs

    // ── 状态暴露 ──
    val envState: StateFlow<ProotEnvironment.State> = env.state
    val envItems: StateFlow<List<ProotEnvironment.SetupItem>> = env.items
    val envLog: StateFlow<List<String>> = env.log
    val envJavaVersions: StateFlow<List<Int>> =
        MutableStateFlow(emptyList()) // 刷新见 refreshJava()

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

    private val _versions = MutableStateFlow<List<GameVersion>>(emptyList())
    val versions: StateFlow<List<GameVersion>> = _versions.asStateFlow()

    private val _versionsLoading = MutableStateFlow(false)
    val versionsLoading: StateFlow<Boolean> = _versionsLoading.asStateFlow()

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
        // 跟随当前实例切换控制台（每实例独立日志流），并跟踪在线玩家
        viewModelScope.launch(Dispatchers.IO) {
            _currentInstanceId.collectLatest { id ->
                _consoleLines.value = emptyList()
                _onlinePlayers.value = emptyList()
                if (id != null) {
                    serverManager.consoleFor(id).lines.collect { line ->
                        _consoleLines.value = (_consoleLines.value + line).takeLast(2000)
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
        }
        refreshJava()
    }

    fun refreshJava() {
        (envJavaVersions as MutableStateFlow).value = env.installedJdkVersions()
    }

    /** 可选下载：安装指定 Java 版本（8/17/21，apt 源） */
    fun installJava(version: Int) {
        if (_javaTask.value.running) return
        viewModelScope.launch(Dispatchers.IO) {
            _javaTask.value = JavaTaskState(running = true, version = version, message = "准备安装 Java $version…")
            try {
                if (!container.env.isReady) {
                    container.env.setup { p, m ->
                        _javaTask.value = JavaTaskState(running = true, version = version, progress = p * 0.3f, message = "准备环境：$m")
                    }
                }
                container.javaManager.install(version) { p, m ->
                    _javaTask.value = JavaTaskState(running = true, version = version, progress = 0.3f + p * 0.7f, message = m)
                }
                refreshJava()
                _javaTask.value = JavaTaskState(version = version, progress = 1f, message = "Java $version 安装完成")
            } catch (e: Exception) {
                _javaTask.value = JavaTaskState(version = version, error = e.message ?: "安装失败")
            }
        }
    }

    /** 删除指定 Java 版本 */
    fun uninstallJava(version: Int) {
        if (_javaTask.value.running) return
        viewModelScope.launch(Dispatchers.IO) {
            _javaTask.value = JavaTaskState(running = true, version = version, message = "卸载 Java $version…")
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
    fun setupEnv() {
        viewModelScope.launch(Dispatchers.IO) {
            env.setup { progress, message ->
                _download.value = DownloadState(running = true, progress = progress, message = message)
            }
            refreshJava()
            _download.value = DownloadState(done = true, message = "环境部署完成")
        }
    }

    // ── 动作：服务端（多开）──
    fun selectInstance(instance: ServerInstance) {
        _currentInstanceId.value = instance.id
    }

    fun startInstance(instance: ServerInstance) {
        _currentInstanceId.value = instance.id
        viewModelScope.launch(Dispatchers.IO) {
            serverManager.start(instance)
        }
    }

    fun stopInstance(instance: ServerInstance) {
        viewModelScope.launch(Dispatchers.IO) {
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

    /** 清空当前实例控制台显示（不影响后端日志流） */
    fun clearConsole() {
        _consoleLines.value = emptyList()
    }

    fun removeInstance(instance: ServerInstance) {
        viewModelScope.launch(Dispatchers.IO) {
            instanceStore.remove(instance.id)
            instance.dir.deleteRecursively()
            if (_currentInstanceId.value == instance.id) _currentInstanceId.value = null
        }
    }

    // ── 动作：版本列表 ──
    fun loadVersions(type: CoreType) {
        _versionsLoading.value = true
        _versions.value = emptyList()
        viewModelScope.launch(Dispatchers.IO) {
            _versions.value = CoreSources.fetchVersions(type).getOrDefault(emptyList())
            _versionsLoading.value = false
        }
    }

    // ── 动作：下载并创建实例 ──
    fun downloadAndCreate(
        name: String,
        type: CoreType,
        mcVersion: String,
        memoryMb: Int,
        javaMajorOverride: Int = 0,
        onComplete: (ServerInstance?) -> Unit,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            _download.value = DownloadState(running = true, progress = 0f, message = "解析下载地址…")
            try {
                val dl = CoreSources.resolveDownload(type, mcVersion).getOrThrow()
                val dir = instanceStore.createInstanceDir(name)
                val target = File(dir, dl.fileName)
                _download.value = DownloadState(running = true, progress = 0f, message = "下载 ${dl.fileName}")
                Downloader.download(dl.url, target) { done, total ->
                    val progress = if (total > 0) done.toFloat() / total else 0f
                    _download.value = DownloadState(
                        running = true,
                        progress = progress,
                        message = "下载中 ${(done / 1024 / 1024)}MB / ${(total / 1024 / 1024)}MB",
                    )
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
                _currentInstanceId.value = instance.id
                _download.value = DownloadState(done = true, message = "下载完成")
                onComplete(instance)
            } catch (e: Exception) {
                _download.value = DownloadState(error = e.message ?: "下载失败")
                onComplete(null)
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
        viewModelScope.launch(Dispatchers.IO) {
            _addonInstall.value = DownloadState(running = true, message = "解析 ${hit.title} 版本…")
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

    /** 导入自定义 jar 创建实例 */
    fun importJar(jarFile: File, name: String, javaMajor: Int, memoryMb: Int): ServerInstance? {
        val dir = instanceStore.createInstanceDir(name)
        val target = File(dir, jarFile.name)
        jarFile.copyTo(target, overwrite = true)
        val instance = ServerInstance(
            name = name,
            coreType = CoreType.CUSTOM,
            javaMajor = javaMajor,
            memoryMb = memoryMb,
            dir = dir,
        )
        instanceStore.add(instance)
        ServerProperties.ensureInitial(instance, instanceStore.instances.value)
        _currentInstanceId.value = instance.id
        return instance
    }
}
