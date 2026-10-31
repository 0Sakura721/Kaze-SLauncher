package com.kaze.newage.shell

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.kaze.newage.core.download.CoreBuild
import com.kaze.newage.core.server.MemoryLimits
import com.kaze.newage.core.server.ServerState
import com.kaze.newage.data.model.CoreCategory
import com.kaze.newage.data.model.CoreType
import com.kaze.newage.data.model.GameVersion
import com.kaze.newage.data.model.ServerInstance
import com.kaze.newage.data.model.VersionType
import com.kaze.newage.ui.isBusy
import com.kaze.newage.ui.toLabel

/**
 * 首页：概览卡 + 实例清单。
 *
 * 排版顺序刻意固定为 概览卡 → 告警 → 分区标题 → 列表。
 * 概览回答"现在有没有东西在跑"，告警回答"跑着的东西有什么问题"，
 * 列表回答"我有哪些实例"。新增内容一律排在这三段之后，
 * 否则用户每次进来第一眼看到的东西都不一样。
 */
class HomeScreen(private val host: ShellHost) : ScreenRoot(host.context) {

    private val c: Context get() = context

    private lateinit var overviewGauge: GaugeView
    private lateinit var overviewTitle: TextView
    private lateinit var overviewSub: TextView
    private lateinit var overviewPrimary: TextView
    private lateinit var overviewHint: TextView
    private lateinit var alertSlot: LinearLayout
    private lateinit var listSlot: LinearLayout

    private var instances: List<ServerInstance> = emptyList()
    private var states: Map<String, ServerState> = emptyMap()

    init {
        head.addView(buildTitle())
        body.addView(buildOverview(), c.stackMargin(top = 12f))
        alertSlot = c.col()
        body.addView(alertSlot, c.stackMargin(top = 4f))
        body.addView(
            c.sectionHeader("实例", "新建") { openCreate() },
            c.stackMargin(top = 18f, bottom = 7f),
        )
        listSlot = c.col()
        body.addView(listSlot)
        body.addView(
            c.txt(
                "点卡片进控制台 · 长按置顶或删除 · 运行中的实例由前台服务保活",
                Type.micro, ShellTheme.current.ink3,
            ).apply {
                gravity = Gravity.CENTER
                setPadding(dp(24f), dp(18f), dp(24f), dp(4f))
            },
        )
    }

    private fun buildTitle(): LinearLayout = c.row().apply {
        val texts = c.col().apply {
            addView(c.txt("岚", Type.display, ShellTheme.current.ink, medium = true))
            addView(
                c.txt(host.deviceSummary(), Type.micro, ShellTheme.current.ink3).apply {
                    setPadding(0, dp(4f), 0, 0)
                },
            )
        }
        addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(
            c.txt("＋", 21f, ShellTheme.current.accent, medium = true).apply {
                gravity = Gravity.CENTER
                background = c.rip(
                    ShellTheme.current.accentSoft, 12f,
                    stroke = ShellTheme.current.accent.alpha(0.26f),
                    strokeWidth = Dim.line,
                )
                tappable("新建实例") { openCreate() }
            },
            LinearLayout.LayoutParams(dp(38f), dp(38f)),
        )
    }

