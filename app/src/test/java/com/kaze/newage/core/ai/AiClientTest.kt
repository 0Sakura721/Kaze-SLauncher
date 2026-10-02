package com.kaze.newage.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI 客户端的纯逻辑部分：端点规范化、请求体格式、响应解析、错误描述。
 * 不发起真实网络请求 —— 网络路径靠真机验证。
 */
class AiClientTest {

    @Test
    fun `base URL 规范化`() {
        assertEquals("https://api.deepseek.com/v1", AiConfig.normalizeBaseUrl(" https://api.deepseek.com/v1/ "))
        assertEquals("http://127.0.0.1:8080", AiConfig.normalizeBaseUrl("http://127.0.0.1:8080"))
        // 不是 http(s) 开头 / 只有协议没有主机 → 视为无效（空串）
        assertEquals("", AiConfig.normalizeBaseUrl("api.deepseek.com/v1"))
        assertEquals("", AiConfig.normalizeBaseUrl("https://"))
        assertEquals("", AiConfig.normalizeBaseUrl(""))
    }

    @Test
    fun `端点拼接不重复`() {
        val c = AiConfig(baseUrl = "https://api.x.com/v1/", model = "m", apiKey = "k")
        assertEquals("https://api.x.com/v1/chat/completions", c.endpoint)
        val full = AiConfig("https://api.x.com/v1/chat/completions", "m", "k")
        assertEquals("https://api.x.com/v1/chat/completions", full.endpoint)
        assertEquals("", AiConfig("api.x.com", "m", "k").endpoint)
    }

    @Test
    fun `配置完整性要求三项齐全`() {
        assertFalse(AiConfig("https://api.x.com", "m", "").isConfigured)
        assertFalse(AiConfig("", "m", "k").isConfigured)
        assertFalse(AiConfig("https://api.x.com", " ", "k").isConfigured)
        assertTrue(AiConfig("https://api.x.com", "m", "k").isConfigured)
    }

    @Test
    fun `请求体为 OpenAI 兼容格式`() {
        val body = AiClient.buildRequestBody(
            "m1",
            listOf(AiMessage(AiMessage.ROLE_SYSTEM, "sys"), AiMessage(AiMessage.ROLE_USER, "问题")),
        )
        assertTrue(body.contains("\"model\":\"m1\""))
        assertTrue(body.contains("\"stream\":false"))
        assertTrue(body.contains("\"role\":\"system\""))
        assertTrue(body.contains("\"role\":\"user\""))
        assertTrue(body.contains("\"content\":\"sys\""))
        assertTrue(body.contains("\"content\":\"问题\""))
    }

    @Test
    fun `响应解析取首个 choice 的 content`() {
        val reply = """{"choices":[{"message":{"role":"assistant","content":"hello 世界"}}]}"""
        assertEquals("hello 世界", AiClient.parseReply(reply))
    }

    @Test
    fun `空 choices 与坏 JSON 都给出可读错误`() {
        try {
            AiClient.parseReply("""{"choices":[]}""")
            throw AssertionError("应当抛出")
        } catch (e: RuntimeException) {
            assertTrue(e.message!!.contains("为空"))
        }
        try {
            AiClient.parseReply("<html>502 Bad Gateway</html>")
            throw AssertionError("应当抛出")
        } catch (e: RuntimeException) {
            assertTrue(e.message!!.contains("无法解析"))
        }
    }

    @Test
    fun `HTTP 错误描述带官方提示与不带回显密钥`() {
        val msg = AiClient.describeHttpError(401, """{"error":{"message":"Invalid API key provided"}}""")
        assertTrue(msg.contains("401"))
        assertTrue(msg.contains("Invalid API key"))
        assertTrue(AiClient.describeHttpError(429, "").contains("429"))
        assertTrue(AiClient.describeHttpError(503, "").contains("稍后再试"))
    }
}
