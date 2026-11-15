package com.kaze.newage.core.world

import com.kaze.newage.core.server.ServerProperties
import com.kaze.newage.testutil.TestJars
import com.kaze.newage.testutil.TestNbt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WorldManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var instance: com.kaze.newage.data.model.ServerInstance

    private fun makeWorld(dir: File, name: String, version: String = "1.21.1") {
        File(dir, "region").mkdirs()
        File(dir, "level.dat").writeBytes(TestNbt.levelDat(name, version))
        File(dir, "icon.png").writeBytes(ByteArray(16) { 1 })
        File(dir, "region/r.0.0.mca").writeBytes(ByteArray(32))
    }

    @org.junit.Before
    fun setUp() {
        instance = TestJars.instance(tmp.newFolder("srv"))
        makeWorld(File(instance.dir, "world"), "主世界")
        ServerProperties.save(instance.dir, linkedMapOf("level-name" to "world", "server-port" to "25565"))
    }

    @Test
    fun listsActiveWorldWithMetadataAndIcon() {
        val worlds = WorldManager.worlds(instance)
        assertEquals(1, worlds.size)
        val world = worlds.first()
        assertEquals("world", world.name)
        // 中文世界名要能从 gzip NBT 里正确读出来
        assertEquals("主世界", world.displayName)
        assertEquals("1.21.1", world.versionName)
        assertTrue(world.active)
        assertNotNull(world.iconFile)
        assertTrue(world.sizeBytes > 0)
    }

    @Test
    fun importsNestedZipAndFindsLevelDat() {
        val zip = TestNbt.zip(
            File(tmp.newFolder("src"), "map.zip"),
            mapOf(
                "MyMap/level.dat" to TestNbt.levelDat("冒险地图"),
                "MyMap/region/r.0.0.mca" to ByteArray(64),
                "MyMap/icon.png" to ByteArray(16),
                "README.txt" to "hi".toByteArray(),
            ),
        )
        val world = WorldManager.importZip(instance, zip, "冒险地图")
        assertEquals("冒险地图", world.displayName)
        assertTrue(world.dir.isDirectory)
        assertTrue(File(world.dir, "level.dat").isFile)
        // 不能自动激活：切换世界是显式动作
        assertFalse(world.active)
        assertEquals(2, WorldManager.worlds(instance).size)
    }

    @Test
    fun importRejectsZipWithoutLevelDat() {
        val zip = TestNbt.zip(
            File(tmp.newFolder("bad"), "bad.zip"),
            mapOf("readme.txt" to "no level.dat".toByteArray()),
        )
        val error = runCatching { WorldManager.importZip(instance, zip) }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error!!.message!!.contains("level.dat"))
    }

    @Test
    fun activateBacksUpOldWorldAndSwaps() {
        val zip = TestNbt.zip(
            File(tmp.newFolder("src2"), "new.zip"),
            mapOf("NewWorld/level.dat" to TestNbt.levelDat("新世界")),
        )
        val imported = WorldManager.importZip(instance, zip, "新世界")
        val activated = WorldManager.activate(instance, imported)

        assertEquals("world", activated.name)
        assertEquals("新世界", activated.displayName)
        assertTrue(activated.active)
        // 旧世界改名备份，而不是删除
        val backups = instance.dir.listFiles { f -> f.name.startsWith("world-backup-") }.orEmpty()
        assertEquals(1, backups.size)
        assertEquals("world", ServerProperties.load(instance.dir)["level-name"])
    }

    @Test
    fun activeWorldCannotBeDeleted() {
        val active = WorldManager.worlds(instance).first()
        assertFalse(WorldManager.delete(instance, active))
        assertTrue(active.dir.exists())

        val other = WorldManager.importZip(
            instance,
            TestNbt.zip(File(tmp.newFolder("s3"), "o.zip"), mapOf("W/level.dat" to TestNbt.levelDat("W"))),
            "W",
        )
        assertTrue(WorldManager.delete(instance, other))
        assertFalse(other.dir.exists())
    }

    @Test
    fun rejectsPlainHttpDownload() {
        val error = runCatching {
            WorldManager.downloadZip(instance, "http://example.com/map.zip")
        }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error!!.message!!.contains("https"))
    }

    @Test
    fun sanitizesWorldName() {
        val name = WorldManager.sanitizeWorldName("../../etc/passwd")
        assertFalse(name.contains("/"))
        assertFalse(name.contains("\\"))
        assertFalse(name.contains(".."))
        assertTrue(name.isNotBlank())
    }

    @Test
    fun ignoresBukkitDimensionFolders() {
        makeWorld(File(instance.dir, "world_nether"), "下界")
        val names = WorldManager.worlds(instance).map { it.name }
        assertFalse(names.contains("world_nether"))
    }

    @Test
    fun unknownLevelDatFallsBackToDirName() {
        val dir = File(instance.dir, "broken-world")
        dir.mkdirs()
        File(dir, "level.dat").writeBytes(byteArrayOf(1, 2, 3))   // 不是 NBT
        val world = WorldManager.worlds(instance).firstOrNull { it.name == "broken-world" }
        assertNotNull(world)
        assertEquals("broken-world", world!!.displayName)
        assertNull(world.iconFile)
    }
}
