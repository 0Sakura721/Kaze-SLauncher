package com.kaze.newage.data.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Forge/NeoForge「装好了没有」的判据测试。
 *
 * 这层判错一次的代价是**不可恢复**的：安装器先写 `unix_args.txt`、后下 60 多个依赖库，
 * 中途失败会留下"看着装好了、库却不全"的目录。若把它当成已安装，之后每次启动都跳过安装，
 * 启动时只看到一长串 `Missing required library` 然后 exit=1，用户除了删实例没有别的出路
 * （真机实锤过：断网装 Forge → DNS 修好后仍然起不来）。
 */
class ForgeInstallDetectionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun instance(dir: File) = ServerInstance(
        name = "t",
        coreType = CoreType.FORGE,
        mcVersion = "1.21.1",
        dir = dir,
    )

    /** 模拟安装器"解压完但还没下完依赖"的中间状态 */
    private fun partialInstall(dir: File) {
        File(dir, "libraries/net/minecraftforge/forge/1.21.1-52.1.16").apply { mkdirs() }
        File(dir, "libraries/net/minecraftforge/forge/1.21.1-52.1.16/unix_args.txt").writeText("-p libraries/...")
    }

    private fun markInstalled(dir: File) {
        File(dir, ".forge-installed").writeText("21")
    }

    @Test
    fun `空目录 = 没装`() {
        val dir = tmp.newFolder()
        assertFalse(instance(dir).forgeInstalled())
        assertFalse(instance(dir).forgeInstallIncomplete())
    }

    @Test
    fun `只有 unix_args_txt 而没有成功标记 = 不算装好`() {
        val dir = tmp.newFolder()
        partialInstall(dir)
        assertFalse("中断的安装不能被当成已安装，否则永远不会重试", instance(dir).forgeInstalled())
    }

    @Test
    fun `中断的安装会被识别为 incomplete（用于触发补齐）`() {
        val dir = tmp.newFolder()
        partialInstall(dir)
        assertTrue(instance(dir).forgeInstallIncomplete())
    }

    @Test
    fun `入口文件加成功标记 = 装好了`() {
        val dir = tmp.newFolder()
        partialInstall(dir)
        markInstalled(dir)
        assertTrue(instance(dir).forgeInstalled())
        assertFalse(instance(dir).forgeInstallIncomplete())
    }

    @Test
    fun `旧版 Forge 的 forge jar 加成功标记也算装好`() {
        val dir = tmp.newFolder()
        File(dir, "forge-1.12.2-14.23.5.2860.jar").writeText("x")
        assertFalse(instance(dir).forgeInstalled()) // 没有标记 → 仍要重装
        markInstalled(dir)
        assertTrue(instance(dir).forgeInstalled())
    }

    @Test
    fun `有标记但没有入口文件 = 不算装好`() {
        val dir = tmp.newFolder()
        markInstalled(dir)
        assertFalse(instance(dir).forgeInstalled())
    }

    @Test
    fun `安装器 jar 不会被误认成启动入口`() {
        val dir = tmp.newFolder()
        File(dir, "forge-1.21.1-52.1.16-installer.jar").writeText("x")
        markInstalled(dir)
        assertFalse(instance(dir).forgeInstalled())
    }
}
