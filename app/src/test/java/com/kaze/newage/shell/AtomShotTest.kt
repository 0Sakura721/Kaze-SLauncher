package com.kaze.newage.shell

import android.graphics.Bitmap
import android.graphics.Canvas
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
 * 单个原子件的放大快照。
 *
 * 整屏截图里 12sp 的小字只有几个像素高，判断不了"换行到底是不是设计意图"。
 * 这里只画一个组件、留足边距，任何折行/裁切都一目了然。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class AtomShotTest {

    private val outDir = File("build/shell-shots").apply { mkdirs() }

    private fun shot(name: String, heightDp: Int, block: android.content.Context.(android.widget.LinearLayout) -> Unit) {
        val ctx = RuntimeEnvironment.getApplication()
        val root = FrameLayout(ctx)
        root.addView(PaperBackdrop(ctx), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        val col = ctx.col().apply { setPadding(ctx.dp(18f), ctx.dp(18f), ctx.dp(18f), ctx.dp(18f)) }
        ctx.block(col)
        root.addView(col, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
        ))

        val density = ctx.resources.displayMetrics.density
        val w = (411 * density).toInt()
        val h = (heightDp * density).toInt()
        root.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(ShellTheme.current.bg)
        root.draw(canvas)
        FileOutputStream(File(outDir, "$name.png")).use {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun banner_long_text() = shot("atom-banner", 190) { col ->
        ShellTheme.current = PaperTokens
        col.addView(
            banner(
                "Linux 环境未部署。启动实例时会自动下载环境，也可以在设置里先部署。",
                ShellTheme.current.warn,
                "部署",
            ) { },
        )
        col.addView(spacer(14f))
        col.addView(
            banner(
                "「模组服」启动失败，进控制台看具体原因。",
                ShellTheme.current.danger,
                "查看",
            ) { },
        )
        col.addView(spacer(14f))
        col.addView(
            banner(
                "控制台日志已导出到 应用私有目录 logs/ 下，共 3 个文件。",
                ShellTheme.current.info,
            ),
        )
    }

    @Test
    fun empty_and_errors() = shot("atom-empty", 330) { col ->
        ShellTheme.current = PaperTokens
        col.addView(emptyState("还没有服务端实例", "选核心与版本，应用会下载 jar 并建好目录", "新建实例") { })
    }

    @Test
    fun rows_and_kv() = shot("atom-rows", 300) { col ->
        ShellTheme.current = PaperTokens
        col.addView(card().apply {
            addView(kvRow("核心", "Paper 1.21.4"))
            addView(splitter())
            addView(kvRow("内存上限", "4096 MB"))
            addView(splitter())
            addView(settingRow("nogui", "不加载服务端图形界面", TogglePill(context).apply { setChecked(true, animate = false) }))
            addView(splitter())
            addView(settingRow("停服自动备份", "保留最近 5 份", chevron()) { })
        })
    }
}
