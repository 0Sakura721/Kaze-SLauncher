package com.kaze.newage.core.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 带后缀的版本号能不能被识别成"有更新"。
 *
 * 起因（真机反馈）："更新机制查不到带原版本加后缀的" —— 装着 v0.3.3 的用户
 * **永远查不到** v0.3.3-fix，因为旧实现把所有后缀都当成"预发布"，而
 * 「预发布 vs 正式」直接返回 false。
 *
 * 后缀有两种语义，必须分开：
 *  · alpha/beta/rc/… → 预发布，正式版用户不该被引导去装
 *  · fix/hotfix/patch/… 以及**任何未知后缀** → 正式版之上的修订，算更新
 *    （本仓库的 v0.3.1-fix / v0.3.3-fix 就是这种；用户也说了"以后可能会有其他名字"）
 */
class UpdateCheckerSuffixTest {

    @Test
    fun `带 fix 后缀的原版本号必须被识别为更新`() {
        assertTrue("0.3.3 → 0.3.3-fix 必须能查到", UpdateChecker.isNewer("0.3.3-fix", "0.3.3"))
        assertTrue("0.3.0 → 0.3.1-fix", UpdateChecker.isNewer("0.3.1-fix", "0.3.0"))
    }

    @Test
    fun `版本号更高时后缀不影响`() {
        assertTrue(UpdateChecker.isNewer("0.3.4-fix", "0.3.3-fix"))
        assertTrue(UpdateChecker.isNewer("0.4.0-fix", "0.3.3-fix"))
        assertTrue(UpdateChecker.isNewer("v0.3.4", "0.3.3-fix"))
    }

    @Test
    fun `同一个带后缀版本不会反复提示`() {
        assertFalse("已是最新就不该再提示", UpdateChecker.isNewer("0.3.3-fix", "0.3.3-fix"))
        assertFalse(UpdateChecker.isNewer("v0.3.3-fix", "0.3.3-fix"))
    }

    @Test
    fun `正式版不会被降级回不带后缀的版本`() {
        assertFalse("装着 fix 的用户不该被引导装回原版", UpdateChecker.isNewer("0.3.3", "0.3.3-fix"))
    }

    @Test
    fun `未来出现别的后缀名也要能识别`() {
        // 用户明确说"以后可能会有其他名字" → 未知后缀一律按"修订"处理
        for (tag in listOf("0.3.3-hotfix", "0.3.3-patch", "0.3.3-rev2", "0.3.3-update", "0.3.3-2")) {
            assertTrue("$tag 应被识别为更新", UpdateChecker.isNewer(tag, "0.3.3"))
        }
    }

    @Test
    fun `真正的预发布仍然不算更新`() {
        for (tag in listOf("0.3.3-alpha", "0.3.3-beta", "0.3.3-beta.1", "0.3.3-rc1", "0.3.3-preview", "0.3.3-dev")) {
            assertFalse("$tag 是预发布，不该引导正式版用户去装", UpdateChecker.isNewer(tag, "0.3.3"))
        }
    }

    @Test
    fun `预发布之间仍按序号比较`() {
        assertTrue(UpdateChecker.isNewer("0.3.3-beta.2", "0.3.3-beta.1"))
        assertFalse(UpdateChecker.isNewer("0.3.3-beta.1", "0.3.3-beta.2"))
    }

    @Test
    fun `主版本提升时预发布仍算更新`() {
        assertTrue(UpdateChecker.isNewer("0.4.0-beta.1", "0.3.3-fix"))
    }

    @Test
    fun `预发布判定只认公认的预发布词`() {
        assertTrue(UpdateChecker.isPrereleaseTag(listOf("beta")))
        assertTrue(UpdateChecker.isPrereleaseTag(listOf("rc1")))
        assertFalse(UpdateChecker.isPrereleaseTag(listOf("fix")))
        assertFalse(UpdateChecker.isPrereleaseTag(listOf("whatever-new-name")))
        assertFalse(UpdateChecker.isPrereleaseTag(emptyList()))
    }
}
