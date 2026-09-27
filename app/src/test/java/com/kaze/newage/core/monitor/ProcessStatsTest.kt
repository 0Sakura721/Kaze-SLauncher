package com.kaze.newage.core.monitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解析 /proc 的纯函数部分。
 *
 * 这些细节很容易写错，而且错了之后**只是数字难看、不会报错**：
 *  - `/proc/<pid>/stat` 的第 2 个字段是进程名，可能含空格与括号 → 不能按空格 split
 *  - CPU 占用必须两次采样求差（累计 tick 本身无意义）
 *  - 多线程服务端单核占用率**可以超过 100%**，折算整机占用率时要除核数
 */
class ProcessStatsTest {

    /** 真实的 /proc/<pid>/stat 形态：进程名带空格与括号 */
    private fun stat(utime: Long, stime: Long) =
        "1234 (java) (1) S 1 1234 1234 0 -1 4194560 12345 0 0 0 $utime $stime 0 0 20 0 42 0 123456 789012345 6789 ..."

    @Test
    fun `解析累计 CPU tick_进程名含空格与括号也不能错位`() {
        assertEquals(1500L, ProcessStats.parseCpuTicks(stat(1000, 500)))
        assertEquals(0L, ProcessStats.parseCpuTicks(stat(0, 0)))
    }

    @Test
    fun `字段不足或格式异常时返回 null 而不是崩`() {
        assertNull(ProcessStats.parseCpuTicks(""))
        assertNull(ProcessStats.parseCpuTicks("1234 (java) S 1 2"))
        assertNull(ProcessStats.parseCpuTicks("no parens here"))
    }

    @Test
    fun `解析 VmRSS`() {
        val status = """
            Name:	java
            State:	S (sleeping)
            VmPeak:	 8123456 kB
            VmSize:	 7234567 kB
            VmRSS:	 1234567 kB
            Threads:	42
        """.trimIndent()
        assertEquals(1234567L, ProcessStats.parseVmRssKb(status))
        assertNull(ProcessStats.parseVmRssKb("Name:\tjava\n"))
    }

    @Test
    fun `CPU 占用按两次采样求差`() {
        // 2 秒里涨了 200 tick = 100 tick/秒 = 占满 **1 个核** → 8 核机器上整机占用 12.5%
        // （第一版把这里写成 2 个核，是把"2 秒"和"200 tick"算重了一次）
        val (percent, cores) = ProcessStats.cpuFrom(prevTicks = 1000, ticks = 1200, elapsedMs = 2000, cores = 8)
        assertEquals(1f, cores, 0.01f)
        assertEquals(12.5f, percent, 0.5f)
    }

    @Test
    fun `空闲时是 0`() {
        val (percent, cores) = ProcessStats.cpuFrom(prevTicks = 5000, ticks = 5000, elapsedMs = 2000, cores = 4)
        assertEquals(0f, cores, 0.001f)
        assertEquals(0f, percent, 0.001f)
    }

    @Test
    fun `单核占用率可以超过 100%_多线程服务端就是这样`() {
        // 1 秒涨 250 tick → 2.5 个核
        val (percent, cores) = ProcessStats.cpuFrom(prevTicks = 0, ticks = 250, elapsedMs = 1000, cores = 4)
        assertEquals(2.5f, cores, 0.01f)
        assertEquals(62.5f, percent, 0.5f)
        assertTrue("整机占用率必须被压到 100 以内", percent <= 100f)
    }

    @Test
    fun `进程重启导致 tick 变小_不能算成负占用`() {
        val (percent, cores) = ProcessStats.cpuFrom(prevTicks = 99999, ticks = 10, elapsedMs = 1000, cores = 4)
        assertEquals(0f, cores, 0.001f)
        assertEquals(0f, percent, 0.001f)
    }

    @Test
    fun `间隔为 0 或核数异常时不崩`() {
        assertEquals(0f, ProcessStats.cpuFrom(0, 100, 0, 4).first, 0.001f)
        val (p, _) = ProcessStats.cpuFrom(0, 100, 1000, cores = 0)
        assertTrue("核数非法时按 1 核处理，不该出现 NaN/Inf", p.isFinite() && p in 0f..100f)
    }
}
