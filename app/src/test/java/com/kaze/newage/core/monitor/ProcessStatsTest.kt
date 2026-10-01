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

    // ── 选进程（真机反馈"CPU 偏低 + ram 显示 0"）────────────────────────────
    // `proot` 自己的 cmdline 里也含 "java"（它就是要去跑的 java 路径），而它的 pid 更小、
    // 会被先扫到。旧逻辑有一句 `cmdline.contains(实例目录名) -> return pid` 的短路，
    // 于是把 **proot 包装进程**当成了服务端：CPU 显示 proot 的（实测 8%）、
    // 内存显示 proot 的 RSS（≈2.8 MB，两位小数就是 `0.00 GB`）。

    /** 真实形态：proot 包装进程 + 它拉起的 JVM */
    private fun prootAndJvm() = listOf(
        ProcessStats.Candidate(
            pid = 21360,
            cmdline = "proot --link2symlink -0 -r /rootfs /rootfs/usr/lib/jvm/java-17/bin/java -Xmx2048M -jar server.jar nogui Forge-1.20.1",
            comm = "proot",
            descendant = true,
            rssKb = 2_820,
        ),
        ProcessStats.Candidate(
            pid = 21365,
            cmdline = "/rootfs/usr/lib/jvm/java-17/bin/java -Xmx2048M -jar server.jar nogui",
            comm = "java",
            descendant = true,
            rssKb = 1_800_000,
        ),
    )

    @Test
    fun `选进程_proot 的 cmdline 也含 java_必须选 JVM 而不是它`() {
        assertEquals(21365, ProcessStats.pickServer(prootAndJvm())!!.pid)
    }

    @Test
    fun `选进程_comm 读不到时按 RSS 取最重的`() {
        val cands = listOf(
            ProcessStats.Candidate(1, "proot ... java ...", "proot", true, 2_820),
            ProcessStats.Candidate(2, "/x/java -jar server.jar", "", true, 1_800_000),
        )
        assertEquals(2, ProcessStats.pickServer(cands)!!.pid)
    }

    @Test
    fun `选进程_RSS 全读不到时取 pid 最大的`() {
        val cands = listOf(
            ProcessStats.Candidate(100, "proot ... java ...", "proot", true, -1),
            ProcessStats.Candidate(220, "java -jar server.jar", "java", true, -1),
        )
        assertEquals(220, ProcessStats.pickServer(cands)!!.pid)
    }

    @Test
    fun `选进程_优先本进程的后代_别人家的 java 再重也不要`() {
        val cands = listOf(
            ProcessStats.Candidate(900, "java -jar some-other-app.jar", "java", false, 9_000_000),
            ProcessStats.Candidate(9000, "java -jar server.jar", "java", true, 1_000_000),
        )
        assertEquals(9000, ProcessStats.pickServer(cands)!!.pid)
    }

    @Test
    fun `选进程_没有候选时返回 null`() {
        assertNull(ProcessStats.pickServer(emptyList()))
    }

    // ── CPU 平滑（读数别乱跳）──────────────────────────────────────────────
    // 采样窗口只有 1 秒，服务端负载是突发的（GC / 区块生成 / 玩家进服），
    // 单拍求差的原始值会让界面上的百分比和"（x 核）"来回闪。

    @Test
    fun `平滑_第一拍直接用原始值`() {
        assertEquals(2.0f, ProcessStats.smoothCores(prev = -1f, raw = 2.0f), 0.001f)
    }

    @Test
    fun `平滑_之后按 0.5 权重向新值靠拢`() {
        // 上一拍 1 核，这一拍 3 核 → 1*0.5 + 3*0.5 = 2 核
        assertEquals(2.0f, ProcessStats.smoothCores(prev = 1f, raw = 3f), 0.001f)
        // 再来一拍 3 核 → 2*0.5 + 3*0.5 = 2.5 核（不会一步跳到 3，这就是"防跳"）
        assertEquals(2.5f, ProcessStats.smoothCores(prev = 2f, raw = 3f), 0.001f)
    }

    @Test
    fun `平滑_异常输入不产生 NaN 或负值`() {
        assertEquals(0f, ProcessStats.smoothCores(prev = -1f, raw = Float.NaN), 0.001f)
        assertEquals(1.5f, ProcessStats.smoothCores(prev = 1.5f, raw = Float.NaN), 0.001f)
        assertEquals(0f, ProcessStats.smoothCores(prev = -1f, raw = -5f), 0.001f)
    }
}
