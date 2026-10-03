package com.kaze.newage.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI 回复解析与命令安全闸门测试。
 *
 * 模型输出是不可信输入：这里锁住"多行命令进不来、危险命令能识别、
 * 非 JSON 回复不炸"这三条底线。
 */
class AiSuggestionTest {

    @Test
    fun `解析纯 JSON 回复`() {
        val p = AiSuggestion.parse("""{"analysis":"内存充足","command":"list"}""")
        assertEquals("内存充足", p.analysis)
        assertEquals("list", p.command)
    }

    @Test
    fun `解析带代码围栏的回复`() {
        val raw = "```json\n{\"analysis\":\"运行正常\",\"command\":\"\"}\n```"
        val p = AiSuggestion.parse(raw)
        assertEquals("运行正常", p.analysis)
        assertEquals("", p.command)
    }

    @Test
    fun `解析前后带散文的 JSON`() {
        val raw = "好的，结果如下：\n{\"analysis\":\"A\",\"command\":\"list\"}\n希望有帮助"
        val p = AiSuggestion.parse(raw)
        assertEquals("A", p.analysis)
        assertEquals("list", p.command)
    }

    @Test
    fun `非 JSON 回复整段作为分析且不带命令`() {
        val raw = "我觉得服务端运行正常，日志里没有异常。"
        val p = AiSuggestion.parse(raw)
        assertEquals(raw, p.analysis)
        assertEquals("", p.command)
    }

    @Test
    fun `空回复解析为空`() {
        val p = AiSuggestion.parse("  ")
        assertEquals("", p.analysis)
        assertEquals("", p.command)
    }

    @Test
    fun `think 推理段被剥离后正常解析`() {
        val p = AiSuggestion.parse(
            "<think>先看日志，内存充足，玩家在线正常。</think>\n{\"analysis\":\"A\",\"command\":\"list\"}"
        )
        assertEquals("A", p.analysis)
        assertEquals("list", p.command)
    }

    @Test
    fun `splitThinking 把推理段提取出来`() {
        val (stripped, reasoning) = AiSuggestion.splitThinking("<think>先查日志</think>{\"analysis\":\"A\"}")
        assertEquals("{\"analysis\":\"A\"}", stripped)
        assertEquals("先查日志", reasoning)
    }

    @Test
    fun `无 think 时推理为 null`() {
        val (stripped, reasoning) = AiSuggestion.splitThinking("纯文本回答")
        assertEquals("纯文本回答", stripped)
        assertEquals(null, reasoning)
    }

    @Test
    fun `未闭合 think 的内容也算推理`() {
        val (stripped, reasoning) = AiSuggestion.splitThinking("<think>推理了一半没有结论")
        assertEquals("", stripped)
        assertEquals("推理了一半没有结论", reasoning)
    }

    @Test
    fun `多个 think 段全部剥离`() {
        val p = AiSuggestion.parse(
            "<think>第一段</think>中间不该出现<think>第二段</think>{\"analysis\":\"B\",\"command\":\"\"}"
        )
        assertEquals("B", p.analysis)
        assertFalse(p.analysis.contains("中间不该出现"))
    }

    @Test
    fun `只有未闭合 think 时视为无答案`() {
        val p = AiSuggestion.parse("<think>推理了一半没有结论")
        assertEquals("", p.analysis)
        assertEquals("", p.command)
    }

    @Test
    fun `正文含 think 字样但非标签时不受影响`() {
        val p = AiSuggestion.parse("""{"analysis":"abc think def","command":""}""")
        assertEquals("abc think def", p.analysis)
    }

    @Test
    fun `命令里的换行被拒绝`() {
        // JSON 字符串解码后 \n 是真实的换行符：一条变多条是最必须堵的口子
        val p = AiSuggestion.parse("""{"analysis":"a","command":"say hi\nstop"}""")
        assertEquals("", p.command)
    }

    @Test
    fun `制表符等控制字符同样被拒绝`() {
        assertNull(AiSuggestion.sanitizeCommand("say\thi"))
    }

