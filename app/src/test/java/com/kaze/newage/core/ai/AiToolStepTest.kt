package com.kaze.newage.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具执行步骤的展示语义测试。
 *
 * 这些规则会被界面用来决定"要不要给用户上色提醒"，写错会让**失败看起来像成功** ——
 * 那正是改造前的老问题（只有一行 `· 读取 xxx ·`，成功失败一个样）。
 */
class AiToolStepTest {

    @Test
    fun `失败与拒绝与跳过都要被标出来`() {
        assertTrue(AiToolStep("读取 a", status = AiToolStep.Status.FAILED).isBad)
        assertTrue(AiToolStep("读取 a", status = AiToolStep.Status.DENIED).isBad)
        assertTrue(AiToolStep("读取 a", status = AiToolStep.Status.SKIPPED).isBad)
    }

    @Test
    fun `成功与进行中不算异常`() {
        assertFalse(AiToolStep("读取 a", status = AiToolStep.Status.OK).isBad)
        assertFalse(AiToolStep("读取 a", status = AiToolStep.Status.RUNNING).isBad)
    }

    @Test
    fun `进行中不显示耗时`() {
        val running = AiToolStep("写入 a", status = AiToolStep.Status.RUNNING, elapsedMs = 5000)
        assertEquals("进行中", running.statusText())
    }

    @Test
    fun `没计时只显示状态`() {
        assertEquals("完成", AiToolStep("读取 a").statusText())
    }

    @Test
    fun `有耗时显示状态加时间`() {
        // 1200ms 走的是"秒"档（>=1000 就换成秒），不是毫秒档
        assertEquals("完成 · 1.2 秒", AiToolStep("读取 a", elapsedMs = 1200).statusText())
        assertEquals(
            "失败 · 2.5 秒",
            AiToolStep("读取 a", status = AiToolStep.Status.FAILED, elapsedMs = 2500).statusText(),
        )
    }

    @Test
    fun `毫秒格式化分档`() {
        assertEquals("0ms", AiToolStep.formatElapsed(0))
        assertEquals("999ms", AiToolStep.formatElapsed(999))
        assertEquals("1.0 秒", AiToolStep.formatElapsed(1000))
        assertEquals("59.9 秒", AiToolStep.formatElapsed(59_900))
        assertEquals("1 分 0 秒", AiToolStep.formatElapsed(60_000))
        assertEquals("2 分 5 秒", AiToolStep.formatElapsed(125_000))
    }

    @Test
    fun `状态标签覆盖全部枚举`() {
        for (s in AiToolStep.Status.entries) {
            assertTrue("枚举 $s 缺标签", s.label.isNotEmpty())
        }
        assertEquals("已拒绝", AiToolStep.Status.DENIED.label)
    }

    @Test
    fun `默认值是成功且无耗时无详情`() {
        val step = AiToolStep("读取 a")
        assertEquals(AiToolStep.Status.OK, step.status)
        assertEquals(0L, step.elapsedMs)
        assertEquals("", step.detail)
    }
}