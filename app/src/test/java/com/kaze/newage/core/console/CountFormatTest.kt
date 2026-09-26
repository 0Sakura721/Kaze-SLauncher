package com.kaze.newage.core.console

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 计数缩写：1.2万 / 1.2百万 / 1.2千万 / 1.2亿，统统一位小数。
 *
 * 重点是**档位切换不会出现"100.0万"这种怪值**：选档按"四舍五入之后"的值判，
 * 999,999 → `1.0百万` 而不是 `100.0万`。
 */
class CountFormatTest {

    @Test
    fun `一万以下显示精确值`() {
        assertEquals("0", CountFormat.short(0))
        assertEquals("999", CountFormat.short(999))
        assertEquals("9499", CountFormat.short(9499))
    }

    @Test
    fun `一万起缩写成万`() {
        assertEquals("1.0万", CountFormat.short(9_500))
        assertEquals("1.0万", CountFormat.short(10_000))
        assertEquals("1.2万", CountFormat.short(12_345))
        assertEquals("9.9万", CountFormat.short(99_499))
    }

    @Test
    fun `百万 千万 亿各自成档`() {
        assertEquals("1.2百万", CountFormat.short(1_234_567))
        assertEquals("1.2千万", CountFormat.short(12_345_678))
        assertEquals("1.2亿", CountFormat.short(123_456_789))
    }

    @Test
    fun `档位边界不会出现 100_0万 这种怪值`() {
        // 这是第一版挂掉的用例：999,999 曾算出 99.9999 → "100.0万"
        assertEquals("1.0百万", CountFormat.short(999_999))
        assertEquals("1.0千万", CountFormat.short(9_999_999))
        assertEquals("1.0亿", CountFormat.short(99_999_999))
    }

    @Test
    fun `档位门槛按 0_95 判定（一位小数会进位）`() {
        assertEquals("9499", CountFormat.short(9_499))          // 0.9499 万 → 不够进位
        assertEquals("1.0万", CountFormat.short(9_500))          // 0.95 万 → 进位成 1.0
        assertEquals("9.5万", CountFormat.short(94_999))         // 9.4999 万 → 一位小数
    }

    @Test
    fun `一行一位小数`() {
        for (n in listOf(10_000L, 12_345L, 1_234_567L, 12_345_678L, 123_456_789L)) {
            val s = CountFormat.short(n)
            val num = s.takeWhile { it.isDigit() || it == '.' }
            assertTrue(
                "$n → $s 应保留一位小数",
                num.contains('.') && num.substringAfter('.').length == 1,
            )
        }
    }

    @Test
    fun `永不返回空串或纯单位`() {
        for (n in listOf(0L, 1L, 999L, 9_499L, 9_500L, 950_000L, 999_999L, 1_000_000L, 999_999_999L)) {
            val s = CountFormat.short(n)
            assertTrue("$n → 「$s」应含数字", s.any { it.isDigit() })
        }
    }

    @Test
    fun `日志体积的短记法`() {
        assertEquals("789 B", CountFormat.bytes(789))
        assertEquals("1.0 KB", CountFormat.bytes(1024))
        assertEquals("12.3 MB", CountFormat.bytes((12.3 * 1024 * 1024).toLong()))
        assertEquals("1.2 GB", CountFormat.bytes((1.2 * 1024 * 1024 * 1024).toLong()))
    }
}