    @Test
    fun `开头的斜杠被剥掉`() {
        assertEquals("list", AiSuggestion.sanitizeCommand("/list"))
        assertEquals("whitelist add Steve", AiSuggestion.sanitizeCommand(" /whitelist add Steve"))
    }

    @Test
    fun `超长命令被拒绝`() {
        assertNull(AiSuggestion.sanitizeCommand("a".repeat(201)))
        assertEquals("a".repeat(200), AiSuggestion.sanitizeCommand("a".repeat(200)))
    }

    @Test
    fun `空白与 null 命令返回 null`() {
        assertNull(AiSuggestion.sanitizeCommand(null))
        assertNull(AiSuggestion.sanitizeCommand("   "))
        assertNull(AiSuggestion.sanitizeCommand("/"))
    }

    @Test
    fun `分析文本超长被截断`() {
        val raw = """{"analysis":"${"长".repeat(3000)}","command":""}"""
        assertTrue(AiSuggestion.parse(raw).analysis.length <= 2000)
    }

    @Test
    fun `危险命令识别按首个词且不区分大小写`() {
        listOf("stop", "op Steve", "WHITELIST add x", "ban-ip 1.2.3.4", "  kick  Steve ")
            .forEach { assertTrue("应判为危险：$it", AiSuggestion.isDangerous(it)) }
        listOf("list", "say hi", "time set day", "weather clear", "stopwatch")
            .forEach { assertFalse("不应判为危险：$it", AiSuggestion.isDangerous(it)) }
    }

    @Test
    fun `重启与执行类命令同样按危险处理`() {
        // restart 不是原版命令，但 Paper/Spigot 与多数管理插件都有：效果等同重启服务端
        listOf("restart", "RESTART now", "execute as @a run say hi", "reload confirm")
            .forEach { assertTrue("应判为危险：$it", AiSuggestion.isDangerous(it)) }
    }

    @Test
    fun `残缺的工具调用 JSON 不被当成分析上屏`() {
        // 输出被截断（max_tokens 用尽）时的典型形态：JSON 少了一半
        val truncated = """{"analysis":"我准备读一下启动日志","command":"","tool":{"name":"read_file","path":"logs/lat"""
        val p = AiSuggestion.parse(truncated)
        assertFalse("不该把半截 JSON 当分析：${p.analysis}", p.analysis.contains("\"tool\""))
        assertTrue("应说明发生了什么：${p.analysis}", p.analysis.contains("不完整"))
        assertEquals("", p.command)
        assertTrue("不完整的工具调用不能被执行", p.tool == null)
        // 普通散文（不含工具 JSON 特征）仍然整段当分析，行为不变
        val prose = "服务端看起来正常，没有发现异常。"
        assertEquals(prose, AiSuggestion.parse(prose).analysis)
    }

    // ── 文件工具调用解析 ──

    @Test
    fun `工具调用解析 read_file`() {
        val p = AiSuggestion.parse(
            """{"analysis":"我看一下启动日志","command":"","tool":{"name":"read_file","path":"logs/latest.log"}}"""
        )
        assertEquals("read_file", p.tool?.name)
        assertEquals("logs/latest.log", p.tool?.path)
        assertEquals("我看一下启动日志", p.analysis)
    }

    @Test
    fun `write_file 内容保留换行且不影响命令清洗`() {
        val p = AiSuggestion.parse(
            """{"analysis":"a","command":"","tool":{"name":"write_file","path":"server.properties","content":"a=1\nb=2"}}"""
        )
        assertEquals("write_file", p.tool?.name)
        assertTrue(p.tool!!.content.contains("\n"))
        assertEquals("", p.command)
    }

    @Test
    fun `未知工具名与空路径视作无工具`() {
        assertTrue(AiSuggestion.parse("""{"analysis":"a","tool":{"name":"rm_rf","path":"x"}}""").tool == null)
        assertTrue(AiSuggestion.parse("""{"analysis":"a","tool":{"name":"read_file","path":"  "}}""").tool == null)
    }

    @Test
    fun `无 tool 字段仍是最终回答`() {
        val p = AiSuggestion.parse("""{"analysis":"完成","command":"list"}""")
        assertEquals(null, p.tool)
        assertEquals("list", p.command)
    }
}
