package com.kaze.newage.core.env

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * guest 命令失败输出的**原因提炼**测试。
 *
 * 这个函数是 v7a 真机现场的产物（MTK，apt 初始化失败、退出码 100）：
 *  - 旧实现取输出**头部** 300 字符，而失败现场头部整段都是 `ld.so` 噪音，
 *    真正的 `E: Unable to locate package` 完全不可见 —— 用户只看到"命令失败"，
 *    排查时无从下手；
 *  - 厂商的 `LD_PRELOAD cannot be preloaded` 每次 apt-get 能刷十几条，
 *    全是非致命的 `ignored`，却会把真错误挤出窗口。
 *
 * 所以这里锁两个不变量：**滤掉噪音**、**取末尾而非开头**。
 */
class GuestFailureSummaryTest {

    private val noise = "ERROR: ld.so: object 'libdirect-coredump.so' from LD_PRELOAD cannot be preloaded (cannot open shared object file): ignored."

    // ══════════ 噪音过滤 ══════════

    @Test
    fun `滤掉 LD_PRELOAD 噪音行`() {
        val out = """
            $noise
            Reading package lists...
            $noise
            E: Unable to locate package foo
        """.trimIndent()
        val s = summarizeGuestFailure(out)
        assertTrue("噪音不该出现: [$s]", !s.contains("LD_PRELOAD"))
        assertTrue("真错误应保留: [$s]", s.contains("E: Unable to locate package foo"))
    }

    @Test
    fun `滤掉噪音后其余行的顺序不变`() {
        val out = "第一行\n$noise\n第三行"
        val s = summarizeGuestFailure(out)
        assertEquals("第一行\n第三行", s)
    }

    @Test
    fun `全是噪音时退回原始输出（不返回空串）`() {
        val out = "$noise\n$noise"
        val s = summarizeGuestFailure(out)
        // 去掉噪音后为空 → 退回 output.take(maxChars)，调用方还能看到点什么
        assertTrue("不该返回空串", s.isNotEmpty())
        assertTrue("应是原始输出的前缀: [$s]", out.startsWith(s) || s.contains("LD_PRELOAD"))
    }

    @Test
    fun `噪声判定是子串匹配（大小写敏感不影响常见形态）`() {
        val out = "prefix from LD_PRELOAD cannot be preloaded (x): ignored.\nREAL ERROR"
        val s = summarizeGuestFailure(out)
        assertTrue(!s.contains("LD_PRELOAD"))
        assertTrue(s.contains("REAL ERROR"))
    }

    // ══════════ 取末尾 ══════════

    @Test
    fun `超长时取末尾而不是开头`() {
        // 头部长填充、末尾才是真错误 —— 这是 apt 的真实形态
        val filler = "Reading package lists...\n".repeat(100)
        val realError = "E: Failed to fetch http://example.com/x.deb 404 Not Found"
        val out = filler + realError
        val s = summarizeGuestFailure(out, maxChars = 100)
        assertTrue("应保留末尾的真错误: [$s]", s.contains("Failed to fetch"))
        assertTrue("长度应 <= maxChars", s.length <= 100)
    }

    @Test
    fun `短输出原样返回（不截断）`() {
        val out = "E: Something simple"
        assertEquals(out, summarizeGuestFailure(out))
    }

    @Test
    fun `长度正好等于 maxChars 时不截断`() {
        val out = "x".repeat(300)
        assertEquals(out, summarizeGuestFailure(out, maxChars = 300))
    }

    @Test
    fun `超过一个字符就截断到末尾`() {
        val out = "abcdefghij"
        assertEquals("defghij", summarizeGuestFailure(out, maxChars = 7))
    }

    // ══════════ 边界 ══════════

    @Test
    fun `空输出返回空串`() {
        assertEquals("", summarizeGuestFailure(""))
    }

    @Test
    fun `纯空白输出原样返回（有内容就不过度清洗）`() {
        // 去掉噪音后为空 → 退回 output.take(maxChars)，于是纯空白会原样返回。
        // 这是刻意的：调用方拿到的"有内容"比"被清成空串"更利于排查。
        assertEquals("   \n  \n", summarizeGuestFailure("   \n  \n"))
    }

    @Test
    fun `只有噪音且超长时仍不崩`() {
        val out = ("$noise\n").repeat(50)
        val s = summarizeGuestFailure(out, maxChars = 50)
        assertTrue("应返回内容而不是抛异常", s.isNotEmpty())
    }

    @Test
    fun `maxChars 为 0 时返回空串而不是崩`() {
        assertEquals("", summarizeGuestFailure("E: x", maxChars = 0))
    }

    @Test
    fun `保留多行结构（不把换行折叠掉）`() {
        val out = "line1\nline2\nline3"
        val s = summarizeGuestFailure(out)
        assertEquals(out, s)
    }

    @Test
    fun `真实 apt 失败现场（v7a 真机形状）`() {
        // 模拟 MTK 设备上 apt-get update 失败的完整输出
        val out = """
            $noise
            $noise
            $noise
            Ign:1 http://ports.ubuntu.com/ubuntu-ports noble InRelease
            Err:1 http://ports.ubuntu.com/ubuntu-ports noble InRelease
              Temporary failure resolving 'ports.ubuntu.com'
            $noise
            Reading package lists...
            W: Failed to fetch http://ports.ubuntu.com/ubuntu-ports/dists/noble/InRelease
            E: Some index files failed to download.
        """.trimIndent()
        val s = summarizeGuestFailure(out, maxChars = 400)
        assertTrue("真错误应可见: [$s]", s.contains("E: Some index files failed"))
        assertTrue("解析失败原因应可见: [$s]", s.contains("Temporary failure resolving"))
        assertTrue("噪音应被滤掉: [$s]", !s.contains("LD_PRELOAD"))
    }
}