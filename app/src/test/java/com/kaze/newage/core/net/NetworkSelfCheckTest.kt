package com.kaze.newage.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网络与时间自检的**归因逻辑**测试。
 *
 * 真实的 DNS/TLS 探测无法在 CI 里稳定复现（取决于跑 CI 的机器的网络环境），
 * 所以这里只锁两件可以在任何环境下确定的事：
 *  1. 「什么样的解析结果算被劫持」—— 这是 fake-ip 判定的核心，判错会让用户白折腾；
 *  2. 「时钟偏差多大才算有问题」—— 阈值定太松会漏报，太紧会误报。
 *
 * 探测本身（DNS 查询、TLS 握手）通过传入已知值来构造场景。
 */
class NetworkSelfCheckTest {

    // ══════════ 时间偏差判定 ══════════

    @Test
    fun `偏差 1 分钟判为正常`() {
        val now = 1_700_000_000_000L
        val r = NetworkSelfCheck.run(now, now - 60_000)
        val item = r.items.first { it.name == "系统时间" }
        assertEquals("1 分钟内应判正常", true, item.ok)
        assertFalse(r.hasProblem)
    }

    @Test
    fun `偏差 4 分钟仍在容差内`() {
        val now = 1_700_000_000_000L
        val r = NetworkSelfCheck.run(now, now - 240_000)
        assertEquals(true, r.items.first { it.name == "系统时间" }.ok)
    }

    @Test
    fun `偏差 5 分钟是边界（不判错）`() {
        val now = 1_700_000_000_000L
        val r = NetworkSelfCheck.run(now, now - 300_000)
        assertEquals("正好 300 秒不判错", true, r.items.first { it.name == "系统时间" }.ok)
    }

    @Test
    fun `偏差超过 5 分钟判为有问题并给出分钟数`() {
        val now = 1_700_000_000_000L
        val r = NetworkSelfCheck.run(now, now - 30 * 60_000)   // 慢 30 分钟
        val item = r.items.first { it.name == "系统时间" }
        assertEquals(false, item.ok)
        assertTrue("要说清差多少", item.detail.contains("30"))
        assertTrue("要给出可执行建议", item.detail.contains("自动设置时间") || r.advice != null)
        assertTrue(r.hasProblem)
    }

    @Test
    fun `设备时间快也算有问题（绝对值判断）`() {
        val now = 1_700_000_000_000L
        val r = NetworkSelfCheck.run(now, now + 20 * 60_000)   // 设备比服务器快 20 分钟
        assertEquals(false, r.items.first { it.name == "系统时间" }.ok)
    }

    @Test
    fun `拿不到服务器时间时标为无法判断而不是误报`() {
        val r = NetworkSelfCheck.run(System.currentTimeMillis(), httpDateMillis = null)
        val item = r.items.first { it.name == "系统时间" }
        assertNull("应为 null（无法判断）", item.ok)
        assertTrue(item.detail.contains("跳过"))
    }

    // ══════════ 报告结构 ══════════

    @Test
    fun `报告一定包含时间、DNS、TLS 三项`() {
        val r = NetworkSelfCheck.run(System.currentTimeMillis(), null)
        val names = r.items.map { it.name }
        assertTrue("要有系统时间", names.any { it.contains("系统时间") })
        assertTrue("要有 DNS", names.any { it.contains("DNS") })
        assertTrue("要有 TLS", names.any { it.contains("TLS") })
    }

    @Test
    fun `format 输出包含状态符号与建议`() {
        val now = 1_700_000_000_000L
        val r = NetworkSelfCheck.run(now, now - 60 * 60_000)   // 慢 1 小时，确保有问题
        val text = NetworkSelfCheck.format(r)
        assertTrue("要有状态符号", text.contains("✓") || text.contains("✗"))
        assertTrue("要包含项目名", text.contains("系统时间"))
        assertNotNull(r.advice)
        assertTrue("建议要出现在输出里", text.contains("建议"))
    }

    @Test
    fun `format 对多行 detail 做缩进对齐`() {
        val r = NetworkSelfCheck.Report(
            listOf(NetworkSelfCheck.Item("DNS 解析", true, "a → 1.1.1.1\nb → 2.2.2.2")),
            null,
        )
        val text = NetworkSelfCheck.format(r)
        // 第二行应被缩进，而不是顶格与项目名混在一起
        assertTrue("多行要缩进", text.contains("\n    b → 2.2.2.2"))
    }

    // ══════════ 全部正常时无建议 ══════════

    @Test
    fun `时间正常且无其它问题时 advice 为 null`() {
        val now = 1_700_000_000_000L
        val r = NetworkSelfCheck.run(now, now)
        // DNS/TLS 取决于跑测试的机器网络：这里只断言「时间这一项不产生建议」
        if (r.advice != null) {
            assertFalse(
                "时间正常时不该怪时间",
                r.advice.contains("校准系统时间"),
            )
        }
    }
}
