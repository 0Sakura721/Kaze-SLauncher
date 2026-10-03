package com.kaze.newage.core.ai

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 联网搜索的纯逻辑测试：Tavily / 博查响应解析、查询词提取、结果格式化、
 * 本机浏览器（Bing）提取结果的解析。真实网络调用与 WebView 不在单测范围（真机验证）。
 */
class AiSearchTest {

    /** 模拟 evaluateJavascript 的返回形式：把 JS 的返回字符串再 JSON 编码一层 */
    private fun encodeAsEvalString(payload: String): String = Json.encodeToString(payload)

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
        // 网页摘要属不可信数据：这行标注是提示词层的第一道缓解（P2-4）
        assertTrue("应标注为不可信数据：$block", block.contains("不可信数据"))
    }

    @Test
    fun `搜索源按 id 查找并回退 Tavily`() {
        assertEquals(AiSearch.Provider.BOCHA, AiSearch.Provider.byId("bocha"))
        assertEquals(AiSearch.Provider.TAVILY, AiSearch.Provider.byId("tavily"))
        assertEquals(AiSearch.Provider.TAVILY, AiSearch.Provider.byId("unknown"))
        // 本机浏览器源不需要 Key，API 源需要
        assertTrue(!AiSearch.Provider.BING_LOCAL.needsKey)
        assertTrue(AiSearch.Provider.TAVILY.needsKey)
        assertTrue(AiSearch.Provider.BOCHA.needsKey)
    }

    @Test
    fun `搜索凭据按源分槽且不会跨源取用`() {
        // 每个源一格，槽名互不相同
        val slots = AiSearch.Provider.entries.map { AiSearch.Provider.keySlot(it.id) }
        assertEquals(slots.size, slots.toSet().size)
        assertTrue(AiSearch.Provider.keySlot("tavily").endsWith("tavily"))

        val keys = mapOf(
            "tavily" to "tvly-secret-A",
            "bocha" to "sk-secret-B",
            "searxng" to "https://searx.example.com",
        )
        // 切到哪家就只拿哪家的凭据：A 家的 Key 不会被发给 B 家
        assertEquals("tvly-secret-A", AiSearch.keyFor("tavily", keys))
        assertEquals("sk-secret-B", AiSearch.keyFor("bocha", keys))
        // SearXNG 的"Key 栏"存的是实例地址（needsUrl），必须原样返回，否则该源直接不可用
        assertEquals("https://searx.example.com", AiSearch.keyFor("searxng", keys))
        // 未知 id 回退 Tavily：落到的也是 Tavily 自己那一格，读不到别家的
        assertEquals("tvly-secret-A", AiSearch.keyFor("nope", keys))
        // 免凭据源恒为空：界面上残留的旧值不该被顺手带出去
        assertEquals("", AiSearch.keyFor("bing_local", keys))
        assertEquals("", AiSearch.keyFor("tavily", emptyMap()))
    }

    // ── 本机浏览器源（Bing）的纯解析部分 ──

    @Test
    fun `必应提取结果双层解码并还原重定向链接`() {
        val hits = """[
            {"title":"Paper 优化","url":"https://docs.papermc.io/paper/optimization","snippet":"内存与视距"},
            {"title":"被包了跳转的","url":"https://www.bing.com/ck/a?!&p=x&uddg=https%3a%2f%2fzh.minecraft.wiki%2fw%2fServer&a=1","snippet":"wiki"}
        ]"""
        val results = AiSearch.parseBingExtraction(encodeAsEvalString(hits))
        assertEquals(2, results.size)
        assertEquals("Paper 优化", results[0].title)
        assertEquals("https://docs.papermc.io/paper/optimization", results[0].url)
        // bing.com/ck/ 重定向被解出真实地址
        assertEquals("https://zh.minecraft.wiki/w/Server", results[1].url)
    }

    @Test
    fun `必应提取的空值与坏数据都按没有结果处理`() {
        assertTrue(AiSearch.parseBingExtraction("null").isEmpty())
        assertTrue(AiSearch.parseBingExtraction(encodeAsEvalString("[]")).isEmpty())
        assertTrue(AiSearch.parseBingExtraction("<html>不是 JSON</html>").isEmpty())
        // 只留 http 链接：javascript: 之类一律丢弃
        val rawEval = encodeAsEvalString("""[{"title":"t","url":"javascript:void(0)","snippet":"s"}]""")
        assertTrue(AiSearch.parseBingExtraction(rawEval).isEmpty())
    }

    @Test
    fun `非重定向链接原样返回`() {
        val direct = "https://docs.papermc.io/paper/optimization"
        assertEquals(direct, AiSearch.unwrapBingRedirect(direct))
        val other = "https://cn.bing.com/search?q=x"
        assertEquals(other, AiSearch.unwrapBingRedirect(other))
    }

    @Test
    fun `必应搜索地址对中文查询做 URL 编码`() {
        val url = AiSearch.buildBingSearchUrl("Paper 内存 优化")
        assertTrue(url.startsWith("https://www.bing.com/search?q="))
        assertTrue(url.contains("Paper+"))
        assertTrue(url.contains("%E5%86%85%E5%AD%98"))  // 内存
        assertTrue(url.endsWith("&count=10"))
    }
}
