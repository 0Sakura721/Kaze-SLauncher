package com.kaze.newage.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 更新包选包规则测试。
 *
 * 这层逻辑错一次的代价很直接：v7a 设备拿到 arm64 包 → 装到一半报
 * INSTALL_FAILED_NO_MATCHING_ABIS；或者根本找不到包 → 永远收不到更新。
 * v0.3.0 起命名变了（v7a 带 `-experimental` 后缀、不再发 universal），
 * 旧逻辑在这两处都会挂，所以把规则固化成测试。
 */
class UpdateCheckerAssetTest {

    private val arm64 = "arm64-v8a"
    private val armhf = "armeabi-v7a"

    @Test
    fun `v7a 带 experimental 后缀也能选中`() {
        val names = listOf(
            "Kaze-SLauncher-v0.3.0-arm64-v8a.apk",
            "Kaze-SLauncher-v0.3.0-armeabi-v7a-experimental.apk",
        )
        assertEquals("Kaze-SLauncher-v0.3.0-armeabi-v7a-experimental.apk", UpdateChecker.pickAssetName(names, armhf))
    }

    @Test
    fun `arm64 选中自己的包而不是 v7a`() {
        val names = listOf(
            "Kaze-SLauncher-v0.3.0-armeabi-v7a-experimental.apk",
            "Kaze-SLauncher-v0.3.0-arm64-v8a.apk",
        )
        assertEquals("Kaze-SLauncher-v0.3.0-arm64-v8a.apk", UpdateChecker.pickAssetName(names, arm64))
    }

    @Test
    fun `没有 universal 时绝不退到别的架构`() {
        // v0.3.0 起不再发布 universal：只有 v7a 包时，arm64 设备应该"没得选"而不是拿到 v7a 包
        val names = listOf("Kaze-SLauncher-v0.3.0-armeabi-v7a-experimental.apk")
        assertNull(UpdateChecker.pickAssetName(names, arm64))
    }

    @Test
    fun `精确后缀优先于模糊匹配`() {
        val names = listOf(
            "Kaze-SLauncher-v0.3.0-arm64-v8a-experimental.apk",
            "Kaze-SLauncher-v0.3.0-arm64-v8a.apk",
        )
        assertEquals("Kaze-SLauncher-v0.3.0-arm64-v8a.apk", UpdateChecker.pickAssetName(names, arm64))
    }

    @Test
    fun `兼容 0_1_x 的旧命名 -arm64`() {
        val names = listOf("Kaze-SLauncher-v0.1.1-arm64.apk")
        assertEquals("Kaze-SLauncher-v0.1.1-arm64.apk", UpdateChecker.pickAssetName(names, arm64))
    }

    @Test
    fun `0_2_0 及更早的 universal 仍是兜底`() {
        val names = listOf(
            "Kaze-SLauncher-v0.2.0-arm64-v8a.apk",
            "Kaze-SLauncher-v0.2.0-universal.apk",
        )
        assertEquals("Kaze-SLauncher-v0.2.0-universal.apk", UpdateChecker.pickAssetName(names, armhf))
    }

    @Test
    fun `非 apk 资产被忽略`() {
        val names = listOf(
            "source.zip",
            "Kaze-SLauncher-v0.3.0-arm64-v8a.apk",
        )
        assertEquals("Kaze-SLauncher-v0.3.0-arm64-v8a.apk", UpdateChecker.pickAssetName(names, arm64))
    }

    @Test
    fun `空资产列表返回 null`() {
        assertNull(UpdateChecker.pickAssetName(emptyList(), arm64))
        assertNull(UpdateChecker.pickAssetName(listOf("readme.txt"), armhf))
    }
}
