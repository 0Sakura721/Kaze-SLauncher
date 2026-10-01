package com.kaze.newage.core.env

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * guest 命令失败输出的提炼规则（v7a/MTK 真机现场回归）：
 * 厂商 LD_PRELOAD 注入的 ld.so 警告是非致命噪音，一次 apt-get 刷十几条，
 * 旧实现取头部 300 字符时真错误完全不可见。
 */
class SummarizeGuestFailureTest {

    private val noise =
        "ERROR: ld.so: object 'libdirect-coredump.so' from LD_PRELOAD cannot be preloaded (cannot open shared object file): ignored."

    @Test
    fun `ldso 噪音被滤掉且保留末尾的真实报错`() {
        val out = buildString {
            repeat(3) { appendLine(noise) }
            appendLine("Hit:1 http://ports.ubuntu.com/ubuntu-ports noble InRelease")
            appendLine("E: Unable to locate package openjdk-17-openjdk-armhf")
        }
        val s = summarizeGuestFailure(out)
        assertTrue("真错误必须保留：$s", s.contains("E: Unable to locate package"))
        assertFalse("噪音必须滤掉：$s", s.contains("LD_PRELOAD"))
    }

    @Test
    fun `超长输出取末尾而不是头部`() {
        val body = (1..200).joinToString("\n") { "progress line $it" }
        val tail = "E: Failed to fetch http://ports.ubuntu.com/... Temporary failure resolving"
        val s = summarizeGuestFailure("$body\n$tail")
        assertTrue("末尾的报错必须在内：$s", s.contains("Failed to fetch"))
        assertTrue("头部进度不应在内：$s", !s.contains("progress line 1\n"))
    }

    @Test
    fun `短输出原样保留`() {
        val s = summarizeGuestFailure("E: Sub-process /usr/bin/dpkg returned an error code (1)")
        assertEquals("E: Sub-process /usr/bin/dpkg returned an error code (1)", s)
    }

    @Test
    fun `纯噪音输出回退为原文头部而非空串`() {
        val out = "$noise\n$noise"
        val s = summarizeGuestFailure(out)
        assertTrue("全噪音时回退原文，不能返回空串", s.contains("ld.so"))
    }
}