    private fun buildOverview(): LinearLayout = c.card(pad = 13f).apply {
        val row = c.row()
        overviewGauge = GaugeView(c).apply { onToggle = { toggleFocused() } }
        row.addView(overviewGauge, LinearLayout.LayoutParams(dp(94f), dp(94f)))

        val texts = c.col().apply {
            setPadding(dp(12f), 0, dp(4f), 0)
            overviewTitle = c.txt("还没有实例", Type.title, ShellTheme.current.ink, medium = true)
            addView(overviewTitle)
            // 这里是"实例名 · 版本"这类中英混排：用等宽字体会让汉字被拉成方块格子，
            // 等宽只留给纯数字（CPU、内存、端口）。
            //
            // 不用 MIDDLE 省略：中段省略会把"服务端跑在本…proot 环境里"这种
            // 前半段+后半段拼在一起，读起来像两句话被吞掉中间，比直接截尾更费解。
            // 最多两行，超出截尾 —— 小屏（720×1280）上一行放不下这句。
            overviewSub = c.txt("—", 11.5f, ShellTheme.current.ink3).apply {
                setPadding(0, dp(5f), 0, 0)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
            addView(overviewSub)
            overviewHint = c.txt("点右上角 ＋ 新建", Type.micro, ShellTheme.current.ink3).apply {
                setPadding(0, dp(7f), 0, 0)
                // 只限行数不给省略号的话，第三行会被硬切，读起来像句子断了
                //（同一块的 overviewSub 就是对的：maxLines + TruncateAt.END）。
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
            addView(overviewHint)
        }
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        overviewPrimary = c.txt("启动", Type.micro, ShellTheme.current.accent, medium = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(13f), dp(9f), dp(13f), dp(9f))
            // 单行且不参与压缩：两字按钮被挤到只剩一个字宽时会竖排（真机实测过），
            // 给一个下限宽度，宁可让文字列少几像素。
            isSingleLine = true
            minWidth = dp(56f)
            background = c.rip(
                ShellTheme.current.accentSoft, 11f,
                stroke = ShellTheme.current.accent.alpha(0.28f),
                strokeWidth = Dim.line,
            )
            tappable("启动或停止当前实例") { toggleFocused() }
        }
        row.addView(overviewPrimary)
        addView(row)
    }

    // ── 数据回填 ───────────────────────────────────────────────────────

    fun renderInstances(list: List<ServerInstance>) {
        instances = list
        renderOverview()
        renderList()
    }

    fun renderStates(map: Map<String, ServerState>) {
        states = map
        renderOverview()
        renderList()
    }

    private fun stateOf(instance: ServerInstance): ServerState =
        states[instance.id] ?: ServerState.Idle

    /** 焦点实例：上次看的 → 运行中的 → 第一个 */
    private fun focused(): ServerInstance? {
        val last = host.themePrefs.lastInstanceId
        return instances.firstOrNull { it.id == last }
            ?: instances.firstOrNull { stateOf(it) == ServerState.Running }
            ?: instances.firstOrNull()
    }

    private fun toggleFocused() {
        val target = focused() ?: run { openCreate(); return }
        when (val state = stateOf(target)) {
            ServerState.Running, ServerState.Stopping -> host.stop(target)
            else -> if (state.isBusy()) host.toast("正在${state.toLabel()}，请稍候") else host.start(target)
        }
    }

    private fun renderOverview() {
        val running = instances.count { stateOf(it) == ServerState.Running }
        val focus = focused()
        overviewGauge.active = running > 0
        overviewGauge.progress = if (running > 0) 0.82f else 0.1f

        when {
            focus == null -> {
                overviewTitle.text = "还没有实例"
                overviewSub.text = "服务端跑在本地 proot 环境里"
                overviewHint.text = "点右上角 ＋ 选择核心与版本"
                overviewPrimary.text = "新建"
            }            running > 0 -> {
                overviewTitle.text = "运行中"
                overviewSub.text = "${focus.name} · ${focus.mcVersion.ifBlank { "自定义" }}"
                overviewHint.text = "${running} / ${instances.size} 个实例在跑 · 内存 ${focus.memoryMb} MB"
                overviewPrimary.text = "停止"
            }
            else -> {
                overviewTitle.text = "全部停止"
                overviewSub.text = "${instances.size} 个实例待命"
                overviewHint.text = "点圆盘或右侧按钮启动最近使用的实例"
                overviewPrimary.text = "启动"
            }
        }
        val tone = if (running > 0) ShellTheme.current.accent else ShellTheme.current.ink2
        overviewPrimary.setTextColor(tone)
        overviewPrimary.background = c.rip(
            if (running > 0) ShellTheme.current.accentSoft else ShellTheme.current.surfaceAlt,
            11f,
            stroke = tone.alpha(0.26f),
            strokeWidth = Dim.line,
        )
    }

    private fun renderList() {
        renderAlerts()
        listSlot.removeAllViews()
        if (instances.isEmpty()) {
            listSlot.addView(
                c.emptyState(
                    "还没有服务端实例",
                    "选核心与版本，应用会下载 jar、建目录、配好 server.properties",
                    "新建实例",
                ) { openCreate() },
                c.stackMargin(),
            )
            return
        }
        instances.forEach { instance ->
            listSlot.addView(buildCard(instance), c.stackMargin(bottom = 9f))
        }
    }

    private fun renderAlerts() {
        alertSlot.removeAllViews()
        if (!host.vm.env.isReady) {
            alertSlot.addView(
                c.banner(
                    "Linux 环境未部署。启动实例时会自动下载环境，也可以在设置里先部署。",
                    ShellTheme.current.warn,
                    "部署",
                ) {
                    host.toast("开始部署环境…")
                    host.setupEnvironment { host.toast("环境已就绪") }
                },
                c.stackMargin(top = 8f),
            )
        }
        val failed = instances.firstOrNull { stateOf(it) == ServerState.Error }
        if (failed != null) {
            alertSlot.addView(
                c.banner(
                    "「${failed.name}」启动失败，进控制台看具体原因。",
                    ShellTheme.current.danger,
                    "查看",
                ) { host.openConsoleAction(failed) },
                c.stackMargin(top = 8f),
            )
        }
    }

    private fun buildCard(instance: ServerInstance): LinearLayout {
        val state = stateOf(instance)
        val running = state == ServerState.Running
        val busy = state.isBusy()
        val tone = toneOf(state)

        val card = c.bareCard()
        val row = c.row().apply {
            setPadding(dp(12f), dp(13f), dp(13f), dp(13f))

            addView(
                EdgeStripe(c).apply {
                    this.tone = when {
                        running -> 2
                        state == ServerState.Error -> 4
                        busy -> 3
                        else -> 0
                    }
                },
                LinearLayout.LayoutParams(dp(2.6f), dp(38f)).apply {
                    marginEnd = dp(11f)
                    gravity = Gravity.CENTER_VERTICAL
                },
            )

            addView(c.tileBadge(initialOf(instance.name), tone, 34f))

            val meta = c.col().apply {
                setPadding(dp(10f), 0, dp(6f), 0)
                val nameRow = c.row()
                nameRow.addView(
                    c.txt(instance.name, Type.subtitle, ShellTheme.current.ink, medium = true).apply {
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    },
                )
                if (instance.pinned) {
                    nameRow.addView(
                        c.txt("置顶", Type.micro, ShellTheme.current.accent, medium = true).apply {
                            setPadding(dp(6f), 0, 0, 0)
                        },
                    )
                }
                addView(nameRow)
                // 状态胶囊放在**名称列内部**的第二行，与副标题同排。
                // 之前它和名称列并排占行宽：胶囊按内容自然宽（"首次启动 · 生成
                // eula.txt" 要 129px），LinearLayout 先量完所有非 weight 子项，
                // 剩给名称列的只有十几像素 —— 名称折三行、副标题逐字竖排，
                // 320dp 屏实测名称区宽度为 0。挪进来之后胶囊只和副标题争，
                // 副标题本身有 2 行上限，最坏情况是它省略，名称永远完整。
                val subRow = c.row()
                // 副标题与胶囊按 weight 分配剩余宽度（3:2）。
                // 不给 weight 的话，胶囊按自然宽先量完，副标题的 weight 列只剩
                // 被压剩的零头（320dp 屏实测副标题宽 0）。两个都用 weight 之后
                // 宽度按比例切，双方都有下限保障；内容超长各自省略。
                subRow.addView(
                    c.txt(
                        "${instance.coreType.displayName} · ${instance.mcVersion.ifBlank { "自定义" }}" +
                            if (running) " · ${instance.memoryMb} MB" else "",
                        Type.micro, ShellTheme.current.ink3,
                    ).apply {
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 3f),
                )
                subRow.addView(
                    c.statusTag(state.toLabel(), tone),
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f).apply {
                        marginStart = dp(6f)
                    },
                )
                addView(subRow, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(4f) })
            }
            // 名称列：weight=1 分到"剩余宽度"，剩余量 = 行宽 - 同行其它所有固定宽。
            // 注意 minimumWidth 在这里**无效** —— LinearLayout 分配权重时直接
            // 用剩余量，不看 minimumWidth（实测 320dp 屏仍是 10px）。所以真正的
            // 保障是把同行的状态胶囊挪到别处（见下面），让名称列至少有半行可用。
            addView(meta, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            addView(
                buildActionButton(instance, state, busy, running),
                LinearLayout.LayoutParams(dp(64f), dp(33f)),
            )
        }
        card.addView(row)

        card.isClickable = true
        card.setOnClickListener { host.openConsoleAction(instance) }
        card.setOnLongClickListener {
            openInstanceMenu(instance)
            true
        }
        return card
    }

