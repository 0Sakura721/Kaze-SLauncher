package com.kaze.newage.core.bedrock

import com.kaze.newage.core.server.ServerProperties
import com.kaze.newage.testutil.TestJars
import com.kaze.newage.testutil.TestNbt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BedrockPacksTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var instance: com.kaze.newage.data.model.ServerInstance

    @org.junit.Before
    fun setUp() {
        instance = TestJars.instance(tmp.newFolder("srv"), mc = "1.26.60")
        instance = instance.copy(bedrock = true)
        // 世界目录：world_*_packs.json 要写进当前世界
        val world = File(instance.dir, "world").apply { mkdirs() }
        File(world, "level.dat").writeBytes(TestNbt.levelDat("基岩世界"))
        ServerProperties.save(instance.dir, linkedMapOf("level-name" to "world", "server-port" to "19132"))
    }

    private fun manifest(
        name: String,
        uuid: String,
        moduleType: String = "resources",
        version: String = "1,2,0",
        minEngine: String = "1,20,0",
    ): String = """
        {"format_version":2,
         "header":{"name":"$name","description":"$name 的说明","uuid":"$uuid",
                   "version":[$version],"min_engine_version":[$minEngine]},
         "modules":[{"type":"$moduleType","uuid":"${uuid}-mod","version":[$version]}]}
    """.trimIndent()

    private fun makePack(file: File, name: String, uuid: String, moduleType: String = "resources"): File =
        TestNbt.zip(
            file,
            mapOf(
                "manifest.json" to manifest(name, uuid, moduleType).toByteArray(),
                "pack_icon.png" to ByteArray(24) { 1 },
            ),
        )

    @Test
    fun importsSingleMcpackWithIcon() {
        val pack = makePack(File(tmp.newFolder("p"), "cool.mcpack"), "Cool Pack", "uuid-res-1")
        val report = BedrockPacks.import(instance, pack)
        assertEquals(1, report.imported.size)
        assertEquals("Cool Pack", report.imported.first().name)
        assertEquals(BedrockPack.Kind.RESOURCE, report.imported.first().kind)

        val installed = BedrockPacks.packs(instance)
        assertEquals(1, installed.size)
        assertEquals("1.2.0", installed.first().versionText)
        // 图标来自包里的 pack_icon.png
        assertNotNull(installed.first().iconFile)
        assertTrue(installed.first().iconFile!!.isFile)
    }

    @Test
    fun distinguishesBehaviorPackByModuleType() {
        val pack = makePack(
            File(tmp.newFolder("b"), "behavior.mcpack"),
            "Behavior Pack",
            "uuid-beh-1",
            moduleType = "data",
        )
        val report = BedrockPacks.import(instance, pack)
        assertEquals(BedrockPack.Kind.BEHAVIOR, report.imported.first().kind)
        // 解压到 behavior_packs/ 而不是 resource_packs/
        assertEquals("behavior_packs", report.imported.first().dir.parentFile!!.name)
    }

    @Test
    fun importsMcaddonWithMultiplePacks() {
        val addon = TestNbt.zip(
            File(tmp.newFolder("a"), "bundle.mcaddon"),
            mapOf(
                "res/manifest.json" to manifest("Res A", "uuid-a").toByteArray(),
                "res/pack_icon.png" to ByteArray(16),
                "beh/manifest.json" to manifest("Beh B", "uuid-b", "data").toByteArray(),
                "beh/pack_icon.png" to ByteArray(16),
            ),
        )
        val report = BedrockPacks.import(instance, addon)
        assertEquals(2, report.imported.size)
        val kinds = report.imported.map { it.kind }.toSet()
        assertEquals(setOf(BedrockPack.Kind.RESOURCE, BedrockPack.Kind.BEHAVIOR), kinds)
        assertEquals(2, BedrockPacks.packs(instance).size)
    }

    @Test
    fun rejectsNonPackZip() {
        val zip = TestNbt.zip(
            File(tmp.newFolder("x"), "notapack.mcpack"),
            mapOf("readme.txt" to "hello".toByteArray()),
        )
        val report = BedrockPacks.import(instance, zip)
        assertTrue(report.imported.isEmpty())
        assertTrue(report.failed.first().reason.contains("manifest.json"))
    }

    @Test
    fun appliesActivePacksToWorldJson() {
        BedrockPacks.import(instance, makePack(File(tmp.newFolder("p2"), "a.mcpack"), "Pack A", "uuid-aaa"))
        val pack = BedrockPacks.packs(instance).first()
        assertFalse(pack.active)

        val ok = BedrockPacks.applyActive(instance, listOf(pack.copy(active = true)))
        assertTrue(ok)
        val json = File(instance.dir, "world/world_resource_packs.json")
        assertTrue(json.isFile)
        assertTrue(json.readText().contains("uuid-aaa"))
        assertTrue(BedrockPacks.packs(instance).first().active)
    }

    @Test
    fun deletesPackDirectory() {
        BedrockPacks.import(instance, makePack(File(tmp.newFolder("p3"), "d.mcpack"), "Pack D", "uuid-d"))
        val pack = BedrockPacks.packs(instance).first()
        assertTrue(BedrockPacks.delete(pack))
        assertFalse(pack.dir.exists())
        assertTrue(BedrockPacks.packs(instance).isEmpty())
    }
}
