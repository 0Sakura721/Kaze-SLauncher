package com.kaze.newage.core.sync

import com.kaze.newage.testutil.TestJars
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

class ClientPackTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var instance: com.kaze.newage.data.model.ServerInstance
    private lateinit var modsDir: File

    @Before
    fun setUp() {
        instance = TestJars.instance(tmp.newFolder(), mc = "1.21.1")
        modsDir = File(instance.dir, "mods").apply { mkdirs() }
        TestJars.fabricMod(File(modsDir, "lithium.jar"), id = "lithium", name = "Lithium")
        // 停用的模组不能进客户端包：服务端根本不加载它
        TestJars.fabricMod(File(modsDir, "sodium.jar.disabled"), id = "sodium", name = "Sodium")
        ClientPack.invalidate(instance.id)
    }

    private fun entriesOf(bytes: ByteArray): List<String> {
        val names = mutableListOf<String>()
        ZipInputStream(bytes.inputStream()).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                names += e.name
                zis.closeEntry()
            }
        }
        return names
    }

    @Test
    fun manifestOnlyListsEnabledMods() {
        val manifest = ClientPack.manifest(instance)
        assertEquals(1, manifest.mods.size)
        assertEquals("lithium.jar", manifest.mods.first().fileName)
        assertEquals(40, manifest.mods.first().sha1.length)
        assertEquals("1.21.1", manifest.mcVersion)
        assertEquals("fabric", manifest.loader)
        assertTrue(manifest.fingerprint.isNotBlank())
        assertTrue(manifest.totalBytes > 0)
    }

    @Test
    fun tsvHasExpectedShape() {
        val tsv = ClientPack.modsTsv(instance)
        val line = tsv.lineSequence().first { it.isNotBlank() }
        val cols = line.split('\t')
        assertEquals(3, cols.size)
        assertEquals("lithium.jar", cols[2])
        assertEquals(40, cols[0].length)
    }

    @Test
    fun fingerprintChangesWhenModsChange() {
        val before = ClientPack.manifest(instance).fingerprint
        TestJars.fabricMod(File(modsDir, "sodium.jar"), id = "sodium", name = "Sodium")
        ClientPack.invalidate(instance.id)
        val after = ClientPack.manifest(instance).fingerprint
        assertNotEquals(before, after)
    }

    @Test
    fun writeZipContainsPackAndScripts() {
        val out = ByteArrayOutputStream()
        ClientPack.writeZip(instance, out)
        val names = entriesOf(out.toByteArray())
        assertTrue(names.contains("manifest.json"))
        assertTrue(names.contains("mods.txt"))
        assertTrue(names.contains("README.txt"))
        assertTrue(names.contains("mods/lithium.jar"))
        assertTrue(names.contains("client-sync/kaze-sync.sh"))
        assertTrue(names.contains("client-sync/kaze-sync.ps1"))
        assertFalse(names.any { it.contains("sodium") })
    }

    @Test
    fun scriptsAreWellFormed() {
        val sh = ClientPack.syncScriptSh()
        assertTrue(sh.startsWith("#!/bin/sh"))
        assertFalse(sh.contains("§"))
        assertTrue(sh.contains("\$BASE/mods.txt"))
        assertTrue(sh.contains("sha1sum"))

        val ps1 = ClientPack.syncScriptPs1()
        assertFalse(ps1.contains("§"))
        assertTrue(ps1.contains("Get-FileHash"))
        assertTrue(ps1.contains("param("))
    }

    @Test
    fun writeMrpackHasIndexAndOverrides() {
        val out = ByteArrayOutputStream()
        ClientPack.writeMrpack(instance, out)
        val bytes = out.toByteArray()
        val names = entriesOf(bytes)
        assertTrue(names.contains("modrinth.index.json"))
        assertTrue(names.contains("overrides/mods/lithium.jar"))
        assertTrue(names.contains("overrides/manifest.json"))

        // index 必须是**合法 JSON**：启动器解析失败会直接拒绝导入
        val index = readEntry(bytes, "modrinth.index.json")
        val root = Json.parseToJsonElement(index).jsonObject
        assertEquals("minecraft", root["game"]?.jsonPrimitive?.content)
        val deps = root["dependencies"]!!.jsonObject
        assertEquals("1.21.1", deps["minecraft"]?.jsonPrimitive?.content)
        assertTrue(deps.keys.contains("fabric-loader"))
    }

    @Test
    fun readmeMentionsSyncInstructions() {
        val readme = ClientPack.readme(instance)
        assertTrue(readme.contains("client-pack.zip"))
        assertTrue(readme.contains("kaze-sync.sh"))
    }

    /** 从内存 zip 里读出一个条目的文本 */
    private fun readEntry(bytes: ByteArray, name: String): String {
        ZipInputStream(bytes.inputStream()).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                if (e.name == name) return zis.readBytes().toString(Charsets.UTF_8)
                zis.closeEntry()
            }
        }
        throw IllegalStateException("zip 里没有 $name")
    }
}
