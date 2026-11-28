package com.kaze.newage.core.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MCP 工具的 **JSON 参数读取**与 **Schema 构造**测试。
 *
 * 这些函数看起来简单，但它们面对的是**外部客户端传任意内容**：
 * MCP 调用方是别的 AI 或脚本，参数类型完全不可控 —— 传 `"count": "10"`、
 * `"count": 10.5`、`"id": null`、甚至整个参数是一个数组，都可能发生。
 * 读错了后果是"工具报参数非法"或"用了错误的默认值"，而用户看不到原因。
 *
 * 这里锁两件事：
 *  1. 读到什么算什么，读不到/类型不符一律返回 null（交给调用方用默认值），**绝不抛异常**；
 *  2. Schema 构造的形状正确（模型靠它生成调用参数，写错会导致模型传错类型）。
 */
class McpToolsJsonTest {

    private fun obj(vararg pairs: Pair<String, kotlinx.serialization.json.JsonElement>): JsonObject =
        buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }

    // ══════════ 字符串读取 ══════════

    @Test
    fun `读字符串`() {
        assertEquals("abc", obj("k" to JsonPrimitive("abc")).str("k"))
    }

    @Test
    fun `缺键返回 null`() {
        assertNull(obj().str("k"))
    }

    @Test
    fun `显式 null 返回 null`() {
        // JSON null 的 jsonPrimitive.contentOrNull 为 null
        val o = obj("k" to kotlinx.serialization.json.JsonNull)
        assertNull(o.str("k"))
    }

    @Test
    fun `数字转字符串也能读到（不崩）`() {
        // 客户端把字符串参数写成数字：不报错，按内容读出来
        assertEquals("42", obj("k" to JsonPrimitive(42)).str("k"))
    }

    @Test
    fun `布尔转字符串也能读到`() {
        assertEquals("true", obj("k" to JsonPrimitive(true)).str("k"))
    }

    @Test
    fun `对象类型的键返回 null 而不是抛异常`() {
        // jsonPrimitive 对 JsonObject 会抛 IllegalArgumentException，
        // contentOrNull 内部是 runCatching —— 确认它确实被兜住
        val nested = buildJsonObject { put("a", 1) }
        assertNull(obj("k" to nested).str("k"))
    }

    @Test
    fun `数组类型的键返回 null 而不是抛异常`() {
        val arr = JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2)))
        assertNull(obj("k" to arr).str("k"))
    }

    // ══════════ 整数读取 ══════════

    @Test
    fun `读整数`() {
        assertEquals(7, obj("k" to JsonPrimitive(7)).int("k"))
    }

    @Test
    fun `数字字符串也能读成整数`() {
        // intOrNull 对 "10" 会解析成功
        assertEquals(10, obj("k" to JsonPrimitive("10")).int("k"))
    }

    @Test
    fun `非数字字符串返回 null`() {
        assertNull(obj("k" to JsonPrimitive("abc")).int("k"))
    }

    @Test
    fun `小数返回 null（不静默截断）`() {
        // intOrNull 对 10.5 返回 null —— 静默截断会悄悄丢掉小数部分
        assertNull(obj("k" to JsonPrimitive(10.5)).int("k"))
    }

    @Test
    fun `缺键与 null 都返回 null`() {
        assertNull(obj().int("k"))
        assertNull(obj("k" to kotlinx.serialization.json.JsonNull).int("k"))
    }

    // ══════════ 布尔读取 ══════════

    @Test
    fun `读布尔`() {
        assertEquals(true, obj("k" to JsonPrimitive(true)).bool("k"))
        assertEquals(false, obj("k" to JsonPrimitive(false)).bool("k"))
    }

    @Test
    fun `布尔字符串也能读`() {
        assertEquals(true, obj("k" to JsonPrimitive("true")).bool("k"))
    }

    @Test
    fun `非布尔值返回 null`() {
        assertNull(obj("k" to JsonPrimitive("yes")).bool("k"))
        assertNull(obj("k" to JsonPrimitive(1)).bool("k"))
    }

    // ══════════ Schema 构造 ══════════

    @Test
    fun `objSchema 基本形状`() {
        val s = objSchema()
        assertEquals("object", s["type"]?.toString()?.trim('"'))
        assertTrue("properties 必须存在", s.containsKey("properties"))
        // required 为空时不该出现（模型会以为必须传空数组里的成员）
        assertTrue("空 required 不应输出", !s.containsKey("required"))
    }

    @Test
    fun `objSchema 带 required`() {
        val s = objSchema(
            properties = props(instanceIdProp()),
            required = listOf("instanceId"),
        )
        assertEquals("[\"instanceId\"]", s["required"]?.toString())
    }

    @Test
    fun `strProp 与 intProp 的类型正确`() {
        assertEquals("string", strProp("x")["type"]?.toString()?.trim('"'))
        assertEquals("integer", intProp("x")["type"]?.toString()?.trim('"'))
        assertEquals("boolean", boolProp("x")["type"]?.toString()?.trim('"'))
    }

    @Test
    fun `intProp 有默认值时才写 default`() {
        assertTrue("未给默认值时不该出现 default", !intProp("x").containsKey("default"))
        assertEquals("10", intProp("x", 10)["default"]?.toString())
    }

    @Test
    fun `instanceIdProp 的键名是 instanceId`() {
        val (k, _) = instanceIdProp()
        assertEquals("instanceId", k)
    }

    @Test
    fun `props 合并多个属性`() {
        val p = props(instanceIdProp(), "cmd" to strProp("命令"))
        assertTrue(p.containsKey("instanceId"))
        assertTrue(p.containsKey("cmd"))
    }

    @Test
    fun `description 被保留（模型靠它理解参数含义）`() {
        assertEquals(
            "\"实例 id 或实例名\"",
            strProp("实例 id 或实例名")["description"]?.toString(),
        )
    }
}
