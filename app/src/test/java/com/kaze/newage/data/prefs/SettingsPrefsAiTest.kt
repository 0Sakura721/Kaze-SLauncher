package com.kaze.newage.data.prefs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.kaze.newage.core.ai.AiProfile
import com.kaze.newage.core.ai.AiSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * AI 相关设置的存储边界（Robolectric：真正的 SharedPreferences，真的落盘再读回）。
 *
 * 盯两件事：
 *  - **搜索凭据按源分槽**：切源不能把 A 家的 Key 发给 B 家（含旧版单槽的迁移）；
 *  - **档案 JSON 损坏**：必须留下"损坏"标志与原串，不能静默当成"没有配置"。
 *
 * 说明：Robolectric 没有 AndroidKeyStore，[AiKeyCipher] 会走明文兜底（这是设计里的降级路径），
 * 所以这里验证的是槽位与迁移逻辑；加解密本身在真机上验证。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsPrefsAiTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val raw get() = context.getSharedPreferences("kaze_ui_settings", Context.MODE_PRIVATE)

    @Test
    fun `搜索凭据按源分槽且互不覆盖`() {
        val prefs = SettingsPrefs(context)
        prefs.setAiSearch(AiSearch.Provider.TAVILY.id, "tvly-A")
        prefs.setAiSearch(AiSearch.Provider.BOCHA.id, "bocha-B")
        // 切回 Tavily（界面上点一下源卡片就会走这一步）：不能把 Bocha 的 Key 带过来
        prefs.setAiSearch(AiSearch.Provider.TAVILY.id, prefs.searchKeyFor(AiSearch.Provider.TAVILY.id))

        assertEquals("tvly-A", prefs.searchKeyFor(AiSearch.Provider.TAVILY.id))
        assertEquals("bocha-B", prefs.searchKeyFor(AiSearch.Provider.BOCHA.id))
        // SearXNG 的凭据是实例地址，同样单独一格
        prefs.setAiSearch(AiSearch.Provider.SEARXNG.id, "https://searx.example.com")
        assertEquals("https://searx.example.com", prefs.searchKeyFor(AiSearch.Provider.SEARXNG.id))
        assertEquals("tvly-A", prefs.searchKeyFor(AiSearch.Provider.TAVILY.id))

        // 重新构造（模拟重启）后仍各归各位
        val reloaded = SettingsPrefs(context)
        assertEquals("tvly-A", reloaded.searchKeyFor(AiSearch.Provider.TAVILY.id))
        assertEquals("bocha-B", reloaded.searchKeyFor(AiSearch.Provider.BOCHA.id))
        assertEquals("https://searx.example.com", reloaded.searchKeyFor(AiSearch.Provider.SEARXNG.id))
    }

    @Test
    fun `旧版单槽搜索 Key 只迁移到当时选中的源`() {
        raw.edit()
            .putString("ai_search_provider", AiSearch.Provider.BOCHA.id)
            .putString("ai_search_key", "legacy-K")
            .apply()

        val prefs = SettingsPrefs(context)
        assertEquals("legacy-K", prefs.searchKeyFor(AiSearch.Provider.BOCHA.id))
        // 旧键是跟"当时选中的源"配对的，搬进别家等于制造一次跨源发送
        assertEquals("", prefs.searchKeyFor(AiSearch.Provider.TAVILY.id))
        assertFalse("旧键迁移后必须删掉，否则同一份 Key 有两个来源", raw.contains("ai_search_key"))
    }

    @Test
    fun `档案 JSON 损坏时保留原文并置标志`() {
        raw.edit().putString("ai_profiles", "[{\"name\":\"x\",\"apiKey\":\"enc1:broken").apply()

        val prefs = SettingsPrefs(context)
        assertTrue("必须标记为损坏，界面才能提示", prefs.aiProfilesCorrupt.value)
        assertTrue("界面不能因此打不开", prefs.aiProfiles.value.isEmpty())
        // 原文一个字符都不能少：用户还有机会人工救回里面的 Key
        assertEquals(
            "[{\"name\":\"x\",\"apiKey\":\"enc1:broken",
            raw.getString("ai_profiles_corrupt_backup", null),
        )
        // 清除入口：只清转存与标志
        prefs.clearCorruptAiProfiles()
        assertFalse(prefs.aiProfilesCorrupt.value)
        assertFalse(raw.contains("ai_profiles_corrupt_backup"))
    }

    @Test
    fun `损坏后重新保存档案会撤掉提示`() {
        raw.edit().putString("ai_profiles", "{oops").apply()
        val prefs = SettingsPrefs(context)
        assertTrue(prefs.aiProfilesCorrupt.value)

        prefs.saveAiProfile(AiProfile(name = "DeepSeek", apiKey = "k"))
        assertFalse("用户已重新填好配置", prefs.aiProfilesCorrupt.value)
        assertEquals(1, prefs.aiProfiles.value.size)
        // 重新读回仍是合法配置（含 Key）
        assertEquals("k", SettingsPrefs(context).aiProfiles.value.first().apiKey)
    }

    @Test
    fun `正常档案不会误判为损坏`() {
        val prefs = SettingsPrefs(context)
        prefs.saveAiProfile(AiProfile(name = "本地", baseUrl = "http://127.0.0.1:8080", apiKey = "k"))
        val reloaded = SettingsPrefs(context)
        assertFalse(reloaded.aiProfilesCorrupt.value)
        assertEquals("本地", reloaded.aiProfiles.value.first().name)
        assertEquals("k", reloaded.aiProfiles.value.first().apiKey)
    }
}