    private fun initialOf(name: String): String =
        name.trim().take(1).ifBlank { "?" }.uppercase()

    private fun buildActionButton(
        instance: ServerInstance,
        state: ServerState,
        busy: Boolean,
        running: Boolean,
    ): TextView {
        val label: String
        val tone: Int
        when {
            state == ServerState.Stopping -> {
                label = "强制"; tone = ShellTheme.current.danger
            }
            running -> {
                label = "停止"; tone = ShellTheme.current.ink2
            }
            busy -> {
                label = "…"; tone = ShellTheme.current.ink3
            }
            else -> {
                label = "启动"; tone = ShellTheme.current.accent
            }
        }
        return c.txt(label, Type.micro, tone, medium = true).apply {
            gravity = Gravity.CENTER
            background = c.rip(
                tone.alpha(if (ShellTheme.current.dark) 0.18f else 0.10f),
                9f,
                stroke = tone.alpha(0.32f),
                strokeWidth = Dim.line,
            )
            if (busy && state != ServerState.Stopping) {
                alpha = 0.55f
            } else {
                tappable(if (running) "停止 ${instance.name}" else "启动 ${instance.name}") {
                    if (running || state == ServerState.Stopping) host.stop(instance)
                    else host.start(instance)
                }
            }
        }
    }

