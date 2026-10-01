package com.kaze.newage.core.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Forge maven 版本 id → MC 版本号的解析回归测试。
 *
 * 老格式（1.7.10 时代）的 id 形如 `1.7.10-10.13.4.1614-1.7.10` —— **末尾还有一段重复的
 * MC 版本号**。原来用 `^(\d+\.\d+(?:\.\d+)?)-[\d.]+$` 匹配，那个 `$` 让整代老版本一个都命中不了：
 * 新建向导里 Forge 的版本列表看不到 1.7.10 / 1.8.9，用户以为"不支持这些版本"。
 */
class ForgeVersionParseTest {

    @Test
    fun `老格式带重复 MC 版本号也能解析出来`() {
        assertEquals("1.7.10", CoreSources.forgeMcVersionOf("1.7.10-10.13.4.1614-1.7.10"))
        assertEquals("1.8.9", CoreSources.forgeMcVersionOf("1.8.9-11.15.1.2318-1.8.9"))
        assertEquals("1.9.4", CoreSources.forgeMcVersionOf("1.9.4-12.17.0.2051-1.9.4"))
    }

    @Test
    fun `常见格式照旧解析`() {
        assertEquals("1.20.1", CoreSources.forgeMcVersionOf("1.20.1-47.2.0"))
        assertEquals("1.12.2", CoreSources.forgeMcVersionOf("1.12.2-14.23.5.2860"))
        assertEquals("1.16.5", CoreSources.forgeMcVersionOf("1.16.5-36.2.39"))
    }

    @Test
    fun `没有 build 段或形状不对的不当作版本行`() {
        // 只有 MC 版本号（没有连字符）：不是 forge 的版本行
        assertNull(CoreSources.forgeMcVersionOf("1.20.1"))
        // 第一段不是 MC 版本号形状
        assertNull(CoreSources.forgeMcVersionOf("forge-1.20.1"))
        assertNull(CoreSources.forgeMcVersionOf("10.13.4.1614-1.7.10-x"))
    }

    /** 一份缩略的 maven-metadata 内容：解析后的 MC 版本列表必须同时含老格式与常见格式 */
    @Test
    fun `版本列表同时包含老格式与常见格式且去重`() {
        val ids = listOf(
            "1.7.10-10.13.4.1614-1.7.10",
            "1.7.10-10.13.4.1614",
            "1.8.9-11.15.1.2318-1.8.9",
            "1.12.2-14.23.5.2860",
            "1.20.1-47.2.0",
        )
        val mc = ids.mapNotNull { CoreSources.forgeMcVersionOf(it) }.distinct()
        assertEquals(listOf("1.7.10", "1.8.9", "1.12.2", "1.20.1"), mc)
    }
}
