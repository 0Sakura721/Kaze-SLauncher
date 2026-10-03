package com.kaze.newage.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /**
     * "从没配过"和"配过但数据坏了"必须分得开：前者可以引导用户新建，
     * 后者如果也当成空的，用户一新建就把损坏的原串（里面还有 Key）覆盖掉了。
     */
    @Test
    fun `损坏与未配置由 corrupt 标志区分`() {
        assertFalse(AiProfileStore.decodeResult("").corrupt)
        assertFalse(AiProfileStore.decodeResult("   ").corrupt)
        // 合法的空列表：配过，只是被删空了 —— 不算损坏
        assertFalse(AiProfileStore.decodeResult("[]").corrupt)
        assertTrue(AiProfileStore.decodeResult("{\"broken\":").corrupt)
        assertTrue(AiProfileStore.decodeResult("not json at all").corrupt)
        // 形态不对（对象而不是数组）同样算损坏，而不是"没有配置"
        assertTrue(AiProfileStore.decodeResult("{\"name\":\"x\"}").corrupt)
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
