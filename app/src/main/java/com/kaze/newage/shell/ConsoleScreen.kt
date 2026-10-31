package com.kaze.newage.shell

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.kaze.newage.core.console.ConsoleLine
import com.kaze.newage.core.monitor.ProcessStats
import com.kaze.newage.core.server.ServerState
import com.kaze.newage.data.model.ServerInstance
import com.kaze.newage.ui.formatUptime
import com.kaze.newage.ui.isBusy
import com.kaze.newage.ui.toLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 控制台页。
 *
 * 结构：标题 / 实例切换条 / 运行指标 / 日志（唯一可滚区） / 命令输入（固定底栏）。
 *
 * 为什么只有日志可滚：日志是整页唯一持续变化的部分。把指标也塞进滚动区，
 * 用户翻历史日志时就看不到 CPU 了 —— 而"服务端到底卡不卡"恰恰是最常在
 * 翻日志时冒出来的疑问。
 *
 * 日志用**追加**而不是整表重画：一行行 addView 到末尾，超过上限才从头摘。
 * 每秒几百行（Forge 启动）时整表重画会直接把主线程拖死。
 */
class ConsoleScreen(private val host: ShellHost) : ScreenRoot(host.context, scrollable = false) {

    private val c: Context get() = context

    /**
     * 订阅用的 scope。
     *
     * **每次 attach 重建、detach 时 cancel**：屏被 Activity 缓存、从不重建，
     * 所以一个 val scope 会跟着 Activity 活到底；detach 只 cancel 子 job 的话
     * SupervisorJob 永远 isActive，日后误用（比如在 detach 后仍 launch）
     * 会静默留下活着的协程。用可重建的 var 让"生命周期"这件事显式化。
     */
    private var scope = newScope()
    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var jobs: MutableList<Job> = mutableListOf()

    private lateinit var switcherValue: TextView
    private lateinit var stateSlot: LinearLayout
    private lateinit var uptimeValue: TextView
    private lateinit var playerValue: TextView
    private lateinit var cpuMeter: SegmentMeter
    private lateinit var cpuValue: TextView
    private lateinit var memMeter: SegmentMeter
    private lateinit var memValue: TextView
    private lateinit var logArea: LinearLayout
    private lateinit var logScroll: ScrollView
    private lateinit var emptyHint: TextView
    private lateinit var input: EditText
    private lateinit var actionButton: TextView

    private var instance: ServerInstance? = null
    private var renderedSeq = Long.MIN_VALUE
    private var atBottom = true

    /** 界面上保留的最大行数：再多就只留磁盘日志，内存里不留 */
    private val maxVisibleLines = 1200

    init {
        head.addView(buildHeader())
        // 控制台不滚动：日志区要吃掉所有剩余高度。
        // 放进 ScrollView 的话，日志的固定高度会在小屏上把输入区挤出可视区
        //（MuMu 720×1280 上实机就是输入行被底部导航压住），
        // 而 ScrollView 又不会报错，只是"下面那块看不见了"。
        frameBody.addView(buildSwitcher(), c.stackMargin(top = 8f))
        frameBody.addView(buildMetrics(), c.stackMargin(top = 9f))
        // 左右留白由**布局参数**给（不能用 stackMargin：它会把 height 设成
        // WRAP_CONTENT，覆盖掉 weight=1，日志区就不再撑满剩余高度了）
        frameBody.addView(buildLogPane(), LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
        ).apply {
            marginStart = c.dp(Dim.gutter)
            marginEnd = c.dp(Dim.gutter)
            topMargin = c.dp(9f)
            bottomMargin = c.dp(6f)
        })

