package com.kaze.newage.core.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 整合包清单解析测试（三种格式：Modrinth `.mrpack` / CurseForge `.zip` / 通用懒人包）。
 *
 * 这里的输入全部是**远端不可信数据**，所以测试重点是：
 *  - 字段缺失/结构不对时要**认出来并回退**，而不是抛异常或当成有效清单；
 *  - `path` 带 `../` 时不能写到实例目录之外；
 *  - 纯客户端模组（`env.server = unsupported`）必须被判出来并跳过 ——
 *    服务端装了这类 mod 有些会直接崩（引用客户端才有的类）。
 */
class ModpackManifestTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun dir(name: String = "pack"): File = tmp.newFolder(name)

    private fun put(root: File, name: String, body: String) {
        File(root, name).writeText(body)
    }

    private fun mrpackJson(
        mc: String = "1.20.1",
        loaderKey: String = "forge",
        loaderVer: String = "47.2.0",
        files: String = """
            {
              "path": "mods/jei.jar",
              "hashes": { "sha1": "aa11", "sha512": "bb22" },
              "env": { "client": "required", "server": "required" },
              "downloads": ["https://cdn.modrinth.com/data/x/jei.jar"],
              "fileSize": 1234
            }
        """.trimIndent(),
    ) = """
        {
          "formatVersion": 1,
          "game": "minecraft",
          "versionId": "1.0.0",
          "name": "示例整合包",
          "files": [ $files ],
          "dependencies": { "minecraft": "$mc", "$loaderKey": "$loaderVer" }
        }
    """.trimIndent()

    private fun curseJson(
        mc: String = "1.20.1",
        loaderId: String = "forge-47.2.0",
        files: String = """{"projectID": 238222, "fileID": 4593545, "required": true}""",
        overrides: String = "overrides",
    ) = """
        {
          "minecraft": { "version": "$mc", "modLoaders": [ { "id": "$loaderId", "primary": true } ] },
          "manifestType": "minecraftModpack",
          "name": "示例 CurseForge 包",
          "version": "1.0",
          "files": [ $files ],
          "overrides": "$overrides"
        }
    """.trimIndent()

    // ══════════ 格式识别 ══════════

    @Test
    fun `有 modrinth_index_json 就是 Modrinth 格式`() {
        val d = dir()
        put(d, ModpackManifest.MODRINTH_INDEX, mrpackJson())
        val f = ModpackManifest.detect(d)
        assertTrue(f is ModpackManifest.Format.Mrpack)
        assertEquals("1.20.1", (f as ModpackManifest.Format.Mrpack).manifest.mcVersion)
    }

    @Test
    fun `有 manifest_json 就是 CurseForge 格式`() {
        val d = dir()
        put(d, ModpackManifest.CURSEFORGE_MANIFEST, curseJson())
        assertTrue(ModpackManifest.detect(d) is ModpackManifest.Format.Curse)
    }

    @Test
    fun `两个清单都在时以 Modrinth 为准`() {
        // 有些导出工具会顺手塞一份 CurseForge 兼容清单，但真正决定怎么装的是 modrinth 那份
        val d = dir()
        put(d, ModpackManifest.MODRINTH_INDEX, mrpackJson())
        put(d, ModpackManifest.CURSEFORGE_MANIFEST, curseJson())
        assertTrue(ModpackManifest.detect(d) is ModpackManifest.Format.Mrpack)
    }

    @Test
    fun `什么清单都没有就是通用懒人包`() {
        val d = dir()
        File(d, "mods").mkdirs()
        assertTrue(ModpackManifest.detect(d) is ModpackManifest.Format.Plain)
    }

    @Test
    fun `清单是坏 JSON 时回退到懒人包而不抛异常`() {
        val d = dir()
        put(d, ModpackManifest.MODRINTH_INDEX, "{ 这不是 JSON")
        assertTrue(ModpackManifest.detect(d) is ModpackManifest.Format.Plain)
    }

    @Test
    fun `恰好叫 manifest_json 但不是整合包清单时回退`() {
        // 别的工具可能也在包里放一个 manifest.json（比如某些启动器的元数据）
        val d = dir()
        put(d, ModpackManifest.CURSEFORGE_MANIFEST, """{"somethingElse": 1}""")
        assertTrue(ModpackManifest.detect(d) is ModpackManifest.Format.Plain)
    }

    // ══════════ Modrinth 解析 ══════════

    @Test
    fun `解析 Modrinth 的版本 加载器 文件与哈希`() {
        val m = ModpackManifest.parseModrinth(mrpackJson())!!
        assertEquals("1.20.1", m.mcVersion)
        assertEquals(ModpackManifest.Loader.FORGE, m.loader)
        assertEquals("47.2.0", m.loaderVersion)
        assertEquals("示例整合包", m.name)
        assertEquals(1, m.files.size)
        val f = m.files.single()
        assertEquals("mods/jei.jar", f.path)
        assertEquals("bb22", f.sha512)
        assertEquals("aa11", f.sha1)
        assertEquals(1234L, f.size)
        assertEquals(listOf("https://cdn.modrinth.com/data/x/jei.jar"), f.urls)
        assertEquals(ModpackManifest.Side.SERVER_OK, f.side)
    }

    @Test
    fun `纯客户端模组被判为 CLIENT_ONLY 并从服务端清单里排除`() {
        val d = dir()
        put(
            d, ModpackManifest.MODRINTH_INDEX,
            mrpackJson(
                files = """
                    {
                      "path": "mods/sodium.jar",
                      "hashes": { "sha1": "cc" },
                      "env": { "client": "required", "server": "unsupported" },
                      "downloads": ["https://cdn.modrinth.com/data/y/sodium.jar"],
                      "fileSize": 10
                    }
                """.trimIndent(),
            ),
        )
        val m = (ModpackManifest.detect(d) as ModpackManifest.Format.Mrpack).manifest
        assertEquals(1, m.files.size)
        assertEquals(ModpackManifest.Side.CLIENT_ONLY, m.files.single().side)
        assertTrue("服务端清单里不该有这个 mod", m.serverFiles.isEmpty())
        assertEquals(1, m.skippedClientOnly)
    }

    @Test
    fun `没有 env 段的文件按服务端可用处理`() {
        val d = dir()
        put(
            d, ModpackManifest.MODRINTH_INDEX,
            mrpackJson(files = """{"path": "config/x.toml", "downloads": ["https://cdn/x"], "fileSize": 1}"""),
        )
        val m = (ModpackManifest.detect(d) as ModpackManifest.Format.Mrpack).manifest
        assertEquals(ModpackManifest.Side.SERVER_OK, m.files.single().side)
    }

    @Test
    fun `缺 path 的文件被跳过而不是崩`() {
        val d = dir()
        put(
            d, ModpackManifest.MODRINTH_INDEX,
            mrpackJson(files = """{"downloads": ["https://cdn/x"], "fileSize": 1}"""),
        )
        val m = (ModpackManifest.detect(d) as ModpackManifest.Format.Mrpack).manifest
        assertTrue(m.files.isEmpty())
    }

    @Test
    fun `非 http 的下载地址被丢弃`() {
        val d = dir()
        put(
            d, ModpackManifest.MODRINTH_INDEX,
            mrpackJson(files = """{"path": "mods/a.jar", "downloads": ["file:///etc/passwd", "https://ok/a.jar"]}"""),
        )
        val m = (ModpackManifest.detect(d) as ModpackManifest.Format.Mrpack).manifest
        assertEquals(listOf("https://ok/a.jar"), m.files.single().urls)
    }

    @Test
    fun `既没有 files 也没有 versionId 的 JSON 不算有效清单`() {
        assertNull(ModpackManifest.parseModrinth("""{"foo": 1}"""))
    }

    @Test
    fun `只有 versionId 没有 files 也算清单（服务端包可能全在 overrides 里）`() {
        val m = ModpackManifest.parseModrinth("""{"versionId": "1.0", "dependencies": {"minecraft": "1.20.1"}}""")
        assertEquals("1.20.1", m!!.mcVersion)
        assertTrue(m.files.isEmpty())
    }

    @Test
    fun `Modrinth 的 overrides 目录含服务端覆盖目录`() {
        val m = ModpackManifest.parseModrinth(mrpackJson())!!
        assertTrue(m.overrideDirs.contains("overrides"))
        assertTrue("Modrinth 服务端包会额外带 server-overrides", m.overrideDirs.contains("server-overrides"))
    }

    // ══════════ 加载器识别 ══════════

    @Test
    fun `依赖段里的四种加载器都认得`() {
        fun loaderOf(key: String, ver: String): Pair<ModpackManifest.Loader, String> {
            val json = """{"versionId":"1","dependencies":{"minecraft":"1.20.1","$key":"$ver"}}"""
            val m = ModpackManifest.parseModrinth(json)!!
            return m.loader to m.loaderVersion
        }
        assertEquals(ModpackManifest.Loader.FORGE to "47.2.0", loaderOf("forge", "47.2.0"))
        assertEquals(ModpackManifest.Loader.NEOFORGE to "21.1.9", loaderOf("neoforge", "21.1.9"))
        assertEquals(ModpackManifest.Loader.FABRIC to "0.15.11", loaderOf("fabric-loader", "0.15.11"))
        assertEquals(ModpackManifest.Loader.QUILT to "0.23.1", loaderOf("quilt-loader", "0.23.1"))
    }

    @Test
    fun `没有加载器依赖时算原版`() {
        val m = ModpackManifest.parseModrinth("""{"versionId":"1","dependencies":{"minecraft":"1.20.1"}}""")!!
        assertEquals(ModpackManifest.Loader.VANILLA, m.loader)
        assertEquals("", m.loaderVersion)
    }

    @Test
    fun `同时给了多个加载器时按 forge neoforge fabric 的顺序取`() {
        // 理论上不该同时出现，但清单是远端数据 —— 给一个确定的优先级比随机取好
        val m = ModpackManifest.parseModrinth(
            """{"versionId":"1","dependencies":{"minecraft":"1.20.1","fabric-loader":"0.15.11","forge":"47.2.0"}}""",
        )!!
        assertEquals(ModpackManifest.Loader.FORGE, m.loader)
    }

    // ══════════ CurseForge 解析 ══════════

    @Test
    fun `解析 CurseForge 的版本 加载器 引用与 overrides 名`() {
        val c = ModpackManifest.parseCurseForge(curseJson())!!
        assertEquals("1.20.1", c.mcVersion)
        assertEquals(ModpackManifest.Loader.FORGE, c.loader)
        assertEquals("47.2.0", c.loaderVersion)
        assertEquals(1, c.refs.size)
        assertEquals(238222L, c.refs.single().projectId)
        assertEquals(4593545L, c.refs.single().fileId)
        assertEquals("overrides", c.overrideDir)
    }

    @Test
    fun `CurseForge 服务端包的 files 为空也能解析`() {
        // 服务端包的正常形态：mods/config 全实体放在 overrides 里，清单不引用远端文件
        val c = ModpackManifest.parseCurseForge(curseJson(files = ""))!!
        assertTrue(c.refs.isEmpty())
    }

    @Test
    fun `自定义 overrides 目录名被尊重`() {
        val c = ModpackManifest.parseCurseForge(curseJson(overrides = "myoverrides"))!!
        assertEquals("myoverrides", c.overrideDir)
    }

    @Test
    fun `modLoader id 的各种形态都解析对`() {
        assertEquals(ModpackManifest.Loader.FORGE to "47.2.0", ModpackManifest.parseLoaderId("forge-47.2.0"))
        assertEquals(ModpackManifest.Loader.NEOFORGE to "21.1.9", ModpackManifest.parseLoaderId("neoforge-21.1.9"))
        assertEquals(ModpackManifest.Loader.FABRIC to "0.15.11", ModpackManifest.parseLoaderId("fabric-0.15.11"))
        assertEquals(ModpackManifest.Loader.QUILT to "0.23.1", ModpackManifest.parseLoaderId("quilt-0.23.1"))
        // 老包的 id 里带 MC 版本：取最后一段作为加载器版本
        assertEquals(
            ModpackManifest.Loader.FORGE to "14.23.5.2860",
            ModpackManifest.parseLoaderId("forge-1.12.2-14.23.5.2860"),
        )
    }

    @Test
    fun `空或未知的 modLoader id 安全回落`() {
        assertEquals(ModpackManifest.Loader.VANILLA to "", ModpackManifest.parseLoaderId(""))
        assertEquals(ModpackManifest.Loader.UNKNOWN to "9", ModpackManifest.parseLoaderId("weird-9"))
        assertEquals(ModpackManifest.Loader.VANILLA to "", ModpackManifest.parseLoaderId("forge"))
    }

    // ══════════ 远端路径的边界校验 ══════════

    @Test
    fun `清单里的路径不能跳出实例目录`() {
        val root = dir()
        assertNull("必须拦住 ../", ModpackImporter.resolveRemoteDest(root, "../../evil.txt"))
        assertNull("嵌套的 ../ 也要拦", ModpackImporter.resolveRemoteDest(root, "mods/../../evil.txt"))
        assertNull("空路径要拦", ModpackImporter.resolveRemoteDest(root, ""))

        // 关于"看起来像绝对路径"的输入：这里**不拒绝**，而是把前导 `/` 规范化成
        // 实例目录内的相对路径。理由：很多整合包清单把路径写成 `/mods/x.jar`，
        // 直接拒绝会让这些包装不上；而规范化之后它仍然落在实例目录内，
        // 真正的安全属性（**不能写到实例目录之外**）没有被破坏。
        // 所以断言的是"落在了实例里"，而不是"返回 null"。
        for (raw in listOf("/etc/passwd", "C:/Windows/system32/x.dll", "//weird//double.txt")) {
            val dest = ModpackImporter.resolveRemoteDest(root, raw)
            assertTrue(
                "「$raw」规范化后仍必须落在实例目录内",
                dest != null && dest.canonicalPath.startsWith(root.canonicalPath + File.separator),
            )
        }
    }

    @Test
    fun `正常的相对路径能解析成目标文件`() {
        val root = dir()
        val f = ModpackImporter.resolveRemoteDest(root, "mods/jei.jar")
        assertEquals("mods/jei.jar", f!!.relativeTo(root).path.replace('\\', '/'))
        // 前导斜杠会被去掉（有些清单写成 /mods/x.jar）
        val g = ModpackImporter.resolveRemoteDest(root, "/mods/x.jar")
        assertEquals("mods/x.jar", g!!.relativeTo(root).path.replace('\\', '/'))
    }

    // ══════════ 加载器安装地址（纯函数）══════════

    @Test
    fun `Forge 用清单里的确切版本构造 maven 地址`() {
        val (url, name) = ModpackImporter.loaderInstallerUrl(
            ModpackManifest.Loader.FORGE, "1.20.1", "47.2.0",
        )!!
        assertEquals(
            "https://maven.minecraftforge.net/net/minecraftforge/forge/1.20.1-47.2.0/forge-1.20.1-47.2.0-installer.jar",
            url,
        )
        assertEquals("forge-1.20.1-47.2.0-installer.jar", name)
    }

    @Test
    fun `NeoForge 的地址格式`() {
        val (url, name) = ModpackImporter.loaderInstallerUrl(
            ModpackManifest.Loader.NEOFORGE, "1.21.1", "21.1.9",
        )!!
        assertEquals(
            "https://maven.neoforged.net/releases/net/neoforged/neoforge/21.1.9/neoforge-21.1.9-installer.jar",
            url,
        )
        assertEquals("neoforge-21.1.9-installer.jar", name)
    }

    @Test
    fun `Fabric 与版本缺失时不给安装器地址`() {
        // Fabric 没有 installer（走现成的服务端启动器 jar）；Quilt 本项目不支持
        assertNull(ModpackImporter.loaderInstallerUrl(ModpackManifest.Loader.FABRIC, "1.20.1", "0.15.11"))
        assertNull(ModpackImporter.loaderInstallerUrl(ModpackManifest.Loader.QUILT, "1.20.1", "0.23.1"))
        assertNull("版本为空时不能构造地址", ModpackImporter.loaderInstallerUrl(ModpackManifest.Loader.FORGE, "1.20.1", ""))
        assertNull("MC 版本为空时 Forge 构造不出", ModpackImporter.loaderInstallerUrl(ModpackManifest.Loader.FORGE, "", "47.2.0"))
    }

    // ══════════ 清单与目录识别的配合 ══════════

    @Test
    fun `只有 mods 但清单声明了加载器时不再判为缺核心`() {
        val d = dir()
        File(d, "mods").mkdirs()
        File(d, "mods/a.jar").writeText("m")
        put(d, ModpackManifest.MODRINTH_INDEX, mrpackJson(files = """{"path": "mods/a.jar", "downloads": []}"""))

        val plan = ModpackImporter.inspect(d)

        assertEquals("清单说了加载器，导入流程会自动装", "FORGE", plan.coreTypeName)
        assertEquals("1.20.1", plan.mcVersion)
        assertEquals(ModpackManifest.Loader.FORGE, plan.loader)
        assertTrue(
            "不该再提示'没有服务端核心'（会自动装）",
            plan.warnings.none { it.contains("没有服务端核心") },
        )
    }

    @Test
    fun `清单里的 MC 版本优先于目录推断`() {
        val d = dir()
        File(d, "mods").mkdirs()
        // 目录里没有任何 jar，正常只能认不出 → 但清单明确写了 1.20.5（需要 Java 21）
        put(
            d, ModpackManifest.MODRINTH_INDEX,
            mrpackJson(
                mc = "1.20.5", loaderKey = "neoforge", loaderVer = "21.0.0",
                files = """{"path": "mods/a.jar", "downloads": []}""",
            ),
        )
        val plan = ModpackImporter.inspect(d)
        assertEquals("1.20.5", plan.mcVersion)
        assertEquals("1.20.5 需要 Java 21", 21, plan.javaMajor)
        assertEquals("NEOFORGE", plan.coreTypeName)
    }

    @Test
    fun `通用懒人包不会带出清单信息`() {
        val d = dir()
        File(d, "mods").mkdirs()
        File(d, "mods/a.jar").writeText("m")
        val plan = ModpackImporter.inspect(d)
        assertTrue(plan.format is ModpackManifest.Format.Plain)
        assertEquals(ModpackManifest.Loader.UNKNOWN, plan.loader)
    }

