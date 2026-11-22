package com.kaze.newage.core.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CoreDownload 的多源候选。
 *
 * 背景：Fabric 的版本元数据挂在 `meta.fabricmc.net`，该域名在部分网络下 TLS 握手超时
 * （实测 20s 无响应），而 BMCLAPI 的 `fabric-meta` 提供**同一份 JSON**、200~700ms 返回。
 * 单源时官方不通 = 用户看到空版本列表，既不知道原因也没法绕开。
 *
 * 这里锁住两个不变量：
 *  1. 主地址永远排在最前（既有优先序不变）；
 *  2. 没有镜像时行为与改造前**完全一致**（不会因为加了字段就改变老路径）。
 */
class CoreDownloadMirrorsTest {

    @Test
    fun `没有镜像时只有一个候选_行为与改造前一致`() {
        val d = CoreDownload("https://example.com/a.jar", "a.jar")
        assertEquals(listOf("https://example.com/a.jar"), d.allUrls)
    }

    @Test
    fun `镜像排在主地址之后`() {
        val d = CoreDownload(
            url = "https://meta.example.com/a.jar",
            fileName = "a.jar",
            mirrors = listOf("https://m1.example.com/a.jar", "https://m2.example.com/a.jar"),
        )
        assertEquals(
            listOf(
                "https://meta.example.com/a.jar",
                "https://m1.example.com/a.jar",
                "https://m2.example.com/a.jar",
            ),
            d.allUrls,
        )
    }

    @Test
    fun `sha1 与 mirrors 互不影响`() {
        val d = CoreDownload("u", "f", sha1 = "abc123", mirrors = listOf("m1"))
        assertEquals("abc123", d.sha1)
        assertEquals(listOf("u", "m1"), d.allUrls)
    }

    @Test
    fun `allUrls 不含空串`() {
        val d = CoreDownload("u", "f", mirrors = listOf("m1", "m2"))
        assertTrue(d.allUrls.none { it.isBlank() })
    }
}