        // 输入区贴在日志下面：键盘弹出时它跟着浮上来
        val dock = c.col().apply {
            setBackgroundColor(ShellTheme.current.bg)
            setPadding(dp(Dim.gutter), dp(8f), dp(Dim.gutter), dp(9f))
        }
        dock.addView(c.hairline(0f, 0f))
        dock.addView(buildInputDock())
        installDock(dock)
    }

    /** 占满剩余高度（FrameBody 是 LinearLayout，靠 weight 分配） */
    private fun weightParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)

    // ── 标题 ───────────────────────────────────────────────────────────

    private fun buildHeader(): LinearLayout {
        val c = this.c
        return c.row().apply {
            val texts = c.col().apply {
                addView(c.txt("控制台", Type.display, ShellTheme.current.ink, medium = true))
                addView(
                    c.txt("实时输出与命令", Type.micro, ShellTheme.current.ink3).apply {
                        setPadding(0, dp(4f), 0, 0)
                    },
                )
            }
            addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            actionButton = c.txt("启动", Type.micro, ShellTheme.current.accent, medium = true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(15f), dp(9f), dp(15f), dp(9f))
                background = c.rip(
                    ShellTheme.current.accentSoft, 11f,
                    stroke = ShellTheme.current.accent.alpha(0.28f),
                    strokeWidth = Dim.line,
                )
                tappable("启动或停止") { toggleInstance() }
            }
            addView(actionButton)
        }
    }

    private fun buildSwitcher(): LinearLayout {
        val c = this.c
        return c.row().apply {
            background = c.rounded(
                ShellTheme.current.surface, Dim.field,
                ShellTheme.current.stroke, Dim.line,
            )
            setPadding(dp(13f), dp(10f), dp(10f), dp(10f))
            addView(c.txt("实例", Type.body, ShellTheme.current.ink3))
            switcherValue = c.txt("未选择", Type.body, ShellTheme.current.ink, medium = true).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(10f), 0, dp(4f), 0)
            }
            addView(switcherValue, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            stateSlot = c.row()
            addView(stateSlot)
            addView(
                c.chevron(),
                LinearLayout.LayoutParams(dp(18f), dp(18f)).apply { marginStart = dp(5f) },
            )
            tappable("切换实例") { pickInstance() }
        }
    }

    // ── 指标 ───────────────────────────────────────────────────────────

    private fun buildMetrics(): LinearLayout {
        val c = this.c
        return c.card(pad = 13f).apply {
            val topRow = c.row()
            uptimeValue = c.mono("—", Type.title, ShellTheme.current.ink)
            topRow.addView(uptimeValue)
            topRow.addView(View(c), LinearLayout.LayoutParams(0, 1, 1f))
            playerValue = c.txt("在线 0", Type.micro, ShellTheme.current.ink3)
            topRow.addView(playerValue)
            addView(topRow)

            addView(View(c), LinearLayout.LayoutParams(1, dp(10f)))

            addView(metricRow("CPU", ::onCpuMeter, ::onCpuValue, tone = 0))
            addView(View(c), LinearLayout.LayoutParams(1, dp(7f)))
            addView(metricRow("内存", ::onMemMeter, ::onMemValue, tone = 1))
        }
    }

    /**
     * 指标行。
     *
     * 用"回调把新建的控件交回去"而不是先建控件再组装：
     * SegmentMeter / TextView 都需要在布局里拿到正确的 LayoutParams，
     * 先 new 出来再 addView 很容易把 weight 丢掉（表现为条子不占满）。
     */
    private fun metricRow(
        label: String,
        onMeter: (SegmentMeter) -> Unit,
        onValue: (TextView) -> Unit,
        tone: Int,
    ): LinearLayout {
        val c = this.c
        return c.row().apply {
            addView(
                c.txt(label, Type.micro, ShellTheme.current.ink3),
                LinearLayout.LayoutParams(dp(34f), LinearLayout.LayoutParams.WRAP_CONTENT),
            )
            val meter = SegmentMeter(c).apply {
                segments = 18
                this.tone = tone
            }
            addView(meter, LinearLayout.LayoutParams(0, dp(9f), 1f))
            val value = c.mono("—", Type.micro, ShellTheme.current.ink2).apply {
                gravity = Gravity.END
            }
            addView(
                value,
                LinearLayout.LayoutParams(dp(62f), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(9f)
                },
            )
            onMeter(meter)
            onValue(value)
        }
    }

    private fun onCpuMeter(meter: SegmentMeter) { cpuMeter = meter }
    private fun onCpuValue(view: TextView) { cpuValue = view }
    private fun onMemMeter(meter: SegmentMeter) { memMeter = meter }
    private fun onMemValue(view: TextView) { memValue = view }

    private fun renderStats(reading: ProcessStats.Reading?) {
        if (reading == null) {
            cpuMeter.fraction = 0f
            memMeter.fraction = 0f
            cpuValue.text = "—"
            memValue.text = "—"
            return
        }
        val cpu = reading.cpuPercent.coerceIn(0f, 100f)
        cpuMeter.fraction = cpu / 100f
        cpuMeter.tone = when {
            cpu >= 85f -> 3
            cpu >= 55f -> 2
            else -> 0
        }
        cpuValue.text = "${cpu.toInt()}%"

        val usedMb = if (reading.rssKb > 0) reading.rssKb / 1024 else -1L
        val limit = instance?.memoryMb ?: 0
        if (usedMb < 0 || limit <= 0) {
            memMeter.fraction = 0f
            memValue.text = "—"
        } else {
            val f = (usedMb.toFloat() / limit).coerceIn(0f, 1f)
            memMeter.fraction = f
            memMeter.tone = if (f >= 0.9f) 3 else if (f >= 0.7f) 2 else 1
            memValue.text = "$usedMb MB"
        }
    }

    // ── 日志 ───────────────────────────────────────────────────────────

    private fun buildLogPane(): LinearLayout {
        val c = this.c
        val card = c.bareCard(radius = 13f)
        logScroll = ScrollView(c).apply {
            isVerticalScrollBarEnabled = true
            scrollBarStyle = View.SCROLLBARS_INSIDE_INSET
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(dp(12f), dp(11f), dp(6f), dp(10f))
            // 用户手动上翻后停止自动跟随；回到底部才恢复。
            // 不判断这一条的话，正在读报错时新来一行就把视图拽回底部。
            setOnScrollChangeListener { _, _, scrollY, _, _ ->
                val content = getChildAt(0)?.height ?: 0
                atBottom = scrollY >= content - height - dp(28f)
            }
        }
        logArea = c.col()
        logScroll.addView(
            logArea,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        // 空态要压在日志区**正中**：直接 addView 到卡片里会贴在底部，
        // 看上去像一条页脚而不是"这里还没内容"。
        emptyHint = c.txt("还没有输出", Type.micro, ShellTheme.current.ink3).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        val logWrap = FrameLayout(c).apply {
            addView(logScroll, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ))
            addView(emptyHint, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ))
        }

        // 日志区吃掉卡片里除工具栏之外的全部高度（weight=1）。
        // 之前按屏幕 42% 算固定高度，在 720×1280 这类小屏上算出来偏大，
        // 卡片被撑出可视区，工具栏和输入行一起被挤到屏幕外。
        card.addView(
            logWrap,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        card.addView(buildLogTools())
        return card
    }

    private fun buildLogTools(): LinearLayout {
        val c = this.c
        return c.row().apply {
            setPadding(dp(12f), dp(5f), dp(10f), dp(8f))
            addView(c.txt("清屏", Type.micro, ShellTheme.current.ink3).apply {
                setPadding(dp(4f), dp(5f), dp(12f), dp(5f))
                tappable("清空控制台") {
                    host.vm.clearConsole()
                    logArea.removeAllViews()
                    renderedSeq = Long.MIN_VALUE
                    refreshEmpty()
                }
            })
            addView(c.txt("更早", Type.micro, ShellTheme.current.ink3).apply {
                setPadding(dp(4f), dp(5f), dp(12f), dp(5f))
                tappable("加载更早的日志") {
                    host.vm.loadOlderConsole()
                    // 前插会打乱 renderedSeq 的单调性，下一次刷新走整表重画
                    renderedSeq = Long.MIN_VALUE
                }
            })
            addView(c.txt("字号", Type.micro, ShellTheme.current.ink3).apply {
                setPadding(dp(4f), dp(5f), dp(12f), dp(5f))
                tappable("调整控制台字号") { cycleFontSize() }
            })
            addView(c.txt("时间", Type.micro, ShellTheme.current.ink3).apply {
                setPadding(dp(4f), dp(5f), dp(12f), dp(5f))
                tappable("切换时间戳") {
                    host.themePrefs.consoleStamps = !host.themePrefs.consoleStamps
                    host.toast(if (host.themePrefs.consoleStamps) "显示时间戳" else "隐藏时间戳")
                    renderedSeq = Long.MIN_VALUE
                    renderLines(host.vm.consoleLines.value, forceFull = true)
                }
            })
            addView(View(c), LinearLayout.LayoutParams(0, 1, 1f))
            addView(c.txt("复制", Type.micro, ShellTheme.current.accent, medium = true).apply {
                setPadding(dp(4f), dp(5f), dp(4f), dp(5f))
                tappable("复制全部日志") {
                    val lines = host.vm.consoleLines.value
                    if (lines.isEmpty()) {
                        host.toast("控制台是空的")
                    } else {
                        host.copy(lines.joinToString("\n") { it.text }, "已复制 ${lines.size} 行")
                    }
                }
            })
        }
    }

    private fun cycleFontSize() {
        val current = host.themePrefs.consoleFont
        val next = when {
            current < 11.5f -> 12f
            current < 13.5f -> 14f
            current < 15.5f -> 16f
            else -> 10.5f
        }
        host.themePrefs.consoleFont = next
        host.toast("字号 $next")
        renderedSeq = Long.MIN_VALUE
        renderLines(host.vm.consoleLines.value, forceFull = true)
    }

    private fun renderLines(lines: List<ConsoleLine>, forceFull: Boolean = false) {
        val last = lines.lastOrNull()
        if (!forceFull && last != null && last.seq == renderedSeq) return

        if (forceFull || renderedSeq == Long.MIN_VALUE || !monotonicFrom(lines)) {
            logArea.removeAllViews()
            lines.takeLast(maxVisibleLines).forEach { logArea.addView(buildLine(it)) }
            renderedSeq = last?.seq ?: Long.MIN_VALUE
        } else {
            val fresh = lines.filter { it.seq > renderedSeq }
            if (fresh.isEmpty()) return
            fresh.forEach { logArea.addView(buildLine(it)) }
            renderedSeq = fresh.last().seq
            val over = logArea.childCount - maxVisibleLines
            if (over > 0) logArea.removeViews(0, over)
        }
        refreshEmpty()
        if (atBottom) logArea.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    /**
     * 判断快照是否仍然"比上次渲染的更新"。
     *
     * 回读历史会前插更早的行，此时列表末尾可能**没变**（末尾还是最新一行），
     * 但中间多了一段 —— 只看末尾 seq 会漏掉这次前插，界面永远不显示历史。
     * 这里用"末尾 seq 必须大于上一次渲染的 seq"，前插场景下末尾不变、
     * 于是走整表重画，历史才会出来。
     */
    private fun monotonicFrom(lines: List<ConsoleLine>): Boolean {
        if (renderedSeq == Long.MIN_VALUE) return false
        val last = lines.lastOrNull() ?: return false
        return last.seq > renderedSeq
    }

    private fun buildLine(line: ConsoleLine): TextView {
        val c = this.c
        val text = if (host.themePrefs.consoleStamps) {
            val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date(line.timestamp))
            "$stamp  ${line.text}"
        } else {
            line.text
        }
        return c.txt(text, host.themePrefs.consoleFont, host.lineColor(line.type)).apply {
            typeface = android.graphics.Typeface.create(
                "monospace", android.graphics.Typeface.NORMAL,
            )
            setLineSpacing(dp(2.2f).toFloat(), 1f)
            setTextIsSelectable(true)
        }
    }

    private fun refreshEmpty() {
        emptyHint.visibility = if (logArea.childCount == 0) View.VISIBLE else View.GONE
    }

    // ── 命令输入 ───────────────────────────────────────────────────────

    private fun buildInputDock(): LinearLayout {
        val c = this.c
        return c.col().apply {
            addView(buildQuickCommands())
            val row = c.row().apply {
                background = c.rounded(
                    ShellTheme.current.surface, Dim.field,
                    ShellTheme.current.stroke, Dim.line,
                )
                setPadding(dp(12f), dp(3f), dp(4f), dp(3f))
            }
            input = c.input("输入命令，如 list / say hi").apply {
                background = null
                setPadding(0, dp(10f), 0, dp(10f))
                setOnEditorActionListener { _, _, _ ->
                    submit()
                    true
                }
            }
            row.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(
                c.txt("发送", Type.micro, ShellTheme.current.accent, medium = true).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(15f), dp(9f), dp(15f), dp(9f))
                    background = c.rip(ShellTheme.current.accentSoft, 9f)
                    tappable("发送命令") { submit() }
                },
            )
            addView(row)
        }
    }

    private fun buildQuickCommands(): LinearLayout {
        val c = this.c
        return c.row().apply {
            setPadding(0, 0, 0, dp(8f))
            listOf("list" to "玩家", "say " to "广播", "save-all" to "存档", "stop" to "停服")
                .forEach { (cmd, label) ->
                    addView(
                        c.txt(label, Type.micro, ShellTheme.current.ink2, medium = true).apply {
                            setPadding(dp(10f), dp(5f), dp(10f), dp(5f))
                            background = c.rip(
                                ShellTheme.current.surfaceAlt, Dim.pill,
                                stroke = ShellTheme.current.stroke,
                                strokeWidth = Dim.line,
                            )
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                            ).apply { marginEnd = dp(6f) }
                            tappable("快捷命令 $cmd") {
                                if (cmd.endsWith(" ")) {
                                    input.setText(cmd)
                                    input.setSelection(cmd.length)
                                    input.requestFocus()
                                } else {
                                    sendDirect(cmd)
                                }
                            }
                        },
                    )
                }
        }
    }

    private fun sendDirect(cmd: String) {
        val inst = instance
        if (inst == null || host.stateOf(inst.id) != ServerState.Running) {
            host.toast("实例没在运行，命令发不出去", error = true)
            return
        }
        host.send(cmd)
    }

    private fun submit() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        val inst = instance
        if (inst == null || host.stateOf(inst.id) != ServerState.Running) {
            host.toast("实例没在运行，命令发不出去", error = true)
            return
        }
        host.send(text)
        input.setText("")
    }

    // ── 生命周期 ───────────────────────────────────────────────────────

    override fun attach() {
        detach()
        jobs.add(scope.launch {
            host.vm.currentInstanceId.collectLatest { id ->
                instance = id?.let { host.vm.instanceStore.get(it) }
                refreshSwitcher()
                renderedSeq = Long.MIN_VALUE
                renderLines(host.vm.consoleLines.value, forceFull = true)
            }
        })
        jobs.add(scope.launch {
            host.vm.consoleLines.collectLatest { lines -> renderLines(lines) }
        })
        jobs.add(scope.launch {
            host.vm.uptimeSec.collect { sec ->
                uptimeValue.text = if (sec > 0) formatUptime(sec) else "—"
            }
        })
        jobs.add(scope.launch {
            host.vm.serverStates.collect { refreshSwitcher() }
        })
        jobs.add(scope.launch {
            host.vm.procStats.collect { renderStats(it) }
        })
        jobs.add(scope.launch {
            host.vm.onlinePlayers.collect { players -> playerValue.text = "在线 ${players.size}" }
        })

        // 首次进来没有选中实例：接上次看的，再退化到第一个
        if (host.vm.currentInstanceId.value == null) {
            val fallback = host.themePrefs.lastInstanceId
                ?.let { host.vm.instanceStore.get(it) }
                ?: host.vm.instances.value.firstOrNull()
            if (fallback != null) host.select(fallback)
        }
        instance = host.vm.currentInstanceId.value?.let { host.vm.instanceStore.get(it) }
        refreshSwitcher()
        renderLines(host.vm.consoleLines.value, forceFull = true)
    }

    override fun detach() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        // 连同 scope 一起取消，并换一个新的给下次 attach 用。
        // 只 cancel 子 job 的话 SupervisorJob 仍是 active，且旧 scope 引用不释放。
        scope.cancel()
        scope = newScope()
    }

    fun renderStates(map: Map<String, ServerState>) {
        refreshSwitcher()
    }

    private fun refreshSwitcher() {
        val inst = instance
        if (inst == null) {
            switcherValue.text = "未选择实例"
            switcherValue.setTextColor(ShellTheme.current.ink3)
            stateSlot.removeAllViews()
            actionButton.text = "启动"
            actionButton.alpha = 1f
            return
        }
        switcherValue.text = inst.name
        switcherValue.setTextColor(ShellTheme.current.ink)
        val state = host.stateOf(inst.id)
        stateSlot.removeAllViews()
        stateSlot.addView(c.statusTag(state.toLabel(), toneOf(state)))
        actionButton.text = when {
            state == ServerState.Running || state == ServerState.Stopping -> "停止"
            state.isBusy() -> "…"
            else -> "启动"
        }
        actionButton.alpha = if (state.isBusy() && state != ServerState.Stopping) 0.55f else 1f
    }

    private fun toneOf(state: ServerState): Int = when (state) {
        ServerState.Running -> ShellTheme.current.good
        ServerState.Starting, ServerState.FirstRun, ServerState.AcceptingEula, ServerState.Stopping ->
            ShellTheme.current.warn
        ServerState.Error -> ShellTheme.current.danger
        else -> ShellTheme.current.ink3
    }

    private fun toggleInstance() {
        val inst = instance
        if (inst == null) {
            pickInstance()
            return
        }
        when (host.stateOf(inst.id)) {
            ServerState.Running, ServerState.Stopping -> host.stop(inst)
            else -> host.start(inst)
        }
    }

    private fun pickInstance() {
        val list = host.vm.instances.value
        if (list.isEmpty()) {
            host.toast("还没有实例，先去首页新建")
            return
        }
        val options = list.map {
            it.name to (it.coreType.displayName + " · " + it.mcVersion.ifBlank { "自定义" })
        }
        val current = list.indexOfFirst { it.id == instance?.id }
        c.choiceSheet("切换实例", "共 ${list.size} 个", options, current) { index ->
            host.select(list[index])
        }
    }
}
