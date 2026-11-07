package com.kaze.newage.shell

import android.content.Context
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.kaze.newage.core.server.MemoryLimits
import com.kaze.newage.core.server.ServerState
import com.kaze.newage.data.model.CoreType
import com.kaze.newage.data.model.ServerInstance
import com.kaze.newage.ui.toLabel
import com.kaze.newage.core.i18n.AppLanguage
import com.kaze.newage.core.i18n.t

/**
 * 设置页。
 *
 * 分三段：外观 / 当前实例 / 环境与诊断。
 * 顺序按"改动的频率 × 影响面"排：主题是最常改的；实例参数改动次之；
 * 环境与诊断基本只在出问题时才进，放最下面。
 *
 * 所有开关都是"即时生效 + 立即落盘"，没有"保存"按钮 —— 一个只有开关的页面
 * 加保存按钮，用户会永远不确定自己点的那一下有没有生效。
 */
class SettingsScreen(private val host: ShellHost) : ScreenRoot(host.context) {

    private val c: Context get() = context

    private lateinit var instanceSection: LinearLayout
    private lateinit var instanceTitle: LinearLayout
    private lateinit var envSection: LinearLayout
    private lateinit var envValue: TextView
    private lateinit var envProgress: RibbonProgress

    private var instance: ServerInstance? = null