    private fun toneOf(state: ServerState): Int = when (state) {
        ServerState.Running -> ShellTheme.current.good
        ServerState.Starting, ServerState.FirstRun, ServerState.AcceptingEula, ServerState.Stopping ->
            ShellTheme.current.warn
        ServerState.Error -> ShellTheme.current.danger
        else -> ShellTheme.current.ink3
    }

    // ── 长按菜单 ───────────────────────────────────────────────────────

    private fun openInstanceMenu(instance: ServerInstance) {
        val state = stateOf(instance)
        val running = state == ServerState.Running
        val sheet = Sheet(
            c,
            instance.name,
            "${instance.coreType.displayName} · ${instance.mcVersion.ifBlank { "自定义" }} · ${state.toLabel()}",
        )
        sheet.add(c.settingRow("打开控制台", "实时日志、发送命令、CPU 与内存占用") {
            sheet.dismiss()
            host.openConsoleAction(instance)
        })
        sheet.add(c.splitter())
        sheet.add(c.settingRow(if (running) "重启" else "启动", "重启会先优雅停服再拉起") {
            sheet.dismiss()
            if (running) host.restart(instance) else host.start(instance)
        })
        if (state == ServerState.Stopping) {
            sheet.add(c.splitter())
            sheet.add(c.settingRow("强制停止", "世界可能来不及保存", c.chevron(ShellTheme.current.danger)) {
                sheet.dismiss()
                host.stop(instance)
            })
        }
        sheet.add(c.splitter())
        sheet.add(c.settingRow(if (instance.pinned) "取消置顶" else "置顶", "置顶的实例排在最前") {
            sheet.dismiss()
            host.vm.setInstancePinned(instance, !instance.pinned)
        })
        sheet.add(c.splitter())
        sheet.add(c.settingRow("删除实例", "世界与目录保留在磁盘上", c.chevron(ShellTheme.current.danger)) {
            sheet.dismiss()
            confirmDelete(instance)
        })
        sheet.footerText("关闭", ShellTheme.current.ink3) { sheet.dismiss() }
        sheet.show()
    }

    private fun confirmDelete(instance: ServerInstance) {
        val sheet = Sheet(c, "删除「${instance.name}」", scrollable = false)
        sheet.add(
            c.txt(
                "只移除应用里的实例记录；目录 ${instance.dir.name} 与世界存档仍在磁盘上，" +
                    "可以重新导入。此动作不可撤销。",
                Type.body, ShellTheme.current.ink2,
            ).apply { setLineSpacing(dp(3f).toFloat(), 1f) },
        )
        sheet.buttons(
            "删除",
            {
                sheet.dismiss()
                host.remove(instance)
                host.toast("已删除 ${instance.name}")
            },
            secondaryLabel = "取消",
            onSecondary = { sheet.dismiss() },
            danger = true,
        )
        sheet.show()
    }

