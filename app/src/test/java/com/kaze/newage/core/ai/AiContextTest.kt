package com.kaze.newage.core.ai

import com.kaze.newage.core.console.ConsoleLine
import com.kaze.newage.core.console.LineType
import com.kaze.newage.core.monitor.ProcessStats
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI 上下文快照构建测试。
 *
 * 重点锁三件事：实例/状态/玩家等摘要齐全；更早的报错要能进上下文
 * （日志卡死时报错往往滚出了尾部窗口）；单行截断防止 stack trace 撑爆 token。
 */
class AiContextTest {

    private fun line(text: String, type: LineType = LineType.Info) = ConsoleLine(text, type)

    @Test
    fun `实例与状态摘要进入上下文`() {
        val ctx = AiContext.build(
            instanceName = "测试服", mcVersion = "1.21.4", coreType = "Paper",
            javaMajor = 21, memoryMb = 2048, stateLabel = "运行中", uptimeSec = 3725,
            players = listOf("Steve", "Alex"), stats = null, lines = emptyList(),
        )
        assertTrue(ctx.contains("测试服"))
        assertTrue(ctx.contains("1.21.4"))
        assertTrue(ctx.contains("Paper"))
        assertTrue(ctx.contains("Java 21"))
        assertTrue(ctx.contains("2048MB"))
        assertTrue(ctx.contains("运行中"))
        assertTrue(ctx.contains("1小时2分"))
        assertTrue(ctx.contains("2 人：Steve、Alex"))
    }

    @Test
    fun `零玩家与未知版本不崩且可读`() {
        val ctx = AiContext.build(
            instanceName = "", mcVersion = "", coreType = "导入 jar",
            javaMajor = 17, memoryMb = 1024, stateLabel = "空闲", uptimeSec = 0,
            players = emptyList(), stats = null, lines = emptyList(),
        )
        assertTrue(ctx.contains("（未命名）"))
        assertTrue(ctx.contains("MC 未知"))
        assertTrue(ctx.contains("【在线玩家】无"))
        assertFalse(ctx.contains("【资源】"))
        assertFalse(ctx.contains("【控制台最近"))
    }

    @Test
    fun `尾部日志与更早的报错都进入上下文`() {
        // 40 条报错在尾部窗口（100 行）之外；TAIL_LINES=100、MAX_ERROR_LINES=30
        val errors = (1..40).map { line("[Server] ERROR #$it boom", LineType.Error) }
        val tail = (1..100).map { line("info line $it") }
        val ctx = AiContext.build(
            instanceName = "n", mcVersion = "1.21.4", coreType = "Paper",
            javaMajor = 21, memoryMb = 2048, stateLabel = "运行中", uptimeSec = 10,
            players = emptyList(), stats = null, lines = errors + tail,
        )
        assertTrue(ctx.contains("info line 1"))
        assertTrue(ctx.contains("info line 100"))
        // 报错节选最多 30 条 → 最早 10 条被截掉，最新的保留
        assertTrue(ctx.contains("ERROR #40"))
        assertTrue(ctx.contains("ERROR #11"))
        assertFalse(ctx.contains("ERROR #10"))
    }

    @Test
    fun `尾部窗口里的报错不重复出现在报错节选`() {
        val tail = (1..99).map { line("info $it") } + line("ERROR in tail", LineType.Error)
        val ctx = AiContext.build(
            instanceName = "n", mcVersion = "1.21.4", coreType = "Paper",
            javaMajor = 21, memoryMb = 2048, stateLabel = "运行中", uptimeSec = 10,
            players = emptyList(), stats = null, lines = tail,
        )
        assertTrue(ctx.contains("ERROR in tail"))
        // 全部行都在尾部窗口里 → 没有单独的报错节选段
        assertFalse(ctx.contains("更早的告警"))
    }

    @Test
    fun `超长行被截断`() {
        val ctx = AiContext.build(
            instanceName = "n", mcVersion = "1.21.4", coreType = "Paper",
            javaMajor = 21, memoryMb = 2048, stateLabel = "运行中", uptimeSec = 10,
            players = emptyList(), stats = null,
            lines = listOf(line("E".repeat(1000))),
        )
        assertTrue(ctx.contains("…"))
        assertFalse(ctx.contains("E".repeat(300)))
    }

    @Test
    fun `资源快照进入上下文且读不到的项不显示假数据`() {
        val stats = ProcessStats.Reading(
            cpuPercent = 42f, coresUsed = 3.4f, rssKb = 1_500_000, pid = 123,
            availMemKb = 2_000_000, totalMemKb = 8_000_000, lowMemory = true,
        )
        val ctx = AiContext.build(
            instanceName = "n", mcVersion = "1.21.4", coreType = "Paper",
            javaMajor = 21, memoryMb = 2048, stateLabel = "运行中", uptimeSec = 10,
            players = emptyList(), stats = stats, lines = emptyList(),
        )
        assertTrue(ctx.contains("42%"))
        assertTrue(ctx.contains("3.4 核"))
        assertTrue(ctx.contains("低内存"))
        assertTrue(ctx.contains("整机可用 1.9G"))

        val unreadable = ProcessStats.Reading(
            cpuPercent = 0f, coresUsed = 0f, rssKb = -1, pid = 1,
        )
        val ctx2 = AiContext.build(
            instanceName = "n", mcVersion = "1.21.4", coreType = "Paper",
            javaMajor = 21, memoryMb = 2048, stateLabel = "运行中", uptimeSec = 10,
            players = emptyList(), stats = unreadable, lines = emptyList(),
        )
        assertTrue(ctx2.contains("服务端内存读不到"))
        assertFalse(ctx2.contains("0.00"))
    }

    @Test
    fun `文本含 ERROR 或 Exception 的 Info 行也算告警`() {
        val infoError = line("java.lang.RuntimeException: something broke", LineType.Info)
        val tail = (1..100).map { line("info $it") }
        val ctx = AiContext.build(
            instanceName = "n", mcVersion = "1.21.4", coreType = "Paper",
            javaMajor = 21, memoryMb = 2048, stateLabel = "运行中", uptimeSec = 10,
            players = emptyList(), stats = null, lines = listOf(infoError) + tail,
        )
        assertTrue(ctx.contains("RuntimeException"))
    }
}
