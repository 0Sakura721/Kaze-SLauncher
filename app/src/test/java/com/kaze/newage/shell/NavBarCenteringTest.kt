package com.kaze.newage.shell

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 底部导航的居中约束。
 *
 * ## 为什么必须有这条测试
 * 页签文字曾经整体贴在自己那格的左边缘。根因是父容器写了 `gravity = CENTER`，
 * 而 `LinearLayout.layoutVertical` 对子 View 用的是
 * `mGravity & VERTICAL_GRAVITY_MASK` —— 横向位在那一步被抹掉，落到
 * `default: childLeft = paddingLeft`。也就是说**父容器的横向 gravity 不生效**，
 * 必须逐个下发给子 View 的 `lp.gravity`。
 *
 * 这种"看起来只是有点偏"的缺陷在白底浅色截图上几乎看不出来（上一版就是这么漏掉的），
 * 所以这里不靠肉眼，直接把 layout 后的坐标量出来断言。
 *
 * 断言阈值取 2px：字体左右出边（side bearing）本来就有亚像素差异，
 * 真机上实测居中后仍有 1~2px 偏差；超过 2px 就是真的没居中。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xhdpi")
class NavBarCenteringTest {

    private fun buildNav(): ShellNavBar {
        ShellTheme.current = PaperTokens
        val ctx = RuntimeEnvironment.getApplication()
        return ShellNavBar(ctx).apply {
            tabs = listOf(
                ShellNavBar.Tab("home", "实例"),
                ShellNavBar.Tab("console", "控制台"),
                ShellNavBar.Tab("settings", "设置"),
            )
            selected = 0
        }
    }

    private fun layoutNav(nav: ShellNavBar, density: Float): Pair<Int, Int> {
        val w = (360 * density).toInt()
        val h = (58 * density).toInt()
        nav.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        nav.layout(0, 0, w, h)
        return w to h
    }

    @Test
    fun every_tab_label_is_centered_in_its_cell() {
        val ctx = RuntimeEnvironment.getApplication()
        val nav = buildNav()
        val density = ctx.resources.displayMetrics.density
        layoutNav(nav, density)

        val problems = StringBuilder()
        for (i in 0 until nav.childCount) {
            val cell = nav.getChildAt(i) as android.view.ViewGroup
            // 子 View 的 left/right 是**相对父**的，所以基准也要用 cell 的内容区中线
            val inL = cell.paddingLeft
            val inR = cell.width - cell.paddingRight
            val expected = (inL + inR) / 2f

            for (j in 0 until cell.childCount) {
                val child = cell.getChildAt(j)
                val actual = (child.left + child.right) / 2f
                val off = actual - expected
                if (kotlin.math.abs(off) > 2f) {
                    problems.append("cell[$i].${child.tag}: center=$actual 期望=$expected 偏移=$off\n")
                }
            }
        }
        assertTrue("页签未在各自格内居中：\n$problems", problems.isEmpty())
    }

    /**
     * 像素级复核：把导航条真的画出来，量每个文字块的墨迹中心。
     *
     * 布局坐标对了、绘制出来却偏了的情况是存在的（例如背景 drawable 带 inset、
     * 或 textView 自己又加了一层 padding）。这条测试守住"最终画出来的样子"。
     */
    @Test
    fun drawn_labels_are_centered_in_their_cells() {
        val ctx = RuntimeEnvironment.getApplication()
        val nav = buildNav()
        val density = ctx.resources.displayMetrics.density
        val (w, h) = layoutNav(nav, density)

        // 放在同尺寸容器里画，避免 nav 自己的 background 影响 alpha
        val host = FrameLayout(ctx).apply {
            setBackgroundColor(ShellTheme.current.bg)
            addView(nav, FrameLayout.LayoutParams(w, h))
        }
        host.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        host.layout(0, 0, w, h)

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        host.draw(Canvas(bmp))

        val pre = nav.paddingLeft
        val cellW = (w - nav.paddingLeft - nav.paddingRight) / 3.0
        val bgLum = lum(Color.red(ShellTheme.current.bg), Color.green(ShellTheme.current.bg), Color.blue(ShellTheme.current.bg))

        val report = StringBuilder("w=$w h=$h cellW=$cellW pre=$pre bgLum=$bgLum\n")
        for (i in 0 until 3) {
            val x0 = (pre + cellW * i).toInt()
            val x1 = (pre + cellW * (i + 1)).toInt() - 1
            var min = Int.MAX_VALUE
            var max = -1
            for (y in 0 until h) {
                for (x in x0..x1) {
                    val p = bmp.getPixel(x, y)
                    val l = lum(Color.red(p), Color.green(p), Color.blue(p))
                    // 与底色差得够多才算墨迹（文字或指示条）
                    if (kotlin.math.abs(l - bgLum) > 40) {
                        if (x < min) min = x
                        if (x > max) max = x
                    }
                }
            }
            val cellCenter = pre + cellW * i + cellW / 2.0
            if (max < 0) {
                report.append("cell[$i] 无墨迹\n")
            } else {
                val c = (min + max) / 2.0
                report.append(
                    "cell[$i] ink=$min..$max center=${"%.1f".format(c)} " +
                        "cellCenter=${"%.1f".format(cellCenter)} off=${"%.1f".format(c - cellCenter)}\n",
                )
                assertTrue(
                    "第 $i 格绘制结果未居中（偏移 ${"%.1f".format(c - cellCenter)}px）\n$report",
                    kotlin.math.abs(c - cellCenter) <= 2.0,
                )
            }
        }
    }

    private fun lum(r: Int, g: Int, b: Int) = 0.299 * r + 0.587 * g + 0.114 * b
}
