package com.kaze.newage.core.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 版本比较测试。
 *
 * 这段逻辑（`isNewer`）处理了预发布段的语义：「同主版本下正式版 > 预发布」，
 * 否则 beta 用户永远收不到同号转正的提示。规则细、易回归，因此逐条锁住。
 */
class UpdateCheckerTest {

    @Test
    fun `主版本号递增算更新`() {
        assertTrue(UpdateChecker.isNewer("0.1.3", "0.1.2"))
        assertTrue(UpdateChecker.isNewer("0.2.0", "0.1.9"))
        assertTrue(UpdateChecker.isNewer("1.0.0", "0.9.9"))
    }

    @Test
    fun `主版本号下降不算更新`() {
        assertFalse(UpdateChecker.isNewer("0.1.2", "0.1.3"))
        assertFalse(UpdateChecker.isNewer("0.9.9", "1.0.0"))
    }

    @Test
    fun `相同版本不算更新`() {
        assertFalse(UpdateChecker.isNewer("0.1.2", "0.1.2"))
        assertFalse(UpdateChecker.isNewer("v0.1.2", "0.1.2"))
        // 尾随零差异不应产生"假更新"
        assertFalse(UpdateChecker.isNewer("1.0", "1.0.0"))
    }

    @Test
    fun `带 v 前缀与多位版本号`() {
        assertTrue(UpdateChecker.isNewer("v1.2.3", "1.2.2"))
        assertTrue(UpdateChecker.isNewer("26.2", "1.99.99"))
    }

    @Test
    fun `同号正式版高于预发布`() {
        // beta 用户转到正式版必须收到提示
        assertTrue(UpdateChecker.isNewer("0.2.0", "0.2.0-beta.1"))
        // 反之，正式版用户不该被引导去装预发布
        assertFalse(UpdateChecker.isNewer("0.2.0-beta.1", "0.2.0"))
    }

    @Test
    fun `预发布序号递增`() {
        assertTrue(UpdateChecker.isNewer("1.0.0-beta.2", "1.0.0-beta.1"))
        assertFalse(UpdateChecker.isNewer("1.0.0-beta.1", "1.0.0-beta.2"))
    }

    @Test
    fun `主版本更高时忽略预发布段`() {
        // 0.3.0-beta.1 仍高于 0.2.0
        assertTrue(UpdateChecker.isNewer("0.3.0-beta.1", "0.2.0"))
    }
}