    init {
        head.addView(
            c.row().apply {
                addView(
                    c.col().apply {
                        addView(c.txt(t("设置"), Type.display, ShellTheme.current.ink, medium = true))
                        addView(
                            c.txt(t("外观、实例与环境"), Type.micro, ShellTheme.current.ink3).apply {
                                setPadding(0, dp(4f), 0, 0)
                            },
                        )
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                )
            },
        )

        buildLanguage()
        buildAppearance()
        instanceTitle = c.sectionHeader(t("当前实例"))
        body.addView(instanceTitle, c.stackMargin(top = 20f, bottom = 7f))
        instanceSection = c.col()
        body.addView(instanceSection)

        body.addView(
            c.sectionHeader(t("环境与诊断")),
            c.stackMargin(top = 20f, bottom = 7f),
        )
        envSection = c.col()
        body.addView(envSection)

        body.addView(
            c.txt(
                t("岚壳 v1 · 界面层为自绘视图，业务逻辑复用既有核心"),
                Type.micro, ShellTheme.current.ink3,
            ).apply {
                gravity = Gravity.CENTER
                setPadding(dp(24f), dp(22f), dp(24f), dp(4f))
            },
        )
    }

    // ── 外观 ───────────────────────────────────────────────────────────

    // ── 语言（放在最上方：它是"要用哪种语言读这一屏"的元设置）────────────
    //
    // 岚是自绘 View，没有 Compose 的 mutableStateOf 可订阅，所以切换语言后
    // **整壳重建**（与主题切换同一套机制，见 ShellActivity 的注释：重建一次几十毫秒，
    // 比给每个 View 写 refresh() 便宜得多，也不会漏掉某个没刷新的控件）。
    private fun buildLanguage() {
        body.addView(c.sectionHeader(t("语言")), c.stackMargin(top = 16f, bottom = 7f))
        val card = c.bareCard()
        val current = AppLanguage.fromId(host.vm.uiPrefs.appLanguage.value)
        val currentLabel = if (current == AppLanguage.SYSTEM) t("跟随系统") else current.label

        card.addView(
            c.settingRow(
                title = t("界面语言"),
                subtitle = t("跟随系统 / 简体中文 / English"),
                trailing = c.row().apply {
                    addView(
                        c.txt(currentLabel, Type.body, ShellTheme.current.accent, medium = true),
                    )
                    addView(c.chevron())
                },
                onClick = {
                Sheet(c, t("界面语言"), t("选中后立即生效，整壳会重建一次")).apply {
                    AppLanguage.entries.forEach { lang ->
                        val label = if (lang == AppLanguage.SYSTEM) t("跟随系统") else lang.label
                        add(
                            c.settingRow(
                                label,
                                if (lang == current) t("当前使用") else t("点一下切换"),
                                c.txt(
                                    if (lang == current) "✓" else "",
                                    Type.body, ShellTheme.current.accent, medium = true,
                                ),
                            ) {
                                dismiss()
                                host.setAppLanguage(lang.id)
                            },
                        )
                    }
                    buttons(t("关闭"), { dismiss() })
                    show()
                }
            },
            ),
        )
        body.addView(card, c.stackMargin(bottom = 0f))
    }

    private fun buildAppearance() {
        body.addView(c.sectionHeader(t("外观")), c.stackMargin(top = 16f, bottom = 7f))
        val card = c.bareCard()

        // 主题模式：三个分段按钮
        val modeCard = c.col().apply {
            setPadding(dp(Dim.cardPad), dp(13f), dp(Dim.cardPad), dp(13f))
            addView(c.txt(t("主题"), Type.body, ShellTheme.current.ink, medium = true))
            addView(
                c.txt(t("素纸偏暖、墨夜偏冷，两者共用同一套版式"), Type.micro, ShellTheme.current.ink3)
                    .apply { setPadding(0, dp(4f), 0, dp(10f)) },
            )
        }
        val segments = c.row().apply {
            background = c.rounded(
                ShellTheme.current.surfaceSunken, Dim.field,
                ShellTheme.current.stroke, Dim.line,
            )
            setPadding(dp(3f), dp(3f), dp(3f), dp(3f))
        }
        val current = host.themeMode()
        ThemeMode.entries.forEach { mode ->
            val active = mode == current
            segments.addView(
                c.txt(mode.label, Type.micro, if (active) ShellTheme.current.ink else ShellTheme.current.ink3, medium = active)
                    .apply {
                        gravity = Gravity.CENTER
                        setPadding(0, dp(8f), 0, dp(8f))
                        background = if (active) {
                            c.rounded(
                                ShellTheme.current.surface, Dim.field - 2f,
                                ShellTheme.current.strokeStrong, Dim.line,
                            )
                        } else {
                            null
                        }
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        tappable(t("主题 %s", mode.label)) {
                            if (mode != host.themeMode()) {
                                host.setThemeMode(mode)
                            } else {
                                host.toast(t("已经是%s", mode.label))
                            }
                        }
                    },
            )
        }
        modeCard.addView(segments)
        card.addView(modeCard)

        // 主题样式切换：与 Compose 侧设置页写同一个键（uiPrefs.themeMode /
        // AppThemeMode），「岚 / Material 3」都从这一个选择器进出。
        // 现在在本壳里所以只有"切走"一个方向；Material 3 样式选中时会整壳
        // 重启到 Compose 的 Activity（见 switchToComposeTheme）。
        card.addView(c.splitter())
        card.addView(
            c.settingRow(t("主题样式"), t("岚 · 纸感 / 墨夜（自绘视图）"), c.chevron()) {
                Sheet(c, t("主题样式"), t("切换界面外观，数据完全共用")).apply {
                    add(
                        c.txt(
                            t("「岚」是自绘视图层（本界面，纸感 / 墨夜两套配色）；") +
                                t("「Material 3」是 Compose 界面，含壁纸动态取色与自定义颜色。") +
                                t("两者的实例、控制台与设置数据完全共用，随时可以换回来。") +
                                t("正在运行的服务端不受影响（它们跑在前台服务里）。"),
                            Type.body, ShellTheme.current.ink2,
                        ).apply { setLineSpacing(dp(3f).toFloat(), 1f) },
                    )
                    buttons(
                        t("切到 Material 3"),
                        {
                            dismiss()
                            host.switchToComposeTheme("m3")
                        },
                        secondaryLabel = t("取消"),
                        onSecondary = { dismiss() },
                    )
                    show()
                }
            },
        )

        card.addView(c.splitter())
        card.addView(
            c.settingRow(t("置顶到顶栏"), t("开启后标题区更紧凑，多留出列表空间"),
                TogglePill(c).apply {
                    setChecked(host.themePrefs.heroCompact, animate = false)
                    onCheckedChange = { v ->
                        host.themePrefs.heroCompact = v
                        host.toast(if (v) t("已开启紧凑标题") else t("已关闭紧凑标题"))
                    }
                },
            ),
        )

        card.addView(c.splitter())
        card.addView(
            c.settingRow(t("控制台时间戳"), t("每行前面带上时分秒"),
                TogglePill(c).apply {
                    setChecked(host.themePrefs.consoleStamps, animate = false)
                    onCheckedChange = { v ->
                        host.themePrefs.consoleStamps = v
                    }
                },
            ),
        )

        card.addView(c.splitter())
        val fontRow = c.settingRow(
            t("控制台字号"),
            t("等宽字体，%s sp", host.themePrefs.consoleFont.toInt()),
        )
        card.addView(fontRow)
        card.addView(
            c.col().apply {
                setPadding(dp(Dim.cardPad), 0, dp(Dim.cardPad), dp(12f))
                val slider = SliderBar(c).apply {
                    min = 10f
                    max = 18f
                    step = 0.5f
                    value = host.themePrefs.consoleFont
                    onValueChange = { v ->
                        host.themePrefs.consoleFont = v
                        (fontRow.getChildAt(0) as? LinearLayout)
                            ?.getChildAt(1)
                            ?.let { (it as? TextView)?.text = t("等宽字体，%s sp", v.toInt()) }
                    }
                }
                addView(slider)
            },
        )

        body.addView(card, c.stackMargin(bottom = 8f))
    }

    // ── 当前实例 ───────────────────────────────────────────────────────

    fun renderInstances(list: List<ServerInstance>) {
        renderInstance()
    }

    fun renderStates(map: Map<String, ServerState>) {
        renderInstance()
    }

    private fun currentInstance(): ServerInstance? {
        val id = host.vm.currentInstanceId.value
            ?: host.themePrefs.lastInstanceId
        return id?.let { host.vm.instanceStore.get(it) }
            ?: host.vm.instances.value.firstOrNull()
    }

    private fun renderInstance() {
        instance = currentInstance()
        instanceSection.removeAllViews()
        val inst = instance
        if (inst == null) {
            instanceSection.addView(
                c.emptyState(t("没有可配置的实例"), t("先在首页新建一个服务端"), t("去新建")) {
                    host.toast(t("在首页右上角 ＋ 新建"))
                },
                c.stackMargin(),
            )
            return
        }

        val card = c.bareCard()

        // 标题行：实例名 + 状态
        card.addView(
            c.row().apply {
                setPadding(dp(Dim.cardPad), dp(13f), dp(Dim.cardPad), dp(12f))
                addView(c.tileBadge(inst.name.take(1).uppercase(), ShellTheme.current.accent, 34f))
                addView(
                    c.col().apply {
                        setPadding(dp(10f), 0, 0, 0)
                        addView(c.txt(inst.name, Type.subtitle, ShellTheme.current.ink, medium = true))
                        addView(
                            c.txt(
                                "${inst.coreType.displayName} · ${inst.mcVersion.ifBlank { "自定义" }}",
                                Type.micro, ShellTheme.current.ink3,
                            ).apply { setPadding(0, dp(3f), 0, 0) },
                        )
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(c.statusTag(host.formatState(host.stateOf(inst.id)), ShellTheme.current.accent))
            },
        )

        // 内存：滑块 + 数值
        card.addView(c.splitter())
        val memLabel = c.mono("${inst.memoryMb} MB", Type.body, ShellTheme.current.ink, medium = true)
        card.addView(
            c.settingRow(t("内存上限"), t("启动参数 -Xmx，改动后重启生效"), memLabel),
        )
        card.addView(
            c.col().apply {
                setPadding(dp(Dim.cardPad), 0, dp(Dim.cardPad), dp(6f))
                addView(SliderBar(c).apply {
                    min = MemoryLimits.MIN_MB.toFloat()
                    max = MemoryLimits.MAX_MB.toFloat()
                    step = MemoryLimits.STEP_MB.toFloat()
                    value = inst.memoryMb.toFloat()
                    onValueChange = { v -> memLabel.text = "${v.toInt()} MB" }
                    onValueSettled = { v ->
                        val mb = MemoryLimits.clamp(v.toInt())
                        host.vm.setInstanceMemory(inst, mb)
                        memLabel.text = "$mb MB"
                        host.toast(t("内存改为 %s MB，重启生效", mb))
                    }
                })
                addView(
                    c.txt(
                        t("范围 %s–%s MB，按 %s MB 对齐", MemoryLimits.MIN_MB, MemoryLimits.MAX_MB, MemoryLimits.STEP_MB),
                        Type.micro, ShellTheme.current.ink3,
                    ).apply { setPadding(0, dp(4f), 0, dp(6f)) },
                )
            },
        )

        // Java 版本
        card.addView(c.splitter())
        card.addView(
            c.settingRow(t("Java 版本"), t("默认按游戏版本自动推断"), c.txt("Java ${inst.javaMajor}", Type.body, ShellTheme.current.accent, medium = true).apply {
                setPadding(dp(6f), 0, 0, 0)
            }, onClick = {
                c.choiceSheet(
                    t("选择 Java 版本"),
                    t("同一台设备可以装多个 Java"),
                    listOf("8" to t("1.16 及更早"), "17" to "1.17 – 1.20.4", "21" to t("1.20.5 及更新"), "25" to t("2026 年之后的版本")),
                    listOf(8, 17, 21, 25).indexOf(inst.javaMajor),
                ) { index ->
                    val major = listOf(8, 17, 21, 25)[index]
                    host.vm.setInstanceJava(inst, major)
                    host.toast(t("Java %s，重启生效", major))
                    renderInstance()
                }
            }),
        )

        // 其他开关
        card.addView(c.splitter())
        card.addView(
            c.settingRow("nogui", t("不加载服务端的图形界面（手机上没有意义，建议开）"),
                TogglePill(c).apply {
                    setChecked(inst.nogui, animate = false)
                    onCheckedChange = { v -> host.vm.setNogui(inst, v) }
                },
            ),
        )
        card.addView(c.splitter())
        card.addView(
            c.settingRow(t("停服自动备份"), t("每次正常停止后备份世界，保留最近 5 份"),
                TogglePill(c).apply {
                    setChecked(inst.autoBackup, animate = false)
                    onCheckedChange = { v -> host.vm.setInstanceAutoBackup(inst, v) }
                },
            ),
        )

        // JVM 参数
        card.addView(c.splitter())
        card.addView(
            c.settingRow(t("附加 JVM 参数"), inst.extraJvmArgs.ifBlank { t("未设置") }) {
                editJvmArgs(inst)
            },
        )

        instanceSection.addView(card, c.stackMargin(bottom = 8f))
    }

    private fun editJvmArgs(inst: ServerInstance) {
        val sheet = Sheet(c, t("附加 JVM 参数"), t("空格分隔，例如 -XX:+UseG1GC"))
        val field = c.input("-XX:+UseG1GC -Dfile.encoding=UTF-8", inst.extraJvmArgs)
        sheet.add(field)
        sheet.note(t("这些参数会直接拼进 java 命令行。写错会导致服务端起不来，启动日志里能看到具体报错。"))
        sheet.buttons(t("保存"), {
            host.vm.setExtraJvmArgs(inst, field.text.toString())
            sheet.dismiss()
            host.toast(t("已保存，重启生效"))
            renderInstance()
        }, secondaryLabel = t("取消"), onSecondary = { sheet.dismiss() })
        sheet.show()
    }

    // ── 环境与诊断 ─────────────────────────────────────────────────────

    private fun buildEnvironment() {
        envSection.removeAllViews()
        val card = c.bareCard()
        val ready = host.vm.env.isReady

        val row = c.settingRow(
            t("Linux 环境"),
            if (ready) t("已就绪") else t("未部署，首次启动实例时会自动下载"),
            c.statusTag(
                if (ready) t("就绪") else t("未就绪"),
                if (ready) ShellTheme.current.good else ShellTheme.current.warn,
            ),
        )
        card.addView(row)
        card.addView(
            c.col().apply {
                setPadding(dp(Dim.cardPad), 0, dp(Dim.cardPad), dp(12f))
                envProgress = RibbonProgress(c).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(4f),
                    )
                    visibility = View.GONE
                }
                addView(envProgress)
                addView(
                    c.txt(t("部署"), Type.micro, ShellTheme.current.accent, medium = true).apply {
                        setPadding(0, dp(9f), 0, 0)
                        tappable(t("部署 Linux 环境")) {
                            host.toast(t("开始部署…"))
                            host.setupEnvironment {
                                host.toast(t("环境已就绪"))
                                buildEnvironment()
                            }
                        }
                    },
                )
            },
        )

        card.addView(c.splitter())
        card.addView(
            c.settingRow(t("电池优化白名单"), t("不加入白名单时后台服务可能被系统回收"),
                c.txt(t("去授权"), Type.micro, ShellTheme.current.accent, medium = true)
                    .apply { setPadding(dp(6f), 0, 0, 0) },
                onClick = { host.requestBatteryExemption() }),
        )

        // ── Java 运行时：与 M3 的「Java 运行时」分组对齐 ──
        card.addView(c.splitter())
        card.addView(
            c.settingRow(t("Java 运行时"), t("已装版本；启动实例时按需自动安装"), c.chevron()) {
                showJavaSheet()
            },
        )

        // ── MCP 服务：开关 + 端口/绑定/令牌（M3 里有完整一组） ──
        card.addView(c.splitter())
        card.addView(
            c.settingRow(t("MCP 服务"), t("向外部 AI 暴露实例控制接口"),
                TogglePill(c).apply {
                    setChecked(host.vm.uiPrefs.mcpEnabled.value, animate = false)
                    onCheckedChange = { v ->
                        host.vm.setMcpEnabled(v)
                        host.toast(if (v) t("MCP 已开启") else t("MCP 已关闭"))
                    }
                }),
        )
        if (host.vm.uiPrefs.mcpEnabled.value) {
            card.addView(c.splitter())
            card.addView(
                c.settingRow(
                    t("MCP 端口与令牌"),
                    t("端口 %s · %s", host.vm.uiPrefs.mcpPort.value, host.vm.uiPrefs.mcpBindAddress.value),
                    c.chevron(),
                ) { showMcpSheet() },
            )
        }

        // ── 更新：自动检查 + 手动检查（M3 的「后台与更新」核心项） ──
        card.addView(c.splitter())
        card.addView(
            c.settingRow(t("自动检查更新"), t("启动时检查新版本"),
                TogglePill(c).apply {
                    setChecked(host.vm.uiPrefs.autoUpdate.value, animate = false)
                    onCheckedChange = { v ->
                        host.vm.uiPrefs.setAutoUpdate(v)
                        host.toast(if (v) t("已开启自动检查") else t("已关闭自动检查"))
                    }
                }),
        )
        card.addView(c.splitter())
        card.addView(
            c.settingRow(t("立即检查更新"), "当前 ${host.appVersion()}", c.chevron()) {
                host.vm.checkUpdate()
                host.toast(t("正在检查更新…"))
            },
        )

        card.addView(c.splitter())
        card.addView(
            c.settingRow(t("应用日志"), t("诊断信息与崩溃现场"), c.chevron()) {
                showLog()
            },
        )

        card.addView(c.splitter())
        card.addView(
            c.settingRow(t("关于"), t("岚壳 · 自绘视图界面层"),
                c.txt(host.appVersion(), Type.micro, ShellTheme.current.ink3), onClick = {
                    Sheet(c, t("关于"), scrollable = false).apply {
                        add(c.kvRow(t("壳版本"), "v1"))
                        add(c.kvRow(t("应用版本"), host.appVersion()))
                        add(c.kvRow(t("设备"), host.deviceSummary()))
                        add(c.kvRow(t("实例数"), "${host.vm.instances.value.size}"))
                        footerText(t("关闭"), ShellTheme.current.ink3) { dismiss() }
                        show()
                    }
                }),
        )

        envSection.addView(card, c.stackMargin(bottom = 8f))
    }

    /**
     * Java 运行时管理。
     *
     * 与 M3 的「Java 运行时」分组对齐：列出已装版本、可安装/卸载。
     * 版本列表来自 env.installedJdkVersions()，不是硬编码 —— 用户可能手工装过。
     */
    private fun showJavaSheet() {
        val sheet = Sheet(c, t("Java 运行时"), t("服务端按游戏版本选择需要的 Java"))
        val box = c.col()

        // 装/卸 Java 是异步的，这里靠延时重建列表来反映结果。
        // 用可取消的 Handler 而不是 `box.postDelayed`：后者挂在 Dialog 的视图上，
        // 弹层一 dismiss，post 的 runnable 会进 RunQueue 且**永不执行**，
        // 同时把 sheet/box 的引用挂到 GC 上（延迟队列持有）。
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        sheet.setOnDismissListener { handler.removeCallbacksAndMessages(null) }

        // 局部函数在 Kotlin 里不能前向引用，而 scheduleRebuild 需要调 rebuild，
        // 所以用一个可赋值的函数变量中转（rebuild 定义在下面）。
        var rebuildRef: () -> Unit = {}

        fun scheduleRebuild(delayMs: Long) {
            handler.postDelayed({
                // 弹层已关就不再碰视图
                if (sheet.isShowing) rebuildRef()
            }, delayMs)
        }

        fun rebuild() {
            box.removeAllViews()
            val installed = runCatching { host.vm.env.installedJdkVersions() }.getOrDefault(emptyList())
            val details = runCatching { host.vm.env.installedJdkFullVersions() }.getOrDefault(emptyMap())

            if (installed.isEmpty()) {
                box.addView(
                    c.txt(t("还没有安装 Java"), Type.body, ShellTheme.current.ink2).apply {
                        setPadding(0, dp(8f), 0, dp(10f))
                    },
                )
            } else {
                box.addView(c.micro(t("已安装")))
                installed.sorted().forEach { major ->
                    val full = details[major]
                    box.addView(
                        c.settingRow(
                            "Java $major",
                            full ?: t("已就绪"),
                            c.txt(t("卸载"), Type.micro, ShellTheme.current.danger, medium = true)
                                .apply { setPadding(dp(6f), 0, 0, 0) },
                        ) {
                            host.vm.uninstallJava(major)
                            host.toast(t("正在卸载 Java %s", major))
                            scheduleRebuild(800L)
                        },
                    )
                }
            }

            box.addView(c.splitter())
            box.addView(c.micro(t("可安装")))
            // 只列服务端真正会用到的版本：与 JavaVersionInference 的映射一致
            listOf(8, 17, 21, 25).filter { it !in installed }.forEach { major ->
                box.addView(
                    c.settingRow(
                        "Java $major",
                        when (major) {
                            8 -> t("1.16 及更早")
                            17 -> "1.17 – 1.20.4"
                            21 -> "1.20.5 – 25.x"
                            else -> t("26.x 及更新")
                        },
                        c.txt(t("安装"), Type.micro, ShellTheme.current.accent, medium = true)
                            .apply { setPadding(dp(6f), 0, 0, 0) },
                    ) {
                        host.vm.installJava(major)
                        host.toast(t("开始安装 Java %s", major))
                        scheduleRebuild(1200L)
                    },
                )
            }
        }

        // 把 rebuild 接到中转变量上（scheduleRebuild 里用的是 rebuildRef）
        rebuildRef = ::rebuild

        rebuild()
        sheet.add(box)
        sheet.buttons(t("刷新"), {
            host.vm.refreshJava()
            rebuild()
        }, secondaryLabel = t("关闭"), onSecondary = { sheet.dismiss() })
        sheet.show()
    }

    /** MCP 服务配置：端口、绑定地址、令牌 */
    private fun showMcpSheet() {
        val prefs = host.vm.uiPrefs
        val sheet = Sheet(c, t("MCP 服务"), t("端口、绑定地址与访问令牌"))

        sheet.add(c.micro(t("监听端口")))
        val portField = c.input(t("端口"), prefs.mcpPort.value.toString(), numeric = true)
        sheet.add(portField)

        sheet.add(c.micro(t("绑定地址")))
        val bindField = c.input(t("0.0.0.0 或 127.0.0.1"), prefs.mcpBindAddress.value)
        sheet.add(bindField)
        sheet.note(t("绑定 0.0.0.0 会允许同一局域网内的设备访问；只在本机用请填 127.0.0.1。"))

        sheet.add(c.micro(t("访问令牌")))
        val tokenBox = c.row().apply {
            setPadding(0, dp(4f), 0, 0)
            addView(
                c.mono(prefs.mcpToken.value.ifBlank { t("（空）") }, 11f, ShellTheme.current.ink2)
                    .apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(c.txt(t("复制"), Type.micro, ShellTheme.current.accent, medium = true).apply {
                setPadding(dp(10f), 0, 0, 0)
                tappable(t("复制令牌")) { host.copy(prefs.mcpToken.value, t("令牌已复制")) }
            })
        }
        sheet.add(tokenBox)

        sheet.buttons(t("保存并重启服务"), {
            val port = portField.text.toString().trim().toIntOrNull()
            if (port == null || port !in 1024..65535) {
                host.toast(t("端口需在 1024–65535 之间"), error = true)
                return@buttons
            }
            prefs.setMcpPort(port)
            prefs.setMcpBindAddress(bindField.text.toString().trim().ifBlank { "127.0.0.1" })
            host.vm.rebindMcp()
            sheet.dismiss()
            host.toast(t("MCP 配置已保存，服务正在重启"))
        }, secondaryLabel = t("取消"), onSecondary = { sheet.dismiss() })
        sheet.show()
    }

    private fun showLog() {
        val sheet = Sheet(c, t("应用日志"), t("最近的运行记录"))
        val box = c.col().apply {
            background = c.rounded(
                ShellTheme.current.surfaceSunken, Dim.field,
                ShellTheme.current.stroke, Dim.line,
            )
            setPadding(dp(11f), dp(10f), dp(11f), dp(10f))
        }
        val placeholder = c.txt(t("读取中…"), Type.micro, ShellTheme.current.ink3)
        box.addView(placeholder)
        sheet.add(box)
        val copyButton = sheet.buttons(t("复制"), {
            sheet.dismiss()
        }, secondaryLabel = t("关闭"), onSecondary = { sheet.dismiss() })

        // tail() 是挂起函数（读磁盘）：先显示"读取中"，回来再替换内容。
        // 回调必须判 sheet.isShowing：读盘可能比用户关弹层慢，
        // 不判的话会往已 dismiss 的 Dialog 里 addView 上百个 TextView
        //（视图树已脱离窗口，纯浪费且拖住 GC）。
        host.readLogTail(120) { lines ->
            if (!sheet.isShowing) return@readLogTail
            box.removeAllViews()
            if (lines.isEmpty()) {
                box.addView(c.txt(t("暂无日志"), Type.micro, ShellTheme.current.ink3))
            } else {
                lines.forEach { line ->
                    box.addView(c.mono(line, 10.5f, ShellTheme.current.ink2).apply {
                        setLineSpacing(dp(1.6f).toFloat(), 1f)
                    })
                }
            }
            copyButton.setOnClickListener(null)
            copyButton.tappable(t("复制日志")) {
                host.copy(lines.joinToString("\n"), t("已复制 %s 行", lines.size))
            }
        }
        sheet.show()
    }

    // ── 生命周期 ───────────────────────────────────────────────────────

    override fun attach() {
        buildEnvironment()
        renderInstance()
    }

    override fun detach() {}
}
