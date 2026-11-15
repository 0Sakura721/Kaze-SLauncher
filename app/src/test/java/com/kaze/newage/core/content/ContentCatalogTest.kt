package com.kaze.newage.core.content

import com.kaze.newage.core.bedrock.BedrockServer
import com.kaze.newage.core.console.QuickCommands
import com.kaze.newage.core.server.ServerProperties
import com.kaze.newage.data.model.CoreType
import com.kaze.newage.testutil.TestJars
import com.kaze.newage.testutil.TestNbt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ContentCatalogTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun instance(core: CoreType = CoreType.FABRIC) = TestJars.instance(tmp.newFolder(), core)

    @Test
    fun loadersFollowCoreType() {
        assertEquals(listOf("fabric", "quilt"), ContentCatalog.loadersFor(instance(CoreType.FABRIC), ContentKind.MOD))
        assertEquals(listOf("forge"), ContentCatalog.loadersFor(instance(CoreType.FORGE), ContentKind.MOD))
        assertEquals(listOf("neoforge"), ContentCatalog.loadersFor(instance(CoreType.NEOFORGE), ContentKind.MOD))
        // 认不出核心：不做加载器过滤，宁可多给结果
        assertTrue(ContentCatalog.loadersFor(instance(CoreType.CUSTOM), ContentKind.MOD).isEmpty())
        // 资源包没有加载器概念
        assertTrue(ContentCatalog.loadersFor(instance(CoreType.FABRIC), ContentKind.RESOURCEPACK).isEmpty())
        // 插件一侧要带上 spigot/bukkit，否则大量只标 bukkit 的插件搜不到
        assertTrue(ContentCatalog.loadersFor(instance(CoreType.PAPER), ContentKind.PLUGIN).contains("bukkit"))
    }

    @Test
    fun bedrockInstanceHasNoJavaLoaders() {
        val inst = instance(CoreType.CUSTOM).copy(bedrock = true)
        assertTrue(ContentCatalog.loadersFor(inst, ContentKind.MOD).isEmpty())
        assertTrue(ContentCatalog.loadersFor(inst, ContentKind.PLUGIN).isEmpty())
    }

    @Test
    fun appliesAndClearsServerResourcePack() {
        val inst = instance()
        val zip = TestNbt.zip(
            File(inst.dir, "resourcepacks/pack.zip"),
            mapOf("pack.mcmeta" to """{"pack":{"pack_format":34}}""".toByteArray()),
        )
        val installed = ContentCatalog.Installed(zip, "1.0", "https://cdn.example.com/pack.zip")
        assertTrue(ContentCatalog.applyServerResourcePack(inst, installed))

        val props = ServerProperties.load(inst.dir)
        assertEquals("https://cdn.example.com/pack.zip", props["resource-pack"])
        // sha1 必须是 40 位十六进制，否则客户端校验会失败并回退原版
        assertEquals(40, props["resource-pack-sha1"]!!.length)
        assertEquals("false", props["require-resource-pack"])

        ContentCatalog.clearServerResourcePack(inst)
        val cleared = ServerProperties.load(inst.dir)
        assertTrue(cleared["resource-pack"].isNullOrEmpty())
        assertTrue(cleared["resource-pack-sha1"].isNullOrEmpty())
    }

    @Test
    fun installWithoutUrlCannotBeApplied() {
        val inst = instance()
        val file = File(inst.dir, "resourcepacks/x.zip").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(8))
        }
        assertFalse(
            ContentCatalog.applyServerResourcePack(inst, ContentCatalog.Installed(file, "", "")),
        )
    }

    @Test
    fun installedPacksListsOnlyZips() {
        val inst = instance()
        val dir = File(inst.dir, "resourcepacks").apply { mkdirs() }
        File(dir, "a.zip").writeBytes(ByteArray(8))
        File(dir, "b.txt").writeText("x")
        val packs = ContentCatalog.installedPacks(inst)
        assertEquals(1, packs.size)
        assertEquals("a.zip", packs.first().name)
    }

    // ── 推荐表完整性 ──

    @Test
    fun recommendedTablesAreSane() {
        ContentKind.entries.forEach { kind ->
            val entries = RecommendedProjects.entries(kind)
            assertTrue("${kind.label} 推荐表为空", entries.isNotEmpty())
            assertEquals(
                "${kind.label} 推荐表有重复 slug",
                entries.size,
                entries.map { it.slug }.distinct().size,
            )
            entries.forEach {
                assertTrue("slug 不能含空格：${it.slug}", it.slug.isNotBlank() && !it.slug.contains(' '))
                assertTrue("推荐理由不能为空：${it.slug}", it.reason.isNotBlank())
            }
        }
    }

    @Test
    fun reasonLookupBySlug() {
        val slug = RecommendedProjects.MODS.first().slug
        assertTrue(RecommendedProjects.reasonOf(ContentKind.MOD, slug).isNotBlank())
        assertEquals("", RecommendedProjects.reasonOf(ContentKind.MOD, "not-a-real-slug"))
    }

    // ── 常用指令表 ──

    @Test
    fun quickCommandsAreWellFormed() {
        assertTrue(QuickCommands.GROUPS.isNotEmpty())
        assertTrue(QuickCommands.totalCount >= 25)
        QuickCommands.GROUPS.forEach { group ->
            assertTrue(group.title.isNotBlank())
            assertTrue(group.items.isNotEmpty())
            assertEquals(
                "分组「${group.title}」里有重复命令",
                group.items.size,
                group.items.map { it.command }.distinct().size,
            )
            group.items.forEach {
                assertTrue(it.label.isNotBlank())
                assertTrue(it.command.isNotBlank())
            }
        }
    }

    @Test
    fun sayCommandOnlyFillsInput() {
        val say = QuickCommands.GROUPS.flatMap { it.items }.first { it.command.startsWith("say") }
        assertTrue("say 需要用户补文本，不能直接发送", say.fillOnly)
    }

    // ── 基岩版版本说明解析 ──

    @Test
    fun parsesMinecraftVersionFromReleaseNotes() {
        val table = "| **Minecraft Version** | 1.26.60 |\n| **Protocol Version** | 2225 |"
        assertEquals("1.26.60", BedrockServer.parseMinecraftVersion(table))
        assertEquals("1.21.90", BedrockServer.parseMinecraftVersion("feat: Add 1.21.90 support"))
        assertEquals("", BedrockServer.parseMinecraftVersion("no version here"))
    }
}
