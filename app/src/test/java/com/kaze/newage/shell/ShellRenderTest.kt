package com.kaze.newage.shell

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * 壳的渲染快照。
 *
 * 目的不是"断言像素"，而是把自绘界面真的画一遍：
 *  - 自绘 View 的 onDraw 只有在真有 Canvas 时才会跑，纯单测覆盖不到；
 *  - 尺寸错配（文字被裁、条子不占满、行高不够）只有在真实布局后量出来才看得见。
 *
 * 输出到 app/build/shell-shots/，人工核对即可。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class ShellRenderTest {

    private val outDir = File("build/shell-shots").apply { mkdirs() }

    private fun capture(name: String, view: View, widthDp: Int = 411, heightDp: Int = 891) {
        val density = RuntimeEnvironment.getApplication().resources.displayMetrics.density
        val w = (widthDp * density).toInt()
        val h = (heightDp * density).toInt()
        view.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        view.draw(canvas)
        FileOutputStream(File(outDir, "$name.png")).use {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    /** 自绘控件总览：刻度盘 / 开关 / 分段条 / 进度条 / 呼吸点 / 状态条 */
    @Test
    fun widgets_paper() {
        ShellTheme.current = PaperTokens
        renderWidgets("widgets-paper")
    }

    @Test
    fun widgets_ink() {
        ShellTheme.current = InkTokens
        renderWidgets("widgets-ink")
    }

    /** 卡片放在真实页面底色上：验证盘心、描边、按钮与背景的对比关系 */
    @Test
    fun card_on_page_paper() {
        ShellTheme.current = PaperTokens
        renderCardOnPage("card-page-paper")
    }

    @Test
    fun card_on_page_ink() {
        ShellTheme.current = InkTokens
        renderCardOnPage("card-page-ink")
    }

    private fun renderCardOnPage(name: String) {
        val ctx = RuntimeEnvironment.getApplication()
        val root = FrameLayout(ctx)
        root.addView(PaperBackdrop(ctx), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        val body = ctx.col().apply { setPadding(ctx.dp(18f), ctx.dp(26f), ctx.dp(18f), ctx.dp(20f)) }

        body.addView(ctx.row().apply {
            addView(ctx.col().apply {
                addView(ctx.txt("岚", Type.display, ShellTheme.current.ink, medium = true))
                addView(ctx.txt("arm64-v8a · Android 35", Type.micro, ShellTheme.current.ink3).apply {
                    setPadding(0, ctx.dp(4f), 0, 0)
                })
            }, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(ctx.txt("＋", 21f, ShellTheme.current.accent, medium = true).apply {
                gravity = android.view.Gravity.CENTER
                background = ctx.rip(
                    ShellTheme.current.accentSoft, 12f,
                    stroke = ShellTheme.current.accent.alpha(0.26f),
                    strokeWidth = Dim.line,
                )
            }, android.widget.LinearLayout.LayoutParams(ctx.dp(38f), ctx.dp(38f)))
        }, ctx.grow())

        body.addView(ctx.spacer(14f))
        body.addView(ctx.card(pad = 13f).apply {
            val row = ctx.row()
            val gauge = GaugeView(ctx).apply { active = true; progress = 0.82f }
            row.addView(gauge, android.widget.LinearLayout.LayoutParams(ctx.dp(94f), ctx.dp(94f)))
            row.addView(ctx.col().apply {
                setPadding(ctx.dp(12f), 0, 0, 0)
                addView(ctx.txt("运行中", Type.title, ShellTheme.current.ink, medium = true))
                addView(ctx.mono("生存服 · 1.21", 11.5f, ShellTheme.current.ink3).apply {
                    setPadding(0, ctx.dp(5f), 0, 0)
                })
                addView(ctx.txt("1 / 3 个实例在跑 · 内存 4096 MB", Type.micro, ShellTheme.current.ink3).apply {
                    setPadding(0, ctx.dp(7f), 0, 0)
                })
            }, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(ctx.txt("停止", Type.micro, ShellTheme.current.accent, medium = true).apply {
                gravity = android.view.Gravity.CENTER
                setPadding(ctx.dp(13f), ctx.dp(9f), ctx.dp(13f), ctx.dp(9f))
                background = ctx.rip(
                    ShellTheme.current.accentSoft, 11f,
                    stroke = ShellTheme.current.accent.alpha(0.28f), strokeWidth = Dim.line,
                )
            })
            addView(row)
        }, ctx.grow())

        body.addView(ctx.spacer(18f))
        body.addView(ctx.sectionHeader("实例", "新建") { }, ctx.grow())
        body.addView(ctx.spacer(8f))

        listOf(
            Triple("生存服", "运行中", 2),
            Triple("创造测试", "已停止", 0),
            Triple("模组服", "启动失败", 4),
        ).forEach { (title, status, toneIndex) ->
            val tone = when (toneIndex) {
                2 -> ShellTheme.current.good
                4 -> ShellTheme.current.danger
                else -> ShellTheme.current.ink3
            }
            body.addView(ctx.bareCard().apply {
                addView(ctx.row().apply {
                    setPadding(ctx.dp(12f), ctx.dp(13f), ctx.dp(13f), ctx.dp(13f))
                    addView(
                        EdgeStripe(ctx).apply { this.tone = toneIndex },
                        android.widget.LinearLayout.LayoutParams(ctx.dp(2.6f), ctx.dp(38f)).apply {
                            marginEnd = ctx.dp(11f)
                            gravity = android.view.Gravity.CENTER_VERTICAL
                        },
                    )
                    addView(ctx.tileBadge(title.take(1), tone, 34f))
                    addView(ctx.col().apply {
                        setPadding(ctx.dp(10f), 0, ctx.dp(6f), 0)
                        addView(ctx.txt(title, Type.subtitle, ShellTheme.current.ink, medium = true))
                        addView(ctx.txt("Paper · 1.21.4", Type.micro, ShellTheme.current.ink3).apply {
                            setPadding(0, ctx.dp(4f), 0, 0)
                        })
                    }, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    addView(
                        ctx.statusTag(status, tone),
                        android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply { marginEnd = ctx.dp(8f) },
                    )
                    addView(ctx.txt(if (toneIndex == 2) "停止" else "启动", Type.micro, tone, medium = true).apply {
                        gravity = android.view.Gravity.CENTER
                        background = ctx.rip(
                            tone.alpha(0.12f), 9f,
                            stroke = tone.alpha(0.32f), strokeWidth = Dim.line,
                        )
                    }, android.widget.LinearLayout.LayoutParams(ctx.dp(64f), ctx.dp(33f)))
                })
            }, ctx.grow().apply { bottomMargin = ctx.dp(9f) })
        }

        root.addView(body)
        capture(name, root)
    }

    private fun renderWidgets(name: String) {
        val ctx = RuntimeEnvironment.getApplication()
        val root = FrameLayout(ctx).apply { setBackgroundColor(ShellTheme.current.bg) }
        val body = ctx.col().apply { setPadding(ctx.dp(20f), ctx.dp(24f), ctx.dp(20f), ctx.dp(24f)) }

        body.addView(ctx.txt("自绘控件", Type.display, ShellTheme.current.ink, medium = true))
        body.addView(ctx.spacer(14f))

        // 刻度盘：空闲 / 运行
        val gaugeRow = ctx.row()
        gaugeRow.addView(GaugeView(ctx).apply { progress = 0.12f }, android.widget.LinearLayout.LayoutParams(ctx.dp(110f), ctx.dp(110f)))
        gaugeRow.addView(ctx.gap(16f))
        gaugeRow.addView(GaugeView(ctx).apply { active = true; progress = 0.86f }, android.widget.LinearLayout.LayoutParams(ctx.dp(110f), ctx.dp(110f)))
        body.addView(gaugeRow)

        body.addView(ctx.spacer(18f))
        body.addView(ctx.sectionHeader("分段条", "更多"))
        body.addView(ctx.spacer(8f))
        body.addView(SegmentMeter(ctx).apply { segments = 18; fraction = 0.35f }, ctx.grow())
        body.addView(ctx.spacer(7f))
        body.addView(SegmentMeter(ctx).apply { segments = 18; fraction = 0.72f; tone = 2 }, ctx.grow())
        body.addView(ctx.spacer(7f))
        body.addView(SegmentMeter(ctx).apply { segments = 18; fraction = 0.95f; tone = 3 }, ctx.grow())

        body.addView(ctx.spacer(16f))
        body.addView(RibbonProgress(ctx).apply { fraction = 0.42f }, ctx.grow())
        body.addView(ctx.spacer(8f))
        body.addView(RibbonProgress(ctx).apply { indeterminate = true }, ctx.grow())

        body.addView(ctx.spacer(18f))
        val toggleRow = ctx.row()
        toggleRow.addView(TogglePill(ctx).apply { setChecked(true, animate = false) })
        toggleRow.addView(ctx.gap(14f))
        toggleRow.addView(TogglePill(ctx).apply { setChecked(false, animate = false) })
        toggleRow.addView(ctx.gap(14f))
        toggleRow.addView(BreathDot(ctx).apply { running = true; color = ShellTheme.current.good })
        toggleRow.addView(ctx.gap(14f))
        toggleRow.addView(EdgeStripe(ctx).apply { tone = 2 }, android.widget.LinearLayout.LayoutParams(ctx.dp(3f), ctx.dp(26f)))
        body.addView(toggleRow)

        body.addView(ctx.spacer(18f))
        val chipRow = ctx.row()
        chipRow.addView(ctx.chip("Paper"))
        chipRow.addView(ctx.gap(8f))
        chipRow.addView(ctx.statusTag("运行中", ShellTheme.current.good))
        chipRow.addView(ctx.gap(8f))
        chipRow.addView(ctx.statusTag("启动失败", ShellTheme.current.danger))
        body.addView(chipRow)

        body.addView(ctx.spacer(18f))
        body.addView(
            ctx.card().apply {
                addView(ctx.kvRow("内存上限", "2048 MB"))
                addView(ctx.splitter())
                addView(ctx.settingRow("nogui", "不加载服务端图形界面", TogglePill(ctx).apply { setChecked(true, animate = false) }))
            },
            ctx.grow(),
        )

        body.addView(ctx.spacer(14f))
        body.addView(ctx.primaryButton("启动服务端") { }, ctx.grow())
        body.addView(ctx.spacer(8f))
        val btnRow = ctx.row()
        btnRow.addView(ctx.outlineButton("取消") { })
        btnRow.addView(ctx.gap(9f))
        btnRow.addView(ctx.dangerButton("删除") { })
        body.addView(btnRow, ctx.grow())

        body.addView(ctx.spacer(14f))
        body.addView(ctx.banner("Linux 环境未部署，首次启动会自动下载", ShellTheme.current.warn, "部署") { }, ctx.grow())

        body.addView(ctx.spacer(14f))
        body.addView(ctx.skeleton(12f), ctx.grow())
        body.addView(ctx.spacer(6f))
        body.addView(ctx.skeleton(12f), ctx.grow())

        root.addView(body)
        capture(name, root)
    }

    /** 空态与选择弹层构图 */
    @Test
    fun states_paper() {
        ShellTheme.current = PaperTokens
        val ctx = RuntimeEnvironment.getApplication()
        val root = FrameLayout(ctx).apply { setBackgroundColor(ShellTheme.current.bg) }
        val body = ctx.col().apply { setPadding(ctx.dp(20f), ctx.dp(30f), ctx.dp(20f), ctx.dp(20f)) }
        body.addView(
            ctx.emptyState("还没有服务端实例", "选核心与版本，应用会下载 jar 并建好目录", "新建实例") { },
            ctx.grow(),
        )
        body.addView(ctx.spacer(18f))
        body.addView(ctx.card().apply {
            addView(ctx.row().apply {
                setPadding(ctx.dp(2f), ctx.dp(10f), ctx.dp(2f), ctx.dp(10f))
                addView(ctx.col().apply {
                    addView(ctx.txt("Paper", Type.body, ShellTheme.current.ink, medium = true))
                    addView(ctx.txt("性能优化 · 服务端专用", Type.micro, ShellTheme.current.ink3).apply {
                        setPadding(0, ctx.dp(3f), 0, 0)
                    })
                }, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(ctx.txt("✓", 15f, ShellTheme.current.accent, medium = true))
            })
            addView(ctx.splitter(0f))
            addView(ctx.row().apply {
                setPadding(ctx.dp(2f), ctx.dp(10f), ctx.dp(2f), ctx.dp(10f))
                addView(ctx.col().apply {
                    addView(ctx.txt("Purpur", Type.body, ShellTheme.current.ink, medium = true))
                    addView(ctx.txt("性能优化 · 可定制机制", Type.micro, ShellTheme.current.ink3).apply {
                        setPadding(0, ctx.dp(3f), 0, 0)
                    })
                }, android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            })
        }, ctx.grow())
        root.addView(body)
        capture("states-paper", root)
    }

    private fun android.content.Context.grow() = android.widget.LinearLayout.LayoutParams(
        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private fun android.content.Context.gap(width: Float) = View(this).apply {
        layoutParams = android.widget.LinearLayout.LayoutParams(dp(width), 1)
    }
}
