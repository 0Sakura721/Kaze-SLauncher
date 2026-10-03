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

    /**
     * `app:` 的可读范围被刻意收窄到"应用日志 + 诊断文件"。
     *
     * 原来整个 filesDir 都是可读根，里面有 `instances.json`（实例库：所有实例的名字、
     * 路径、内存配置）、`background.png`、`linux/`（rootfs）。这些与诊断无关，
     * 却是注入者最想要的东西（一次 read_file 就能把整份实例清单发往第三方端点）。
     */
    @Test
    fun `app 前缀只开放日志与诊断文件`() {
        val appDir = tmp.newFolder("appfiles")
        File(appDir, "logs").mkdirs()
        File(appDir, "logs/latest.log").writeText("log line")
        File(appDir, "instances.json").writeText("""[{"name":"生存服"}]""")
        File(appDir, "background.png").writeBytes(byteArrayOf(1))
        File(appDir, "linux").mkdirs()

        // 允许：日志目录下的文件、日志目录本身、诊断文件
        assertEquals("log line", AiFileTools.readFile(instanceDir(), appDir, "app:logs/latest.log"))
        assertTrue(AiFileTools.listDir(instanceDir(), appDir, "app:logs").contains("latest.log"))
        File(appDir, "diagnostics.txt").writeText("env ok")
        assertEquals("env ok", AiFileTools.readFile(instanceDir(), appDir, "app:diagnostics.txt"))

        // 拒绝：实例库与其它任何文件
        listOf(
            "app:instances.json",
            "app:background.png",
            "app:linux",
            "app:",                    // 连根目录列表都不给（否则等于列出整个私有目录）
            "app:logs/../instances.json", // 绕行写法：按规范路径判定，仍然拦下
        ).forEach { p ->
            try {
                AiFileTools.readFile(instanceDir(), appDir, p)
                fail("应当拒绝读取：$p")
            } catch (e: IllegalArgumentException) {
                assertTrue("错误信息应说明只开放日志与诊断：${e.message}", e.message!!.contains("只允许读取应用日志"))
            }
        }
    }

    // ── 敏感读取策略（凭据类文件读之前要用户确认）──

    @Test
    fun `凭据类文件名与权限名单需要确认后才能读`() {
        val root = instanceDir()
        listOf(
            "plugins/AuthMe/config.yml",             // 名字本身不含凭据片段
            "plugins/MySQL/db_password.txt",
            "config/apikey.json",
            "config/access_token.yml",
            "config/rcon.secret",
            "config/serverkey.txt",
            "ops.json",
        ).forEach { p ->
            val policy = AiFileTools.readPolicyFor(root, p)
            if (p == "plugins/AuthMe/config.yml") {
                // 名字不含凭据片段：不额外打扰（内容层面的判断做不到，也不该猜）
                assertFalse("不该无端要求确认：$p", policy.needsConfirm)
            } else {
                assertTrue("应要求确认：$p", policy.needsConfirm)
            }
        }
    }

    @Test
    fun `读策略给出解析后的绝对路径`() {
        val root = instanceDir()
        val policy = AiFileTools.readPolicyFor(root, "plugins/MySQL/db_password.txt")
        assertEquals(
            root.canonicalFile.path + File.separator + "plugins" + File.separator +
                "MySQL" + File.separator + "db_password.txt",
            policy.resolvedPath,
        )
    }

    @Test
    fun `server_properties 只在这真的带 rcon 时才要确认`() {
        val root = instanceDir()
        val props = File(root, "server.properties")
        // 普通 server.properties：诊断最常用的文件，一律要求确认会平白拦住正常排查
        props.writeText("server-port=25565\nmax-players=20\n")
        assertFalse(AiFileTools.readPolicyFor(root, "server.properties").needsConfirm)
        // 带 rcon 密码：读了等于把远程控制口令交给模型
        props.writeText("server-port=25565\nrcon.password=S3cret\n")
        assertTrue(AiFileTools.readPolicyFor(root, "server.properties").needsConfirm)
        // 开了 rcon 但密码行为空/被注释掉：不算（没有口令可泄露）
        props.writeText("enable-rcon=true\nrcon.password=\n# rcon.password=x\n")
        assertTrue("enable-rcon=true 也算敏感", AiFileTools.readPolicyFor(root, "server.properties").needsConfirm)
        props.writeText("enable-rcon=false\nrcon.password=\n")
        assertFalse(AiFileTools.readPolicyFor(root, "server.properties").needsConfirm)
        // 单独的 rcon 判定
        assertTrue(AiFileTools.isRconSecretLine("rcon.password=abc"))
        assertFalse(AiFileTools.isRconSecretLine("# rcon.password=abc"))
        assertFalse(AiFileTools.isRconSecretLine("rcon.password="))
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
        val root = instanceDir()
        try {
            AiFileTools.readFile(root, null, "logs/latest.log")
            fail("应当抛出")
        } catch (e: Exception) {
            assertTrue(e.message!!.contains("不存在"))
            // 错误信息会回喂给模型：不能把设备的绝对路径（用户名、存储布局）一并交出去
            assertFalse("错误信息不该回显绝对路径：${e.message}", e.message!!.contains(root.canonicalFile.path))
        }
    }

    @Test
    fun `列目录超过上限只统计剩余条数`() {
        val root = instanceDir()
        repeat(205) { File(root, "file-$it.txt").writeText("x") }
        val out = AiFileTools.listDir(root, null, ".")
        assertTrue("应注明还有多少项未列出：$out", out.contains("还有"))
        // 上限是 200：多出来的 5 项只计数，不逐条列出
        assertTrue(out.contains("…还有 5 项未列出"))
        assertFalse(out.contains("file-204.txt"))
    }

    @Test
    fun `列目录失败信息不回显绝对路径`() {
        val root = instanceDir()
        try {
            AiFileTools.listDir(root, null, "nope")
            fail("应当抛出")
        } catch (e: Exception) {
            assertTrue(e.message!!.contains("不存在"))
            assertFalse("错误信息不该回显绝对路径：${e.message}", e.message!!.contains(root.canonicalFile.path))
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
    fun `覆盖已有文件留带时间戳的备份`() {
        val root = instanceDir()
        File(root, "server.properties").writeText("old=1")
        val out = AiFileTools.writeFile(root, "server.properties", "new=2")
        assertEquals("new=2", File(root, "server.properties").readText())
        val baks = root.listFiles().orEmpty().filter { it.name.startsWith("server.properties.") && it.name.endsWith(".bak") }
        assertEquals("应当恰好留一代备份：${baks.map { it.name }}", 1, baks.size)
        assertEquals("old=1", baks[0].readText())
        // 备份名必须带时间戳：`server.properties.bak` 这种固定名会在第二次写入时被覆盖，
        // 而它正是"改坏之前的状态"，覆盖掉就等于没有回滚点
        assertTrue("备份名应含时间戳：${baks[0].name}", baks[0].name != "server.properties.bak")
        assertTrue("提示行应说明备份文件名：$out", out.contains(baks[0].name))
        assertTrue("提示行应按字符口径给出上限相关内容：$out", out.contains("字符"))
    }

    @Test
    fun `连续两次覆盖留下两代备份`() {
        val root = instanceDir()
        File(root, "server.properties").writeText("v1")
        AiFileTools.writeFile(root, "server.properties", "v2")
        AiFileTools.writeFile(root, "server.properties", "v3")
        val contents = root.listFiles().orEmpty()
            .filter { it.name.startsWith("server.properties.") && it.name.endsWith(".bak") }
            .map { it.readText() }
            .toSet()
        assertEquals("原始内容与中间态都该留着：$contents", setOf("v1", "v2"), contents)
    }

    @Test
    fun `备份文件本身不在 AI 可读写的类型名单里`() {
        val root = instanceDir()
        File(root, "server.properties").writeText("old=1")
        AiFileTools.writeFile(root, "server.properties", "new=2")
        val bak = root.listFiles()!!.first { it.name.endsWith(".bak") }
        try {
            AiFileTools.writeFile(root, bak.name, "hacked")
            fail("AI 不应能写 .bak：备份是唯一的回滚凭据")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("不在可写类型名单"))
        }
        try {
            AiFileTools.readFile(root, null, bak.name)
            fail("AI 不应能读 .bak")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("不在可读类型名单"))
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
            AiFileTools.writeFile(root, "big.txt", "x".repeat(AiFileTools.MAX_WRITE_CHARS + 1))
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
