package com.kaze.newage.shell

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.kaze.newage.core.console.ConsoleLine
import com.kaze.newage.core.console.LineType
import com.kaze.newage.core.download.CoreBuild
import com.kaze.newage.data.model.CoreType
import com.kaze.newage.data.model.GameVersion
import com.kaze.newage.data.model.ServerInstance
import com.kaze.newage.ui.AppViewModel
import com.kaze.newage.ui.DownloadState
import com.kaze.newage.ui.formatUptime
import com.kaze.newage.ui.isBusy
import com.kaze.newage.ui.toLabel
import com.kaze.newage.core.server.ServerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 壳的"宿主"层：把 ViewModel 的状态翻译成界面能直接用的东西。
 *
 * 分这一层的理由：
 *  - 屏（Screen）只该做布局，不该知道 `StateFlow`、协程、生命周期；
 *  - 一处订阅、多处复用：首页与实例详情都要"这个实例在不在跑"，若各自订阅，
 *    就会出现两份不同步的副本（一屏显示运行中、另一屏显示已停止）；
 *  - 状态订阅可以整体随 Activity 销毁，不会因为某屏忘了取消而泄漏。
 *
 * 订阅用**回调式**而不是暴露 Flow：自绘 View 层没有重组概念，
 * 回调 + 局部刷新（只找 tag 对应的子 View 改字）是这里唯一不闪烁的更新方式。
 */
