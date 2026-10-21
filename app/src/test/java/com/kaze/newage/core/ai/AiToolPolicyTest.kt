package com.kaze.newage.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 每工具权限策略的解析与默认值测试。
 *
 * 重点：**升级到本策略不能悄悄放宽任何已有边界** —— 写文件必须仍是"每次询问"，
 * 读取类仍是"自动"，且存储串读坏时回落默认而不是放行或崩溃。
 */
class AiToolPolicyTest {

    @Test
    fun `默认档位：只读自动、写盘询问`() {
        assertEquals(AiToolPolicy.Mode.AUTO, AiToolPolicy.defaultMode("read_file"))
        assertEquals(AiToolPolicy.Mode.AUTO, AiToolPolicy.defaultMode("list_dir"))
        assertEquals(AiToolPolicy.Mode.AUTO, AiToolPolicy.defaultMode("fetch_page"))
        assertEquals(AiToolPolicy.Mode.ASK, AiToolPolicy.defaultMode("write_file"))
    }

    @Test
    fun `未知工具回落 AUTO 而不是被静默禁用`() {
        assertEquals(AiToolPolicy.Mode.AUTO, AiToolPolicy.defaultMode("some_future_tool"))
        assertEquals(AiToolPolicy.Mode.AUTO, AiToolPolicy.modeOf("", "some_future_tool"))
    }

    @Test
    fun `空串与 null 都返回默认`() {
        assertTrue(AiToolPolicy.parse(null).isEmpty())
        assertTrue(AiToolPolicy.parse("").isEmpty())
        assertTrue(AiToolPolicy.parse("   ").isEmpty())
        assertEquals(AiToolPolicy.Mode.ASK, AiToolPolicy.modeOf(null, "write_file"))
        assertEquals(AiToolPolicy.Mode.AUTO, AiToolPolicy.modeOf("", "read_file"))
    }

    @Test
    fun `正常串能解析出覆盖值`() {
        val raw = "read_file=ask;write_file=auto"
        assertEquals(AiToolPolicy.Mode.ASK, AiToolPolicy.modeOf(raw, "read_file"))
        assertEquals(AiToolPolicy.Mode.AUTO, AiToolPolicy.modeOf(raw, "write_file"))
        // 没提到的仍然是默认
        assertEquals(AiToolPolicy.Mode.AUTO, AiToolPolicy.modeOf(raw, "list_dir"))
    }

    @Test
    fun `非法项被忽略而不是抛异常`() {
        val raw = "read_file=ask;bad;=auto;write_file=nonsense;list_dir=deny;"
        val parsed = AiToolPolicy.parse(raw)
        assertEquals(AiToolPolicy.Mode.ASK, parsed["read_file"])
        assertEquals(AiToolPolicy.Mode.DENY, parsed["list_dir"])
        // write_file=nonsense 非法 → 回落默认 ASK
        assertEquals(AiToolPolicy.Mode.ASK, AiToolPolicy.modeOf(raw, "write_file"))
        assertFalse(parsed.containsKey(""))
        assertFalse(parsed.containsKey("bad"))
    }

    @Test
    fun `encode 改一项不丢其它项`() {
        val raw = "read_file=ask;list_dir=deny"
        val next = AiToolPolicy.encode(raw, "fetch_page", AiToolPolicy.Mode.DENY)
        assertEquals(AiToolPolicy.Mode.ASK, AiToolPolicy.modeOf(next, "read_file"))
        assertEquals(AiToolPolicy.Mode.DENY, AiToolPolicy.modeOf(next, "list_dir"))
        assertEquals(AiToolPolicy.Mode.DENY, AiToolPolicy.modeOf(next, "fetch_page"))
        // 写盘那项没动过 → 仍是默认
        assertEquals(AiToolPolicy.Mode.ASK, AiToolPolicy.modeOf(next, "write_file"))
    }

    @Test
    fun `encode 改回默认值时不写进串里`() {
        val raw = "read_file=ask"
        val next = AiToolPolicy.encode(raw, "read_file", AiToolPolicy.Mode.AUTO)
        assertEquals("", next)
        assertEquals(AiToolPolicy.Mode.AUTO, AiToolPolicy.modeOf(next, "read_file"))
    }

    @Test
    fun `encode 会顺手清掉非法项`() {
        val raw = "bad;read_file=ask;write_file=nonsense"
        val next = AiToolPolicy.encode(raw, "list_dir", AiToolPolicy.Mode.DENY)
        val parsed = AiToolPolicy.parse(next)
        assertFalse(parsed.containsKey("bad"))
        assertFalse(parsed.containsKey("write_file"))
        assertEquals(AiToolPolicy.Mode.ASK, parsed["read_file"])
        assertEquals(AiToolPolicy.Mode.DENY, parsed["list_dir"])
    }

    @Test
    fun `isDenied 与 needsAsk 与档位一致`() {
        val raw = "read_file=deny;list_dir=ask"
        assertTrue(AiToolPolicy.isDenied(raw, "read_file"))
        assertFalse(AiToolPolicy.isDenied(raw, "list_dir"))
        assertTrue(AiToolPolicy.needsAsk(raw, "list_dir"))
        assertFalse(AiToolPolicy.needsAsk(raw, "read_file"))
        // 默认：写盘 needsAsk
        assertTrue(AiToolPolicy.needsAsk(null, "write_file"))
        assertFalse(AiToolPolicy.isDenied(null, "write_file"))
    }

    @Test
    fun `工具表里的 id 唯一且都能查到`() {
        val ids = AiToolPolicy.TOOLS.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        for (t in AiToolPolicy.TOOLS) {
            assertEquals(t, AiToolPolicy.toolById(t.id))
        }
        // execute_command 故意不在表里：它由 AiCommandPolicy 管，两套档位不该互相打架
        assertFalse(ids.contains("execute_command"))
    }

    @Test
    fun `往返编解码稳定`() {
        var raw: String? = null
        raw = AiToolPolicy.encode(raw, "read_file", AiToolPolicy.Mode.DENY)
        raw = AiToolPolicy.encode(raw, "write_file", AiToolPolicy.Mode.AUTO)
        raw = AiToolPolicy.encode(raw, "list_dir", AiToolPolicy.Mode.ASK)
        val once = AiToolPolicy.parse(raw)
        val again = AiToolPolicy.parse(raw)
        assertEquals(once, again)
        assertEquals(AiToolPolicy.Mode.DENY, once["read_file"])
        assertEquals(AiToolPolicy.Mode.AUTO, once["write_file"])
        assertEquals(AiToolPolicy.Mode.ASK, once["list_dir"])
    }
}