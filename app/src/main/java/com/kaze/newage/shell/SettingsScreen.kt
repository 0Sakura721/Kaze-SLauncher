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
                        addView(c.txt("设置", Type.display, ShellTheme.current.ink, medium = true))
                        addView(
                            c.txt("外观、实例与环境", Type.micro, ShellTheme.current.ink3).apply {
                                setPadding(0, dp(4f), 0, 0)
                            },
                        )
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                )
            },
        )

        buildAppearance()
        instanceTitle = c.sectionHeader("当前实例")
        body.addView(instanceTitle, c.stackMargin(top = 20f, bottom = 7f))
        instanceSection = c.col()
        body.addView(instanceSection)

        body.addView(
            c.sectionHeader("环境与诊断"),
            c.stackMargin(top = 20f, bottom = 7f),
        )
        envSection = c.col()
        body.addView(envSection)

        body.addView(
            c.txt(
                "岚壳 v1 · 界面层为自绘视图，业务逻辑复用既有核心",
                Type.micro, ShellTheme.current.ink3,
            ).apply {
                gravity = Gravity.CENTER
                setPadding(dp(24f), dp(22f), dp(24f), dp(4f))
            },
        )
    }

    // ── 外观 ───────────────────────────────────────────────────────────

    private fun buildAppearance() {
        body.addView(c.sectionHeader("外观"), c.stackMargin(top = 16f, bottom = 7f))
        val card = c.bareCard()

        // 主题模式：三个分段按钮
        val modeCard = c.col().apply {
            setPadding(dp(Dim.cardPad), dp(13f), dp(Dim.cardPad), dp(13f))
            addView(c.txt("主题", Type.body, ShellTheme.current.ink, medium = true))
            addView(
                c.txt("素纸偏暖、墨夜偏冷，两者共用同一套版式", Type.micro, ShellTheme.current.ink3)
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
                        tappable("主题 ${mode.label}") {
                            if (mode != host.themeMode()) {
                                host.setThemeMode(mode)
                            } else {
                                host.toast("已经是${mode.label}")
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
            c.settingRow("主题样式", "岚 · 纸感 / 墨夜（自绘视图）", c.chevron()) {
                Sheet(c, "主题样式", "切换界面外观，数据完全共用").apply {
                    add(
                        c.txt(
                            "「岚」是自绘视图层（本界面，纸感 / 墨夜两套配色）；" +
                                "「Material 3」是 Compose 界面，含壁纸动态取色与自定义颜色。" +
                                "两者的实例、控制台与设置数据完全共用，随时可以换回来。" +
                                "正在运行的服务端不受影响（它们跑在前台服务里）。",
                            Type.body, ShellTheme.current.ink2,
                        ).apply { setLineSpacing(dp(3f).toFloat(), 1f) },
                    )
                    buttons(
                        "切到 Material 3",
                        {
                            dismiss()
                            host.switchToComposeTheme("m3")
                        },
                        secondaryLabel = "取消",
                        onSecondary = { dismiss() },
                    )
                    show()
                }
            },
        )

        card.addView(c.splitter())
        card.addView(
            c.settingRow("置顶到顶栏", "开启后标题区更紧凑，多留出列表空间",
                TogglePill(c).apply {
                    setChecked(host.themePrefs.heroCompact, animate = false)
                    onCheckedChange = { v ->
                        host.themePrefs.heroCompact = v
                        host.toast(if (v) "已开启紧凑标题" else "已关闭紧凑标题")
                    }
                },
            ),
        )

        card.addView(c.splitter())
        card.addView(
            c.settingRow("控制台时间戳", "每行前面带上时分秒",
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
            "控制台字号",
            "等宽字体，${host.themePrefs.consoleFont.toInt()} sp",
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
                            ?.let { (it as? TextView)?.text = "等宽字体，${v.toInt()} sp" }
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
                c.emptyState("没有可配置的实例", "先在首页新建一个服务端", "去新建") {
                    host.toast("在首页右上角 ＋ 新建")
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
            c.settingRow("内存上限", "启动参数 -Xmx，改动后重启生效", memLabel),
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
                        host.toast("内存改为 $mb MB，重启生效")
                    }
                })
                addView(
                    c.txt(
                        "范围 ${MemoryLimits.MIN_MB}–${MemoryLimits.MAX_MB} MB，按 ${MemoryLimits.STEP_MB} MB 对齐",
                        Type.micro, ShellTheme.current.ink3,
                    ).apply { setPadding(0, dp(4f), 0, dp(6f)) },
                )
            },
        )

        // Java 版本
        card.addView(c.splitter())
        card.addView(
            c.settingRow("Java 版本", "默认按游戏版本自动推断", c.txt("Java ${inst.javaMajor}", Type.body, ShellTheme.current.accent, medium = true).apply {
                setPadding(dp(6f), 0, 0, 0)
            }, onClick = {
                c.choiceSheet(
                    "选择 Java 版本",
                    "同一台设备可以装多个 Java",
                    listOf("8" to "1.16 及更早", "17" to "1.17 – 1.20.4", "21" to "1.20.5 及更新", "25" to "2026 年之后的版本"),
                    listOf(8, 17, 21, 25).indexOf(inst.javaMajor),
                ) { index ->
                    val major = listOf(8, 17, 21, 25)[index]
                    host.vm.setInstanceJava(inst, major)
                    host.toast("Java $major，重启生效")
                    renderInstance()
                }
            }),
        )

        // 其他开关
        card.addView(c.splitter())
        card.addView(
            c.settingRow("nogui", "不加载服务端的图形界面（手机上没有意义，建议开）",
                TogglePill(c).apply {
                    setChecked(inst.nogui, animate = false)
                    onCheckedChange = { v -> host.vm.setNogui(inst, v) }
                },
            ),
        )
        card.addView(c.splitter())
        card.addView(
            c.settingRow("停服自动备份", "每次正常停止后备份世界，保留最近 5 份",
                TogglePill(c).apply {
                    setChecked(inst.autoBackup, animate = false)
                    onCheckedChange = { v -> host.vm.setInstanceAutoBackup(inst, v) }
                },
            ),
        )

        // JVM 参数
        card.addView(c.splitter())
        card.addView(
            c.settingRow("附加 JVM 参数", inst.extraJvmArgs.ifBlank { "未设置" }) {
                editJvmArgs(inst)
            },
        )

        instanceSection.addView(card, c.stackMargin(bottom = 8f))
    }

    private fun editJvmArgs(inst: ServerInstance) {
        val sheet = Sheet(c, "附加 JVM 参数", "空格分隔，例如 -XX:+UseG1GC")
        val field = c.input("-XX:+UseG1GC -Dfile.encoding=UTF-8", inst.extraJvmArgs)
        sheet.add(field)
        sheet.note("这些参数会直接拼进 java 命令行。写错会导致服务端起不来，启动日志里能看到具体报错。")
        sheet.buttons("保存", {
            host.vm.setExtraJvmArgs(inst, field.text.toString())
            sheet.dismiss()
            host.toast("已保存，重启生效")
            renderInstance()
        }, secondaryLabel = "取消", onSecondary = { sheet.dismiss() })
        sheet.show()
    }

    // ── 环境与诊断 ─────────────────────────────────────────────────────

    private fun buildEnvironment() {
        envSection.removeAllViews()
        val card = c.bareCard()
        val ready = host.vm.env.isReady

        val row = c.settingRow(
            "Linux 环境",
            if (ready) "已就绪" else "未部署，首次启动实例时会自动下载",
            c.statusTag(
                if (ready) "就绪" else "未就绪",
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
                    c.txt("部署", Type.micro, ShellTheme.current.accent, medium = true).apply {
                        setPadding(0, dp(9f), 0, 0)
                        tappable("部署 Linux 环境") {
                            host.toast("开始部署…")
                            host.setupEnvironment {
                                host.toast("环境已就绪")
                                buildEnvironment()
                            }
                        }
                    },
                )
            },
        )

        card.addView(c.splitter())
        card.addView(
            c.settingRow("电池优化白名单", "不加入白名单时后台服务可能被系统回收",
                c.txt("去授权", Type.micro, ShellTheme.current.accent, medium = true)
                    .apply { setPadding(dp(6f), 0, 0, 0) },
                onClick = { host.requestBatteryExemption() }),
        )

        // ── Java 运行时：与 M3 的「Java 运行时」分组对齐 ──
        card.addView(c.splitter())
        card.addView(
            c.settingRow("Java 运行时", "已装版本；启动实例时按需自动安装", c.chevron()) {
                showJavaSheet()
            },
        )

        // ── MCP 服务：开关 + 端口/绑定/令牌（M3 里有完整一组） ──
        card.addView(c.splitter())
        card.addView(
            c.settingRow("MCP 服务", "向外部 AI 暴露实例控制接口",
                TogglePill(c).apply {
                    setChecked(host.vm.uiPrefs.mcpEnabled.value, animate = false)
                    onCheckedChange = { v ->
                        host.vm.setMcpEnabled(v)
                        host.toast(if (v) "MCP 已开启" else "MCP 已关闭")
                    }
                }),
        )
        if (host.vm.uiPrefs.mcpEnabled.value) {
            card.addView(c.splitter())
            card.addView(
                c.settingRow(
                    "MCP 端口与令牌",
                    "端口 ${host.vm.uiPrefs.mcpPort.value} · ${host.vm.uiPrefs.mcpBindAddress.value}",
                    c.chevron(),
                ) { showMcpSheet() },
            )
        }

        // ── 更新：自动检查 + 手动检查（M3 的「后台与更新」核心项） ──
        card.addView(c.splitter())
        card.addView(
            c.settingRow("自动检查更新", "启动时检查新版本",
                TogglePill(c).apply {
                    setChecked(host.vm.uiPrefs.autoUpdate.value, animate = false)
                    onCheckedChange = { v ->
                        host.vm.uiPrefs.setAutoUpdate(v)
                        host.toast(if (v) "已开启自动检查" else "已关闭自动检查")
                    }
                }),
        )
        card.addView(c.splitter())
        card.addView(
            c.settingRow("立即检查更新", "当前 ${host.appVersion()}", c.chevron()) {
                host.vm.checkUpdate()
                host.toast("正在检查更新…")
            },
        )

        card.addView(c.splitter())
        card.addView(
            c.settingRow("应用日志", "诊断信息与崩溃现场", c.chevron()) {
                showLog()
            },
        )

        card.addView(c.splitter())
        card.addView(
            c.settingRow("关于", "岚壳 · 自绘视图界面层",
                c.txt(host.appVersion(), Type.micro, ShellTheme.current.ink3), onClick = {
                    Sheet(c, "关于", scrollable = false).apply {
                        add(c.kvRow("壳版本", "v1"))
                        add(c.kvRow("应用版本", host.appVersion()))
                        add(c.kvRow("设备", host.deviceSummary()))
                        add(c.kvRow("实例数", "${host.vm.instances.value.size}"))
                        footerText("关闭", ShellTheme.current.ink3) { dismiss() }
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
        val sheet = Sheet(c, "Java 运行时", "服务端按游戏版本选择需要的 Java")
        val box = c.col()

        fun rebuild() {
            box.removeAllViews()
            val installed = runCatching { host.vm.env.installedJdkVersions() }.getOrDefault(emptyList())
            val details = runCatching { host.vm.env.installedJdkFullVersions() }.getOrDefault(emptyMap())

            if (installed.isEmpty()) {
                box.addView(
                    c.txt("还没有安装 Java", Type.body, ShellTheme.current.ink2).apply {
                        setPadding(0, dp(8f), 0, dp(10f))
                    },
                )
            } else {
                box.addView(c.micro("已安装"))
                installed.sorted().forEach { major ->
                    val full = details[major]
                    box.addView(
                        c.settingRow(
                            "Java $major",
                            full ?: "已就绪",
                            c.txt("卸载", Type.micro, ShellTheme.current.danger, medium = true)
                                .apply { setPadding(dp(6f), 0, 0, 0) },
                        ) {
                            host.vm.uninstallJava(major)
                            host.toast("正在卸载 Java $major")
                            box.postDelayed({ rebuild() }, 800L)
                        },
                    )
                }
            }

            box.addView(c.splitter())
            box.addView(c.micro("可安装"))
            // 只列服务端真正会用到的版本：与 JavaVersionInference 的映射一致
            listOf(8, 17, 21, 25).filter { it !in installed }.forEach { major ->
                box.addView(
                    c.settingRow(
                        "Java $major",
                        when (major) {
                            8 -> "1.16 及更早"
                            17 -> "1.17 – 1.20.4"
                            21 -> "1.20.5 – 25.x"
                            else -> "26.x 及更新"
                        },
                        c.txt("安装", Type.micro, ShellTheme.current.accent, medium = true)
                            .apply { setPadding(dp(6f), 0, 0, 0) },
                    ) {
                        host.vm.installJava(major)
                        host.toast("开始安装 Java $major")
                        box.postDelayed({ rebuild() }, 1200L)
                    },
                )
            }
        }

        rebuild()
        sheet.add(box)
        sheet.buttons("刷新", {
            host.vm.refreshJava()
            rebuild()
        }, secondaryLabel = "关闭", onSecondary = { sheet.dismiss() })
        sheet.show()
    }

    /** MCP 服务配置：端口、绑定地址、令牌 */
    private fun showMcpSheet() {
        val prefs = host.vm.uiPrefs
        val sheet = Sheet(c, "MCP 服务", "端口、绑定地址与访问令牌")

        sheet.add(c.micro("监听端口"))
        val portField = c.input("端口", prefs.mcpPort.value.toString(), numeric = true)
        sheet.add(portField)

        sheet.add(c.micro("绑定地址"))
        val bindField = c.input("0.0.0.0 或 127.0.0.1", prefs.mcpBindAddress.value)
        sheet.add(bindField)
        sheet.note("绑定 0.0.0.0 会允许同一局域网内的设备访问；只在本机用请填 127.0.0.1。")

        sheet.add(c.micro("访问令牌"))
        val tokenBox = c.row().apply {
            setPadding(0, dp(4f), 0, 0)
            addView(
                c.mono(prefs.mcpToken.value.ifBlank { "（空）" }, 11f, ShellTheme.current.ink2)
                    .apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(c.txt("复制", Type.micro, ShellTheme.current.accent, medium = true).apply {
                setPadding(dp(10f), 0, 0, 0)
                tappable("复制令牌") { host.copy(prefs.mcpToken.value, "令牌已复制") }
            })
        }
        sheet.add(tokenBox)

        sheet.buttons("保存并重启服务", {
            val port = portField.text.toString().trim().toIntOrNull()
            if (port == null || port !in 1024..65535) {
                host.toast("端口需在 1024–65535 之间", error = true)
                return@buttons
            }
            prefs.setMcpPort(port)
            prefs.setMcpBindAddress(bindField.text.toString().trim().ifBlank { "127.0.0.1" })
            host.vm.rebindMcp()
            sheet.dismiss()
            host.toast("MCP 配置已保存，服务正在重启")
        }, secondaryLabel = "取消", onSecondary = { sheet.dismiss() })
        sheet.show()
    }

    private fun showLog() {
        val sheet = Sheet(c, "应用日志", "最近的运行记录")
        val box = c.col().apply {
            background = c.rounded(
                ShellTheme.current.surfaceSunken, Dim.field,
                ShellTheme.current.stroke, Dim.line,
            )
            setPadding(dp(11f), dp(10f), dp(11f), dp(10f))
        }
        val placeholder = c.txt("读取中…", Type.micro, ShellTheme.current.ink3)
        box.addView(placeholder)
        sheet.add(box)
        val copyButton = sheet.buttons("复制", {
            sheet.dismiss()
        }, secondaryLabel = "关闭", onSecondary = { sheet.dismiss() })

        // tail() 是挂起函数（读磁盘）：先显示"读取中"，回来再替换内容
        host.readLogTail(120) { lines ->
            box.removeAllViews()
            if (lines.isEmpty()) {
                box.addView(c.txt("暂无日志", Type.micro, ShellTheme.current.ink3))
            } else {
                lines.forEach { line ->
                    box.addView(c.mono(line, 10.5f, ShellTheme.current.ink2).apply {
                        setLineSpacing(dp(1.6f).toFloat(), 1f)
                    })
                }
            }
            copyButton.setOnClickListener(null)
            copyButton.tappable("复制日志") {
                host.copy(lines.joinToString("\n"), "已复制 ${lines.size} 行")
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
