package com.kaze.newage.core.console

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 同类刷屏折叠。
 *
 * 起因（真机反馈）：补全依赖 / 解压时每个文件一行，几千行直接把 5000 行环形缓冲冲爆，
 * 屏幕上只剩「5000 行（上限）」，前面真正有用的输出全被挤掉。
 *
 * 折叠只作用于**控制台**：`slot.log()` 已经同步把每一行写进实例目录的
 * `console-output.log`，完整记录一条不少。
 */
class ConsoleStreamBurstTest {

    private fun stream() = ConsoleStream(maxLines = 5000)

    @Test
    fun `逐个文件的同类输出会被折叠成一行汇总`() {
        val s = stream()
        repeat(1000) { i -> s.emit("Downloading library-$i.jar") }

        val lines = s.snapshot()
        assertTrue("1000 行同类输出不该真的占 1000 行，实际 ${lines.size}", lines.size < 20)
        assertTrue(
            "应有一行说明折叠了多少行",
            lines.any { it.text.contains("同类输出已折叠") },
        )
        val folded = lines.first { it.text.contains("同类输出已折叠") }
        assertTrue("应提示完整内容在哪：${folded.text}", folded.text.contains("console-output.log"))
        // 前几条应当原样保留（用户能看清是什么操作）
        assertTrue(lines.any { it.text.trim() == "Downloading library-0.jar" })
    }

    @Test
    fun `折叠计数会随刷屏持续增长`() {
        val s = stream()
        fun foldedNow(): Int =
            Regex("已折叠 (\\d+) 行").findAll(s.snapshot().map { it.text }.joinToString("\n"))
                .last().groupValues[1].toInt()

        repeat(50) { i -> s.emit("Extracting file-$i.so") }
        val n1 = foldedNow()
        repeat(50) { i -> s.emit("Extracting more-$i.so") }
        val n2 = foldedNow()
        assertTrue("继续刷屏时计数应增长（$n1 → $n2）", n2 > n1)
    }

    @Test
    fun `真正的服务器日志不会被折叠（这是信息不是噪声）`() {
        // 这些行共享前缀但没有"刷屏型"动词，早期版本的"前 20 字符相同"判据会把它们折叠掉
        val s = stream()
        repeat(500) { i ->
            s.emit("[12:00:0${i % 10}] [Server thread/INFO]: <Steve> 这是第 $i 条聊天消息")
        }
        assertEquals("聊天/INFO 日志一行都不能少", 500, s.snapshot().size)
        assertTrue(s.snapshot().none { it.text.contains("已折叠") })
    }

    @Test
    fun `长期运行到上限时记录被丢掉的行数`() {
        val s = ConsoleStream(maxLines = 100)
        repeat(350) { i -> s.emit("tick $i") }   // 不同内容，不触发折叠
        assertEquals("缓冲保留上限行数", 100, s.snapshot().size)
        assertEquals("应记下丢掉的行数（供界面说明去哪找）", 250, s.droppedCount)
    }

    @Test
    fun `不同类的输出不会被误折叠`() {
        val s = stream()
        s.emit("正在解压 rootfs…")
        s.emit("Java 检测：/usr/lib/jvm/java-17-openjdk-arm64")
        s.emit("已写入 server.properties")
        s.emit("启动服务端")
        s.emit("[12:00:01] Done (3.2s)! For help, type \"help\"")
        s.emit("实例已就绪")

        val lines = s.snapshot()
        assertEquals("不同内容应一行不少", 6, lines.size)
        assertTrue(lines.none { it.text.contains("已折叠") })
    }

    @Test
    fun `空行不参与折叠_段落结构保留`() {
        val s = stream()
        repeat(30) { s.emit("") }
        assertEquals("连续空行应原样保留（不折叠）", 30, s.snapshot().size)
    }

    @Test
    fun `刷屏结束后换一类输出_计数重新开始`() {
        val s = stream()
        repeat(100) { i -> s.emit("Downloading a-$i.jar") }
        val afterFlood = s.snapshot().size
        s.emit("Downloading complete.")     // 不同 family（前缀不同）
        s.emit("Starting server")

        val lines = s.snapshot()
        assertTrue("刷屏只占很少几行，实际 $afterFlood", afterFlood < 20)
        assertTrue("后续正常输出应正常显示", lines.any { it.text == "Starting server" })
    }

    @Test
    fun `replaceLast 的原地进度行不受折叠影响`() {
        val s = stream()
        // 服务端用 \r 输出进度：每次都应替换上一行，最终只留一行
        repeat(200) { i -> s.emitReplace("Preparing spawn area: $i%") }
        val lines = s.snapshot()
        assertEquals("原地进度行最终只应剩一行", 1, lines.size)
        assertTrue(lines[0].text.contains("99%"))
    }
}
