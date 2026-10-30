package com.kaze.newage.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Java 版本推断测试。
 *
 * 这条逻辑直接决定给实例配哪个 Java —— 推断错了的后果是
 * `UnsupportedClassVersionError`（服务端起不来），而不是界面显示问题。
 *
 * 回归点：`1.17 ~ 1.20.4` 曾返回 **1**（少写了一个 7），界面上显示"自动适配 Java 1"。
 */
class JavaVersionInferenceTest {

    @Test
    fun `旧命名 1_8 到 1_16_5 用 Java 8`() {
        assertEquals(8, JavaVersionInference.infer("1.8"))
        assertEquals(8, JavaVersionInference.infer("1.12.2"))
        assertEquals(8, JavaVersionInference.infer("1.16.5"))
    }

    @Test
    fun `1_17 到 1_20_4 用 Java 17`() {
        assertEquals(17, JavaVersionInference.infer("1.17"))
        assertEquals(17, JavaVersionInference.infer("1.17.1"))
        assertEquals(17, JavaVersionInference.infer("1.18.2"))
        assertEquals(17, JavaVersionInference.infer("1.19.4"))
        assertEquals(17, JavaVersionInference.infer("1.20"))
        assertEquals(17, JavaVersionInference.infer("1.20.1"))
        assertEquals(17, JavaVersionInference.infer("1.20.4"))
    }

    @Test
    fun `1_20_5 起用 Java 21`() {
        assertEquals(21, JavaVersionInference.infer("1.20.5"))
        assertEquals(21, JavaVersionInference.infer("1.20.6"))
        assertEquals(21, JavaVersionInference.infer("1.21"))
        assertEquals(21, JavaVersionInference.infer("1.21.4"))
    }

    @Test
    fun `新命名 24_x 起用 Java 21、26_x 起用 25`() {
        assertEquals(21, JavaVersionInference.infer("24.1"))
        assertEquals(21, JavaVersionInference.infer("25.3"))
        assertEquals(25, JavaVersionInference.infer("26.1"))
        assertEquals(25, JavaVersionInference.infer("26.1.1"))
    }

    @Test
    fun `快照版按年份映射`() {
        assertEquals(8, JavaVersionInference.infer("20w45a"))    // 2020
        assertEquals(17, JavaVersionInference.infer("21w37a"))   // 2021
        assertEquals(21, JavaVersionInference.infer("24w14a"))   // 2024
        assertEquals(25, JavaVersionInference.infer("26w02a"))   // 2026
    }

    @Test
    fun `认不出的版本回落到 17 而不是崩`() {
        assertEquals(17, JavaVersionInference.infer(""))
        assertEquals(17, JavaVersionInference.infer("unknown"))
    }

    @Test
    fun `任何情况下都不会返回 1（历史 bug 的回归锁）`() {
        val samples = listOf(
            "1.8", "1.12.2", "1.16.5", "1.17", "1.17.1", "1.18", "1.18.2",
            "1.19", "1.19.4", "1.20", "1.20.1", "1.20.4", "1.20.5", "1.21",
            "1.21.4", "24.1", "26.1", "20w45a", "21w37a", "24w14a", "26w02a",
            "", "unknown", "1", "2.0",
        )
        for (v in samples) {
            val java = JavaVersionInference.infer(v)
            assertEquals(
                "版本 \"$v\" 推断出 Java $java，必须落在 8/17/21/25 里",
                true,
                java in setOf(8, 17, 21, 25),
            )
        }
    }
}
