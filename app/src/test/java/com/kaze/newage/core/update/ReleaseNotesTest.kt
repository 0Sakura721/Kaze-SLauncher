package com.kaze.newage.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 发布说明的 Markdown → 纯文本转换。起因是真机截图：更新弹窗把 `## v0.3.1-fix`、
 * `>`、`**修复向**`、`| 表格 |` 原样显示给用户，还被 `take(400)` 截断。
 */
class ReleaseNotesTest {

    @Test
    fun `标题 引用 加粗 行内代码 都被清理`() {
        val md = """
            ## v0.3.1-fix

            > 这是一次**修复向**的版本。

            ### 安装前必读

            - 签名与 `0.3.0` 相同，**可直接覆盖升级**。
        """.trimIndent()

        val out = ReleaseNotes.toPlainText(md, dropTitle = "v0.3.1-fix")

        assertFalse("不应残留 # 号", out.contains("#"))
        assertFalse("不应残留引用符号", out.contains(">"))
        assertFalse("不应残留加粗标记", out.contains("**"))
        assertFalse("不应残留行内代码反引号", out.contains("`"))
        assertTrue("正文要保留", out.contains("这是一次修复向的版本"))
        assertTrue("列表项要保留", out.contains("可直接覆盖升级"))
        assertTrue("小标题要保留", out.contains("安装前必读"))
    }

    @Test
    fun `首个含版本号的标题会被丢掉（弹窗标题已经写了版本）`() {
        val out = ReleaseNotes.toPlainText("## v0.3.1-fix\n\n正文", dropTitle = "v0.3.1-fix")
        assertFalse("重复的版本标题应去掉", out.contains("v0.3.1-fix"))
        assertTrue(out.contains("正文"))
    }

    @Test
    fun `表格转成可读的一行 且丢掉分隔行`() {
        val md = """
            | 你的设备 | 下载 |
            |---|---|
            | 64 位手机 | `arm64-v8a` |
        """.trimIndent()

        val out = ReleaseNotes.toPlainText(md)
        assertFalse("不应残留竖线", out.contains("|"))
        assertFalse("不应残留分隔行", out.contains("---"))
        assertTrue(out.contains("你的设备 · 下载"))
        assertTrue(out.contains("64 位手机 · arm64-v8a"))
    }

    @Test
    fun `链接只留文字 分隔线变空行`() {
        val out = ReleaseNotes.toPlainText("见 [CHANGELOG](https://x/y.md)\n\n---\n\n完")
        assertTrue(out.contains("见 CHANGELOG"))
        assertFalse("URL 不该出现在对话框里", out.contains("https://"))
        assertTrue(out.contains("完"))
    }

    @Test
    fun `连续空行会被压掉`() {
        val out = ReleaseNotes.toPlainText("a\n\n\n\n\nb")
        assertEquals("a\n\nb", out)
    }

    @Test
    fun `真实发布正文能被完整转换（不丢内容）`() {
        // 取一段与 v0.3.1-fix 发布说明同构的文本，确认关键信息都在
        val md = """
            ## v0.3.1-fix

            > 这是一次**修复向**的版本。

            ### 关于增量补丁（本次先备好，客户端下版接入）

            | 从 | 到 | 补丁 | 整包 | 占比 |
            |---|---|---|---|---|
            | v0.3.0 arm64 | v0.3.1-fix arm64 | 1.61 MB | 30.80 MB | **5.2%** |

            - 签名与 0.3.0 相同，可直接覆盖升级。
        """.trimIndent()

        val out = ReleaseNotes.toPlainText(md, dropTitle = "v0.3.1-fix")
        for (key in listOf("修复向", "增量补丁", "1.61 MB", "30.80 MB", "5.2%", "可直接覆盖升级")) {
            assertTrue("关键信息不能丢：$key", out.contains(key))
        }
    }
    /**
     * 对**真实发布正文**做一次转换（需要 -Dkaze.notes.file=<文件>）：输出写到同名 .txt，
     * 用来肉眼确认"真东西转出来是不是干净的" —— 单测只证明我构造的样例没问题。
     */
    @Test
    fun `真实发布正文转换后可读_需要提供文件`() {
        val path = System.getProperty("kaze.notes.file")
        if (path.isNullOrBlank() || !java.io.File(path).isFile) {
            println("跳过：未提供 -Dkaze.notes.file")
            return
        }
        val md = java.io.File(path).readText()
        val out = ReleaseNotes.toPlainText(md, dropTitle = "v0.3.1-fix")
        java.io.File(path).resolveSibling("release-body.plain.txt").writeText(out)

        assertFalse("真实正文转换后不应残留 # 标题", out.contains("##"))
        assertFalse("真实正文转换后不应残留表格竖线", out.contains("|"))
        assertFalse("真实正文转换后不应残留加粗标记", out.contains("**"))
        assertFalse("真实正文转换后不应残留引用符号", Regex("(?m)^>").containsMatchIn(out))
        assertFalse("真实正文转换后不应残留行内代码", out.contains("`"))
    }

    @Test
    fun `跨行的加粗标记也要清掉（真机就是这里漏的）`() {
        // 发布正文是硬换行的，**加粗** 很容易跨行 —— 逐行跑正则会漏掉这一对
        val md = """
            而 lines.size 每来一行就变一次 —— **每次变化都会取消
            上一个 effect**，动画就停在半路。
        """.trimIndent()

        val out = ReleaseNotes.toPlainText(md)
        assertFalse("不应残留加粗标记：$out", out.contains("**"))
        assertTrue("两行应合并成一句", out.contains("每次变化都会取消 上一个 effect"))
    }
}
