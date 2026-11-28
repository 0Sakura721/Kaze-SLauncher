package com.kaze.newage.core.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 核心版本源的**纯解析逻辑**测试。
 *
 * 这些函数是 `CoreSources` 里最容易出错的部分（543 行、37 处解析），
 * 而且历史上真的出过 bug：
 *  - `forgeMcVersionOf` 的正则没锚定时，`26.1.1` 被解析成 `1.1`；
 *  - `compareVersions` 直接对整串取数字段时，`26.2-rc-2` 被判为比 `26.2` 新，
 *    于是用户在「正式版」筛选下第一眼看到的是候选版。
 *
 * 真实网络请求不在单测范围（需要联网且会被镜像抖动影响）；
 * 这里只锁解析与排序，网络层的行为由真机验证覆盖。
 */
class CoreSourcesTest {

    // ══════════ Forge 版本行 → MC 版本 ══════════

    @Test
    fun `常见 Forge 版本行提取 MC 版本`() {
        assertEquals("1.20.1", CoreSources.forgeMcVersionOf("1.20.1-47.2.0"))
        assertEquals("1.12.2", CoreSources.forgeMcVersionOf("1.12.2-14.23.5.2860"))
    }

    @Test
    fun `老格式（末尾还有 MC 版本段）也能提取`() {
        // 1.7.10 时代：1.7.10-10.13.4.1614-1.7.10
        // 旧正则因末尾 $ 匹配不上，这一整代版本从列表里消失
        assertEquals("1.7.10", CoreSources.forgeMcVersionOf("1.7.10-10.13.4.1614-1.7.10"))
    }

    @Test
    fun `没有 build 段的行返回 null`() {
        assertNull(CoreSources.forgeMcVersionOf("1.20.1"))
        assertNull(CoreSources.forgeMcVersionOf("26.3"))
    }

    @Test
    fun `形状不合法的行返回 null`() {
        assertNull(CoreSources.forgeMcVersionOf("abc-def"))
        assertNull(CoreSources.forgeMcVersionOf("1-2"))
        assertNull(CoreSources.forgeMcVersionOf(""))
    }

    @Test
    fun `多段 MC 版本号完整保留`() {
        // 关键回归：正则必须整体锚定，不能只匹配到子串
        assertEquals("26.1.1", CoreSources.forgeMcVersionOf("26.1.1-1.0.0"))
        assertEquals("1.21.11", CoreSources.forgeMcVersionOf("1.21.11-61.0.1"))
    }

    // ══════════ 版本号 → 数字段 ══════════

    @Test
    fun `版本号拆成数字段`() {
        assertEquals(listOf(26L, 2L), CoreSources.versionKey("26.2"))
        assertEquals(listOf(1L, 21L, 11L), CoreSources.versionKey("1.21.11"))
    }

    @Test
    fun `非数字后缀不影响数字段`() {
        assertEquals(listOf(26L, 2L, 2L), CoreSources.versionKey("26.2-rc-2"))
    }

    // ══════════ 预发布判定 ══════════

    @Test
    fun `rc pre beta alpha snapshot 都算预发布`() {
        assertTrue(CoreSources.isPreRelease("26.2-rc-2"))
        assertTrue(CoreSources.isPreRelease("1.21.11-pre5"))
        assertTrue(CoreSources.isPreRelease("1.21.9-rc1"))
        assertTrue(CoreSources.isPreRelease("1.20-beta"))
        assertTrue(CoreSources.isPreRelease("1.20-alpha"))
        assertTrue(CoreSources.isPreRelease("1.20-snapshot"))
    }

    @Test
    fun `普通版本号不算预发布`() {
        assertTrue(!CoreSources.isPreRelease("26.2"))
        assertTrue(!CoreSources.isPreRelease("1.21.11"))
    }

    @Test
    fun `大小写不敏感`() {
        assertTrue(CoreSources.isPreRelease("26.2-RC-2"))
        assertTrue(CoreSources.isPreRelease("1.21.11-PRE5"))
    }

    // ══════════ 基础版本号 ══════════

    @Test
    fun `去掉预发布后缀`() {
        assertEquals("26.2", CoreSources.baseVersion("26.2-rc-2"))
        assertEquals("1.21.11", CoreSources.baseVersion("1.21.11-pre5"))
    }

    @Test
    fun `无后缀时原样返回`() {
        assertEquals("26.2", CoreSources.baseVersion("26.2"))
    }

    // ══════════ 版本比较（历史 bug 的重灾区） ══════════

    @Test
    fun `按数字段逐位比较而不是字符串比较`() {
        // 字符串比较会得出 "26.2" < "9.0"（'2' < '9'），数字段比较才是对的
        assertTrue(CoreSources.compareVersions("26.2", "9.0") > 0)
        assertTrue(CoreSources.compareVersions("1.21.11", "1.21.9") > 0)
        assertTrue(CoreSources.compareVersions("1.20.1", "1.20.1") == 0)
        assertTrue(CoreSources.compareVersions("1.20.0", "1.20.1") < 0)
    }

    @Test
    fun `正式版排在预发布版之上（关键回归）`() {
        // 旧实现直接对整串取数字段：26.2-rc-2 → [26,2,2] > 26.2 → [26,2]
        // 于是「正式版」筛选下第一行成了候选版，用户按默认选项建服就会下到 rc。
        assertTrue(
            "正式版 26.2 应排在 26.2-rc-2 之上",
            CoreSources.compareVersions("26.2", "26.2-rc-2") > 0,
        )
        assertTrue(
            "26.2-rc-2 应排在 26.2 之下",
            CoreSources.compareVersions("26.2-rc-2", "26.2") < 0,
        )
    }

    @Test
    fun `段数不同时短的一方补零比较`() {
        assertTrue(CoreSources.compareVersions("1.20", "1.20.0") == 0)
        assertTrue(CoreSources.compareVersions("1.20.1", "1.20") > 0)
    }
}
