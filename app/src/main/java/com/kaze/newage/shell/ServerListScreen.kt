package com.kaze.newage.shell

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.kaze.newage.core.server.ServerState
import com.kaze.newage.data.model.CoreCategory
import com.kaze.newage.data.model.CoreType
import com.kaze.newage.data.model.ServerInstance
import com.kaze.newage.ui.AppViewModel.ImportJarProgress
import com.kaze.newage.ui.isBusy
import com.kaze.newage.ui.toLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 服务端页：多实例列表与管理。
 *
 * 与 M3 的 ServerScreen 对齐的能力：分段筛选（全部/官方/性能/模组，带计数）、
 * 导入 jar、批量启停、置顶排序、点进控制台。
 *
 * 与 M3 的差异（有意为之）：
 *  - M3 把「新建 / 导入」放成一排相连按钮，这里放在标题右侧与底部；
 *    自绘的圆角按钮排成相连组需要额外的 shape 计算，收益不抵成本。
 *  - 批量启停做成一个**只对当前筛选结果生效**的条：M3 也是这样，但这里
 *    把"会影响几个"直接写在按钮上（"全部启动 3"），避免误触。
 */
class ServerListScreen(private val host: ShellHost) : ScreenRoot(host.context) {

    private val c: Context get() = context

    /** 当前筛选：null = 全部 */
    private var filter: CoreCategory? = null

    private var instances: List<ServerInstance> = emptyList()
    private var states: Map<String, ServerState> = emptyMap()

    private lateinit var countRow: LinearLayout
    private lateinit var listSlot: LinearLayout
    private lateinit var batchBar: LinearLayout
    private lateinit var importProgress: RibbonProgress
    private lateinit var importLabel: TextView

    init {
        head.addView(buildTitle())
        countRow = c.row()
        body.addView(countRow, c.stackMargin(top = 10f))
        // 分隔线必须**保留**自己的 1px 高度：stackMargin 会把高度改写成
        // WRAP_CONTENT，而裸 View 在 WRAP_CONTENT 下会吃掉父容器剩余空间，
        // 结果这条线被拉成几百像素高的灰板（真机截图里那块就是它）。
        body.addView(
            c.hairline(0f, 0f),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, c.dp(1f),
            ).apply { topMargin = c.dp(10f) },
        )

        batchBar = c.col()
        body.addView(batchBar, c.stackMargin(top = 10f))

        listSlot = c.col()
        body.addView(listSlot, c.stackMargin(top = 10f))

