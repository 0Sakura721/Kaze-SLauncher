package com.kaze.newage.shell

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * 实例卡片在各种状态下的渲染快照。
 *
 * 空态截图（activity-home）里没有卡片，所以"卡片在长状态文案下是否还完整"
 * 只能靠这种针对性快照来核。320dp 是最窄的现实机型，也是最容易崩的宽度。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h640dp-mdpi")
class CardShotTest {

    private val outDir = File("build/shell-shots").apply { mkdirs() }

    /** 复刻 HomeScreen.buildCard 的行结构（与实现保持同步） */
    private fun buildCard(
        ctx: android.content.Context,
        name: String,
        status: String,
        pinned: Boolean,
        running: Boolean,
    ): LinearLayout {
        val tone = if (running) ShellTheme.current.good else ShellTheme.current.warn
        val card = ctx.bareCard()
        card.addView(ctx.row().apply {
            setPadding(ctx.dp(12f), ctx.dp(13f), ctx.dp(13f), ctx.dp(13f))
            addView(
                EdgeStripe(ctx).apply { this.tone = if (running) 2 else 3 },
                LinearLayout.LayoutParams(ctx.dp(2.6f), ctx.dp(38f)).apply {
                    marginEnd = ctx.dp(11f)
                    gravity = android.view.Gravity.CENTER_VERTICAL
                },
            )
            addView(ctx.tileBadge(name.take(1), tone, 34f))

            val meta = ctx.col().apply {
                setPadding(ctx.dp(10f), 0, ctx.dp(6f), 0)
                val nameRow = ctx.row()
                nameRow.addView(
                    ctx.txt(name, Type.subtitle, ShellTheme.current.ink, medium = true).apply {
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    },
                )
                if (pinned) {
                    nameRow.addView(
                        ctx.txt("置顶", Type.micro, ShellTheme.current.accent, medium = true).apply {
                            setPadding(ctx.dp(6f), 0, 0, 0)
                        },
                    )
                }
                addView(nameRow)
                // 与 HomeScreen 一致：副标题与状态胶囊分两行
                addView(
                    ctx.txt("Paper · 1.20.4 · 1024 MB", Type.micro, ShellTheme.current.ink3).apply {
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setPadding(0, ctx.dp(4f), 0, 0)
                    },
                )
                addView(
                    ctx.statusTag(status, tone),
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = ctx.dp(5f) },
                )
            }
            addView(meta, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            addView(
                ctx.txt(if (running) "停止" else "启动", Type.micro, tone, medium = true).apply {
                    gravity = android.view.Gravity.CENTER
                    background = ctx.rip(tone.alpha(0.12f), 9f, stroke = tone.alpha(0.32f), strokeWidth = Dim.line)
                },
                LinearLayout.LayoutParams(ctx.dp(64f), ctx.dp(33f)),
            )
        })
        return card
    }

    @Test
    fun cards_at_320dp() {
        ShellTheme.current = PaperTokens
        val ctx = RuntimeEnvironment.getApplication()
        val density = ctx.resources.displayMetrics.density
        val w = (320 * density).toInt()
        val h = (400 * density).toInt()

        val root = FrameLayout(ctx)
        root.addView(PaperBackdrop(ctx), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        val col = ctx.col().apply { setPadding(ctx.dp(18f), ctx.dp(18f), ctx.dp(18f), ctx.dp(18f)) }

        listOf(
            Triple("生存服", "首次启动 · 生成 eula.txt", false),
            Triple("模组测试服", "已同意 EULA，正在重启", true),
            Triple("创造", "运行中", false),
        ).forEach { (name, status, pinned) ->
            col.addView(
                buildCard(ctx, name, status, pinned, status == "运行中"),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = ctx.dp(9f) },
            )
        }

        root.addView(col)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, w, h)

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(ShellTheme.current.bg)
        root.draw(canvas)
        FileOutputStream(File(outDir, "cards-320dp.png")).use {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
        }

        // 顺带把每张卡里名称列的实测宽度打出来，便于对照
        for (i in 0 until col.childCount) {
            val card = col.getChildAt(i) as LinearLayout
            val row = card.getChildAt(0) as LinearLayout
            val meta = row.getChildAt(2) as LinearLayout
            val nameText = meta.getChildAt(0) as LinearLayout
            println("CARD[$i] meta=${meta.width}px nameLines=${(nameText.getChildAt(0) as TextView).lineCount}")
        }
    }
}
