package com.kaze.newage.shell

import android.view.View
import android.view.ViewGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 设置行的点击可达性。
 *
 * 起因：在 MuMu 上点「Java 运行时」那一行毫无反应，而 uiautomator 显示
 * 文字节点 clickable=false。需要区分两种可能：
 *  a) 真的点不动（tappable 没生效）
 *  b) 只是 uiautomator 报的是叶子 TextView 的属性，可点的是父容器
 *
 * 靠肉眼看截图分不出来，这里直接对 view 树做 performClick 并断言回调被触发。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-xhdpi")
class SettingRowClickTest {

    private fun layout(v: View, w: Int = 720, h: Int = 120) {
        v.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.AT_MOST),
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
    }

    @Test
    fun row_with_click_is_clickable_and_fires() {
        ShellTheme.current = InkTokens
        val ctx = RuntimeEnvironment.getApplication()
        var hits = 0

        val row = ctx.settingRow(
            title = "Java 运行时",
            subtitle = "已装版本；启动实例时按需自动安装",
            trailing = ctx.chevron(),
            onClick = { hits++ },
        )
        layout(row)

        assertTrue("整行应当可点击", row.isClickable)
        assertTrue("整行应当可获得焦点", row.isFocusable)
        assertEquals("Java 运行时", row.contentDescription)

        row.performClick()
        assertEquals("点击应当触发一次回调", 1, hits)
    }

    @Test
    fun row_without_click_stays_inert() {
        ShellTheme.current = InkTokens
        val ctx = RuntimeEnvironment.getApplication()
        val row = ctx.settingRow("没有动作", "纯展示行", ctx.txt("值", Type.micro))
        layout(row)
        assertTrue("没有 onClick 的行不该可点击", !row.isClickable)
    }

    /**
     * 行内子 View（标题列、trailing）不能吞掉点击。
     *
     * LinearLayout 的子项默认不消费触摸，但如果某个子 View 自己设了
     * clickable=true（例如 trailing 是个按钮），点在它上面就不会冒泡到行 ——
     * 这是设计上可接受的（"去授权"就该只触发自己），所以这里只断言
     * **非交互子项**不会拦截。
     */
    @Test
    fun plain_children_do_not_block_row_click() {
        ShellTheme.current = InkTokens
        val ctx = RuntimeEnvironment.getApplication()
        var hits = 0
        val row = ctx.settingRow(
            title = "主题样式",
            subtitle = "岚 · 纸感 / 墨夜（自绘视图）",
            trailing = ctx.chevron(),
            onClick = { hits++ },
        )
        layout(row)

        // 找到标题列（weight=1 的那个子容器）
        val texts = (0 until row.childCount)
            .map { row.getChildAt(it) }
            .filterIsInstance<ViewGroup>()
            .firstOrNull { (it.layoutParams as android.widget.LinearLayout.LayoutParams).weight > 0f }
        assertTrue("应当有标题列", texts != null)
        assertTrue("标题列本身不该可点击（否则会挡住整行）", !texts!!.isClickable)

        row.performClick()
        assertEquals(1, hits)
    }
}