        importProgress = RibbonProgress(c).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, c.dp(4f),
            )
            visibility = View.GONE
        }
        importLabel = c.txt("", Type.micro, ShellTheme.current.ink3).apply {
            visibility = View.GONE
        }
        body.addView(importLabel, c.stackMargin(top = 10f))
        body.addView(importProgress, c.stackMargin(top = 4f))
    }

    /** 导入进度的订阅（随屏存活，attach/detach 里管） */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var importJob: Job? = null

    private fun buildTitle(): LinearLayout = c.row().apply {
        val texts = c.col().apply {
            addView(c.txt("服务端", Type.display, ShellTheme.current.ink, medium = true))
            addView(
                c.txt("多开 · 筛选 · 导入", Type.micro, ShellTheme.current.ink3).apply {
                    setPadding(0, dp(4f), 0, 0)
                },
            )
        }
        addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(
            c.txt("导入", Type.micro, ShellTheme.current.ink2, medium = true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(13f), dp(9f), dp(13f), dp(9f))
                background = c.rip(
                    ShellTheme.current.surface, 11f,
                    stroke = ShellTheme.current.strokeStrong,
                    strokeWidth = Dim.line,
                )
                tappable("导入已有 jar") { host.pickJar() }
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(34f),
            ).apply { marginEnd = dp(8f) },
        )
        addView(
            c.txt("＋", 20f, ShellTheme.current.accent, medium = true).apply {
                gravity = Gravity.CENTER
                background = c.rip(
                    ShellTheme.current.accentSoft, 11f,
                    stroke = ShellTheme.current.accent.alpha(0.26f),
                    strokeWidth = Dim.line,
                )
                tappable("新建服务端") { host.openCreate() }
            },
            LinearLayout.LayoutParams(dp(34f), dp(34f)),
        )
    }

    // ── 数据 ───────────────────────────────────────────────────────────

    fun renderInstances(list: List<ServerInstance>) {
        instances = list
        render()
    }

    fun renderStates(map: Map<String, ServerState>) {
        states = map
        render()
    }

    private fun stateOf(inst: ServerInstance): ServerState = states[inst.id] ?: ServerState.Idle

    /** 当前筛选结果。置顶优先，组内保持原顺序（sortedByDescending 稳定） */
    private fun filtered(): List<ServerInstance> {
        val f = filter
        val base = if (f == null) instances else instances.filter { it.coreType.category == f }
        return base.sortedByDescending { it.pinned }
    }

    private fun render() {
        renderFilters()
        renderBatchBar()
        renderList()
    }

    private fun renderFilters() {
        countRow.removeAllViews()
        // 分段：全部 + 四个分类。计数取自当前实例集，不是筛选结果。
        val segments = listOf<Pair<String, CoreCategory?>>(
            "全部" to null,
            CoreCategory.OFFICIAL.displayName to CoreCategory.OFFICIAL,
            CoreCategory.OPTIMIZED.displayName to CoreCategory.OPTIMIZED,
            CoreCategory.MODDED.displayName to CoreCategory.MODDED,
        )
        val wrap = c.row().apply {
            background = c.rounded(
                ShellTheme.current.surfaceSunken, Dim.field,
                ShellTheme.current.stroke, Dim.line,
            )
            setPadding(dp(3f), dp(3f), dp(3f), dp(3f))
        }
        segments.forEach { (label, cat) ->
            val active = filter == cat
            val count = if (cat == null) instances.size
            else instances.count { it.coreType.category == cat }
            wrap.addView(
                c.txt("$label $count", Type.micro, if (active) ShellTheme.current.ink else ShellTheme.current.ink3, medium = active)
                    .apply {
                        gravity = Gravity.CENTER
                        setPadding(0, dp(8f), 0, dp(8f))
                        background = if (active) {
                            c.rounded(
                                ShellTheme.current.surface, Dim.field - 2f,
                                ShellTheme.current.strokeStrong, Dim.line,
                            )
                        } else null
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        tappable(label) {
                            filter = cat
                            render()
                        }
                    },
            )
        }
        countRow.addView(wrap, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ))
    }

    private fun renderBatchBar() {
        batchBar.removeAllViews()
        val list = filtered()
        if (list.isEmpty()) return

        val startable = list.filter {
            val s = stateOf(it)
            s != ServerState.Running && !s.isBusy()
        }
        val stoppable = list.filter { stateOf(it) == ServerState.Running }
        if (startable.isEmpty() && stoppable.isEmpty()) return

        val row = c.row()
        if (startable.isNotEmpty()) {
            row.addView(
                c.txt("全部启动 ${startable.size}", Type.micro, ShellTheme.current.accent, medium = true)
                    .apply {
                        gravity = Gravity.CENTER
                        setPadding(0, dp(10f), 0, dp(10f))
                        background = c.rip(
                            ShellTheme.current.accentSoft, Dim.field,
                            stroke = ShellTheme.current.accent.alpha(0.28f),
                            strokeWidth = Dim.line,
                        )
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        tappable("启动当前筛选的 ${startable.size} 个实例") {
                            // 串行启动：多实例同时冷启会一起抢 CPU 与磁盘，
                            // 在手机上表现为每个都慢、还可能超时。逐个来。
                            host.startSequentially(startable)
                        }
                    },
            )
        }
        if (startable.isNotEmpty() && stoppable.isNotEmpty()) {
            row.addView(View(c), LinearLayout.LayoutParams(dp(8f), 1))
        }
        if (stoppable.isNotEmpty()) {
            row.addView(
                c.txt("全部停止 ${stoppable.size}", Type.micro, ShellTheme.current.ink2, medium = true)
                    .apply {
                        gravity = Gravity.CENTER
                        setPadding(0, dp(10f), 0, dp(10f))
                        background = c.rip(
                            ShellTheme.current.surface, Dim.field,
                            stroke = ShellTheme.current.strokeStrong,
                            strokeWidth = Dim.line,
                        )
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        tappable("停止当前筛选的 ${stoppable.size} 个实例") {
                            stoppable.forEach { host.stop(it) }
                        }
                    },
            )
        }
        batchBar.addView(row)
    }

    private fun renderList() {
        listSlot.removeAllViews()
        val list = filtered()
        if (list.isEmpty()) {
            listSlot.addView(
                c.emptyState(
                    if (instances.isEmpty()) "还没有服务端实例" else "这个分类下没有实例",
                    if (instances.isEmpty()) "选核心与版本下载，或导入已有的 server.jar"
                    else "换一个筛选条件试试",
                    if (instances.isEmpty()) "新建实例" else null,
                ) { if (instances.isEmpty()) host.openCreate() },
                c.stackMargin(),
            )
            return
        }
        list.forEach { inst ->
            listSlot.addView(buildRow(inst), c.stackMargin(bottom = 8f))
        }
    }

    private fun buildRow(inst: ServerInstance): LinearLayout {
        val state = stateOf(inst)
        val running = state == ServerState.Running
        val tone = toneOf(state)

        return c.bareCard().apply {
            addView(c.row().apply {
                setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
                addView(
                    EdgeStripe(c).apply {
                        this.tone = when {
                            running -> 2
                            state == ServerState.Error -> 4
                            state.isBusy() -> 3
                            else -> 0
                        }
                    },
                    LinearLayout.LayoutParams(dp(2.6f), dp(36f)).apply {
                        marginEnd = dp(10f)
                        gravity = Gravity.CENTER_VERTICAL
                    },
                )
                addView(c.tileBadge(inst.name.take(1).uppercase(), tone, 32f))
                addView(
                    c.col().apply {
                        setPadding(dp(9f), 0, dp(6f), 0)
                        val nameRow = c.row()
                        nameRow.addView(
                            c.txt(inst.name, Type.subtitle, ShellTheme.current.ink, medium = true).apply {
                                maxLines = 1
                                ellipsize = TextUtils.TruncateAt.END
                            },
                        )
                        if (inst.pinned) {
                            nameRow.addView(
                                c.txt("置顶", Type.micro, ShellTheme.current.accent, medium = true).apply {
                                    setPadding(dp(6f), 0, 0, 0)
                                },
                            )
                        }
                        addView(nameRow)
                        addView(
                            c.txt(
                                "${inst.coreType.displayName} · ${inst.mcVersion.ifBlank { "自定义" }}" +
                                    if (running) " · ${inst.memoryMb} MB" else "",
                                Type.micro, ShellTheme.current.ink3,
                            ).apply { setPadding(0, dp(3f), 0, 0) },
                        )
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(
                    c.statusTag(state.toLabel(), tone),
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { marginEnd = dp(8f) },
                )
                // 「⋯」菜单：置顶/改名/删除都收在这里，避免行内堆太多按钮
                addView(
                    c.txt("⋯", 16f, ShellTheme.current.ink3, medium = true).apply {
                        gravity = Gravity.CENTER
                        background = c.rip(ShellTheme.current.surfaceAlt, 9f)
                        tappable("${inst.name} 的更多操作") { openMenu(inst) }
                    },
                    LinearLayout.LayoutParams(dp(30f), dp(30f)),
                )
            })
            isClickable = true
            setOnClickListener { host.openConsoleAction(inst) }
        }
    }

    private fun openMenu(inst: ServerInstance) {
        val state = stateOf(inst)
        val running = state == ServerState.Running
        val sheet = Sheet(c, inst.name, "${inst.coreType.displayName} · ${state.toLabel()}")

        sheet.add(c.settingRow("打开控制台", "实时日志与命令") {
            sheet.dismiss()
            host.openConsoleAction(inst)
        })
        sheet.add(c.splitter())
        sheet.add(c.settingRow(if (running) "重启" else "启动", "重启会先优雅停服") {
            sheet.dismiss()
            if (running) host.restart(inst) else host.start(inst)
        })
        sheet.add(c.splitter())
        sheet.add(c.settingRow("重命名", "只改显示名，不影响目录") {
            sheet.dismiss()
            host.renameDialog(inst)
        })
        sheet.add(c.splitter())
        sheet.add(c.settingRow(if (inst.pinned) "取消置顶" else "置顶", "置顶的排在最前") {
            sheet.dismiss()
            host.vm.setInstancePinned(inst, !inst.pinned)
        })
        sheet.add(c.splitter())
        sheet.add(c.settingRow("删除实例", "世界与目录保留在磁盘上", c.chevron(ShellTheme.current.danger)) {
            sheet.dismiss()
            host.confirmDelete(inst) { host.toast("已删除 ${inst.name}") }
        })
        sheet.footerText("关闭", ShellTheme.current.ink3) { sheet.dismiss() }
        sheet.show()
    }

    private fun renderImport(progress: ImportJarProgress) {
        when (progress) {
            is ImportJarProgress.Idle -> {
                importProgress.visibility = View.GONE
                importLabel.visibility = View.GONE
            }
            is ImportJarProgress.Running -> {
                importLabel.visibility = View.VISIBLE
                importLabel.text = "导入中 · ${progress.detail}"
                importProgress.visibility = View.VISIBLE
                if (progress.fraction < 0f) {
                    importProgress.indeterminate = true
                } else {
                    importProgress.indeterminate = false
                    importProgress.fraction = progress.fraction
                }
            }
            is ImportJarProgress.Done -> {
                importProgress.indeterminate = false
                importProgress.fraction = 1f
                importLabel.visibility = View.VISIBLE
                importLabel.text = "导入完成 · ${progress.name}"
                host.toast("已导入 ${progress.name}")
                importProgress.postDelayed({
                    importProgress.visibility = View.GONE
                    importLabel.visibility = View.GONE
                }, 2500L)
            }
            is ImportJarProgress.Failed -> {
                importProgress.indeterminate = false
                importProgress.fraction = 0f
                importLabel.visibility = View.VISIBLE
                importLabel.setTextColor(ShellTheme.current.danger)
                importLabel.text = "导入失败 · ${progress.reason}"
                host.toast("导入失败：${progress.reason}", error = true)
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

    override fun attach() {
        render()
        importJob?.cancel()
        importJob = scope.launch {
            host.vm.importJarProgress.collectLatest { renderImport(it) }
        }
    }

    override fun detach() {
        importJob?.cancel()
        importJob = null
    }
}

/** 服务端页的分类展示名（CoreCategory 的 displayName 已够用，这里只做别名以便阅读） */
private val CoreType.categoryLabel: String get() = category.displayName
