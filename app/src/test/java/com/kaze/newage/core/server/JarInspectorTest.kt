package com.kaze.newage.core.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 导入时识别 jar 的测试。
 *
 * 用真实构造的 zip 验（不依赖网络、不需要真 server.jar）：每种核心/版本形态的
 * 关键特征都造出来，断言识别结果 —— 这块逻辑一旦判错，用户导入后会在启动时
 * 才炸（UnsupportedClassVersionError 或插件版本对不上），很难定位。
 */
class JarInspectorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun jar(name: String = "server.jar", build: (ZipOutputStream) -> Unit): File {
        val f = File(tmp.newFolder(), name)
        ZipOutputStream(f.outputStream()).use { z -> build(z) }
        return f
    }

    private fun ZipOutputStream.text(path: String, body: String) {
        putNextEntry(ZipEntry(path))
        write(body.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    /** 造一个带指定 class 版本号的 class 文件（只要头 8 字节就能判版本） */
    private fun ZipOutputStream.classFile(path: String, major: Int) {
        putNextEntry(ZipEntry(path))
        val head = byteArrayOf(
            0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte(),
            0, 0, ((major shr 8) and 0xFF).toByte(), (major and 0xFF).toByte(),
        )
        write(head)
        write(ByteArray(16))
        closeEntry()
    }

    // ══════════ 快速体检 ══════════

    @Test
    fun `不是 zip 的文件被快速体检拦下`() {
        val f = File(tmp.newFolder(), "not.jar")
        f.writeText("这不是 jar，只是一段文本")
        val info = JarInspector.quickCheck(f)
        assertFalse(info.looksLikeServerJar)
        assertEquals("", info.mcVersion)
        assertTrue(info.evidence.contains("不是 zip"))
    }

    @Test
    fun `空文件被拦下`() {
        val f = File(tmp.newFolder(), "empty.jar")
        f.createNewFile()
        assertEquals("文件为空", JarInspector.quickCheck(f).evidence)
    }

    @Test
    fun `不存在的文件不抛异常`() {
        val info = JarInspector.quickCheck(File(tmp.root, "nope.jar"))
        assertFalse(info.looksLikeServerJar)
        assertEquals("不是文件", info.evidence)
    }

    // ══════════ Paper / Purpur（paperclip）══════════

    @Test
    fun `paperclip 的 version_json 给出 MC 版本与 PAPER`() {
        val f = jar { z ->
            z.text("version.json", """{"id":"1.21.4","name":"1.21.4","world_version":4189}""")
            z.text("META-INF/MANIFEST.MF", "Main-Class: io.papermc.paperclip.Main\n")
            z.classFile("io/papermc/paperclip/Main.class", 61)   // Java 17
        }
        val info = JarInspector.quickCheck(f)
        assertTrue(info.looksLikeServerJar)
        assertEquals("PAPER", info.coreTypeName)
        assertEquals("1.21.4", info.mcVersion)
        assertEquals(21, info.javaMajor)   // 1.21+ 要 21
        assertTrue(info.evidence.contains("version.json"))
    }

    @Test
    fun `purpur 被识别为 PURPUR`() {
        val f = jar { z ->
            z.text("version.json", """{"id":"1.20.4"}""")
            z.text("META-INF/MANIFEST.MF", "Implementation-Title: Purpur\nMain-Class: org.bukkit.craftbukkit.Main\n")
            z.classFile("org/bukkit/craftbukkit/Main.class", 61)
        }
        val info = JarInspector.quickCheck(f)
        assertEquals("PURPUR", info.coreTypeName)
        assertEquals("1.20.4", info.mcVersion)
        assertEquals(17, info.javaMajor)
    }

    @Test
    fun `旧版 paperclip 的 download-context 也能给出版本`() {
        val f = jar { z ->
            z.text("META-INF/download-context", "abc123\thttps://x/y\tmojang_1.16.5.jar")
            z.text("META-INF/MANIFEST.MF", "Main-Class: io.papermc.paperclip.Main\n")
            z.classFile("io/papermc/paperclip/Main.class", 52)   // Java 8
        }
        val info = JarInspector.quickCheck(f)
        assertEquals("1.16.5", info.mcVersion)
        assertEquals(8, info.javaMajor)
    }

    // ══════════ 官方 vanilla（bundler）══════════

    @Test
    fun `bundler 形态的官方服务端被识别为 VANILLA`() {
        val f = jar { z ->
            z.text("META-INF/MANIFEST.MF", "Main-Class: net.minecraft.bundler.Main\n")
            z.text("META-INF/libraries.list", "…")
            z.classFile("net/minecraft/bundler/Main.class", 61)
        }
        val info = JarInspector.quickCheck(f)
        assertTrue(info.looksLikeServerJar)
        assertEquals("VANILLA", info.coreTypeName)
        assertEquals(17, info.javaMajor)
    }

    @Test
    fun `含 MinecraftServer 类的 jar 至少被认成 VANILLA`() {
        val f = jar { z ->
            z.classFile("net/minecraft/server/MinecraftServer.class", 61)
        }
        val info = JarInspector.quickCheck(f)
        assertTrue(info.looksLikeServerJar)
        assertEquals("VANILLA", info.coreTypeName)
    }

    // ══════════ 模组加载器 ══════════

    @Test
    fun `fabric 服务端启动器被识别为 FABRIC`() {
        val f = jar { z ->
            z.text("META-INF/MANIFEST.MF", "Main-Class: net.fabricmc.loader.impl.launch.server.FabricServerLauncher\n")
            z.classFile("net/fabricmc/loader/impl/launch/server/FabricServerLauncher.class", 61)
        }
        assertEquals("FABRIC", JarInspector.quickCheck(f).coreTypeName)
    }

    @Test
    fun `forge 安装器被识别为 FORGE`() {
        val f = jar { z ->
            z.classFile("net/minecraftforge/server/loading/ServerModLoader.class", 61)
        }
        assertEquals("FORGE", JarInspector.quickCheck(f).coreTypeName)
    }

    @Test
    fun `neoforge 被识别为 NEOFORGE`() {
        val f = jar { z ->
            z.classFile("net/neoforged/fml/loading/ModLoader.class", 61)
        }
        assertEquals("NEOFORGE", JarInspector.quickCheck(f).coreTypeName)
    }

    @Test
    fun `spigot 的 craftbukkit main 被识别为 SPIGOT`() {
        val f = jar { z ->
            z.text("META-INF/MANIFEST.MF", "Main-Class: org.bukkit.craftbukkit.Main\n")
            z.classFile("org/bukkit/craftbukkit/Main.class", 61)
        }
        assertEquals("SPIGOT", JarInspector.quickCheck(f).coreTypeName)
    }

    @Test
    fun `认不出的 jar 归 CUSTOM 且不谎报 server`() {
        val f = jar { z -> z.classFile("com/example/Thing.class", 61) }
        val info = JarInspector.quickCheck(f)
        assertEquals("CUSTOM", info.coreTypeName)
        assertEquals("", info.mcVersion)
        assertFalse(info.looksLikeServerJar)
    }

    // ══════════ Java 版本推断 ══════════

    @Test
    fun `class 版本映射到 Java 大版本`() {
        for ((major, java) in listOf(52 to 8, 55 to 8, 61 to 17, 65 to 21, 69 to 25)) {
            val f = jar { z -> z.classFile("net/minecraft/server/MinecraftServer.class", major) }
            assertEquals("class major=$major 应推成 Java $java", java, JarInspector.quickCheck(f).javaMajor)
        }
    }

    @Test
    fun `按 MC 版本推 Java 版本`() {
        assertEquals(8, JarInspector.javaForMc("1.16.5"))
        assertEquals(8, JarInspector.javaForMc("1.12.2"))
        assertEquals(17, JarInspector.javaForMc("1.18"))
        assertEquals(17, JarInspector.javaForMc("1.20.4"))
        assertEquals(21, JarInspector.javaForMc("1.20.5"))
        assertEquals(21, JarInspector.javaForMc("1.21"))
        assertEquals(21, JarInspector.javaForMc("1.21.4"))
    }

    @Test
    fun `没有 class 也没有版本时保守给 17`() {
        val f = jar { z -> z.text("readme.txt", "hi") }
        assertEquals(17, JarInspector.quickCheck(f).javaMajor)
    }

    @Test
    fun `没有 class 但知道 MC 版本时按版本推`() {
        val f = jar { z ->
            z.text("version.json", """{"id":"1.20.5"}""")
            z.text("META-INF/MANIFEST.MF", "Main-Class: io.papermc.paperclip.Main\n")
        }
        val info = JarInspector.quickCheck(f)
        assertEquals("1.20.5", info.mcVersion)
        // 只有 paperclip 的启动类（没联网拉下来的 server class）→ 按 MC 版本推 Java
        assertEquals(21, info.javaMajor)
    }

    // ══════════ 边界 ══════════

    @Test
    fun `坏 zip 不抛异常`() {
        val f = File(tmp.newFolder(), "broken.jar")
        f.writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 1, 2, 3))   // 有魔数但内容是垃圾
        val info = JarInspector.quickCheck(f)
        assertFalse(info.looksLikeServerJar)
    }

    @Test
    fun `版本字符串里的假版本不会被误取`() {
        // MANIFEST 里有很多数字，只认形如 1.x(.y) 的
        val f = jar { z ->
            z.text("META-INF/MANIFEST.MF", "Build-Jdk: 17.0.9\nImplementation-Version: git-Paper-196 (MC: 1.21.4)\n")
            z.text("version.json", """{"id":"1.21.4"}""")
        }
        // version.json 优先，结果必须精确
        assertEquals("1.21.4", JarInspector.quickCheck(f).mcVersion)
    }
}
