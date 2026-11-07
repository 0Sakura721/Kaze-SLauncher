package com.kaze.newage.core.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

/**
 * 内存分配规则测试。
 *
 * 它同时被创建向导和「实例详情 → 改内存」使用，两处必须给出同样的结果
 *（真机需求：建好之后还能像创建时那样改内存）。
 *
 * 出错的表现很具体：
 *  - 建议值太小 → 服务端起不来（用户反馈过 "有的太小开都开不了"）；
 *  - 边界夹不严 → 写入超出设备的分配，启动即 OOM；
 *  - 对齐方向错 → 用户输入 700 被静默改成 512（少 188MB），却没有任何提示。
 */
class MemoryLimitsTest {

    // ══════════ clamp：范围与对齐 ══════════

    @Test
    fun `合法值原样保留（本身就在档位上）`() {
        assertEquals(1024, MemoryLimits.clamp(1024))
        assertEquals(2048, MemoryLimits.clamp(2048))
        assertEquals(3072, MemoryLimits.clamp(3072))
    }

    @Test
    fun `低于下限被抬到 MIN`() {
        assertEquals(MemoryLimits.MIN_MB, MemoryLimits.clamp(0))
        assertEquals(MemoryLimits.MIN_MB, MemoryLimits.clamp(100))
        assertEquals(MemoryLimits.MIN_MB, MemoryLimits.clamp(511))
    }

    @Test
    fun `高于上限被压到 MAX`() {
        assertEquals(MemoryLimits.MAX_MB, MemoryLimits.clamp(99999))
        assertEquals(MemoryLimits.MAX_MB, MemoryLimits.clamp(MemoryLimits.MAX_MB + 1))
    }

    @Test
    fun `负数被夹到下限`() {
        assertEquals(MemoryLimits.MIN_MB, MemoryLimits.clamp(-4096))
    }

    @Test
    fun `值永远对齐到 STEP 的整数倍`() {
        for (v in listOf(513, 700, 1000, 1023, 1025, 2500, 5000, 8000)) {
            assertEquals(
                "clamp($v) 应能被 STEP_MB 整除",
                0,
                MemoryLimits.clamp(v) % MemoryLimits.STEP_MB,
            )
        }
    }

    @Test
    fun `2000 对齐到 1792（向下，不虚报）`() {
        // 原测试就有的具体用例：2000 不是 256 的整数倍，必须向下到 1792 而不是 2048
        assertEquals(1792, MemoryLimits.clamp(2000))
    }

    @Test
    fun `对齐是向下取整（记录在案的行为）`() {
        // 这条**固定住当前语义**：700 → 512。UI 必须据此给出"会按 256MB 对齐为 512"的提示，
        // 否则用户输入 700 会以为设成了 700（详情页的精确输入就是这样修的）。
        assertEquals(512, MemoryLimits.clamp(700))
        assertEquals(512, MemoryLimits.clamp(767))
        assertEquals(768, MemoryLimits.clamp(768))
        assertEquals(768, MemoryLimits.clamp(1023))
    }

    @Test
    fun `结果始终落在 MIN 到 MAX 之间`() {
        for (v in listOf(-9999, 0, 1, 511, 512, 1024, 8191, 8192, 8193, 999999)) {
            val r = MemoryLimits.clamp(v)
            assertEquals(
                "clamp($v)=$r 越界",
                true, r in MemoryLimits.MIN_MB..MemoryLimits.MAX_MB,
            )
        }
    }

    // ══════════ suggestFrom：建议值 ══════════

    @Test
    fun `建议值是可用内存的一半并对齐`() {
        // 8GB 可用 → 一半 4GB
        assertEquals(4096f, MemoryLimits.suggestFrom(8192f), 0.01f)
        // 6GB 可用 → 一半 3GB
        assertEquals(3072f, MemoryLimits.suggestFrom(6144f), 0.01f)
    }

    @Test
    fun `可用内存很小也不会低于 AUTO_MIN`() {
        // 旧实现允许 512MB，用户反馈"有的太小开都开不了"，所以自动建议有下限
        assertEquals(MemoryLimits.AUTO_MIN_MB.toFloat(), MemoryLimits.suggestFrom(1024f), 0.01f)
        assertEquals(MemoryLimits.AUTO_MIN_MB.toFloat(), MemoryLimits.suggestFrom(512f), 0.01f)
        assertEquals(MemoryLimits.AUTO_MIN_MB.toFloat(), MemoryLimits.suggestFrom(0f), 0.01f)
    }

    @Test
    fun `可用内存很大也不会超过 MAX`() {
        assertEquals(MemoryLimits.MAX_MB.toFloat(), MemoryLimits.suggestFrom(64_000f), 0.01f)
    }

    @Test
    fun `建议值也对齐到 STEP`() {
        for (avail in listOf(3000f, 5000f, 7000f, 12000f)) {
            val r = MemoryLimits.suggestFrom(avail)
            assertEquals(
                "suggestFrom($avail)=$r 未对齐",
                0f, (r % MemoryLimits.STEP_MB), 0.01f,
            )
        }
    }

    @Test
    fun `可用内存为负或 NaN 时兜底到 AUTO_MIN 而不是崩`() {
        // 内存读不到时上层可能传负值/NaN 下来（ProcessStats 用 -1 表示读不到）
        assertEquals(MemoryLimits.AUTO_MIN_MB.toFloat(), MemoryLimits.suggestFrom(-1f), 0.01f)
        assertEquals(MemoryLimits.AUTO_MIN_MB.toFloat(), MemoryLimits.suggestFrom(Float.NaN), 0.01f)
    }

    // ══════════ 常量之间的一致性 ══════════

    @Test
    fun `常量关系自洽`() {
        assertEquals("MIN 必须是 STEP 的整数倍", 0, MemoryLimits.MIN_MB % MemoryLimits.STEP_MB)
        assertEquals("MAX 必须是 STEP 的整数倍", 0, MemoryLimits.MAX_MB % MemoryLimits.STEP_MB)
        assertEquals("AUTO_MIN 必须是 STEP 的整数倍", 0, MemoryLimits.AUTO_MIN_MB % MemoryLimits.STEP_MB)
        assertEquals(
            "AUTO_MIN 应落在 MIN..MAX 内",
            true, MemoryLimits.AUTO_MIN_MB in MemoryLimits.MIN_MB..MemoryLimits.MAX_MB,
        )
    }
}