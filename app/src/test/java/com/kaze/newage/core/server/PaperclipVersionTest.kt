package com.kaze.newage.core.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * paperclip 从核心 jar 的 `version.json` 里取 vanilla 版本号。
 *
 * 为什么这个正则值得单测：Paper 的**构建版本号与内部 MC 版本可能不一致**
 * （`paper-1.18.2-217.jar` 的 version.json 里 `id = 1.18.1`）。
 * paperclip 是按 `id` 去找 `versions/1.18.1/1.18.1.jar` 的 ——
 * 用错版本就启动失败，而报错信息会指向"找不到 vanilla jar"，很难联想到这条路。
 */
class PaperclipVersionTest {

    private fun idOf(json: String): String? =
        DefaultServerManager.VERSION_JSON_ID.find(json)?.groupValues?.get(1)

    @Test
    fun `标准 version-json 能取到 id`() {
        val json = """{"id":"1.18.1","name":"1.18.1","world_version":2860}"""
        assertEquals("1.18.1", idOf(json))
    }

    @Test
    fun `真实 Paper jar 形状-构建号与 id 不一致`() {
        // paper-1.18.2-217.jar 的实际内容：外层版本是 1.18.2，id 是 1.18.1
        val json = """
            {
              "id": "1.18.1",
              "name": "1.18.1",
              "release_target": "1.18.1",
              "world_version": 2860,
              "protocol_version": 757
            }
        """.trimIndent()
        assertEquals("1.18.1", idOf(json))
    }

    @Test
    fun `带空格的 JSON 也能匹配`() {
        assertEquals("1", idOf("""{ "id" : "1" }"""))
        assertEquals("1", idOf("""{"id":    "1"}"""))
    }

    @Test
    fun `id 不是第一个字段也能找到`() {
        assertEquals("1.20.4", idOf("""{"name":"x","world_version":1,"id":"1.20.4"}"""))
    }

    @Test
    fun `取第一个 id（忽略后续同名字段）`() {
        // 理论上不该有两个 id，但真出现时取第一个（find 的语义），
        // 记录当前行为，避免以后改成 findAll 而改变语义
        assertEquals("A", idOf("""{"id":"A","id":"B"}"""))
    }

    @Test
    fun `值里含点与连字符都能取到`() {
        assertEquals("1.20.5-pre1", idOf("""{"id":"1.20.5-pre1"}"""))
        assertEquals("24w14a", idOf("""{"id":"24w14a"}"""))
    }

    @Test
    fun `没有 id 字段返回 null`() {
        assertNull(idOf("""{"name":"x"}"""))
    }

    @Test
    fun `id 为空串时返回 null（正则要求至少一个字符）`() {
        // [^"]+ 要求至少一个字符，所以 {"id":""} 匹配不上 →
        // 调用方拿到 null 后回退到实例记录的 mcVersion。这比返回空串更安全：
        // 空串会绕过 isNullOrBlank 检查之外的任何 sentinel 判断。
        assertNull(idOf("""{"id":""}"""))
    }

    @Test
    fun `非 JSON 内容返回 null`() {
        assertNull(idOf("not json at all"))
        assertNull(idOf(""))
        assertNull(idOf("<html>404</html>"))
    }

    @Test
    fun `id 值是数字时不匹配-version-json 的 id 恒为字符串`() {
        // 记录当前行为：正则要求引号，数字型 id 匹配不到 → 调用方回退到实例记录的版本
        assertNull(idOf("""{"id":123}"""))
    }

    @Test
    fun `不会误匹配 some_id 之类的相似键`() {
        // 正则要求字面量 `"id"`（带两侧引号），所以 `"some_id":"x"` 不匹配 ——
        // 比我原先以为的更严格，不需要 ^ 锚定也安全。
        assertNull(idOf("""{"some_id":"x"}"""))
        assertNull(idOf("""{"heal_id":"1.20"}"""))
    }
}