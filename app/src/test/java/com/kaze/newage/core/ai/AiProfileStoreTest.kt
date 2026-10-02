package com.kaze.newage.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模型配置档案（Operit 式多配置）的纯逻辑测试：
 * 逗号分隔模型名解析、JSON 往返存取、损坏数据兜底。
 */
class AiProfileStoreTest {

    @Test
    fun `模型名按中英文逗号分隔并去空白去重`() {
        assertEquals(
            listOf("deepseek-chat", "deepseek-reasoner"),
            AiProfileStore.parseModelList("deepseek-chat, deepseek-reasoner"),
        )
        assertEquals(
            listOf("a", "b"),
            AiProfileStore.parseModelList("a，b"),
        )
        assertEquals(listOf("a", "b"), AiProfileStore.parseModelList("a,, b ,a"))
        assertEquals(emptyList<String>(), AiProfileStore.parseModelList(" , ， "))
    }

    @Test
    fun `档案 JSON 往返一致`() {
        val profiles = listOf(
            AiProfile(name = "DeepSeek", models = listOf("deepseek-flash")),
            AiProfile(name = "本地", baseUrl = "http://127.0.0.1:8080", models = listOf("qwen3"), apiKey = "k"),
        )
        val decoded = AiProfileStore.decode(AiProfileStore.encode(profiles))
        assertEquals(profiles, decoded)
    }

    @Test
    fun `损坏或空数据解码为空列表不抛异常`() {
        assertTrue(AiProfileStore.decode("").isEmpty())
        assertTrue(AiProfileStore.decode("not json at all").isEmpty())
        assertTrue(AiProfileStore.decode("{\"broken\":").isEmpty())
    }

    @Test
    fun `档案摘要含主机与模型名`() {
        val p = AiProfile(
            name = "DS",
            baseUrl = "https://api.deepseek.com/",
            models = listOf("deepseek-flash"),
        )
        val summary = p.summary
        assertTrue(summary.contains("api.deepseek.com"))
        assertTrue(summary.contains("deepseek-flash"))
    }
}
