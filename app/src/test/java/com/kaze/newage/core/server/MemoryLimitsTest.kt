package com.kaze.newage.core.server

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 内存规则的单测。它同时被创建向导和「实例详情 → 改内存」使用，
 * 两处必须给出同样的结果（真机需求：建好之后还能像创建时那样改内存）。
 */
class MemoryLimitsTest {

    @Test
    fun `低于下限时抬到 512`() {
        assertEquals(512, MemoryLimits.clamp(0))
        assertEquals(512, MemoryLimits.clamp(256))
        assertEquals(512, MemoryLimits.clamp(511))
    }

    @Test
    fun `高于上限时压到 8192`() {
        assertEquals(8192, MemoryLimits.clamp(99999))
        assertEquals(8192, MemoryLimits.clamp(8192))
    }

    @Test
    fun `对齐到 256MB`() {
        assertEquals(2048, MemoryLimits.clamp(2048))
        assertEquals(1792, MemoryLimits.clamp(2000))   // 向下对齐，不会虚报
    }

    @Test
    fun `自动建议不低于 1024 也不超过 8192`() {
        assertEquals(1024f, MemoryLimits.suggestFrom(100f), 0.01f)      // 设备很小 → 取下限
        assertEquals(2048f, MemoryLimits.suggestFrom(4096f), 0.01f)     // 一半 → 2048
        assertEquals(8192f, MemoryLimits.suggestFrom(999999f), 0.01f)   // 很大 → 封顶
    }
}
