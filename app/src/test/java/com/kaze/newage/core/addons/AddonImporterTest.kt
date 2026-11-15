package com.kaze.newage.core.addons

import com.kaze.newage.data.model.CoreType
import com.kaze.newage.testutil.TestJars
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

class AddonImporterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun reset() {
        ModMetadataReader.invalidate()
    }

    private fun instance(core: CoreType = CoreType.FABRIC) =
        TestJars.instance(tmp.newFolder(), core)

    @Test
    fun importsSingleJar() {
        val instance = instance()
        val src = TestJars.fabricMod(File(tmp.newFolder("src"), "lithium-0.14.3.jar"))
        val report = AddonImporter.importFromFile(instance, AddonKind.MOD, src)

        assertEquals(1, report.okCount)
        assertTrue(report.failed.isEmpty())
        val dest = File(instance.dir, "mods/lithium-0.14.3.jar")
        assertTrue(dest.isFile)
        assertEquals("Lithium", report.imported.first().meta.name)
    }

    @Test
    fun skipsIdenticalFileWithDifferentName() {
        val instance = instance()
        val src = TestJars.fabricMod(File(tmp.newFolder("src"), "lithium.jar"))
        AddonImporter.importFromFile(instance, AddonKind.MOD, src)

        // 同一个内容、换个名字再导入一次：不应出现两份（服务端会把同一个模组加载两遍）
        val copy = File(src.parentFile, "lithium-copy.jar")
        src.copyTo(copy)
        val report = AddonImporter.importFromFile(instance, AddonKind.MOD, copy)

        assertEquals(0, report.okCount)
        assertEquals(1, report.skipped.size)
        assertFalse(report.skipped.first().conflict)
        assertTrue(report.skipped.first().reason.contains("内容相同"))
        assertEquals(1, File(instance.dir, "mods").listFiles { f -> f.name.endsWith(".jar") }!!.size)
    }

    @Test
    fun sameNameDifferentContentNeedsConfirmation() {
        val instance = instance()
        val first = TestJars.fabricMod(
            File(tmp.newFolder("a"), "lithium.jar"), version = "0.14.3",
        )
        AddonImporter.importFromFile(instance, AddonKind.MOD, first)

        val second = TestJars.fabricMod(
            File(tmp.newFolder("b"), "lithium.jar"), version = "0.15.0",
        )
        val blocked = AddonImporter.importFromFile(instance, AddonKind.MOD, second)
        assertEquals(0, blocked.okCount)
        assertTrue(blocked.hasConflict)

        val replaced = AddonImporter.importFromFile(instance, AddonKind.MOD, second, overwrite = true)
        assertEquals(1, replaced.okCount)
        assertTrue(replaced.imported.first().replaced)
        val meta = ModMetadataReader.read(File(instance.dir, "mods/lithium.jar"))
        assertEquals("0.15.0", meta.version)
    }

    @Test
    fun expandsModpackZip() {
        val instance = instance()
        val sodium = TestJars.fabricMod(
            File(tmp.newFolder("x"), "sodium.jar"), id = "sodium", name = "Sodium",
        )
        val lithium = TestJars.fabricMod(
            File(tmp.newFolder("y"), "lithium.jar"), id = "lithium", name = "Lithium",
        )
        val pack = TestJars.zip(
            File(tmp.newFolder("pack"), "pack.zip"),
            mapOf(
                "mods/sodium.jar" to sodium.readBytes(),
                "overrides/mods/lithium.jar" to lithium.readBytes(),
                "README.txt" to "hi".toByteArray(),
            ),
        )
        val report = AddonImporter.importFromFile(instance, AddonKind.MOD, pack)
        assertEquals(2, report.okCount)
        assertTrue(File(instance.dir, "mods/sodium.jar").isFile)
        assertTrue(File(instance.dir, "mods/lithium.jar").isFile)
    }

    @Test
    fun rejectsNonZip() {
        val instance = instance()
        val fake = File(tmp.newFolder("bad"), "broken.jar")
        fake.writeText("<html>404 Not Found</html>")
        val report = AddonImporter.importFromFile(instance, AddonKind.MOD, fake)
        assertEquals(0, report.okCount)
        assertEquals(1, report.failed.size)
        assertTrue(report.failed.first().reason.contains("不是有效的 jar"))
    }

    @Test
    fun importsFromStreamWithDisplayName() {
        val instance = instance()
        val src = TestJars.fabricMod(
            File(tmp.newFolder("s"), "whatever.bin"), id = "sodium", name = "Sodium",
        )
        val report = AddonImporter.importFromStream(
            instance,
            AddonKind.MOD,
            "sodium-0.6.5",
            ByteArrayInputStream(src.readBytes()),
        )
        assertEquals(1, report.okCount)
        assertTrue(File(instance.dir, "mods/sodium-0.6.5.jar").isFile)
    }

    @Test
    fun disabledSourceImportsAsEnabled() {
        val instance = instance()
        val src = TestJars.fabricMod(File(tmp.newFolder("d"), "lithium.jar.disabled"))
        val report = AddonImporter.importFromFile(instance, AddonKind.MOD, src)
        assertEquals(1, report.okCount)
        val dest = File(instance.dir, "mods/lithium.jar")
        assertTrue(dest.isFile)
        assertTrue(AddonManager.isEnabled(dest))
    }

    @Test
    fun sanitizesTraversalNames() {
        // 目标名只取纯文件名：`../` 必须被剥掉，否则写出 mods/ 之外
        assertEquals("evil.jar", AddonImporter.sanitizeJarName("../../evil.jar", AddonKind.MOD))
        assertEquals("a.jar", AddonImporter.sanitizeJarName("a", AddonKind.MOD))
        assertEquals("x.jar", AddonImporter.sanitizeJarName("x.jar.disabled", AddonKind.MOD))
        assertTrue(AddonImporter.sanitizeJarName("", AddonKind.MOD).endsWith(".jar"))
    }

    @Test
    fun warningsCarryCompatibilityNotes() {
        // 往 Forge 实例导 Fabric 模组：允许（用户可能要试），但必须给出提示
        val instance = instance(CoreType.FORGE)
        val src = TestJars.fabricMod(File(tmp.newFolder("f"), "lithium.jar"))
        val report = AddonImporter.importFromFile(instance, AddonKind.MOD, src)
        assertEquals(1, report.okCount)
        assertTrue(report.imported.first().notes.any { it.level == ModCompatibility.Level.Warn })
    }
}
