package com.kaze.newage.core.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 命令执行分档测试（借鉴 Harness 的权限分层）。
 * 锁住三档语义：SUGGEST 一律不自动、SAFE 只放白名单、ALL 全放。
 */
class AiCommandPolicyTest {

    @Test
    fun `仅建议档任何命令都不自动执行`() {
        val m = AiCommandPolicy.Mode.SUGGEST
        listOf("list", "tps", "time set day", "stop", "op Steve").forEach {
            assertFalse("不应自动执行：$it", AiCommandPolicy.canAuto(m, it))
        }
    }

    @Test
    fun `白名单档放行查询类命令`() {
        val m = AiCommandPolicy.Mode.SAFE
        listOf("list", "tps", "LIST", "ping Steve", "  time set day  ", "weather clear", "gamerule keepInventory true")
            .forEach { assertTrue("应放行：$it", AiCommandPolicy.canAuto(m, it)) }
    }

    @Test
    fun `白名单档拦住危险与未知命令`() {
        val m = AiCommandPolicy.Mode.SAFE
        listOf("stop", "op Steve", "ban x", "whitelist add y", "kick z",
            "data merge block ~ ~ ~ {}", "setblock ~ ~ ~ air", "luckperms user x", "forgeload")
            .forEach { assertFalse("不应放行：$it", AiCommandPolicy.canAuto(m, it)) }
    }

    @Test
    fun `全部自动档放行任何命令`() {
        val m = AiCommandPolicy.Mode.ALL
        listOf("list", "stop", "op Steve", "summon ...", "随便什么").forEach {
            assertTrue("应放行：$it", AiCommandPolicy.canAuto(m, it))
        }
    }

    @Test
    fun `未知档位 id 回退到仅建议`() {
        assertTrue(AiCommandPolicy.modeById("nonexistent") == AiCommandPolicy.Mode.SUGGEST)
        assertTrue(AiCommandPolicy.modeById("safe") == AiCommandPolicy.Mode.SAFE)
        assertTrue(AiCommandPolicy.modeById("all") == AiCommandPolicy.Mode.ALL)
    }
}
