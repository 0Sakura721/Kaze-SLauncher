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
import kotlinx.coroutines.withContext
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
    /** 取消请求标志（下载循环轮询；true 时下载中止并保留断点） */
    val cancelRequested: Boolean = false,
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

    /** 可选下载：安装指定 Java 版本（8/17/21/25）；失败可再次调用重试（断点续传） */
    fun installJava(version: Int) {
        if (_javaTask.value.running) return // 任务进行中（含取消中）：等其退出后再点即续传
        viewModelScope.launch(Dispatchers.IO) {
            _javaTask.value = JavaTaskState(running = true, version = version, message = "准备安装 Java $version…")
            try {
                if (!container.env.isReady) {
                    container.env.setup { p, m ->
                        _javaTask.value = JavaTaskState(running = true, version = version, progress = p * 0.3f, message = "准备环境：$m")
                    }
                }
                container.javaManager.install(
                    version,
                    { p, m ->
                        _javaTask.value = JavaTaskState(running = true, version = version, progress = 0.3f + p * 0.7f, message = m)
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
            _download.value = DownloadState(running = true, progress = 0f, message = "准备部署…")
            try {
                env.setup { progress, message ->
                    _download.value = DownloadState(running = true, progress = progress, message = message)
                }
                refreshJava()
                _download.value = DownloadState(done = true, message = "环境部署完成")
            } catch (e: Exception) {
                _download.value = DownloadState(error = e.message ?: "部署失败")
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
    fun selectInstance(instance: ServerInstance) {
        _currentInstanceId.value = instance.id
    }

    fun startInstance(instance: ServerInstance) {
        _currentInstanceId.value = instance.id
        // 电池优化白名单：首次启动服务端时自动弹系统请求（防止后台被杀）；拒绝可去设置页重试
        requestBatteryWhitelistOnce()
        viewModelScope.launch(Dispatchers.IO) {
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

    /** 导出当前实例完整日志到用户选择的目录（SAF 一次性保存） */
    fun saveConsoleLog(uri: android.net.Uri) {
        val id = _currentInstanceId.value ?: return
        val inst = instanceStore.get(id) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val src = java.io.File(inst.dir, "console-output.log")
                val text = if (src.exists()) src.readText() else "（该实例暂无日志）\n"
                container.appContext.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(text.toByteArray(Charsets.UTF_8))
                }
            } catch (_: Exception) { }
        }
    }

    fun removeInstance(instance: ServerInstance) {
        viewModelScope.launch(Dispatchers.IO) {
            // 运行中先停止，避免进程占用目录文件导致删除失败/残留
            if (serverManager.isRunning(instance.id)) {
                serverManager.stop(instance)
                val deadline = System.currentTimeMillis() + 15_000
                while (serverManager.isRunning(instance.id) && System.currentTimeMillis() < deadline) {
                    kotlinx.coroutines.delay(500)
                }
            }
            instanceStore.remove(instance.id)
            runCatching { instance.dir.deleteRecursively() }
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
            var dir: File? = null
            try {
                val dl = CoreSources.resolveDownload(type, mcVersion).getOrThrow()
                dir = instanceStore.createInstanceDir(name)
                // 半成品用 .part 后缀：断点续传保留，且不会被实例目录扫描误识别为已装 jar
                val part = File(dir, dl.fileName + ".part")
                val target = File(dir, dl.fileName)
                _download.value = DownloadState(running = true, progress = 0f, message = "下载 ${dl.fileName}")
                // 多源回退 + 断网自动重试（断点保留，失败可再次点下载续传）
                val used = Downloader.downloadFromSources(listOf(dl.url), part, onProgress = { done, total ->
                    val progress = if (total > 0) done.toFloat() / total else 0f
                    _download.value = DownloadState(
                        running = true,
                        progress = progress,
                        message = "下载中 ${(done / 1024 / 1024)}MB${if (total > 0) " / ${(total / 1024 / 1024)}MB" else ""}",
                    )
                })
                if (used == null) throw RuntimeException("下载失败：所有源不可用（请检查网络后重试）")
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
