package com.kaze.newage.data.prefs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.kaze.newage.NewAgeApp
import com.kaze.newage.core.ai.AiProfile
import com.kaze.newage.core.ai.AiProfileStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * AI 密钥存储迁移测试（审计 P0-2）：
 * 密钥必须从会被云备份带走的 `kaze_ui_settings` 搬进独立的 `kaze_ai_secrets`，
 * 并且**搬完就从旧文件删掉** —— 只复制不删除等于没修。
 */
@RunWith(RobolectricTestRunner::class)
class SettingsPrefsSecretsTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext<NewAgeApp>()

    private fun legacy() = ctx.getSharedPreferences("kaze_ui_settings", Context.MODE_PRIVATE)
    private fun secret() = ctx.getSharedPreferences(SettingsPrefs.SECRET_PREFS_NAME, Context.MODE_PRIVATE)

    @Test
    fun `旧设置里的模型档案与搜索 Key 迁到独立文件并从旧文件删除`() {
        val profilesJson = AiProfileStore.encode(listOf(AiProfile(name = "DeepSeek", apiKey = "sk-model")))
        legacy().edit()
            .putString("ai_profiles", profilesJson)
            .putString("ai_search_provider", "bocha")
            .putString("ai_search_key", "bocha-key")
            .putString("theme_mode", "m3")
            .commit()

        val prefs = SettingsPrefs(ctx)

        // 界面读到的还是原来的值（迁移对用户透明）
        assertEquals("sk-model", prefs.aiProfiles.value.single().apiKey)
        assertEquals("bocha-key", prefs.aiSearchKey("bocha"))
        // 值进了独立文件
        assertEquals(profilesJson, secret().getString("ai_profiles", null))
        assertEquals("bocha-key", secret().getString("ai_search_key_bocha", null))
        // 旧文件里不再留明文密钥
        assertFalse("旧文件不应再有档案（内含 Key）", legacy().contains("ai_profiles"))
        assertFalse("旧文件不应再有搜索 Key", legacy().contains("ai_search_key"))
        // 普通设置留在旧文件里，照常参与备份
        assertEquals("m3", legacy().getString("theme_mode", null))
    }

    @Test
    fun `旧搜索 Key 只迁给当时选中的那个源`() {
        legacy().edit()
            .putString("ai_search_provider", "tavily")
            .putString("ai_search_key", "t-key")
            .commit()

        val prefs = SettingsPrefs(ctx)

        assertEquals("t-key", prefs.aiSearchKey("tavily"))
        // 别的源绝不会拿到这份 Key
        assertEquals("", prefs.aiSearchKey("bocha"))
        assertEquals("", prefs.aiSearchKey("bing_local"))
        assertEquals(null, secret().getString("ai_search_key_bocha", null))
    }

    @Test
    fun `已迁移过时不覆盖新值也不再重复搬迁`() {
        secret().edit()
            .putString("ai_profiles", "[]")
            .putString("ai_search_key_tavily", "new-key")
            .commit()
        legacy().edit()
            .putString("ai_profiles", "旧内容")
            .putString("ai_search_key", "old-key")
            .commit()

        val prefs = SettingsPrefs(ctx)

        assertEquals("[]", secret().getString("ai_profiles", null))
        assertEquals("new-key", secret().getString("ai_search_key_tavily", null))
        assertTrue(prefs.aiProfiles.value.isEmpty())
        // 旧键依然被清掉（它只是不再被使用，没必要继续留在备份范围内）
        assertFalse(legacy().contains("ai_profiles"))
        assertFalse(legacy().contains("ai_search_key"))
    }

    @Test
    fun `更老的散键 ai_api_key 在进入档案后从旧文件删除`() {
        legacy().edit()
            .putString("ai_api_key", "sk-legacy")
            .putString("ai_base_url", "https://api.deepseek.com")
            .commit()

        val prefs = SettingsPrefs(ctx)

        assertEquals("sk-legacy", prefs.aiProfiles.value.single().apiKey)
        assertFalse("明文 Key 不该继续留在会被备份的文件里", legacy().contains("ai_api_key"))
        assertTrue(secret().getString("ai_profiles", "").orEmpty().contains("sk-legacy"))
    }

    @Test
    fun `保存档案与搜索 Key 都写进独立文件`() {
        val prefs = SettingsPrefs(ctx)
        prefs.saveAiProfile(AiProfile(name = "本地", baseUrl = "http://127.0.0.1:8080", apiKey = "local-key"))
        prefs.setAiSearch("tavily", "t-key")

        assertTrue(secret().getString("ai_profiles", "").orEmpty().contains("local-key"))
        assertEquals("t-key", secret().getString("ai_search_key_tavily", null))
        // 切换搜索源不会把上一家的 Key 带过去
        prefs.setAiSearch("bocha", prefs.aiSearchKey("bocha"))
        assertEquals("", secret().getString("ai_search_key_bocha", null))
        assertEquals("t-key", prefs.aiSearchKey("tavily"))
        assertFalse("普通 prefs 文件不该再出现 AI 档案", legacy().contains("ai_profiles"))
    }

    // ── 档案 JSON 损坏：保留原串 + 提示，绝不静默吞掉 Key（P2-6）──

    @Test
    fun `档案 JSON 损坏时保留原串并被标记为损坏`() {
        secret().edit().putString("ai_profiles", "{\"broken\":").commit()

        val prefs = SettingsPrefs(ctx)

        assertTrue("必须判定为损坏（否则界面会显示成『还没有配置』）", prefs.aiProfilesCorrupt.value)
        assertTrue(prefs.aiProfiles.value.isEmpty())
        // 用户还没做任何决定：原串一个字节都不能动
        assertEquals("{\"broken\":", secret().getString("ai_profiles", null))
    }

    @Test
    fun `损坏后保存新配置先把原文备份再覆盖`() {
        secret().edit().putString("ai_profiles", "{\"broken\":").commit()
        val prefs = SettingsPrefs(ctx)

        prefs.saveAiProfile(AiProfile(name = "新配置", apiKey = "sk-new"))

        assertEquals(
            "覆盖前必须把损坏原文转存到备份键",
            "{\"broken\":",
            secret().getString(SettingsPrefs.CORRUPT_PROFILES_BACKUP_KEY, null),
        )
        assertTrue(secret().getString("ai_profiles", "").orEmpty().contains("sk-new"))
        assertFalse("已经由用户显式覆盖，不再提示损坏", prefs.aiProfilesCorrupt.value)
    }

    @Test
    fun `损坏后清除数据同样保留原文备份`() {
        secret().edit().putString("ai_profiles", "broken-json").commit()
        val prefs = SettingsPrefs(ctx)

        prefs.clearCorruptAiProfiles()

        assertEquals("broken-json", secret().getString(SettingsPrefs.CORRUPT_PROFILES_BACKUP_KEY, null))
        assertFalse(secret().contains("ai_profiles"))
        assertFalse(prefs.aiProfilesCorrupt.value)
    }

    @Test
    fun `损坏时不跑旧散键迁移以免覆盖原文`() {
        // 同时存在损坏档案与更老的散键：迁移会往 ai_profiles 写新内容，等于覆盖损坏原文
        secret().edit().putString("ai_profiles", "{\"broken\":").commit()
        legacy().edit().putString("ai_api_key", "sk-legacy").commit()

        SettingsPrefs(ctx)

        assertEquals("{\"broken\":", secret().getString("ai_profiles", null))
        assertEquals("散键也不能被删掉（还没进档案）", "sk-legacy", legacy().getString("ai_api_key", null))
    }
}
