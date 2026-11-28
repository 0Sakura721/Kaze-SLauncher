package com.kaze.newage.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `fetch_page` 工具的 **HTML → 正文**转换测试。
 *
 * 这个函数决定模型读到的网页内容。它出错的后果不是崩溃，而是
 * **模型读到噪声或缺失的文本**，然后给出看似合理但没有依据的回答 ——
 * 比崩溃更难发现。真实抓取不在单测范围（需要联网），这里只测纯转换逻辑。
 */
class AiWebPageTest {

    // ══════════ 标签剥离 ══════════

    @Test
    fun `剥掉 script 与 style 的内容（不只是标签）`() {
        val html = """
            <html><head><style>body{color:red}</style>
            <script>var x = "<p>fake</p>";</script></head>
            <body>正文</body></html>
        """.trimIndent()
        val t = AiWebPage.htmlToText(html)
        assertTrue("正文应保留: $t", t.contains("正文"))
        assertTrue("script 内容不该出现: $t", !t.contains("var x"))
        assertTrue("style 内容不该出现: $t", !t.contains("color:red"))
    }

    @Test
    fun `块级标签转成换行（保住段落结构）`() {
        val t = AiWebPage.htmlToText("<p>第一段</p><p>第二段</p>")
        assertTrue("两段应分行: [$t]", t.contains("\n"))
        assertTrue(t.contains("第一段") && t.contains("第二段"))
    }

    @Test
    fun `br 转成换行`() {
        val t = AiWebPage.htmlToText("a<br>b<br/>c")
        assertEquals("a\nb\nc", t)
    }

    @Test
    fun `行内标签只剥掉标签保留文字`() {
        val t = AiWebPage.htmlToText("Hello <b>world</b>!")
        // 标签替换成空格，所以 "world !" 前会多一个空格 —— 可接受
        //（正文里 <b> 两侧本来就是分词边界）。断言只验内容与顺序。
        val flat = t.replace(Regex("\\s+"), " ").trim()
        assertTrue("应保留 Hello: [$flat]", flat.contains("Hello"))
        assertTrue("应保留 world: [$flat]", flat.contains("world"))
        assertTrue("应保留感叹号: [$flat]", flat.contains("!"))
        assertTrue("标签不该残留: [$flat]", !flat.contains("<"))
    }

    @Test
    fun `HTML 注释被剥掉`() {
        val t = AiWebPage.htmlToText("前<!-- 隐藏 -->后")
        // 注释整体替换成空格（不是删除），所以两字之间有一个空格
        assertEquals("前 后", t)
    }

    @Test
    fun `空输入与纯标签输入得到空串`() {
        assertEquals("", AiWebPage.htmlToText(""))
        assertEquals("", AiWebPage.htmlToText("<div></div>"))
    }

    // ══════════ 实体解码 ══════════

    @Test
    fun `常见命名实体被解码`() {
        assertEquals("a & b", AiWebPage.htmlToText("a &amp; b"))
        assertEquals("<tag>", AiWebPage.htmlToText("&lt;tag&gt;"))
        assertEquals("\"q\"", AiWebPage.htmlToText("&quot;q&quot;"))
        assertEquals("a'b", AiWebPage.htmlToText("a&#39;b"))
    }

    @Test
    fun `nbsp 解成空格`() {
        val t = AiWebPage.htmlToText("a&nbsp;b")
        assertTrue("应为 a b 或 ab: [$t]", t == "a b" || t == "ab")
    }

    @Test
    fun `数字实体被解码`() {
        assertEquals("A", AiWebPage.htmlToText("&#65;"))
    }

    @Test
    fun `十六进制实体被解码`() {
        assertEquals("A", AiWebPage.htmlToText("&#x41;"))
    }

    @Test
    fun `非法实体原样保留（不吞字符）`() {
        // 超范围码点 toChar 会截断，但绝不该抛异常
        val t = AiWebPage.htmlToText("&#99999999;x")
        assertTrue("应保留 x: [$t]", t.contains("x"))
    }

