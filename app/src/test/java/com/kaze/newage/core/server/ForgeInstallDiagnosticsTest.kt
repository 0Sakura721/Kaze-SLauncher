package com.kaze.newage.core.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Forge 安装失败归因测试。
 *
 * 用例直接取自真机截图里的真实日志 —— 这类"Java 原生异常"最容易被误读成
 * "该升级 Java 了"，而 Android 上 Java 8 根本换不掉，用户照着做只会走进死路。
 */
class ForgeInstallDiagnosticsTest {

    /** 真机日志：Java 8 的 TLS 握手被代理 fake-ip 接管 */
    private val REAL_LOG = """
        Host: launchermeta.mojang.com
        [198.18.0.59]
        Host: piston-meta.mojang.com
        [198.18.0.60]
        Host: sessionserver.mojang.com
        [198.18.0.61]
        > 安装中 0 分 13 秒 · 解析补丁中…（无输出属正常，请勿退出）
        Error checking https://files.minecraftforge.net/: Remote host terminated the handshake
        Error checking https://launchermeta.mojang.com/: Remote host terminated the handshake
        Failed to validate certificates for https://files.minecraftforge.net/,
        https://launchermeta.mojang.com/ this typically means you have an outdated java.
        If installation fails try updating your java!
    """.trimIndent()

    // ══════════ 代理 fake-ip ══════════

    @Test
    fun `198_18 的 fake-ip 判定为代理拦截`() {
        val v = ForgeInstallDiagnostics.diagnose(REAL_LOG)
        assertNotNull("必须识别出原因", v)
        assertTrue("应指向代理", v!!.cause.contains("代理"))
        assertTrue("建议应提到 fake-ip", v.advice.contains("fake-ip"))
        assertTrue("建议要给出具体做法", v.advice.contains("直连"))
    }

    @Test
    fun `198_19 段同样是 fake-ip`() {
        val log = "Error checking https://x/: Remote host terminated the handshake\n[198.19.1.2]"
        val v = ForgeInstallDiagnostics.diagnose(log)
        assertTrue(v!!.cause.contains("代理"))
    }

    @Test
    fun `解析到私网地址也判定为代理接管`() {
        val log = "Host: files.minecraftforge.net\n[192.168.1.5]\n" +
            "Failed to validate certificates for https://files.minecraftforge.net/"
        val v = ForgeInstallDiagnostics.diagnose(log)
        assertTrue("私网地址被解析出来一定是 DNS 被接管", v!!.cause.contains("代理"))
    }

    @Test
    fun `解析到回环地址也判定为代理接管`() {
        val log = "Host: x\n[127.0.0.1]\nSSLHandshakeException: handshake_failure"
        assertTrue(ForgeInstallDiagnostics.diagnose(log)!!.cause.contains("代理"))
    }

    // ══════════ 时钟 ══════════

    @Test
    fun `已知时钟偏差较大时优先归因为时钟`() {
        // 没有 fake-ip 痕迹，但时钟差了 30 分钟
        val log = "Failed to validate certificates for https://files.minecraftforge.net/"
        val v = ForgeInstallDiagnostics.diagnose(log, localClockSkewSec = 1800)
        assertNotNull(v)
        assertTrue("应指出时间偏差", v!!.cause.contains("时间"))
        assertTrue("应给出分钟数", v.cause.contains("30"))
        assertTrue("建议要提到自动校时", v.advice.contains("自动设置时间"))
    }

    @Test
    fun `时钟偏差在 5 分钟内不归因为时钟`() {
        val log = "Failed to validate certificates for https://files.minecraftforge.net/"
        val v = ForgeInstallDiagnostics.diagnose(log, localClockSkewSec = 120)
        assertNotNull(v)
        assertTrue("小偏差不该怪时间", !v!!.cause.contains("时间偏差"))
    }

    @Test
    fun `无其它线索时给出三步排查而不是叫用户升级 Java`() {
        val log = "Failed to validate certificates for https://files.minecraftforge.net/"
        val v = ForgeInstallDiagnostics.diagnose(log)
        assertNotNull(v)
        // 关键：不能只说"更新 java"，必须给出 Android 上可执行的步骤
        assertTrue("要提到系统时间", v!!.advice.contains("系统时间"))
        assertTrue("要提到代理", v.advice.contains("代理"))
        assertTrue("要提到换网络", v.advice.contains("网络"))
        assertTrue("要说明 Java 换不掉", v.advice.contains("固定版本"))
    }

    // ══════════ DNS / 超时 ══════════

    @Test
    fun `UnknownHostException 判定为解析失败`() {
        val v = ForgeInstallDiagnostics.diagnose(
            "java.net.UnknownHostException: files.minecraftforge.net",
        )
        assertTrue(v!!.cause.contains("解析"))
        assertTrue("建议要给 DNS 地址", v.advice.contains("223.5.5.5"))
    }

    @Test
    fun `SocketTimeoutException 判定为超时`() {
        val v = ForgeInstallDiagnostics.diagnose("java.net.SocketTimeoutException: Read timed out")
        assertTrue(v!!.cause.contains("超时"))
        assertTrue("应说明安装器幂等可重试", v.advice.contains("幂等"))
    }

    // ══════════ 不该误判 ══════════

    @Test
    fun `正常安装日志不误报`() {
        val log = "Installing Minecraft 1.12.2\nDownloading libraries...\nDone in 42s"
        assertNull("没有报错特征时不该给归因", ForgeInstallDiagnostics.diagnose(log))
    }

    @Test
    fun `空输出返回 null 而不是编一个原因`() {
        assertNull(ForgeInstallDiagnostics.diagnose(""))
        assertNull(ForgeInstallDiagnostics.diagnose("   \n  "))
    }

    @Test
    fun `版本号里的数字不会被当成 IP`() {
        // 只有方括号里的才算解析结果；"1.12.2" 与 "[1.12.2]" 这种不该命中
        val log = "Forge 1.12.2\n[1.12.2]\nFailed to validate certificates"
        val v = ForgeInstallDiagnostics.diagnose(log)
        // 没有 fake-ip 迹象 → 走通用分支，不该说成"代理拦截"
        assertTrue(v!!.cause.contains("TLS") || v.cause.contains("证书"))
        assertTrue("不该误判为代理", !v.cause.contains("代理"))
    }

    @Test
    fun `大小写混排的错误也能识别`() {
        val log = "error checking https://x/: REMOTE HOST TERMINATED THE HANDSHAKE"
        assertNotNull(ForgeInstallDiagnostics.diagnose(log))
    }

    @Test
    fun `多个原因并存时代理优先级最高`() {
        // 既有 fake-ip 又有时钟偏差：先说代理，因为那是造成 fake-ip 的原因
        val v = ForgeInstallDiagnostics.diagnose(REAL_LOG, localClockSkewSec = 3600)
        assertTrue(v!!.cause.contains("代理"))
    }

    @Test
    fun `建议内容不为空且不含占位符`() {
        listOf(
            REAL_LOG,
            "java.net.UnknownHostException: x",
            "SocketTimeoutException: Read timed out",
            "Failed to validate certificates",
        ).forEach { log ->
            val v = ForgeInstallDiagnostics.diagnose(log)
            assertNotNull("每个特征都该有归因: $log", v)
            assertTrue("原因不能为空", v!!.cause.isNotBlank())
            assertTrue("建议不能为空", v.advice.isNotBlank())
            assertTrue("不能留 TODO 占位", !v.advice.contains("TODO"))
            assertTrue("不能留 % 占位", !v.advice.contains("%s"))
        }
    }
}