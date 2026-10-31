package com.kaze.newage.core.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 整合包导入测试（issue #15 的「服务器整合包 zip 直接导入」）。
 *
 * 这块的风险集中在三处，测试也按这三处组织：
 *  1. **安全**：外部 zip 是不可信输入，`../` 逃逸与 zip 炸弹必须被拦；
 *  2. **目录形态**：整合包常见「整包多一层壳」「CurseForge 的 overrides/」，
 *     摊平失败会导致 `server.jar` 不在实例根目录、启动器找不到它；
 *  3. **怎么启动**：jar / Forge `@argfile` / 只有 mods，三种要分别识别对，
 *     其中 Forge 库不全**绝不能**写成"已安装"（写错就永远修不回来）。
 */
class ModpackImporterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── 造 zip 的小工具 ──

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            for ((name, body) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(body.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun zipBytes(entries: Map<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            for ((name, body) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(body)
                z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun newDir(name: String = "inst"): File = tmp.newFolder(name)

    private fun extractTo(bytes: ByteArray, dest: File = newDir()) =
        ModpackImporter.extract(ByteArrayInputStream(bytes), dest) to dest

    // ══════════ 解压：正常路径 ══════════

    @Test
    fun `解压普通文件与子目录`() {
        val (r, dest) = extractTo(
            zip(
                "server.jar" to "jar-content",
                "mods/a.jar" to "mod-a",
                "config/x.yml" to "cfg",
            ),
        )
        assertEquals(3, r.entryCount)
        assertEquals("jar-content", File(dest, "server.jar").readText())
        assertEquals("mod-a", File(dest, "mods/a.jar").readText())
        assertEquals("cfg", File(dest, "config/x.yml").readText())
        assertTrue(r.skipped.isEmpty())
    }

    @Test
    fun `保留目录条目`() {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            z.putNextEntry(ZipEntry("mods/"))
            z.closeEntry()
            z.putNextEntry(ZipEntry("mods/b.jar"))
            z.write("b".toByteArray())
            z.closeEntry()
        }
        val dest = newDir()
        ModpackImporter.extract(ByteArrayInputStream(bos.toByteArray()), dest)
        assertTrue(File(dest, "mods").isDirectory)
        assertTrue(File(dest, "mods/b.jar").isFile)
    }

    // ══════════ 安全：Zip Slip ══════════

    @Test
    fun `拒绝跳出目标目录的条目（zip slip）`() {
        val dest = newDir()
        val outside = File(dest.parentFile, "evil.txt")
        val r = ModpackImporter.extract(
            ByteArrayInputStream(zip("../evil.txt" to "pwned")), dest,
        )
        assertFalse("绝不能写到目标目录之外", outside.exists())
        assertEquals(1, r.skipped.size)
        assertTrue(r.skipped.first().contains("evil.txt"))
    }

    @Test
    fun `拒绝深层路径逃逸`() {
        val dest = newDir()
        val r = ModpackImporter.extract(
            ByteArrayInputStream(zip("mods/../../evil2.txt" to "pwned")), dest,
        )
        assertFalse(File(dest.parentFile, "evil2.txt").exists())
        assertEquals(1, r.skipped.size)
    }

    @Test
    fun `正常的相对路径不被误杀`() {
        val (r, dest) = extractTo(zip("a/b/c/d.txt" to "ok"))
        assertTrue(r.skipped.isEmpty())
        assertEquals("ok", File(dest, "a/b/c/d.txt").readText())
    }

    // ══════════ 安全：限额（用注入的小限额真跑一遍）══════════

    @Test
    fun `条目数超限时中止`() {
        val bytes = zip("a" to "1", "b" to "2", "c" to "3")
        val dest = newDir()
        val e = runCatching {
            ModpackImporter.extractWithLimits(
                ByteArrayInputStream(bytes), dest,
                maxEntries = 2, maxTotalBytes = 1_000_000, maxEntryBytes = 1_000_000,
            )
        }.exceptionOrNull()
        assertTrue("超限必须抛错", e is IllegalStateException)
        assertTrue(e!!.message!!.contains("条目过多"))
    }

    @Test
    fun `总量超限时中止（zip 炸弹）`() {
        val big = ByteArray(4096) { 0x41 }
        val bytes = zipBytes(mapOf("a.bin" to big, "b.bin" to big))
        val dest = newDir()
        val e = runCatching {
            ModpackImporter.extractWithLimits(
                ByteArrayInputStream(bytes), dest,
                maxEntries = 100, maxTotalBytes = 5000, maxEntryBytes = 100_000,
            )
        }.exceptionOrNull()
        assertTrue(e is IllegalStateException)
        assertTrue(e!!.message!!.contains("总量"))
    }

    @Test
    fun `单个超大文件被跳过而不是整包失败`() {
        val big = ByteArray(8192) { 0x41 }
        val bytes = zipBytes(mapOf("huge.bin" to big, "ok.txt" to "fine".toByteArray()))
        val dest = newDir()
        val r = ModpackImporter.extractWithLimits(
            ByteArrayInputStream(bytes), dest,
            maxEntries = 100, maxTotalBytes = 10_000_000, maxEntryBytes = 4096,
        )
        assertEquals("正常文件照样导进来", "fine", File(dest, "ok.txt").readText())
        assertFalse(File(dest, "huge.bin").exists())
        assertEquals(1, r.skipped.size)
        assertTrue(r.skipped.first().contains("超限"))
    }

    @Test
    fun `取消标志能中断解压`() {
        val bytes = zip("a" to "1", "b" to "2")
        val e = runCatching {
            ModpackImporter.extract(
                ByteArrayInputStream(bytes), newDir(),
                shouldCancel = { true },
            )
        }.exceptionOrNull()
        assertTrue("取消要抛 InterruptedException", e is InterruptedException)
    }

    // ══════════ 摊平：两种真实布局 ══════════

    @Test
    fun `整包多一层壳时摊平到根目录`() {
        val dest = newDir()
        val shell = File(dest, "MyPack-1.20.1")
        File(shell, "mods").mkdirs()
        File(shell, "server.jar").writeText("jar")
        File(shell, "mods/m.jar").writeText("mod")

        val changed = ModpackImporter.flatten(dest)

        assertTrue(changed)
        assertTrue("server.jar 必须在实例根目录", File(dest, "server.jar").isFile)
        assertTrue(File(dest, "mods/m.jar").isFile)
        assertFalse("空壳目录应被清掉", shell.exists())
    }

    @Test
    fun `CurseForge 的 overrides 被摊平`() {
        val dest = newDir()
        File(dest, "manifest.json").writeText("{}")
        File(dest, "overrides/mods").mkdirs()
        File(dest, "overrides/mods/x.jar").writeText("mod")
        File(dest, "overrides/server.jar").writeText("jar")

        assertTrue(ModpackImporter.flatten(dest))

        assertTrue(File(dest, "server.jar").isFile)
        assertTrue(File(dest, "mods/x.jar").isFile)
        assertFalse(File(dest, "overrides").exists())
    }

    @Test
    fun `摊平不覆盖根目录已有文件`() {
        val dest = newDir()
        File(dest, "server.jar").writeText("kept")
        File(dest, "overrides").mkdirs()
        File(dest, "overrides/server.jar").writeText("pack")

        ModpackImporter.flatten(dest)

        assertEquals("同名时保留根目录那份", "kept", File(dest, "server.jar").readText())
    }

    @Test
    fun `已经平铺的目录不再改动`() {
        val dest = newDir()
        File(dest, "mods").mkdirs()
        File(dest, "server.jar").writeText("jar")
        File(dest, "config").mkdirs()
        assertFalse(ModpackImporter.flatten(dest))
        assertTrue(File(dest, "server.jar").isFile)
    }

    // ══════════ 识别：三种启动形态 ══════════

    @Test
    fun `识别可直接 -jar 的服务端`() {
        val dest = newDir()
        // 造一个带 version.json 的 paperclip 形态 jar
        val jarBytes = zipBytes(
            mapOf(
                "version.json" to """{"id":"1.20.1"}""".toByteArray(),
                "META-INF/MANIFEST.MF" to "Main-Class: io.papermc.paperclip.Main\n".toByteArray(),
            ),
        )
        File(dest, "server.jar").writeBytes(jarBytes)
        File(dest, "mods").mkdirs()

        val plan = ModpackImporter.inspect(dest)

        assertEquals(ModpackImporter.Launch.JAR, plan.launch)
        assertEquals("PAPER", plan.coreTypeName)
        assertEquals("1.20.1", plan.mcVersion)
        assertEquals(17, plan.javaMajor)
        assertEquals("server.jar", plan.launchJar?.name)
    }

    @Test
    fun `识别 fabric 启动器 jar`() {
        val dest = newDir()
        File(dest, "mods").mkdirs()
        File(dest, "fabric-server-launch.jar").writeBytes(
            zipBytes(mapOf("x.class" to ByteArray(20))),
        )
        val plan = ModpackImporter.inspect(dest)
        assertEquals(ModpackImporter.Launch.JAR, plan.launch)
        assertEquals("FABRIC", plan.coreTypeName)
    }

    @Test
    fun `识别 Forge 的 unix_args 形态并认出 MC 版本`() {
        val dest = newDir()
        val libDir = File(dest, "libraries/net/minecraftforge/forge/1.20.1-47.2.0")
        libDir.mkdirs()
        File(libDir, "unix_args.txt").writeText(
            "-p libraries/a.jar:libraries/b.jar\n" +
                "-cp libraries/net/minecraftforge/forge/1.20.1-47.2.0/x.jar\n",
        )
        // 造出其中一半的库，另一半点名"缺失"
        File(dest, "libraries/a.jar").parentFile.mkdirs()
        File(dest, "libraries/a.jar").writeText("a")

        val plan = ModpackImporter.inspect(dest)

        assertEquals(ModpackImporter.Launch.FORGE_ARGS, plan.launch)
        assertEquals("FORGE", plan.coreTypeName)
        assertEquals("1.20.1", plan.mcVersion)
        assertTrue(plan.argsFile!!.name == "unix_args.txt")
        assertEquals("应列出缺失的 b.jar 与 forge 主 jar", 2, plan.missingForgeLibs.size)
        assertTrue(plan.warnings.any { it.contains("依赖库不完整") })
    }

    @Test
    fun `依赖库齐全时不报警告`() {
        val dest = newDir()
        val libDir = File(dest, "libraries/net/minecraftforge/forge/1.20.1-47.2.0")
        libDir.mkdirs()
        val a = File(dest, "libraries/a.jar")
        a.parentFile.mkdirs()
        a.writeText("a")
        File(libDir, "unix_args.txt").writeText("-cp libraries/a.jar\n")

        val plan = ModpackImporter.inspect(dest)

        assertEquals(ModpackImporter.Launch.FORGE_ARGS, plan.launch)
        assertTrue("库齐全时不该报不完整", plan.missingForgeLibs.isEmpty())
        assertFalse(plan.warnings.any { it.contains("依赖库不完整") })
    }

    @Test
    fun `只有 mods 没有核心时给出明确指引`() {
        val dest = newDir()
        File(dest, "mods").mkdirs()
        File(dest, "mods/jei-1.20.1.jar").writeText("mod")
        File(dest, "config").mkdirs()

        val plan = ModpackImporter.inspect(dest)

        assertEquals(ModpackImporter.Launch.MODS_ONLY, plan.launch)
        assertTrue(
            "必须告诉用户怎么补核心，而不是只说'没有 jar'",
            plan.warnings.any { it.contains("没有服务端核心") && it.contains("导入 jar") },
        )
    }

    // ══════════ 版本兜底：从 mods 文件名推 ══════════

    @Test
    fun `多数 mod 文件名一致时能推出 MC 版本`() {
        val dest = newDir()
        File(dest, "mods").mkdirs()
        // 3 个指向 1.20.1，1 个是 mod 自己的版本号
        File(dest, "mods/jei-15.2.0+1.20.1.jar").writeText("")
        File(dest, "mods/sodium-0.5.8+1.20.1.jar").writeText("")
        File(dest, "mods/fabric-api-0.90.0+1.20.1.jar").writeText("")
        File(dest, "mods/other-2.0.jar").writeText("")

        assertEquals("1.20.1", ModpackImporter.mcFromModsDir(dest))
    }

    @Test
    fun `版本号分散时不敢猜`() {
        val dest = newDir()
        File(dest, "mods").mkdirs()
        File(dest, "mods/a-1.20.1.jar").writeText("")
        File(dest, "mods/b-1.19.2.jar").writeText("")
        File(dest, "mods/c-1.18.2.jar").writeText("")
        File(dest, "mods/d-1.16.5.jar").writeText("")

        assertEquals("没有明显多数就不猜", "", ModpackImporter.mcFromModsDir(dest))
    }

    @Test
    fun `空 mods 目录安全返回空`() {
        val dest = newDir()
        assertEquals("", ModpackImporter.mcFromModsDir(dest))
        File(dest, "mods").mkdirs()
        assertEquals("", ModpackImporter.mcFromModsDir(dest))
    }

    // ══════════ 形态判定 ══════════

    @Test
    fun `看起来像整合包的目录被认出`() {
        val dest = newDir()
        File(dest, "mods").mkdirs()
        assertTrue(ModpackImporter.looksLikeModpack(dest))

        val other = newDir("other")
        assertFalse(ModpackImporter.looksLikeModpack(other))
    }
}