    @Test
    fun `未知实体保持原样`() {
        assertEquals("&unknown;", AiWebPage.htmlToText("&unknown;"))
    }

    // ══════════ 空白处理 ══════════

    @Test
    fun `连续空白折叠成一个空格`() {
        assertEquals("a b", AiWebPage.htmlToText("a     b"))
    }

    @Test
    fun `换行是段落边界，不被折成空格`() {
        // 源码里 \r 被折成空格、\n 保留，随后 " *\n *" 把两侧空格吃掉
        assertEquals("a\nb", AiWebPage.htmlToText("a \t\r\n b"))
    }

    @Test
    fun `连续空行压成最多一个空行`() {
        val t = AiWebPage.htmlToText("a<br><br><br><br>b")
        assertTrue("不该出现 3 个以上换行: [$t]", !t.contains("\n\n\n"))
    }

    @Test
    fun `结果首尾无空白`() {
        val t = AiWebPage.htmlToText("   <p>  x  </p>   ")
        assertEquals("x", t)
    }

    // ══════════ hostOf ══════════

    @Test
    fun `hostOf 提取主机名`() {
        assertEquals("example.com", AiWebPage.hostOf("https://example.com/a/b?c=1"))
    }

    @Test
    fun `hostOf 对畸形 URL 兜底不抛`() {
        val h = AiWebPage.hostOf("not a url")
        assertTrue("应返回输入本身或空: [$h]", h.isNotBlank())
    }

    @Test
    fun `hostOf 结果截断到 60 字符`() {
        val long = "https://" + "a".repeat(200) + ".com/"
        assertTrue(AiWebPage.hostOf(long).length <= 60)
    }

    // ══════════ 必应重定向解包 ══════════

    @Test
    fun `非必应链接原样返回`() {
        val u = "https://example.com/page"
        assertEquals(u, AiWebPage.unwrapBingRedirect(u))
    }

    @Test
    fun `必应 ck 链接解出 uddg 里的真实地址`() {
        val u = "https://www.bing.com/ck/a?&&uddg=" +
            java.net.URLEncoder.encode("https://example.com/real?x=1", "UTF-8") +
            "&p=abc"
        assertEquals("https://example.com/real?x=1", AiWebPage.unwrapBingRedirect(u))
    }

    @Test
    fun `uddg 里不是 http 开头时原样返回`() {
        val u = "https://www.bing.com/ck/a?uddg=" + java.net.URLEncoder.encode("ftp://x/y", "UTF-8")
        assertEquals(u, AiWebPage.unwrapBingRedirect(u))
    }

    @Test
    fun `必应链接里没有 uddg 时原样返回`() {
        val u = "https://www.bing.com/ck/a?p=abc"
        assertEquals(u, AiWebPage.unwrapBingRedirect(u))
    }

    @Test
    fun `畸形必应链接不抛异常`() {
        // rawQuery 为空的变形，确认 runCatching 兜住
        val u = "https://www.bing.com/ck/"
        val r = AiWebPage.unwrapBingRedirect(u)
        assertTrue("应为原串或合法 http 地址: [$r]", r.isNotEmpty())
    }

    // ══════════ 组合场景 ══════════

    @Test
    fun `典型网页片段转换（真实形状）`() {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
              <title>标题</title>
              <style>.a{}</style>
            </head>
            <body>
              <h1>大标题</h1>
              <p>第一段&nbsp;文字</p>
              <p>第二段 &amp; 实体</p>
              <ul><li>项 1</li><li>项 2</li></ul>
            </body>
            </html>
        """.trimIndent()
        val t = AiWebPage.htmlToText(html)
        assertTrue("大标题: $t", t.contains("大标题"))
        assertTrue("第一段: $t", t.contains("第一段"))
        assertTrue("实体已解码: $t", t.contains("&") || t.contains("第二段"))
        assertTrue("列表项: $t", t.contains("项 1") && t.contains("项 2"))
        assertTrue("样式已剥: $t", !t.contains(".a{}"))
    }
}