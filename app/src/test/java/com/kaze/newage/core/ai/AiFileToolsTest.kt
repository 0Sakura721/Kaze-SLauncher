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
        // 应用日志与审计日志也在白名单内
        File(appDir, "logs").mkdirs()
        File(appDir, "logs/app.log").writeText("log line")
        assertEquals("log line", AiFileTools.readFile(instanceDir(), appDir, "app:logs/app.log"))
        File(appDir, "ai_audit.log").writeText("audit")
        assertEquals("audit", AiFileTools.readFile(instanceDir(), appDir, "app:ai_audit.log"))
    }

    @Test
    fun `app 前缀不再开放实例库与其它应用数据`() {
        val appDir = tmp.newFolder("appfiles")
        val root = instanceDir()
        // 实例库里是所有实例的绝对路径与配置，对诊断没有价值，却会被一路发到第三方端点
        File(appDir, "instances.json").writeText("""[{"name":"x","dir":"/sdcard/KazeS/x"}]""")
        File(appDir, "instances.json.bak").writeText("[]")
        File(appDir, "ai_chat.json").writeText("""{"messages":[]}""")
        File(appDir, "ai_memory_abc.md").writeText("note")
        File(appDir, "logs").mkdirs()
        File(appDir, "logs/app.log").writeText("log line")
        File(appDir, "ai_audit.log").writeText("audit")
        listOf(
            "app:instances.json",
            "app:instances.json.bak",
            "app:ai_chat.json",
            "app:ai_memory_abc.md",
            "app:logs/../instances.json",  // 归一化之后仍要落在白名单外
        ).forEach { p ->
            try {
                AiFileTools.readFile(root, appDir, p)
                fail("应当拒绝读取：$p")
            } catch (e: IllegalArgumentException) {
                assertTrue("错误信息应说明白名单：${e.message}", e.message!!.contains("logs/"))
            }
        }
        // 列应用目录根：白名单之外的项连名字都不该出现
        val listing = AiFileTools.listDir(root, appDir, "app:.")
        assertTrue(listing.contains("logs/"))
        assertTrue(listing.contains("ai_audit.log"))
        assertFalse("实例库不该出现在列表里：$listing", listing.contains("instances.json"))
        assertFalse("会话记录不该出现在列表里：$listing", listing.contains("ai_chat.json"))
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
    fun `覆盖已有文件留带时间戳的 bak 备份`() {
        val root = instanceDir()
        File(root, "server.properties").writeText("old=1")
        val out = AiFileTools.writeFile(root, "server.properties", "new=2")
        assertEquals("new=2", File(root, "server.properties").readText())
        val baks = root.listFiles().orEmpty().filter { it.name.startsWith("server.properties.") && it.name.endsWith(".bak") }
        assertEquals("应留下恰好一份备份", 1, baks.size)
        assertEquals("old=1", baks.first().readText())
        assertTrue("写入结果应说明备份名：$out", out.contains(baks.first().name))
    }

    @Test
    fun `连续覆盖保留多代备份而不是顶掉上一代`() {
        val root = instanceDir()
        File(root, "server.properties").writeText("v1")
        AiFileTools.writeFile(root, "server.properties", "v2")
        AiFileTools.writeFile(root, "server.properties", "v3")
        val baks = root.listFiles().orEmpty().filter { it.name.endsWith(".bak") }.map { it.readText() }.toSet()
        // 只保留一代备份时这里会只剩 v2 —— 而"改坏了要回退"往往正是在第二次写入之后才发现的
        assertEquals(setOf("v1", "v2"), baks)
    }

    @Test
    fun `备份文件名同秒也不撞名`() {
        val root = instanceDir()
        val src = File(root, "a.txt").apply { writeText("x") }
        val now = 1_700_000_000_000L
        val first = AiFileTools.backupNameFor(src, now)
        File(root, first).writeText("x")
        val second = AiFileTools.backupNameFor(src, now)
        assertFalse("同秒第二次备份必须换名", first == second)
        assertTrue(first.endsWith(".bak"))
    }

    @Test
    fun `bak 备份只读不可写`() {
        val root = instanceDir()
        File(root, "server.properties.20260927-101010.bak").writeText("v1")
        // 能读：诊断时要回看上一版
        assertEquals("v1", AiFileTools.readFile(root, null, "server.properties.20260927-101010.bak"))
        // 不能写：否则 AI 可以伪造/顶掉用户唯一的回退版本
        try {
            AiFileTools.writeFile(root, "server.properties.20260927-101010.bak", "hacked")
            fail("应当拒绝写入 .bak")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("只读"))
        }
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

    // ── 写入策略：禁止组 / 高风险组 ──

    @Test
    fun `结构性禁止写入的文件全部被判定`() {
        val root = instanceDir()
        listOf(
            "user_jvm_args.txt",                       // java @argfile → JVM 参数
            "ops.json",                                // 直接给 op
            "banned-ips.json",
            "banned-players.json",
            "eula.txt",                                // EULA 同意状态
            "world/datapacks/x/data/minecraft/functions/boom.mcfunction", // 以服务端权限执行
            "world/datapacks/x/data/minecraft/tags/functions/load.json",  // 自动触发函数
        ).forEach { p ->
            val policy = AiFileTools.writePolicyFor(root, p)
            assertTrue("应禁止写入：$p", policy.forbidden != null)
            assertTrue("禁止组不应同时给出高风险警示：$p", policy.warning == null)
        }
        // 大小写与绕行写法同样落到同一个文件名上
        assertTrue(AiFileTools.writePolicyFor(root, "USER_JVM_ARGS.TXT").forbidden != null)
        assertTrue(AiFileTools.writePolicyFor(root, "config/../ops.json").forbidden != null)
        assertTrue(AiFileTools.writePolicyFor(root, "ops.json").forbidden != null)
    }

    @Test
    fun `脚本类文件允许写但必须红字警示`() {
        val root = instanceDir()
        listOf("start.sh", "backup.ps1", "tools/restart.bat", "kubejs/server_scripts/x.js", "a/b.py")
            .forEach { p ->
                val policy = AiFileTools.writePolicyFor(root, p)
                assertTrue("脚本类应允许写入：$p", policy.forbidden == null)
                assertTrue("脚本类必须警示：$p", policy.warning != null)
            }
        // 普通配置文件既不禁止也不警示
        listOf("server.properties", "plugins/EssentialsX/config.yml", "logs/latest.log")
            .forEach { p ->
                val policy = AiFileTools.writePolicyFor(root, p)
                assertTrue("普通文件不应被禁止：$p", policy.forbidden == null)
                assertTrue("普通文件不应被警示：$p", policy.warning == null)
            }
    }

    @Test
    fun `策略判定给出解析后的绝对路径`() {
        val root = instanceDir()
        val policy = AiFileTools.writePolicyFor(root, "config/new.toml")
        assertEquals(
            root.canonicalFile.path + File.separator + "config" + File.separator + "new.toml",
            policy.resolvedPath,
        )
        // 越界路径解析不出来：回退成原始相对路径，不能让确认卡空着
        assertEquals("../evil.txt", AiFileTools.writePolicyFor(root, "../evil.txt").resolvedPath)
    }

    @Test
    fun `禁止写入的文件在写盘层也被拒绝`() {
        val root = instanceDir()
        listOf("user_jvm_args.txt", "ops.json", "eula.txt", "x/y/boom.mcfunction").forEach { p ->
            try {
                AiFileTools.writeFile(root, p, "hacked")
                fail("应当拒绝写入：$p")
            } catch (e: IllegalArgumentException) {
                assertTrue("错误信息应说明禁止原因：${e.message}", e.message!!.contains("禁止"))
            }
            assertFalse("被拒绝的文件不应落盘：$p", File(root, p).exists())
        }
    }
}
