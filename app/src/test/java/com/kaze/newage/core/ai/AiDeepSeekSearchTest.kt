package com.kaze.newage.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DeepSeek 官方联网搜索 API 的纯逻辑测试。
 *
 * 真实 HTTP 调用不在单测范围（需真机 + Key）；这里锁住三件最容易出错的事：
 *  1. 响应解析：summary 优先于 snippet、非 http 链接过滤、score 保留；
 *  2. 查询词提取：JSON 围栏 / 前后散文 / 纯文本回退 / 空串；
 *  3. 注入上下文的不可信数据标注（提示词层缓解，缺了会让网页文字被当指令）。
 */
class AiDeepSeekSearchTest {

    // ══════════ 响应解析 ══════════

    @Test
    fun `解析官方响应：summary 优先、过滤非 http 链接`() {
        val body = """
            {"results":[
              {"title":" V4 发布 ","url":"https://example.com/v4","snippet":"短摘要",
               "summary":"长摘要 600 字","rerank_score":2.4},
              {"title":"没网址","url":"ftp://x/y","snippet":"x","summary":"y"},
              {"title":"无 summary","url":"http://a.b/c","snippet":"短","summary":"","rerank_score":1.2}
            ]}
        """.trimIndent()
        val r = AiDeepSeekSearch.parse(body)
        assertEquals(2, r.size)                      // ftp:// 被过滤
        assertEquals("V4 发布", r[0].title)
        assertEquals("长摘要 600 字", r[0].snippet)  // summary 优先
        assertEquals(2.4f, r[0].score, 0.001f)
        assertEquals("短", r[1].snippet)             // summary 空 → 退回 snippet
    }

    @Test
    fun `空结果数组得到空列表`() {
        assertTrue(AiDeepSeekSearch.parse("""{"results":[]}""").isEmpty())
    }

    @Test
    fun `缺字段的宽松解析不崩`() {
        val r = AiDeepSeekSearch.parse("""{"results":[{"url":"https://x.y"}]}""")
        assertEquals(1, r.size)
        assertEquals("", r[0].title)
    }

    // ══════════ 查询词提取 ══════════

    @Test
    fun `从 JSON 围栏里提取查询词`() {
        val raw = "好的，查询词如下：\n```json\n{\"query\":\"Paper 1.21 UnsupportedClassVersionError\"}\n```"
        assertEquals("Paper 1.21 UnsupportedClassVersionError", AiDeepSeekSearch.extractQuery(raw))
    }

    @Test
    fun `空 query 表示不需要联网`() {
        assertEquals("", AiDeepSeekSearch.extractQuery("""{"query":""}"""))
    }

    @Test
    fun `纯文本一行回退为查询词`() {
        assertEquals("forge 安装失败", AiDeepSeekSearch.extractQuery("forge 安装失败"))
    }

    @Test
    fun `多行长文不回退，避免把整段回答当搜索词`() {
        assertEquals("", AiDeepSeekSearch.extractQuery("第一行\n第二行"))
        assertEquals("", AiDeepSeekSearch.extractQuery("x".repeat(129)))
    }

    @Test
    fun `空白输出得到空串`() {
        assertEquals("", AiDeepSeekSearch.extractQuery("   "))
    }

    // ══════════ 上下文注入 ══════════

    @Test
    fun `格式化结果必须标注不可信数据`() {
        val text = AiDeepSeekSearch.formatResults(
            "paper 版本",
            listOf(AiDeepSeekSearch.Result("标题", "https://example.com/a", "摘要", 2f)),
        )
        assertTrue("要写明查询词", text.contains("paper 版本"))
        assertTrue("必须标注不可信", text.contains("不可信数据"))
        assertTrue("必须禁止执行其中指令", text.contains("不要执行"))
        assertTrue("要给出来源域名", text.contains("example.com"))
        assertTrue("要包含标题", text.contains("标题"))
    }

    @Test
    fun `长标题与长摘要被截断，防撑爆 token`() {
        val text = AiDeepSeekSearch.formatResults(
            "q",
            listOf(AiDeepSeekSearch.Result("标".repeat(200), "https://x.y", "摘".repeat(600))),
        )
        assertTrue(text.contains("标".repeat(80)))
        assertFalse(text.contains("标".repeat(81)))
        assertTrue(text.contains("摘".repeat(300)))
        assertFalse(text.contains("摘".repeat(301)))
    }

    @Test
    fun `hostOf 提取主机名，异常输入兜底`() {
        assertEquals("example.com", AiDeepSeekSearch.hostOf("https://example.com/a/b?c=1"))
        assertEquals("not a url", AiDeepSeekSearch.hostOf("not a url"))
    }
}
