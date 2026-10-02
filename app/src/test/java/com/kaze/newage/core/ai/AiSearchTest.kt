package com.kaze.newage.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 联网搜索的纯逻辑测试：Tavily / 博查响应解析、查询词提取、结果格式化。
 * 真实网络调用不在单测范围（真机验证）。
 */
class AiSearchTest {

    @Test
    fun `解析 Tavily 响应`() {
        val body = """
            {"query":"Paper 卡顿","results":[
              {"title":"Paper 优化指南","url":"https://docs.papermc.io/paper/optimization","content":"Use paper-global settings...","score":0.9},
              {"title":"无链接的会被丢弃","url":"","content":"x"}
            ]}
        """.trimIndent()
        val results = AiSearch.parseTavily(body)
        assertEquals(1, results.size)
        assertEquals("Paper 优化指南", results[0].title)
        assertEquals("https://docs.papermc.io/paper/optimization", results[0].url)
        assertTrue(results[0].snippet.contains("paper-global"))
    }

    @Test
    fun `解析博查响应并优先 summary`() {
        val body = """
            {"code":200,"data":{"webPages":{"value":[
              {"name":"MC Wiki 内存分配","url":"https://zh.minecraft.wiki/w/内存","snippet":"摘要片段","summary":"更完整的总结内容"}
            ]}}}
        """.trimIndent()
        val results = AiSearch.parseBocha(body)
        assertEquals(1, results.size)
        assertEquals("更完整的总结内容", results[0].snippet)
        assertEquals("MC Wiki 内存分配", results[0].title)
    }

    @Test
    fun `博查业务错误码抛出可读异常`() {
        try {
            AiSearch.parseBocha("""{"code":402,"msg":"余额不足"}""")
            throw AssertionError("应当抛出")
        } catch (e: RuntimeException) {
            assertTrue(e.message!!.contains("402"))
            assertTrue(e.message!!.contains("余额不足"))
        }
    }

    @Test
    fun `提取查询词 支持 JSON 围栏与散文`() {
        assertEquals("Paper 内存 优化", AiSearch.extractQuery("""{"query":"Paper 内存 优化"}"""))
        assertEquals("q1", AiSearch.extractQuery("好的：\n```json\n{\"query\":\"q1\"}\n```"))
        assertEquals("", AiSearch.extractQuery("""{"query":""}"""))  // 本地问题不搜索
        assertEquals("单个短句当查询词", AiSearch.extractQuery("单个短句当查询词"))
        // 多行散文 / 超长非 JSON 不当查询词，防止整段日志被误当搜索词
        assertEquals("", AiSearch.extractQuery("第一行\n第二行"))
        assertEquals("", AiSearch.extractQuery("很长的".repeat(40)))
    }

    @Test
    fun `结果格式化包含查询词与来源域名并截断长摘要`() {
        val results = listOf(
            AiSearch.Result(
                "t".repeat(120),
                "https://docs.example.com/a/b?x=1",
                "s".repeat(1000),
            )
        )
        val block = AiSearch.formatResults("测试词", results)
        assertTrue(block.contains("查询词：测试词"))
        assertTrue(block.contains("docs.example.com"))
        assertFalse(block.contains("s".repeat(400)))
        assertFalse(block.contains("t".repeat(100)))
    }

    @Test
    fun `搜索源按 id 查找并回退 Tavily`() {
        assertEquals(AiSearch.Provider.BOCHA, AiSearch.Provider.byId("bocha"))
        assertEquals(AiSearch.Provider.TAVILY, AiSearch.Provider.byId("tavily"))
        assertEquals(AiSearch.Provider.TAVILY, AiSearch.Provider.byId("unknown"))
    }
}
