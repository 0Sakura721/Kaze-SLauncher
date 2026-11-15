package com.kaze.newage.core.addons

import com.kaze.newage.data.model.CoreType
import com.kaze.newage.testutil.TestJars
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ModCompatibilityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── 版本范围 ──

    @Test
    fun matchesSpaceSeparatedRange() {
        assertTrue(ModCompatibility.rangeMatches(">=1.21 <1.22", "1.21.1"))
        assertFalse(ModCompatibility.rangeMatches(">=1.21 <1.22", "1.20.4"))
        assertTrue(ModCompatibility.rangeMatches(">=1.20.1 <1.21", "1.20.6"))
    }

    @Test
    fun matchesMavenRange() {
        assertTrue(ModCompatibility.rangeMatches("[1.20.1,1.21)", "1.20.6"))
        assertFalse(ModCompatibility.rangeMatches("[1.20.1,1.21)", "1.21"))
        // 上界开区间 → 等号版本不匹配；闭区间才匹配
        assertTrue(ModCompatibility.rangeMatches("[1.20.1,1.21]", "1.21"))
    }

    @Test
    fun matchesWildcardAndAlternatives() {
        assertTrue(ModCompatibility.rangeMatches("1.21.x", "1.21.4"))
        assertFalse(ModCompatibility.rangeMatches("1.21.x", "1.20.4"))
        assertTrue(ModCompatibility.rangeMatches("1.20.1 || 1.20.4", "1.20.4"))
        assertFalse(ModCompatibility.rangeMatches("1.20.1 || 1.20.4", "1.19.2"))
        assertTrue(ModCompatibility.rangeMatches("*", "1.21"))
        assertTrue(ModCompatibility.rangeMatches("", "1.21"))
    }

    @Test
    fun tolerantToUnparsableRange() {
        // 读不出数字就放行：宁可少一条警告，也不要误报不兼容让用户删掉能用的模组
        assertTrue(ModCompatibility.rangeMatches("release", "1.21.1"))
        assertTrue(ModCompatibility.rangeMatches("1.21.1-pre1", "1.21.1"))
    }

    // ── 加载器 ──

    @Test
    fun loaderConflicts() {
        assertNull(ModCompatibility.loaderConflict(CoreType.FABRIC, ModLoader.FABRIC))
        assertNull(ModCompatibility.loaderConflict(CoreType.FABRIC, ModLoader.QUILT))
        assertNotNull(ModCompatibility.loaderConflict(CoreType.FABRIC, ModLoader.FORGE))
        assertNotNull(ModCompatibility.loaderConflict(CoreType.PAPER, ModLoader.FABRIC))
        assertNotNull(ModCompatibility.loaderConflict(CoreType.FORGE, ModLoader.NEOFORGE))
        // 认不出加载器：不做断言
        assertNull(ModCompatibility.loaderConflict(CoreType.FABRIC, ModLoader.UNKNOWN))
        // 导入的 jar 认不出核心类型：也不做断言
        assertNull(ModCompatibility.loaderConflict(CoreType.CUSTOM, ModLoader.FORGE))
    }

    @Test
    fun warnsOnMismatchedMcVersion() {
        val dir = tmp.newFolder("srv")
        val instance = TestJars.instance(dir, CoreType.FABRIC, mc = "1.20.1")
        val meta = ModMetadata(
            modId = "lithium",
            name = "Lithium",
            loader = ModLoader.FABRIC,
            gameVersionRange = ">=1.21 <1.22",
            source = "fabric.mod.json",
        )
        val notes = ModCompatibility.check(instance, AddonKind.MOD, meta)
        assertTrue(notes.any { it.level == ModCompatibility.Level.Warn })
    }

    @Test
    fun silentForMatchingFabricMod() {
        val instance = TestJars.instance(tmp.newFolder("srv2"), CoreType.FABRIC, mc = "1.21.1")
        val meta = ModMetadata(
            modId = "lithium",
            name = "Lithium",
            loader = ModLoader.FABRIC,
            gameVersionRange = ">=1.21 <1.22",
            source = "fabric.mod.json",
        )
        assertEquals(emptyList<ModCompatibility.Note>(), ModCompatibility.check(instance, AddonKind.MOD, meta))
    }

    @Test
    fun readsRealJarThenChecks() {
        val instance = TestJars.instance(tmp.newFolder("srv3"), CoreType.FORGE, mc = "1.20.1")
        val jar: File = TestJars.fabricMod(File(tmp.newFolder("m"), "lithium.jar"))
        val meta = ModMetadataReader.read(jar)
        val notes = ModCompatibility.check(instance, AddonKind.MOD, meta)
        // 把 Fabric 模组装进 Forge 实例：必须给出加载器冲突提示
        assertTrue(notes.any { it.text.contains("Fabric") })
    }
}
