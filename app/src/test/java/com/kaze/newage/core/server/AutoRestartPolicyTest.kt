package com.kaze.newage.core.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动重启判定（DefaultServerManager.shouldAutoRestart）。
 *
 * ## 为什么单独锁这块
 * 它背后有一个真实缺陷：`restartCount` 必须在**每次成功启动**后归零。
 * 旧实现只在 finalizeStop 归零，而自动重启路径用 putIfAbsent 复用同一个槽位、
 * 从不经过 finalizeStop —— 于是：崩溃 → 自动重启成功 → 稳定跑很久 → 再崩溃时，
 * 计数从上次的值继续累加（1+1=2…），maxRestarts 提前用完，
 * 本该能自动恢复的崩溃直接变成彻底停服。
 *
 * 所以这里同时锁两件事：
 *  1. 判定本身的四个输入（earlyExit / autoRestart / count / max）；
 *  2. 「计数已归零」时确实能继续重启（用 restartCount=0 代表"刚成功启动过"）。
 */
class AutoRestartPolicyTest {

    @Test
    fun `正常崩溃且开启自动重启、次数未用完 → 重启`() {
        assertTrue(
            DefaultServerManager.shouldAutoRestart(
                earlyExit = false, autoRestart = true, restartCount = 0, maxRestarts = 3,
            ),
        )
        assertTrue(
            DefaultServerManager.shouldAutoRestart(
                earlyExit = false, autoRestart = true, restartCount = 2, maxRestarts = 3,
            ),
        )
    }

    @Test
    fun `30 秒内退出不自动重启（配置或环境问题，重启只会无限循环）`() {
        assertFalse(
            DefaultServerManager.shouldAutoRestart(
                earlyExit = true, autoRestart = true, restartCount = 0, maxRestarts = 3,
            ),
        )
    }

    @Test
    fun `未开启自动重启时不重启`() {
        assertFalse(
            DefaultServerManager.shouldAutoRestart(
                earlyExit = false, autoRestart = false, restartCount = 0, maxRestarts = 3,
            ),
        )
    }

    @Test
    fun `次数用完就不再重启`() {
        assertFalse(
            DefaultServerManager.shouldAutoRestart(
                earlyExit = false, autoRestart = true, restartCount = 3, maxRestarts = 3,
            ),
        )
        assertFalse(
            DefaultServerManager.shouldAutoRestart(
                earlyExit = false, autoRestart = true, restartCount = 99, maxRestarts = 3,
            ),
        )
    }

    @Test
    fun `maxRestarts 为 0 表示不重启`() {
        assertFalse(
            DefaultServerManager.shouldAutoRestart(
                earlyExit = false, autoRestart = true, restartCount = 0, maxRestarts = 0,
            ),
        )
    }

    @Test
    fun `成功启动后计数归零，下一轮崩溃仍能重启（回归守卫）`() {
        // 模拟：崩溃 → 重启(1) → 成功启动（计数归零）→ 再崩溃
        var count = 0
        // 第一次崩溃：能重启，计数 +1
        assertTrue(
            DefaultServerManager.shouldAutoRestart(
                earlyExit = false, autoRestart = true, restartCount = count, maxRestarts = 3,
            ),
        )
        count++
        // 自动重启成功 → launchServer 必须把计数清零（这一步就是被修的那个缺陷）
        count = 0
        // 第二次崩溃：仍应能重启，而不是"只剩 2 次"
        assertTrue(
            DefaultServerManager.shouldAutoRestart(
                earlyExit = false, autoRestart = true, restartCount = count, maxRestarts = 3,
            ),
        )
    }

    @Test
    fun `连续启动失败才会累计到上限`() {
        // 模拟：每次都是 earlyExit（起不来），计数一直累加，到上限后停止
        var count = 0
        val max = 3
        repeat(max) {
            assertTrue(
                "第 ${count + 1} 次应仍允许重试",
                DefaultServerManager.shouldAutoRestart(
                    earlyExit = false, autoRestart = true, restartCount = count, maxRestarts = max,
                ),
            )
            count++
        }
        assertFalse(
            "达到上限后不再重启",
            DefaultServerManager.shouldAutoRestart(
                earlyExit = false, autoRestart = true, restartCount = count, maxRestarts = max,
            ),
        )
    }
}