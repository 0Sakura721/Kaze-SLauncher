package com.kaze.newage.core.addons

import com.kaze.newage.testutil.TestJars
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ModMetadataReaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun reset() {
        ModMetadataReader.invalidate()
        ModIconCache.init(tmp.newFolder("cache"))
    }

    @Test
    fun readsFabricMetadata() {
        val jar = TestJars.fabricMod(File(tmp.newFolder("m"), "lithium.jar"))
        val meta = ModMetadataReader.read(jar)
        assertEquals("lithium", meta.modId)
        assertEquals("Lithium", meta.name)
        assertEquals("0.14.3+mc1.21.1", meta.version)
        assertEquals(ModLoader.FABRIC, meta.loader)
        assertEquals(">=1.21 <1.22", meta.gameVersionRange)
        assertEquals(listOf("jellysquid3", "2No2Name"), meta.authors)
        assertEquals("fabric.mod.json", meta.source)
        assertEquals("assets/lithium/icon.png", meta.iconEntry)
        // minecraft / fabricloader 都是加载器自身，不属于"模组依赖"
        assertTrue(meta.depends.isEmpty())
    }

    @Test
    fun readsForgeToml() {
        val jar = TestJars.forgeMod(File(tmp.newFolder("m"), "jei.jar"))
        val meta = ModMetadataReader.read(jar)
        assertEquals("jei", meta.modId)
        assertEquals("Just Enough Items", meta.name)
        assertEquals("15.2.0", meta.version)
        assertEquals(ModLoader.FORGE, meta.loader)
        assertEquals("[1.21,1.22)", meta.gameVersionRange)
        assertEquals(listOf("mezz"), meta.authors)
        assertEquals("logo.png", meta.iconEntry)
        assertEquals("META-INF/mods.toml", meta.source)
    }

    @Test
    fun fallsBackToFileNameWithoutMetadata() {
        val jar = TestJars.bareJar(File(tmp.newFolder("m"), "JourneyMap-5.9.7.jar"))
        val meta = ModMetadataReader.read(jar)
        assertEquals("JourneyMap-5.9.7", meta.name)
        assertEquals("5.9.7", meta.version)
        assertEquals(ModMetadata.SOURCE_FILENAME, meta.source)
        // 元数据读不出来，但图标仍然要被找到（很多手搓 jar 是这种形态）
        assertEquals("assets/mystery/icon.png", ModMetadataReader.findIconEntry(meta.modId, listOf(
            "some/class.class", "assets/mystery/icon.png",
        )))
    }

    @Test
    fun readsMetadataFromDisabledFile() {
        val jar = TestJars.fabricMod(File(tmp.newFolder("m"), "lithium.jar"))
        val disabled = File(jar.parentFile, "lithium.jar.disabled")
        jar.renameTo(disabled)
        assertEquals("Lithium", ModMetadataReader.read(disabled).name)
    }

    @Test
    fun iconCacheExtractsImageAndCaches() {
        val jar = TestJars.fabricMod(File(tmp.newFolder("m"), "lithium.jar"))
        val icon = ModIconCache.fileFor(jar)
        assertNotNull(icon)
        assertEquals(TestJars.PNG_BYTES.size.toLong(), icon!!.length())
        // 第二次必须命中磁盘缓存（不再打开 jar 也算通过：这里只校验返回同一个文件）
        assertEquals(icon.absolutePath, ModIconCache.fileFor(jar)!!.absolutePath)
    }

    @Test
    fun iconCacheReturnsNullForIconlessJar() {
        val jar = TestJars.fabricMod(File(tmp.newFolder("m"), "noicon.jar"), icon = false, id = "noicon")
        assertNull(ModIconCache.fileFor(jar))
    }

    @Test
    fun prefersAssetsOfMatchingModId() {
        val names = listOf(
            "assets/other/icon.png",
            "assets/mymod/icon.png",
            "assets/mymod/textures/icon_old.png",
        )
        assertEquals("assets/mymod/icon.png", ModMetadataReader.findIconEntry("mymod", names))
    }
}
