package com.kaze.newage.core.ai

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * fetch_page 工具：抓取搜索结果里的网页正文，让 AI 从"查到标题"升级为"读过原文"。
 *
 * 边界：
 *  - 仅 http(s)；单页截断到 [MAX_PAGE_BYTES]（防止无限网页撑爆上下文）；
 *  - 只收 text/html / text/plain / application/json —— 二进制一律拒绝；
 *  - HTML → 正文是**保守**的降级转换（剥 script/style/注释/标签、解码常见实体、折叠空白），
 *    不追求排版，只保留可读文本。
 */
object AiWebPage {

    const val MAX_PAGE_BYTES = 64 * 1024

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    fun fetch(rawUrl: String): String {
        val url = rawUrl.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw IllegalArgumentException("只支持 http(s) 链接")
        }
        // 必应重定向链接解出真实地址，省一跳也避免引用语义丢失
        val target = AiSearch.unwrapBingRedirect(url)
        val conn = URL(target).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = READ_TIMEOUT_MS
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "text/html,text/plain,application/json;q=0.9,*/*;q=0.5")
            val code = conn.responseCode
            if (code != 200) throw RuntimeException("网页返回 HTTP $code")
            val contentType = (conn.contentType ?: "").lowercase()
            val readable = contentType.contains("text/") || contentType.contains("json") || contentType.isBlank()
            if (!readable) throw IllegalArgumentException("该链接不是网页（$contentType），无法读取正文")
            val bytes = ByteArray(MAX_PAGE_BYTES)
            var n = 0
            conn.inputStream.use { ins ->
                while (n < bytes.size) {
                    val r = ins.read(bytes, n, bytes.size - n)
                    if (r < 0) break
                    n += r
                }
            }
            val charset = runCatching {
                contentType.substringAfter("charset=").substringBefore(';').trim().ifBlank { "utf-8" }
            }.getOrDefault("utf-8")
            val body = String(bytes, 0, n, runCatching { charset(charset) }.getOrDefault(Charsets.UTF_8))
            val text = if (contentType.contains("html")) htmlToText(body) else collapse(body)
            if (text.isBlank()) throw RuntimeException("网页没有提取到正文（可能是纯脚本渲染的页面）")
            // 读满缓冲 = 大概率被截断
            return if (n >= MAX_PAGE_BYTES) {
                "$text\n…（已截断：仅读取前 $MAX_PAGE_BYTES 字节）"
            } else {
                text
            }
        } catch (e: IOException) {
            throw RuntimeException("无法打开网页（${e.message ?: "网络错误"}）", e)
        } finally {
            conn.disconnect()
        }
    }

    /** HTML → 可读正文：剥脚本/样式/注释/标签，解码常见实体，折叠空白 */
    internal fun htmlToText(html: String): String {
        var s = html
        s = s.replace(Regex("(?is)<(script|style|noscript|svg)[^>]*>.*?</\\1>"), " ")
        s = s.replace(Regex("(?s)<!--.*?-->"), " ")
        s = s.replace(Regex("(?i)<(br|/p|/div|/h[1-6]|/li|/tr)[^>]*>"), "\n")
        s = s.replace(Regex("<[^>]+>"), " ")
        s = decodeEntities(s)
        return s.replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
            .replace(Regex(" *\\n *"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    private fun collapse(text: String): String =
        text.replace(Regex("\\r\\n?|\\n{3,}"), "\n\n").trim()

    private val ENTITIES = mapOf(
        "&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"",
        "&#39;" to "'", "&apos;" to "'", "&nbsp;" to " ", "&mdash;" to "—",
        "&hellip;" to "…", "&middot;" to "·", "&copy;" to "©",
    )

    private fun decodeEntities(s: String): String {
        var out = s
        ENTITIES.forEach { (k, v) -> out = out.replace(k, v) }
        // 数字实体 &#123; / &#x1F;
        out = out.replace(Regex("&#(\\d+);")) { m ->
            m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: m.value
        }
        out = out.replace(Regex("&#x([0-9a-fA-F]+);")) { m ->
            m.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: m.value
        }
        return out
    }

    /** 供测试/诊断使用：确认 URL 结构合法 */
    internal fun hostOf(url: String): String =
        runCatching { URI(url).host ?: url }.getOrDefault(url).take(60)
}
