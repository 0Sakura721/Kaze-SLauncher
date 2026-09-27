package com.kaze.newage.core.console

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 往回读日志文件。
 *
 * 核心断言只有一条但很强：**把所有分块按顺序拼起来，必须与原始文件逐行相同** ——
 * 块边界切行是这类实现最容易漏行的地方，而漏掉的往往正好是用户要找的报错。
 */
class ConsoleArchiveTest {

    private fun dirWith(name: String, content: String): File {
        val d = createTempDirectory("kaze-arc").toFile()
        File(d, name).writeText(content)
        return d
    }

    private fun drain(dir: File, chunk: Int = 10): List<String> {
        val out = ArrayList<String>()
        var cursor: ConsoleArchive.Cursor? = null
        var guard = 0
        while (guard++ < 1000) {
            val c = ConsoleArchive.readOlder(dir, cursor, chunk)
            out.addAll(0, c.lines)
            if (!c.hasMore) break
            cursor = c.cursor ?: break
        }
        return out
    }

    @Test
    fun `分块回读拼起来与原文逐行相同`() {
        val lines = (1..1000).map { "第 $it 行：内容 content-$it" }
        val dir = dirWith("console-output.log", lines.joinToString("\n") + "\n")

        assertEquals("回读结果必须与原文一致", lines, drain(dir))
    }

    @Test
    fun `极端小的分块也不会漏行`() {
        // 每块只要 1 行 → 逼出"块边界切断行"的情况
        val lines = (1..200).map { "line-$it" }
        val dir = dirWith("console-output.log", lines.joinToString("\n") + "\n")
        assertEquals(lines, drain(dir, chunk = 1))
    }

    @Test
    fun `新日志读完后继续读轮转的旧日志`() {
        val dir = createTempDirectory("kaze-arc").toFile()
        File(dir, "console-output.old.log").writeText((1..50).joinToString("\n") { "旧-$it" } + "\n")
        File(dir, "console-output.log").writeText((1..50).joinToString("\n") { "新-$it" } + "\n")

        val got = drain(dir)
        assertEquals("轮转前后的行都要能读到", 100, got.size)
        assertEquals("最早的是旧日志", "旧-1", got.first())
        assertEquals("最新的是当前日志", "新-50", got.last())
        assertTrue(got.contains("旧-50"))
        assertTrue(got.contains("新-1"))
    }

    @Test
    fun `没有日志文件时安全返回`() {
        val dir = createTempDirectory("kaze-arc").toFile()
        val c = ConsoleArchive.readOlder(dir, null)
        assertTrue(c.lines.isEmpty())
        assertFalse("没有内容就不该说还有更多", c.hasMore)
    }

    @Test
    fun `hasMore 在读到文件头后变为 false`() {
        val dir = dirWith("console-output.log", (1..30).joinToString("\n") { "l$it" } + "\n")
        var cursor: ConsoleArchive.Cursor? = null
        var rounds = 0
        while (true) {
            val c = ConsoleArchive.readOlder(dir, cursor, 10)
            rounds++
            if (!c.hasMore) break
            cursor = c.cursor!!
            if (rounds > 50) break
        }
        assertTrue("30 行 / 每块 10 行，应在大约 3~4 轮内到底（实际 $rounds）", rounds <= 5)
    }

    @Test
    fun `文件末尾没有换行符也能读到最后一行`() {
        val dir = dirWith("console-output.log", "a\nb\nc")   // 末行无 \n
        val got = drain(dir)
        assertEquals(listOf("a", "b", "c"), got)
    }

    /**
     * 超过一个块（[ConsoleArchive] 内部 BLOCK = 256 KB）的文件。
     *
     * 这是旧实现的**盲区**：文件不到 256 KB 时块边界永远落不进文件内部，
     * "块首半行交给下一次"那条分支根本走不到，而正是它在跨块时把边界行拼坏
     * （块首半行与下一块的读取区间重叠 → 同一段读两遍、边界行被截断重复）。
     * 真机上 console-output.log 轻易就有几 MB，所以这里必须真的超过一个块。
     *
     * 用中文压"按字节结算"这条路径：一行 3 字节起，按字符数算落点必然错位。
     */
    @Test
    fun `超过一个块的文件分块回读不重不漏`() {
        val lines = (1..40000).map { "第 $it 行：这是中文日志内容 content-$it" }
        val dir = dirWith("console-output.log", lines.joinToString("\n") + "\n")
        val size = File(dir, "console-output.log").length()
        assertTrue("用例必须真的超过一个块（实际 $size 字节），否则又回到盲区", size > 256 * 1024)

        assertEquals("跨块回读必须与原文逐行相同", lines, drain(dir, chunk = 1000))
    }

    /** 两份日志都超过一个块，且跨文件切换正好落在块边界附近 */
    @Test
    fun `超过一个块时轮转的两份日志都能完整读回`() {
        val dir = createTempDirectory("kaze-arc").toFile()
        val old = (1..20000).map { "旧日志第 $it 行：内容内容内容" }
        val new = (1..20000).map { "新日志第 $it 行：内容内容内容" }
        File(dir, "console-output.old.log").writeText(old.joinToString("\n") + "\n")
        File(dir, "console-output.log").writeText(new.joinToString("\n") + "\n")
        assertTrue(File(dir, "console-output.old.log").length() > 256 * 1024)
        assertTrue(File(dir, "console-output.log").length() > 256 * 1024)

        val got = drain(dir, chunk = 1000)
        assertEquals(old.size + new.size, got.size)
        assertEquals("两份日志的拼接顺序必须是先旧后新", old + new, got)
    }
}
