package com.kaze.newage.core.net

import com.kaze.newage.core.addons.AddonKind
import com.kaze.newage.core.addons.ModAudit
import com.kaze.newage.core.content.ContentSources
import com.kaze.newage.testutil.TestJars
import com.kaze.newage.testutil.TestNbt
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NetworkCoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Before
    fun resetSources() {
        ContentSources.configure("auto")
    }

    // ── 内容源 ──

    @Test
    fun autoSourceTriesOfficialThenMirror() {
        ContentSources.configure("auto")
        val order = ContentSources.active()
        assertEquals(ContentSources.OFFICIAL_URL, order.first().baseUrl)
        assertTrue(order.any { it.baseUrl == ContentSources.MCIM_URL })
    }

    @Test
    fun canLockSingleSource() {
        ContentSources.configure("official")
        assertEquals(1, ContentSources.active().size)
        ContentSources.configure("mcim")
        assertEquals(ContentSources.MCIM_URL, ContentSources.active().first().baseUrl)
    }

    @Test
    fun customSourceFallsBackToBuiltIns() {
        ContentSources.configure("custom", "https://mirror.example.com/modrinth/v2")
        assertEquals("https://mirror.example.com/modrinth/v2", ContentSources.active().first().baseUrl)
        assertTrue(ContentSources.active().any { it.isOfficial })

        // 非法自定义地址（不是 https 或不像 v2）→ 只用内置源，避免把应用卡死在一个坏地址上
        ContentSources.configure("custom", "http://insecure.example/v2")
        assertTrue(ContentSources.active().all { it.baseUrl != "http://insecure.example/v2" })
    }

    @Test
    fun extractsApiPathFromFullUrl() {
        assertEquals(
            "/search?limit=1",
            ContentSources.pathOf("https://api.modrinth.com/v2/search?limit=1"),
        )
    }

    /** 回归：baseUrl 与路径拼接不能丢/多斜杠（丢斜杠会让 Modrinth 全部 404） */
    @Test
    fun joinsApiUrlWithoutDoubleOrMissingSlash() {
        assertEquals(
            "https://api.modrinth.com/v2/project/sodium",
            ContentSources.join(ContentSources.OFFICIAL_URL, "project/sodium"),
        )
        assertEquals(
            "https://api.modrinth.com/v2/search?query=x",
            ContentSources.join(ContentSources.OFFICIAL_URL, "/search?query=x"),
        )
        assertEquals(
            "https://mod.mcimirror.top/modrinth/v2/project/sodium/version",
            ContentSources.join(ContentSources.MCIM_URL, "project/sodium/version"),
        )
    }
    /** 回归：Modrinth 的 CDN 文件/图标要能映射到 MCIM 镜像（国内直连 cdn 会失败） */
    @Test
    fun mapsCdnFilesToMcimMirror() {
        val icon = "https://cdn.modrinth.com/data/AANobbMI/295862f4724dc3f78df3447ad6072b2dcd3ef0c9_96.webp"
        val candidates = ContentSources.fileCandidates(icon)
        assertEquals(2, candidates.size)
        assertEquals(icon, candidates.first())
        assertTrue(candidates.last().startsWith("https://mod.mcimirror.top/data/"))
        assertTrue(ContentSources.iconUrl(icon).startsWith("https://mod.mcimirror.top/"))

        // 非 CDN 地址不做改写，避免把别人的 URL 弄坏
        val other = "https://example.com/a.jar"
        assertEquals(listOf(other), ContentSources.fileCandidates(other))
    }
    // ── 自建 frp 配置 ──

    @Test
    fun buildsValidFrpcToml() {
        val cfg = NetworkAccess.FrpConfig(
            serverAddr = "frp.example.com",
            serverPort = 7000,
            token = "secret",
            localPort = 25565,
            remotePort = 25570,
        )
        val toml = cfg.toToml()
        assertTrue(toml.contains("serverAddr = \"frp.example.com\""))
        assertTrue(toml.contains("serverPort = 7000"))
        assertTrue(toml.contains("auth.token = \"secret\""))
        assertTrue(toml.contains("localPort = 25565"))
        assertTrue(toml.contains("remotePort = 25570"))
        assertTrue(toml.contains("[[proxies]]"))
    }

    // ── 樱花隧道反序列化 ──

    @Test
    fun parsesSakuraTunnelJson() {
        val json = Json { ignoreUnknownKeys = true }
        val body = """
            [{"id":114514,"name":"我的服","node":233,"type":"tcp","online":true,
              "status":1,"status_reason":"","note":"","extra":"","remote":"cn-1.natfrp.cloud:23456",
              "local_ip":"127.0.0.1","local_port":25565,"export":""}]
        """.trimIndent()
        val tunnels = json.decodeFromString<List<SakuraTunnel>>(body)
        assertEquals(1, tunnels.size)
        val tunnel = tunnels.first()
        assertEquals(114514L, tunnel.id)
        assertEquals("tcp", tunnel.type)
        assertEquals(25565, tunnel.local_port)
        assertTrue(tunnel.summary.contains("TCP"))
        assertTrue(tunnel.summary.contains("23456"))
    }

    /** 回归：樱花 /tunnels 的真实响应里 status_reason/note 等字段是 null */
    @Test
    fun parsesRealSakuraTunnelsWithNullFields() {
        val body = """
            [{"id":114514,"name":"我的服","node":233,"type":"tcp","online":true,
              "status":0,"status_reason":null,"note":null,"extra":null,
              "remote":null,"local_ip":null,"local_port":25565,"export":null}]
        """.trimIndent()
        val tunnels = SakuraFrpApi.parseTunnels(body)
        assertEquals(1, tunnels.size)
        assertEquals(114514L, tunnels.first().id)
        assertEquals("", tunnels.first().status_reason)
        assertEquals(25565, tunnels.first().local_port)
    }

    /** 回归：字段类型漂移（online 给成字符串）时走宽松解析，而不是整页报错 */
    @Test
    fun parsesTunnelsWithDriftedTypes() {
        val body = """[{"id":"7","name":"x","node":"2","type":"udp","online":"true","status":"1","local_port":"19132"}]"""
        val tunnels = SakuraFrpApi.parseTunnels(body)
        assertEquals(1, tunnels.size)
        assertEquals(7L, tunnels.first().id)
        assertTrue(tunnels.first().online)
        assertEquals(19132, tunnels.first().local_port)
    }
    // ── 客户端专用模组检测 ──

    @Test
    fun detectsClientOnlyMods() = runBlocking {
        val instance = TestJars.instance(tmp.newFolder("srv"), mc = "1.21.1")
        val mods = File(instance.dir, "mods").apply { mkdirs() }

        // 作者声明 environment=client
        TestJars.zip(
            File(mods, "client-declared.jar"),
            mapOf(
                "fabric.mod.json" to """
                    {"schemaVersion":1,"id":"coolhud","name":"Cool HUD","version":"1.0",
                     "environment":"client"}
                """.trimIndent().toByteArray(),
            ),
        )
        // 已知客户端专用（Sodium）
        TestJars.fabricMod(File(mods, "sodium.jar"), id = "sodium", name = "Sodium")
        // 双端都需要（Lithium）
        TestJars.fabricMod(File(mods, "lithium.jar"), id = "lithium", name = "Lithium")
        com.kaze.newage.core.addons.ModMetadataReader.invalidate()

        val items = ModAudit.audit(instance, useNetwork = false)
        val byName = items.associateBy { it.file.name }
        assertEquals(ModAudit.Verdict.CLIENT_ONLY, byName["client-declared.jar"]!!.verdict)
        assertEquals(ModAudit.Verdict.CLIENT_ONLY, byName["sodium.jar"]!!.verdict)
        assertEquals(ModAudit.Verdict.UNKNOWN, byName["lithium.jar"]!!.verdict)
        // 默认排序把客户端专用的排最前
        assertTrue(items.first().clientOnly)
    }

    @Test
    fun disablesSelectedWithoutDeleting() = runBlocking {
        val instance = TestJars.instance(tmp.newFolder("srv2"), mc = "1.21.1")
        val mods = File(instance.dir, "mods").apply { mkdirs() }
        TestJars.fabricMod(File(mods, "sodium.jar"), id = "sodium", name = "Sodium")
        com.kaze.newage.core.addons.ModMetadataReader.invalidate()

        val items = ModAudit.audit(instance, useNetwork = false).filter { it.clientOnly }
        assertEquals(1, ModAudit.disable(items))
        assertTrue(File(mods, "sodium.jar.disabled").isFile)
        assertTrue(!File(mods, "sodium.jar").exists())
    }

    @Test
    fun modAuditIgnoresDisabledByDefault() = runBlocking {
        val instance = TestJars.instance(tmp.newFolder("srv3"), mc = "1.21.1")
        val mods = File(instance.dir, "mods").apply { mkdirs() }
        TestJars.fabricMod(File(mods, "sodium.jar"), id = "sodium", name = "Sodium")
            .renameTo(File(mods, "sodium.jar.disabled"))
        com.kaze.newage.core.addons.ModMetadataReader.invalidate()
        assertTrue(ModAudit.audit(instance, includeDisabled = false, useNetwork = false).isEmpty())
        assertEquals(1, ModAudit.audit(instance, includeDisabled = true, useNetwork = false).size)
    }

    /** 让测试不依赖真实存档也能建实例目录 */
    private fun dummyWorld(dir: File) {
        TestNbt.zip(File(dir, "world.zip"), mapOf("world/level.dat" to TestNbt.levelDat("w")))
    }
}
