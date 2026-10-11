package com.kaze.newage.data.prefs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.kaze.newage.NewAgeApp
import com.kaze.newage.core.ai.AiProfile
import com.kaze.newage.core.ai.AiProfileStore
import com.kaze.newage.core.ai.AiSearch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsPrefsSecretsTest {
    private val context: NewAgeApp
        get() = ApplicationProvider.getApplicationContext()

    private fun legacy() = context.getSharedPreferences("kaze_ui_settings", Context.MODE_PRIVATE)
    private fun secrets() = context.getSharedPreferences(SettingsPrefs.SECRET_PREFS_NAME, Context.MODE_PRIVATE)

    @Before
    fun clearPrefs() {
        legacy().edit().clear().commit()
        secrets().edit().clear().commit()
    }

    @After
    fun cleanup() {
        legacy().edit().clear().commit()
        secrets().edit().clear().commit()
    }

    @Test
    fun `profiles and search credentials migrate while normal settings remain`() {
        legacy().edit()
            .putString("ai_profiles", AiProfileStore.encode(listOf(AiProfile(name = "DeepSeek", apiKey = "sk-model"))))
            .putString("ai_search_provider", "bocha")
            .putString("ai_search_key", "bocha-key")
            .putString("theme_mode", "m3")
            .commit()

        val prefs = SettingsPrefs(context)

        assertEquals("sk-model", prefs.aiProfiles.value.single().apiKey)
        assertEquals("bocha-key", prefs.searchKeyFor("bocha"))
        assertFalse(legacy().contains("ai_profiles"))
        assertFalse(legacy().contains("ai_search_key"))
        assertTrue(secrets().contains("ai_profiles"))
        assertTrue(secrets().contains(AiSearch.Provider.keySlot("bocha")))
        assertEquals("m3", legacy().getString("theme_mode", null))
        assertEquals("bocha", legacy().getString("ai_search_provider", null))
    }

    @Test
    fun `legacy search key only migrates to selected provider`() {
        legacy().edit()
            .putString("ai_search_provider", "tavily")
            .putString("ai_search_key", "tavily-key")
            .commit()

        val prefs = SettingsPrefs(context)

        assertEquals("tavily-key", prefs.searchKeyFor("tavily"))
        assertEquals("", prefs.searchKeyFor("bocha"))
        assertFalse(legacy().contains("ai_search_key"))
        assertFalse(secrets().contains(AiSearch.Provider.keySlot("bocha")))
    }

    @Test
    fun `legacy standalone api key is migrated into profile and removed from ordinary prefs`() {
        legacy().edit()
            .putString("ai_api_key", "sk-legacy")
            .putString("ai_base_url", "https://api.deepseek.com")
            .putString("theme_mode", "m3")
            .commit()

        val prefs = SettingsPrefs(context)

        assertEquals("sk-legacy", prefs.aiProfiles.value.single().apiKey)
        assertFalse(legacy().contains("ai_api_key"))
        assertTrue(secrets().contains("ai_profiles"))
        assertEquals("m3", legacy().getString("theme_mode", null))
    }

    @Test
    fun `corrupt profile json is retained for recovery after migration`() {
        legacy().edit().putString("ai_profiles", "{broken-json").commit()

        val prefs = SettingsPrefs(context)

        assertTrue(prefs.aiProfilesCorrupt.value)
        assertEquals("{broken-json", secrets().getString("ai_profiles", null))
        assertEquals("{broken-json", secrets().getString("ai_profiles_corrupt_backup", null))
        assertFalse(legacy().contains("ai_profiles"))
    }
}