class ShellHost(
    val context: Context,
    val vm: AppViewModel,
    private val prefs: ShellPrefs,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val jobs = mutableListOf<Job>()

    /** 实例列表变化时通知（增删改、置顶排序） */
    var onInstances: ((List<ServerInstance>) -> Unit)? = null

    /** 任一实例状态变化（启停）时通知 */
    var onStates: ((Map<String, ServerState>) -> Unit)? = null

    /** 全局消息（成功/失败），由 ShellActivity 负责呈现 */
    var onToast: ((String, Boolean) -> Unit)? = null

    /** 主题需要重建时通知（切换主题） */
    var onThemeChanged: (() -> Unit)? = null

    val themePrefs get() = prefs

    fun start() {
        jobs += scope.launch {
            vm.instances.collect { list -> onInstances?.invoke(sorted(list)) }
        }
        jobs += scope.launch {
            vm.serverStates.collect { map -> onStates?.invoke(map) }
        }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
    }

    /**
     * 排序：置顶在前，其余保持创建顺序（按目录名无关的稳定序）。
     *
     * 用 [sortedByDescending] 而不是先 filter 再拼：后者需要两次遍历，
     * 且会让"新建的实例"因为 id 是随机 UUID 而随机插入到中间。
     * 这里以 `pinned` 为唯一权重，相等时依赖 Kotlin 的稳定排序保序。
     */
    private fun sorted(list: List<ServerInstance>): List<ServerInstance> =
        list.sortedByDescending { it.pinned }

    // ── 实例动作 ────────────────────────────────────────────────────────

    fun stateOf(id: String): ServerState = vm.serverStates.value[id] ?: ServerState.Idle

    fun isRunning(id: String): Boolean = stateOf(id) == ServerState.Running

    fun start(instance: ServerInstance) {
        vm.selectInstance(instance)
        vm.startInstance(instance)
        prefs.lastInstanceId = instance.id
    }

    fun stop(instance: ServerInstance) {
        val state = stateOf(instance.id)
        if (state == ServerState.Stopping) {
            // 已在停止中再点一次 = 用户不想等了 → 必须经确认，不能直接砍
            Sheet(context, "强制停止", "优雅停止会先把世界存盘，世界大时要等一会儿").apply {
                add(context.txt(
                    "强制停止会让服务端来不及保存世界，最近一段时间的方块改动可能丢失。确定要现在终止吗？",
                    Type.body, ShellTheme.current.ink2,
                ).apply { setLineSpacing(context.dp(3f).toFloat(), 1f) })
                buttons("立即终止", {
                    vm.forceStopInstance(instance)
                    dismiss()
                }, secondaryLabel = "再等等", onSecondary = { dismiss() }, danger = true)
                show()
            }
            return
        }
        vm.stopInstance(instance)
    }

    fun restart(instance: ServerInstance) {
        vm.restartInstance(instance)
        prefs.lastInstanceId = instance.id
    }

    fun remove(instance: ServerInstance) {
        vm.removeInstance(instance)
    }

    /**
     * 串行启动一批实例。
     *
     * 多实例同时冷启会一起抢 CPU 与磁盘：手机上表现为每个都慢，还容易撞上
     * 各自的启动超时。逐个来虽然总时长更长，但每个都能真的起来。
     * 已经在跑或正在启动的跳过，不重复拉起。
     */
    fun startSequentially(list: List<ServerInstance>) {
        val todo = list.filter {
            val s = stateOf(it.id)
            s != ServerState.Running && !s.isBusy()
        }
        if (todo.isEmpty()) {
            toast("没有可启动的实例")
            return
        }
        toast("开始依次启动 ${todo.size} 个实例")
        jobs += scope.launch {
            todo.forEach { inst ->
                vm.startInstance(inst)
                // 等到它离开 Starting 阶段（或超时 3 分钟）再启动下一个
                withTimeoutOrNull(180_000) {
                    vm.serverStates.first { map ->
                        val s = map[inst.id] ?: ServerState.Idle
                        s == ServerState.Running || s == ServerState.Error
                    }
                }
            }
        }
    }

    /** 选一个 jar 导入为新实例（SAF 选择器） */
    fun pickJar() {
        onPickJar?.invoke()
    }

    /** 由 ShellActivity 注入（需要 Activity 级 registerForActivityResult） */
    var onPickJar: (() -> Unit)? = null

    /** 由 ShellActivity 注入：打开新建向导（首页与服务端页共用同一个入口） */
    var onCreateRequested: (() -> Unit)? = null

    fun openCreate() {
        onCreateRequested?.invoke()
    }

    /** 重命名对话框 */
    fun renameDialog(instance: ServerInstance) {
        val sheet = Sheet(context, "重命名实例", "只改显示名，不影响目录与 server.properties")
        val field = context.input("实例名", instance.name)
        sheet.add(field)
        sheet.buttons("保存", {
            val name = field.text.toString().trim()
            if (name.isEmpty()) {
                toast("名称不能为空", error = true)
            } else if (name == instance.name) {
                sheet.dismiss()
            } else {
                vm.renameInstance(instance.id, name)
                sheet.dismiss()
                toast("已重命名为 $name")
            }
        }, secondaryLabel = "取消", onSecondary = { sheet.dismiss() })
        sheet.show()
    }

    /** 删除确认（带世界保留说明） */
    fun confirmDelete(instance: ServerInstance, onDone: () -> Unit = {}) {
        val sheet = Sheet(context, "删除「${instance.name}」", scrollable = false)
        sheet.add(
            context.txt(
                "只移除应用里的实例记录；目录 ${instance.dir.name} 与世界存档仍在磁盘上，" +
                    "可以重新导入。此动作不可撤销。",
                Type.body, ShellTheme.current.ink2,
            ).apply { setLineSpacing(context.dp(3f).toFloat(), 1f) },
        )
        sheet.buttons(
            "删除",
            {
                sheet.dismiss()
                remove(instance)
                onDone()
            },
            secondaryLabel = "取消",
            onSecondary = { sheet.dismiss() },
            danger = true,
        )
        sheet.show()
    }

    fun send(command: String) {
        vm.sendCommand(command)
    }

    fun select(instance: ServerInstance) {
        vm.selectInstance(instance)
        prefs.lastInstanceId = instance.id
    }

    // ── 环境 / 依赖 ─────────────────────────────────────────────────────

    fun setupEnvironment(onDone: (() -> Unit)? = null) {
        vm.setupEnv()
        if (onDone != null) {
            jobs += scope.launch {
                vm.envState.collect { state ->
                    if (state == com.kaze.newage.core.env.ProotEnvironment.State.READY) {
                        withContext(Dispatchers.Main) { onDone() }
                    }
                }
            }
        }
    }

    val envState: StateFlow<com.kaze.newage.core.env.ProotEnvironment.State> get() = vm.envState

    // ── 提示与系统跳转 ──────────────────────────────────────────────────

    fun toast(message: String, error: Boolean = false) {
        onToast?.invoke(message, error)
    }

    /**
     * 申请电池优化白名单。
     *
     * 直接跳系统对话框而不是先读状态：有的 ROM 上 `isIgnoringBatteryOptimizations`
     * 恒返回 false（厂商改过实现），先查再跳会导致"永远显示未授权、点了没反应"。
     * 跳过去由系统自己判断，用户看到的至少是个真弹窗。
     */
    fun requestBatteryExemption() {
        runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(context.packageName)) {
                toast("已经在电池优化白名单里")
                return
            }
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { toast("无法打开电池优化设置：${it.message}", error = true) }
    }

    fun openUrl(url: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { toast("没有可打开该链接的应用", error = true) }
    }

    fun copy(text: String, label: String = "已复制") {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
        toast(label)
    }

    fun appVersion(): String = vm.currentVersionName()

    fun deviceSummary(): String {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "?"
        val sdk = Build.VERSION.SDK_INT
        return "$abi · Android $sdk"
    }

    fun formatState(state: ServerState): String = state.toLabel()

    fun formatUptime(sec: Long): String = formatUptime(sec)

    fun busy(state: ServerState): Boolean = state.isBusy()

    fun themeMode(): ThemeMode = prefs.mode

    fun setThemeMode(mode: ThemeMode) {
        prefs.mode = mode
        onThemeChanged?.invoke()
    }

    /**
     * 切回 Compose 界面（设置 → 外观 → 主题样式 → Material 3）。
     *
     * 主题样式的唯一事实来源是 [AppViewModel.uiPrefs.themeMode]（AppThemeMode），
     * 这里只负责"换 Activity"；选择器本身在岚的设置页与 Compose 的设置页各有一份，
     * 写的都是同一个键。
     */
    fun switchToComposeTheme(modeId: String) {
        vm.uiPrefs.setThemeMode(modeId)
        com.kaze.newage.shell.ShellSwitch.restart(context, com.kaze.newage.shell.ShellSwitch.M3)
    }

    fun consoleLines(): List<ConsoleLine> = vm.consoleLines.value

    // ── 一次性异步等待 ────────────────────────────────────────────────
    //
    // 向导里的"选版本 / 选构建"背后是网络请求。给这些动作包一层统一的
    // "等到有结果或超时就回调"，屏里就不用各自处理协程、超时与主线程切换。

    /** 等待版本列表；超时回调空列表 */
    fun awaitVersions(onResult: (List<GameVersion>) -> Unit) {
        jobs += scope.launch {
            val list = withTimeoutOrNull(20_000) {
                vm.versions.first { it.isNotEmpty() }
            }
            onResult(list.orEmpty())
        }
    }

    /** 等待构建列表（Paper / Fabric） */
    fun awaitBuilds(onResult: (List<CoreBuild>) -> Unit) {
        jobs += scope.launch {
            val list = withTimeoutOrNull(20_000) {
                vm.builds.first { it.isNotEmpty() }
            }
            onResult(list.orEmpty())
        }
    }

    /**
     * 跟踪下载进度。
     *
     * 只在这个弹层活着的时候订阅：下载本身跑在 appScope 里，
     * 关掉弹层不会中断下载，只是不再刷新这一屏。
     */
    fun watchDownload(onProgress: (DownloadState) -> Unit) {
        val job = scope.launch {
            vm.download.collect { st -> onProgress(st) }
        }
        jobs += job
    }

    /** 打开控制台页并切到该实例（由 ShellActivity 注入具体实现） */
    var openConsoleHook: ((ServerInstance) -> Unit)? = null

    fun openConsoleAction(instance: ServerInstance) {
        select(instance)
        openConsoleHook?.invoke(instance)
    }

    /** 读应用日志尾部若干行（挂起函数在 IO 上跑，回调回主线程） */
    fun readLogTail(maxLines: Int, onDone: (List<String>) -> Unit) {
        jobs += scope.launch {
            val text = runCatching { vm.appLog.tail(maxLines) }.getOrElse { "" }
            onDone(text.lines().filter { it.isNotBlank() })
        }
    }

    fun lineColor(type: LineType): Int {
        val t = ShellTheme.current
        return when (type) {
            LineType.Error -> t.danger
            LineType.Warn -> t.warn
            LineType.Command -> t.accent
            LineType.System -> t.ink3
            LineType.Info -> t.ink2
        }
    }

    /** 核心类型的显示名；用于实例卡片的类型标签 */
    fun coreLabel(type: CoreType): String = type.displayName
}
