package com.kaze.newage.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * AI 文件工具的安全边界测试：路径越界、类型白名单、.bak 备份、大小上限。
 * 这几条是"给 AI 文件权限"的底线，任何一条被绕过都是事故。
 */
class AiFileToolsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // 随机名：同一测试里可能创建多个实例根（如"先读后写拒绝"两步），固定名会重名
    private fun instanceDir(): File = tmp.newFolder()

    // ── 路径解析 ──

    @Test
    fun `相对路径解析到实例目录内`() {
        val root = instanceDir()
        val f = AiFileTools.resolve(root, null, "server.properties", forWrite = false)
        assertEquals(root.canonicalFile.path + File.separator + "server.properties", f.path)
    }

    @Test
    fun `双点越界被拒绝`() {
        val root = instanceDir()
        listOf("../evil.txt", "..", "config/../../escape.txt").forEach { p ->
            try {
                AiFileTools.resolve(root, null, p, forWrite = false)
                fail("应当拒绝：$p")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("越界"))
            }
        }
    }

    @Test
    fun `app 前缀走应用目录且写入被拒绝`() {
        val appDir = tmp.newFolder("appfiles")
        val f = AiFileTools.resolve(instanceDir(), appDir, "app:diagnostics.txt", forWrite = false)
        assertEquals(appDir.canonicalFile.path + File.separator + "diagnostics.txt", f.path)
        try {
            AiFileTools.resolve(instanceDir(), appDir, "app:x.txt", forWrite = true)
            fail("app: 写入应当被拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("只读"))
        }
    }

    @Test
    fun `app 目录自身越界同样拒绝`() {
        try {
            AiFileTools.resolve(instanceDir(), tmp.newFolder("appfiles"), "app:../../evil", forWrite = false)
            fail("应当拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("越界"))
        }
    }

    // ── 读取 ──

    @Test
    fun `读文本文件返回内容`() {
        val root = instanceDir()
        File(root, "server.properties").writeText("max-players=20\n")
        assertEquals("max-players=20\n", AiFileTools.readFile(root, null, "server.properties"))
    }

    @Test
    fun `读不存在的文件给可读错误`() {
        try {
            AiFileTools.readFile(instanceDir(), null, "logs/latest.log")
            fail("应当抛出")
        } catch (e: Exception) {
            assertTrue(e.message!!.contains("不存在"))
        }
    }

    @Test
    fun `二进制类型拒绝读取`() {
        val root = instanceDir()
        File(root, "server.jar").writeBytes(byteArrayOf(1, 2))
        try {
            AiFileTools.readFile(root, null, "server.jar")
            fail("应当抛出")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("二进制"))
        }
    }

    @Test
    fun `白名单之外的类型拒绝读取`() {
        val root = instanceDir()
        File(root, "world.mca").writeBytes(byteArrayOf(1))
        try {
            AiFileTools.readFile(root, null, "world.mca")
            fail("应当抛出")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("二进制"))
        }
    }

    @Test
    fun `超长文件截断并注明`() {
        val root = instanceDir()
        val big = File(root, "logs.log")
        big.writeText("x".repeat(AiFileTools.MAX_READ_BYTES + 100))
        val out = AiFileTools.readFile(root, null, "logs.log")
        assertTrue(out.contains("已截断"))
        assertTrue(out.length < AiFileTools.MAX_READ_BYTES + 200)
    }

    @Test
    fun `app 前缀读取应用目录文件`() {
        val appDir = tmp.newFolder("appfiles")
        File(appDir, "diagnostics.txt").writeText("env ok")
        assertEquals(
            "env ok",
            AiFileTools.readFile(instanceDir(), appDir, "app:diagnostics.txt"),
        )
    }

    // ── 列目录 ──

    @Test
    fun `列目录目录在前并带大小`() {
        val root = instanceDir()
        File(root, "config").mkdir()
        File(root, "config").resolve("a.toml").writeText("x")
        File(root, "z.properties").writeText("y".repeat(2048))
        val out = AiFileTools.listDir(root, null, ".")
        assertTrue(out.contains("config/"))
        assertTrue(out.contains("z.properties"))
        assertTrue(out.indexOf("config/") < out.indexOf("z.properties"))
        assertTrue(out.contains("2 KB"))
    }

    // ── 写入 ──

    @Test
    fun `写新文件并自动创建父目录`() {
        val root = instanceDir()
        val out = AiFileTools.writeFile(root, "config/deep/new.toml", "a = 1")
        assertTrue(out.contains("已写入"))
        assertEquals("a = 1", File(root, "config/deep/new.toml").readText())
    }

    @Test
    fun `覆盖已有文件留 bak 备份`() {
        val root = instanceDir()
        File(root, "server.properties").writeText("old=1")
        AiFileTools.writeFile(root, "server.properties", "new=2")
        assertEquals("new=2", File(root, "server.properties").readText())
        assertEquals("old=1", File(root, "server.properties.bak").readText())
    }

    @Test
    fun `写入 jar 与 app 前缀被拒绝`() {
        val root = instanceDir()
        try {
            AiFileTools.writeFile(root, "mods/hack.jar", "x")
            fail("应当拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("不允许"))
        }
        try {
            AiFileTools.writeFile(root, "app:x.txt", "x")
            fail("应当拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("只读"))
        }
    }

    @Test
    fun `超大内容拒绝写入`() {
        val root = instanceDir()
        try {
            AiFileTools.writeFile(root, "big.txt", "x".repeat(AiFileTools.MAX_WRITE_BYTES + 1))
            fail("应当拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("过大"))
        }
    }

    @Test
    fun `大小格式化`() {
        assertEquals("0 B", AiFileTools.formatSize(0))
        assertEquals("512 B", AiFileTools.formatSize(512))
        assertEquals("2 KB", AiFileTools.formatSize(2048))
        assertFalse(AiFileTools.formatSize(1500) == "1 KB")
    }
}