    // ── 新建向导 ───────────────────────────────────────────────────────

    /**
     * 打开新建向导。
     *
     * 委托给 host：服务端页也要开同一个向导，两处各写一份必然分叉
     *（一边记得检查环境就绪、另一边忘了）。真正的实现在 ShellActivity.showCreateSheet。
     */
    private fun openCreate() = host.openCreate()
}

/**
 * 新建实例向导：核心 → 版本 → 名称 → 内存 → 下载。
 *
 * 收在一个弹层里而不是分四页：这个流程每次顺序固定、参数互不依赖，
 * 分页只会把"改上一项"变成一次返回操作。
 *
 * 状态管理只有三件事：选了什么（coreType/mcVersion/buildId）、
 * 界面上要跟着变的三个控件、以及一个下载进度回调。
 */
/**
 * 新建实例向导。
 *
 * 从 private 提到 internal：首页与服务端页都要能打开它（由 ShellActivity 统一
 * 提供入口，见 showCreateSheet）。两处各建一份会导致行为分叉 —— 例如一边记得
 * 检查环境是否就绪、另一边忘了。
 */
internal class CreateWizard(
    context: Context,
    private val host: ShellHost,
) : Sheet(context, "新建服务端", "选核心与版本，应用会下载 jar 并建目录") {

    private val c = context
    private val vm = host.vm

    private var coreType: CoreType = CoreType.PAPER
    private var mcVersion: String = ""
    private var buildId: String = ""
    private var builds: List<CoreBuild> = emptyList()
    private var memoryMb: Int = 2048
    private var busy = false

    private lateinit var coreGrid: LinearLayout
    private lateinit var versionValue: TextView
    private lateinit var buildRow: LinearLayout
    private lateinit var buildValue: TextView
    private lateinit var nameInput: EditText
    private lateinit var memoryValue: TextView
    private lateinit var memorySlider: SliderBar
    private lateinit var status: TextView
    private lateinit var progress: RibbonProgress

    private val maxMem: Int = run {
        runCatching {
            val info = android.app.ActivityManager.MemoryInfo()
            (c.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager)
                .getMemoryInfo(info)
            val totalMb = (info.totalMem / 1024L / 1024L).toInt()
            (totalMb / 2).coerceIn(MemoryLimits.AUTO_MIN_MB, MemoryLimits.MAX_MB)
        }.getOrDefault(MemoryLimits.MAX_MB)
    }

    init {
        label("服务端核心")
        coreGrid = c.col()
        add(coreGrid)
        renderCores()

        label("游戏版本")
        add(
            c.row().apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                background = c.rounded(
                    ShellTheme.current.surfaceAlt, Dim.field,
                    ShellTheme.current.stroke, Dim.line,
                )
                setPadding(c.dp(13f), c.dp(11f), c.dp(12f), c.dp(11f))
                addView(c.txt("目标版本", Type.body, ShellTheme.current.ink))
                addView(View(c), LinearLayout.LayoutParams(0, 1, 1f))
                versionValue = c.mono("未选择", Type.body, ShellTheme.current.ink3)
                addView(versionValue)
                addView(
                    c.chevron(),
                    LinearLayout.LayoutParams(c.dp(18f), c.dp(18f)).apply { marginStart = c.dp(3f) },
                )
                tappable("选择游戏版本") { pickVersion() }
            },
        )

        buildRow = c.row().apply {
            background = c.rounded(
                ShellTheme.current.surfaceAlt, Dim.field,
                ShellTheme.current.stroke, Dim.line,
            )
            setPadding(c.dp(13f), c.dp(11f), c.dp(12f), c.dp(11f))
            addView(c.txt("构建", Type.body, ShellTheme.current.ink))
            addView(View(c), LinearLayout.LayoutParams(0, 1, 1f))
            buildValue = c.mono("最新", Type.body, ShellTheme.current.ink3)
            addView(buildValue)
            addView(
                c.chevron(),
                LinearLayout.LayoutParams(c.dp(18f), c.dp(18f)).apply { marginStart = c.dp(3f) },
            )
            visibility = View.GONE
            tappable("选择构建") { pickBuild() }
        }
        add(buildRow)

        label("实例名称")
        nameInput = c.input("留空则用「核心-版本」命名")
        add(nameInput)

        label("内存上限")
        memoryValue = c.mono("", Type.title, ShellTheme.current.ink)
        memoryMb = MemoryLimits.clamp(MemoryLimits.suggestFrom(maxMem.toFloat()).toInt())
        memoryValue.text = "$memoryMb MB"
        add(memoryValue)
        memorySlider = SliderBar(c).apply {
            min = MemoryLimits.MIN_MB.toFloat()
            max = maxMem.toFloat()
            step = MemoryLimits.STEP_MB.toFloat()
            value = memoryMb.toFloat()
            onValueChange = { v ->
                memoryMb = MemoryLimits.clamp(v.toInt())
                memoryValue.text = "$memoryMb MB"
            }
        }
        add(memorySlider)
        note("设备可分配给单个实例的上限约 $maxMem MB。分配偏小会在生成区块时卡顿，偏大会挤占系统。")

        status = c.txt("", Type.micro, ShellTheme.current.ink3).apply {
            setPadding(0, c.dp(12f), 0, 0)
            visibility = View.GONE
        }
        add(status)
        progress = RibbonProgress(c).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, c.dp(5f),
            ).apply { topMargin = c.dp(8f) }
            visibility = View.GONE
        }
        add(progress)

        buttons("下载并创建", { startDownload() }, secondaryLabel = "取消", onSecondary = { dismiss() })
    }

    // ── 核心选择 ───────────────────────────────────────────────────────

    private fun renderCores() {
        coreGrid.removeAllViews()
        CoreCategory.entries.forEach { category ->
            val types = CoreType.entries.filter { it.category == category }
            if (types.isEmpty()) return@forEach
            coreGrid.addView(
                c.micro(category.displayName).apply { setPadding(0, c.dp(10f), 0, c.dp(4f)) },
            )
            types.chunked(2).forEach { pair ->
                val rowView = c.row()
                pair.forEach { type -> rowView.addView(coreChip(type)) }
                if (pair.size == 1) {
                    rowView.addView(View(c), LinearLayout.LayoutParams(0, 1, 1f))
                }
                coreGrid.addView(rowView)
            }
        }
    }

    private fun coreChip(type: CoreType): TextView {
        val active = type == coreType
        return c.txt(
            type.displayName,
            Type.micro,
            if (active) ShellTheme.current.accent else ShellTheme.current.ink2,
            medium = active,
        ).apply {
            gravity = Gravity.CENTER
            setPadding(c.dp(6f), c.dp(10f), c.dp(6f), c.dp(10f))
            background = c.rounded(
                if (active) ShellTheme.current.accentSoft else ShellTheme.current.surfaceAlt,
                Dim.field,
                if (active) ShellTheme.current.accent.alpha(0.42f) else ShellTheme.current.stroke,
                Dim.line,
            )
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = c.dp(6f)
                bottomMargin = c.dp(6f)
            }
            tappable(type.displayName) {
                if (busy) return@tappable
                coreType = type
                mcVersion = ""
                buildId = ""
                builds = emptyList()
                versionValue.text = "未选择"
                versionValue.setTextColor(ShellTheme.current.ink3)
                buildValue.text = "最新"
                buildRow.visibility = View.GONE
                renderCores()
            }
        }
    }

    // ── 版本 / 构建 ────────────────────────────────────────────────────

    private fun pickVersion() {
        if (busy) return
        if (coreType == CoreType.CUSTOM) {
            host.toast("「导入 jar」不通过下载创建，请在设置里用导入功能")
            return
        }
        vm.loadVersions(coreType)
        val cached = vm.versions.value
        if (cached.isNotEmpty()) {
            showVersionPicker(cached)
            return
        }
        // 首次拉取是网络请求：给一个可见的等待态，而不是让点击静默失败
        setStatus("正在拉取 ${coreType.displayName} 的版本列表…", ShellTheme.current.ink3)
        progress.visibility = View.VISIBLE
        progress.indeterminate = true
        host.awaitVersions { list ->
            progress.visibility = View.GONE
            clearStatus()
            if (list.isEmpty()) {
                host.toast("没取到版本列表，检查网络后重试", error = true)
                return@awaitVersions
            }
            showVersionPicker(list)
        }
    }

    private fun showVersionPicker(list: List<GameVersion>) {
        val options = list.take(150).map { v ->
            v.id to when (v.type) {
                VersionType.SNAPSHOT -> "快照版"
                VersionType.RELEASE -> "正式版"
                VersionType.OLD_BETA -> "远古测试版"
                VersionType.OLD_ALPHA -> "远古预览版"
            }
        }
        c.choiceSheet(
            "选择版本",
            "${coreType.displayName} · 共 ${list.size} 个",
            options,
            options.indexOfFirst { it.first == mcVersion },
        ) { index ->
            mcVersion = options[index].first
            versionValue.text = mcVersion
            versionValue.setTextColor(ShellTheme.current.ink)
            buildId = ""
            buildValue.text = "最新"
            buildValue.setTextColor(ShellTheme.current.ink3)
            if (coreType == CoreType.PAPER || coreType == CoreType.FABRIC) {
                loadBuilds()
            } else {
                buildRow.visibility = View.GONE
            }
        }
    }

    private fun loadBuilds() {
        vm.loadBuilds(coreType, mcVersion)
        host.awaitBuilds { list ->
            builds = list
            buildRow.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun pickBuild() {
        if (busy) return
        if (builds.isEmpty()) {
            host.toast("该核心没有可选构建，将取最新")
            return
        }
        val options = builds.map { it.name to (it.fileName ?: "") }
        c.choiceSheet(
            "选择构建",
            "$mcVersion · 共 ${builds.size} 个",
            options,
            options.indexOfFirst { it.first == buildId },
        ) { index ->
            buildId = options[index].first
            buildValue.text = buildId
            buildValue.setTextColor(ShellTheme.current.ink)
        }
    }

    // ── 下载 ───────────────────────────────────────────────────────────

    private fun startDownload() {
        if (busy) return
        if (mcVersion.isBlank()) {
            host.toast("先选一个游戏版本", error = true)
            return
        }
        if (vm.download.value.running) {
            host.toast("已有下载在进行中", error = true)
            return
        }
        val name = nameInput.text.toString().trim().ifBlank {
            "${coreType.displayName}-$mcVersion"
        }

        busy = true
        setPrimaryBusy(true, "下载中…")
        progress.visibility = View.VISIBLE
        progress.indeterminate = true
        progress.fraction = 0f
        setStatus("解析下载地址…", ShellTheme.current.ink3)

        vm.downloadAndCreate(
            name = name,
            type = coreType,
            mcVersion = mcVersion,
            memoryMb = memoryMb,
            buildId = buildId,
        ) { instance ->
            busy = false
            progress.indeterminate = false
            if (instance == null) {
                val err = vm.download.value.error ?: "创建失败"
                setStatus(err, ShellTheme.current.danger)
                progress.fraction = 0f
                setPrimaryBusy(false, "重试下载")
            } else {
                progress.fraction = 1f
                setStatus("创建完成：${instance.name}", ShellTheme.current.good)
                host.select(instance)
                host.toast("已创建 ${instance.name}")
                dismiss()
                host.openConsoleAction(instance)
            }
        }

        // 订阅下载进度。返回的 Job 必须随弹层一起结束：vm.download 是 StateFlow，
        // collect 永不返回，不取消就会在每次打开向导后永久留下一个订阅者，
        // 且它捕获着已关闭的弹层与整棵视图树（见 ShellHost.watchDownload 的说明）。
        downloadJob?.cancel()
        downloadJob = host.watchDownload { st ->
            if (st.progress > 0f) {
                progress.indeterminate = false
                progress.fraction = st.progress
            }
            if (st.message.isNotBlank()) setStatus(st.message, ShellTheme.current.ink3)
            if (st.error != null) {
                busy = false
                progress.indeterminate = false
                setStatus(st.error, ShellTheme.current.danger)
                setPrimaryBusy(false, "重试下载")
            }
        }
    }

    /** 下载进度订阅；弹层关闭时取消，避免泄漏 */
    private var downloadJob: kotlinx.coroutines.Job? = null

    override fun dismiss() {
        downloadJob?.cancel()
        downloadJob = null
        super.dismiss()
    }

    private fun setStatus(text: String, color: Int) {
        status.visibility = View.VISIBLE
        status.text = text
        status.setTextColor(color)
    }

    private fun clearStatus() {
        status.visibility = View.GONE
    }
}
