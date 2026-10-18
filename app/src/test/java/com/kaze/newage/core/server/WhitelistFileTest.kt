package com.kaze.newage.core.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 白名单读写测试。
 *
 * 重点验 **UUID 算法**：离线服 UUID 写错不会报错，只会让玩家静默进不来 ——
 * 这种 bug 用户根本定位不到，所以必须有测试钉住。
 */
class WhitelistFileTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun dir(): File = tmp.newFolder()

    @Test
    fun `离线 UUID 与 Minecraft 服务端一致`() {
        // 这两个值是 Minecraft 离线模式对同名玩家算出来的 UUID，用来钉死算法
        // （UUID.nameUUIDFromBytes("OfflinePlayer:<name>")，即 MD5 + 版本/变体位）
        assertEquals(
            "5627dd98-e6be-3c21-b8a8-e92344183641",
            WhitelistFile.offlineUuid("Steve"),
        )
        // 同一名字必须稳定
        assertEquals(WhitelistFile.offlineUuid("Steve"), WhitelistFile.offlineUuid("Steve"))
        // 不同名字必须不同
        assertFalse(WhitelistFile.offlineUuid("Steve") == WhitelistFile.offlineUuid("Alex"))
    }

    @Test
    fun `空目录读取为空名单且不抛异常`() {
        assertEquals(emptyList<WhitelistFile.Entry>(), WhitelistFile.read(dir()))
    }

    @Test
    fun `坏 JSON 不抛异常而是当空名单`() {
        val d = dir()
        File(d, "whitelist.json").writeText("""[{"uuid":"半截""")
        assertEquals(emptyList<WhitelistFile.Entry>(), WhitelistFile.read(d))
    }

    @Test
    fun `添加后能读回且写出的是标准格式`() {
        val d = dir()
        val r = WhitelistFile.add(d, "Steve")
        assertTrue(r is WhitelistFile.AddResult.Added)
        val entries = WhitelistFile.read(d)
        assertEquals(1, entries.size)
        assertEquals("Steve", entries[0].name)
        assertEquals(WhitelistFile.offlineUuid("Steve"), entries[0].uuid)

        // 必须是合法 JSON 数组（服务端解析不了就等于白名单坏了）
        val raw = File(d, "whitelist.json").readText().trim()
        assertTrue(raw.startsWith("["))
        assertTrue(raw.endsWith("]"))
        assertTrue(raw.contains("\"uuid\""))
        assertTrue(raw.contains("\"name\""))
    }

    @Test
    fun `重复添加不产生重复条目（名字大小写不敏感）`() {
        val d = dir()
        WhitelistFile.add(d, "Steve")
        val again = WhitelistFile.add(d, "steve")
        assertEquals(WhitelistFile.AddResult.AlreadyThere, again)
        assertEquals(1, WhitelistFile.read(d).size)
    }

    @Test
    fun `删除按名字忽略大小写`() {
        val d = dir()
        WhitelistFile.add(d, "Steve")
        WhitelistFile.add(d, "Alex")
        assertTrue(WhitelistFile.remove(d, "STEVE"))
        assertEquals(listOf("Alex"), WhitelistFile.read(d).map { it.name })
        // 再删一次返回 false
        assertFalse(WhitelistFile.remove(d, "Steve"))
    }

    @Test
    fun `清空名单`() {
        val d = dir()
        WhitelistFile.add(d, "Steve")
        WhitelistFile.clear(d)
        assertEquals(emptyList<WhitelistFile.Entry>(), WhitelistFile.read(d))
    }

    @Test
    fun `有 usercache 时优先用其中的正版 UUID`() {
        val d = dir()
        File(d, "usercache.json").writeText(
            """[{"name":"Notch","uuid":"069a79f4-44e9-4726-a5be-fca90e38aaf5","expiresOn":"2026-01-01"}]""",
        )
        WhitelistFile.add(d, "Notch")
        assertEquals(
            "069a79f4-44e9-4726-a5be-fca90e38aaf5",
            WhitelistFile.read(d).single().uuid,
        )
    }

    @Test
    fun `usercache 里字段顺序反过来也能认`() {
        val d = dir()
        File(d, "usercache.json").writeText(
            """[{"uuid":"069a79f4-44e9-4726-a5be-fca90e38aaf5","name":"Notch"}]""",
        )
        WhitelistFile.add(d, "Notch")
        assertEquals("069a79f4-44e9-4726-a5be-fca90e38aaf5", WhitelistFile.read(d).single().uuid)
    }

    @Test
    fun `无连字符的 UUID 会被规范化`() {
        assertEquals(
            "069a79f4-44e9-4726-a5be-fca90e38aaf5",
            WhitelistFile.normalizeUuid("069A79F444E94726A5BEFCA90E38AAF5"),
        )
    }

    @Test
    fun `名字校验`() {
        assertTrue(WhitelistFile.isValidName("Steve"))
        assertTrue(WhitelistFile.isValidName("Steve_123"))
        assertTrue(WhitelistFile.isValidName("小明"))       // 离线服中文名常见
        assertFalse(WhitelistFile.isValidName(""))
        assertFalse(WhitelistFile.isValidName("   "))
        assertFalse(WhitelistFile.isValidName("a b"))        // 空格会拆成两个参数
        assertFalse(WhitelistFile.isValidName("a\"b"))       // 破坏 JSON
        assertFalse(WhitelistFile.isValidName("a\\b"))
        assertFalse(WhitelistFile.isValidName("x".repeat(17)))  // 超长
    }

    @Test
    fun `过长名字添加被拒`() {
        val d = dir()
        assertEquals(WhitelistFile.AddResult.TooLong, WhitelistFile.add(d, "x".repeat(17)))
        assertEquals(emptyList<WhitelistFile.Entry>(), WhitelistFile.read(d))
    }

    @Test
    fun `写文件不会留下 tmp`() {
        val d = dir()
        WhitelistFile.add(d, "Steve")
        assertFalse(File(d, "whitelist.json.tmp").exists())
    }

    @Test
    fun `多个玩家顺序保持`() {
        val d = dir()
        listOf("Steve", "Alex", "Notch").forEach { WhitelistFile.add(d, it) }
        assertEquals(listOf("Steve", "Alex", "Notch"), WhitelistFile.read(d).map { it.name })
    }
}
