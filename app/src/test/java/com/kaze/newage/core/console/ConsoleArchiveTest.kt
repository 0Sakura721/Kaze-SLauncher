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
        // 分块是"从新到旧"返回的，每个块都要前插。用 `addAll(0, …)` 在万行级是 O(n²)，
        // 所以先把块收集起来，最后倒序拼接。轮数上限按块数算 —— 写死 1000 会让
        // "每块只取几行 + 文件上万行"的用例在没读完时被截断（那看起来像漏行）。
        val chunks = ArrayList<List<String>>()
        var cursor: ConsoleArchive.Cursor? = null
        while (true) {
            val c = ConsoleArchive.readOlder(dir, cursor, chunk)
            chunks.add(c.lines)
            if (!c.hasMore) break
            cursor = c.cursor ?: break
            check(chunks.size < 100_000) { "回读没有推进（块数 ${chunks.size}）：游标落点有问题" }
        }
        val out = ArrayList<String>(chunks.sumOf { it.size })
        for (i in chunks.indices.reversed()) out.addAll(chunks[i])
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
     * 块大小是 256 KB，所以**文件必须超过一个块**才会让"块边界落在文件内部"。
     * 上面那几条用例都只有几十 KB —— 那条分支根本走不到，正是旧实现（块首半行带在
     * carry 里、与下一块区间重叠）的盲区：它会把边界行读两遍、拼成 `line-1` 这种残行。
     *
     * 每块取默认的 3000 行（贴近真实翻页），否则一个块要被反复重读、用例慢到没意义。
     */
    @Test
    fun `超过一个块的文件分块回读不重不漏`() {
        // 中文一行 3 字节：4 万行 ≈ 1 MB，稳稳超过四个块
        val lines = (1..40_000).map { "第 $it 行：区块生成与实体 tick 的日志内容 content-$it" }
        val dir = dirWith("console-output.log", lines.joinToString("\n") + "\n")

        val got = drain(dir, chunk = 3000)
        assertEquals("行数必须一致（重读会变多、漏读会变少）", lines.size, got.size)
        assertEquals("回读结果必须与原文逐行相同", lines, got)
    }

    /** 轮转后的两份日志**都超过一个块**时，拼接顺序与内容依然完整 */
    @Test
    fun `超过一个块时轮转的两份日志都能完整读回`() {
        val oldLines = (1..20_000).map { "旧日志第 $it 行：世界保存与自动重启的记录 old-$it" }
        val newLines = (1..20_000).map { "新日志第 $it 行：玩家加入与命令执行的记录 new-$it" }
        val dir = createTempDirectory("kaze-arc").toFile()
        File(dir, "console-output.old.log").writeText(oldLines.joinToString("\n") + "\n")
        File(dir, "console-output.log").writeText(newLines.joinToString("\n") + "\n")

        val got = drain(dir, chunk = 3000)
        assertEquals(listOf(oldLines, newLines).sumOf { it.size }, got.size)
        assertEquals("旧日志在前、新日志在后且各自完整", oldLines + newLines, got)
    }

    /**
     * 文件只比一个块大一点（刚好让块起点落在文件内部），而每块取的**行数远小于块容量**：
     * 同一个块会被连着读好几次，游标落点但凡算错就会重复或漏行。
     */
    @Test
    fun `超过一个块时分块远小于块容量也能翻到底`() {
        val lines = (1..9_000).map { "第 $it 行 line-$it 内容" }
        val dir = dirWith("console-output.log", lines.joinToString("\n") + "\n")
        assertTrue("用例前提：文件必须超过一个块", File(dir, "console-output.log").length() > 256 * 1024)

        assertEquals(lines, drain(dir, chunk = 200))
    }
}