// ══════════ 已有文件能否复用（防"长度相同的损坏文件被永久复用"）══════════

    @Test
    fun `有哈希时必须以哈希为准，长度相同也不能复用`() {
        val root = newDir()
        val f = File(root, "mods/x.jar")
        f.parentFile!!.mkdirs()
        f.writeBytes(ByteArray(100) { 1 })   // 长度与清单一致，但内容损坏

        val remote = ModpackManifest.RemoteFile(
            path = "mods/x.jar",
            urls = listOf("https://cdn/x.jar"),
            sha512 = "expected-sha512",
            sha1 = null,
            size = 100L,                     // 大小**恰好**一致
            side = ModpackManifest.Side.SERVER_OK,
        )

        // 哈希不匹配 → 必须重新下载（这正是"只看大小"会漏掉的情形）
        assertFalse(
            "长度一致但哈希不符的文件绝不能复用",
            ModpackImporter.canReuseExisting(f, remote) { false },
        )
        // 哈希匹配 → 可以复用（不重复下载）
        assertTrue(
            "哈希正确时应当复用，避免每次导入都重下几百 MB",
            ModpackImporter.canReuseExisting(f, remote) { true },
        )
    }

    @Test
    fun `清单没给哈希时才退回比大小`() {
        val root = newDir()
        val f = File(root, "mods/y.jar")
        f.parentFile!!.mkdirs()
        f.writeBytes(ByteArray(50))

        fun remote(size: Long) = ModpackManifest.RemoteFile(
            path = "mods/y.jar", urls = listOf("https://cdn/y.jar"),
            sha512 = null, sha1 = null, size = size, side = ModpackManifest.Side.SERVER_OK,
        )

        // 没有哈希 → 哈希校验器永远返回 false，但不该因此每次都重下
        assertTrue("无哈希 + 大小一致 → 复用", ModpackImporter.canReuseExisting(f, remote(50)) { false })
        assertFalse("无哈希 + 大小不一致 → 重下", ModpackImporter.canReuseExisting(f, remote(9999)) { false })
        assertTrue("清单 size=0（没给）→ 认任何非空文件", ModpackImporter.canReuseExisting(f, remote(0)) { false })
    }

    @Test
    fun `文件不存在或为空时都要重新下载`() {
        val root = newDir()
        val remote = ModpackManifest.RemoteFile(
            path = "mods/z.jar", urls = listOf("https://cdn/z.jar"),
            sha512 = "sha", sha1 = null, size = 10, side = ModpackManifest.Side.SERVER_OK,
        )
        assertFalse("文件不存在 → 重下", ModpackImporter.canReuseExisting(null, remote) { true })
        assertFalse(
            "路径指向目录 → 不能当文件复用",
            ModpackImporter.canReuseExisting(root, remote) { true },
        )
    }
}