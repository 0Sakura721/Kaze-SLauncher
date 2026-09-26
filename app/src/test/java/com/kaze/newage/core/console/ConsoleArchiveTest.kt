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
}
