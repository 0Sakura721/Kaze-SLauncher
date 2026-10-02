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
        val full = AiConfig(baseUrl = "https://api.x.com/v1/chat/completions", model = "m", apiKey = "k")
        assertEquals("https://api.x.com/v1/chat/completions", full.endpoint)
        assertEquals("", AiConfig(baseUrl = "api.x.com", model = "m", apiKey = "k").endpoint)
    }

    @Test
    fun `配置完整性要求三项齐全`() {
        assertFalse(AiConfig(baseUrl = "https://api.x.com", model = "m", apiKey = "").isConfigured)
        assertFalse(AiConfig(baseUrl = "", model = "m", apiKey = "k").isConfigured)
        assertFalse(AiConfig(baseUrl = "https://api.x.com", model = " ", apiKey = "k").isConfigured)
        assertTrue(AiConfig(baseUrl = "https://api.x.com", model = "m", apiKey = "k").isConfigured)
    }

    @Test
    fun `旧模型名只在官方端点归一到 deepseek-flash`() {
        val official = "https://api.deepseek.com"
        assertEquals("deepseek-flash", AiConfig.normalizeLegacyModel("deepseek-chat", official))
        assertEquals("deepseek-flash", AiConfig.normalizeLegacyModel("deepseek-reasoner", official))
        assertEquals("deepseek-flash", AiConfig.normalizeLegacyModel(" DeepSeek-Chat ", official))
        // 官方现行名与第三方模型名原样保留
        assertEquals("deepseek-flash", AiConfig.normalizeLegacyModel("deepseek-flash", official))
        assertEquals("deepseek-v4-pro", AiConfig.normalizeLegacyModel("deepseek-v4-pro", official))
        assertEquals("qwen3-max", AiConfig.normalizeLegacyModel("qwen3-max", official))
        // 第三方网关不改写：那里 deepseek-chat 可能仍是有效模型名
        val thirdParty = "https://api.siliconflow.cn/v1"
        assertEquals("deepseek-chat", AiConfig.normalizeLegacyModel("deepseek-chat", thirdParty))
    }

    @Test
    fun `深度思考在 DeepSeek 上由 thinking 参数控制而非模型名`() {
        // 思考/非思考都用同一个现行模型名
        val cfg = AiConfig(baseUrl = "https://api.deepseek.com", model = "deepseek-flash", apiKey = "k")
        assertEquals("deepseek-flash", cfg.requestModel)
        assertEquals("deepseek-flash", cfg.copy(thinking = true).requestModel)
        // 迁移遗留的旧名档案：官方端点请求侧仍归一到现行名
        val legacy = cfg.copy(model = "deepseek-chat", thinkingModel = "deepseek-reasoner")
        assertEquals("deepseek-flash", legacy.requestModel)
        assertEquals("deepseek-flash", legacy.copy(thinking = true).requestModel)
        // 第三方服务商：模型名不被改写；「思考=换模型名」仍可用第二个模型名
        val third = AiConfig(
            baseUrl = "https://api.siliconflow.cn/v1",
            model = "deepseek-chat", thinkingModel = "deepseek-reasoner", apiKey = "k",
        )
        assertEquals("deepseek-chat", third.requestModel)
        assertEquals("deepseek-reasoner", third.copy(thinking = true).requestModel)
    }

    @Test
    fun `DeepSeek 原生 thinking 参数随开关发送`() {
        val msgs = listOf(AiMessage(AiMessage.ROLE_USER, "q"))
        val off = AiClient.buildRequestBody(
            "deepseek-flash", msgs, thinkingEnabled = false, includeNativeThinkingParam = true,
        )
        assertTrue(off.contains("\"thinking\":{\"type\":\"disabled\"}"))
        val on = AiClient.buildRequestBody(
            "deepseek-flash", msgs, thinkingEnabled = true, includeNativeThinkingParam = true,
        )
        assertTrue(on.contains("\"thinking\":{\"type\":\"enabled\"}"))
        // 非官方场景不发该参数（避免第三方网关对未知参数 400）
        val third = AiClient.buildRequestBody(
            "qwen3", msgs, thinkingEnabled = true, includeNativeThinkingParam = false,
        )
        assertFalse(third.contains("\"thinking\""))
    }

    @Test
    fun `原生 thinking 参数只对官方 DeepSeek 发送`() {
        assertTrue(AiClient.supportsNativeThinkingParam("deepseek-flash", "https://api.deepseek.com"))
        assertTrue(AiClient.supportsNativeThinkingParam("deepseek-v4-pro", "https://api.deepseek.com/v1"))
        assertTrue(AiClient.supportsNativeThinkingParam("deepseek-flash", "https://proxy.example.com"))
        // 第三方网关上的 deepseek 模型不发；非 deepseek 模型不发
        assertFalse(
            AiClient.supportsNativeThinkingParam("deepseek-chat", "https://api.siliconflow.cn/v1")
        )
        assertFalse(AiClient.supportsNativeThinkingParam("gpt-4o", "https://api.openai.com/v1"))
    }

    @Test
    fun `附加请求参数合入请求体且不覆盖模型与消息`() {
        val body = AiClient.buildRequestBody(
            "m1",
            listOf(AiMessage(AiMessage.ROLE_USER, "q")),
            extraJson = """{"enable_thinking":true,"model":"hack","messages":[],"temperature":0.9}""",
        )
        assertTrue(body.contains("\"enable_thinking\":true"))
        assertTrue(body.contains("\"temperature\":0.9"))
        assertTrue(body.contains("\"model\":\"m1\""))
    }

    @Test
    fun `附加参数为空或非法 JSON 时被忽略`() {
        val normal = AiClient.buildRequestBody("m1", emptyList())
        assertEquals(normal, AiClient.buildRequestBody("m1", emptyList(), extraJson = null))
        assertEquals(normal, AiClient.buildRequestBody("m1", emptyList(), extraJson = "   "))
        assertEquals(normal, AiClient.buildRequestBody("m1", emptyList(), extraJson = "{bad json"))
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
        val parsed = AiClient.parseReply(reply)
        assertEquals("hello 世界", parsed.content)
        assertEquals(null, parsed.reasoning)
    }

    @Test
    fun `思考类模型的 reasoning_content 被解析出来`() {
        val reply = """{"choices":[{"message":{"role":"assistant","content":"答案","reasoning_content":"我是推理过程"}}]}"""
        val parsed = AiClient.parseReply(reply)
        assertEquals("答案", parsed.content)
        assertEquals("我是推理过程", parsed.reasoning)
    }

    @Test
    fun `推理字段的多形态兼容`() {
        // OpenRouter 风格：reasoning 字符串
        assertEquals(
            "think1",
            AiClient.parseReply("""{"choices":[{"message":{"content":"a","reasoning":"think1"}}]}""").reasoning,
        )
        // reasoning_content 数组分段
        assertEquals(
            "段1\n段2",
            AiClient.parseReply(
                """{"choices":[{"message":{"content":"a","reasoning_content":["段1","段2"]}}]}"""
            ).reasoning,
        )
        // reasoning_details 对象数组（取 summary）
        assertEquals(
            "d1",
            AiClient.parseReply(
                """{"choices":[{"message":{"content":"a","reasoning_details":[{"type":"reasoning","summary":"d1"}]}}]}"""
            ).reasoning,
        )
        // 优先级：reasoning_content > reasoning > reasoning_details
        assertEquals(
            "优先",
            AiClient.parseReply(
                """{"choices":[{"message":{"content":"a","reasoning_content":"优先","reasoning":"其次"}}]}"""
            ).reasoning,
        )
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
