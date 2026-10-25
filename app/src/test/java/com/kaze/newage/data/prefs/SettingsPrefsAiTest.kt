package com.kaze.newage.data.prefs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.kaze.newage.core.ai.AiProfile
import com.kaze.newage.core.ai.AiProfileStore
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
 * 说明：Robolectric 没有 AndroidKeyStore，因此测试注入可预测的假加密器验证持久化边界；
 * 真正的 Android Keystore AES-GCM 加解密需要在真机环境验证。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsPrefsAiTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val raw get() = context.getSharedPreferences("kaze_ui_settings", Context.MODE_PRIVATE)
    private val secret get() = context.getSharedPreferences(SettingsPrefs.SECRET_PREFS_NAME, Context.MODE_PRIVATE)
    private val encryptForTest: (String) -> String? = { "enc1:test:$it" }
    private val decryptForTest: (String) -> String? = {
        if (it.startsWith("enc1:test:")) it.removePrefix("enc1:test:") else it.takeIf(String::isNotBlank)
    }
    private fun newPrefs() = SettingsPrefs(context, encryptForTest, decryptForTest)

            @Test
    fun `档案 JSON 损坏时保留原文并置标志`() {
        secret.edit().putString("ai_profiles", "[{\"name\":\"x\",\"apiKey\":\"enc1:broken").apply()

        val prefs = newPrefs()
        assertTrue("必须标记为损坏，界面才能提示", prefs.aiProfilesCorrupt.value)
        assertTrue("界面不能因此打不开", prefs.aiProfiles.value.isEmpty())
        // 原文一个字符都不能少：用户还有机会人工救回里面的 Key
        assertEquals(
            "[{\"name\":\"x\",\"apiKey\":\"enc1:broken",
            secret.getString(SettingsPrefs.CORRUPT_BACKUP_KEY, null),
        )
        // 清除入口：只清转存与标志
        prefs.clearCorruptAiProfiles()
        assertFalse(prefs.aiProfilesCorrupt.value)
        assertFalse(secret.contains("ai_profiles"))
        assertFalse("用户明确清除后，损坏原文转存也应删除", secret.contains(SettingsPrefs.CORRUPT_BACKUP_KEY))
    }

    @Test
    fun `损坏后重新保存档案会撤掉提示`() {
        secret.edit().putString("ai_profiles", "{oops").apply()
        val prefs = newPrefs()
        assertTrue(prefs.aiProfilesCorrupt.value)

        prefs.saveAiProfile(AiProfile(name = "DeepSeek", apiKey = "k"))
        assertFalse("用户已重新填好配置", prefs.aiProfilesCorrupt.value)
        assertEquals(1, prefs.aiProfiles.value.size)
        // 重新读回仍是合法配置（含 Key）
        assertEquals("k", newPrefs().aiProfiles.value.first().apiKey)
    }

    @Test
    fun `正常档案不会误判为损坏`() {
        val prefs = newPrefs()
        prefs.saveAiProfile(AiProfile(name = "本地", baseUrl = "http://127.0.0.1:8080", apiKey = "k"))
        val reloaded = newPrefs()
        assertFalse(reloaded.aiProfilesCorrupt.value)
        assertEquals("本地", reloaded.aiProfiles.value.first().name)
        assertEquals("k", reloaded.aiProfiles.value.first().apiKey)
    }

    @Test
    fun aiCredentialsMigrateToSecretPrefsButOrdinarySettingsRemain() {
        val profilesJson = AiProfileStore.encode(listOf(AiProfile(name = "DeepSeek", apiKey = "legacy-model-key")))
        raw.edit()
            .putString("ai_profiles", profilesJson)
            .putString("theme_mode", "m3")
            .commit()

        val prefs = newPrefs()

        assertEquals("legacy-model-key", prefs.aiProfiles.value.single().apiKey)
        assertTrue(secret.getString("ai_profiles", "").orEmpty().contains("enc1:test:legacy-model-key"))
        assertFalse(raw.contains("ai_profiles"))
        assertEquals("m3", raw.getString("theme_mode", null))
    }

    @Test
    fun encryptionFailureNeverPersistsPlaintextCredentials() {
        val noKeystorePrefs = SettingsPrefs(
            context,
            encryptAiSecret = { null },
            decryptAiSecret = { stored -> stored.takeIf(String::isNotBlank) },
        )

        assertFalse(noKeystorePrefs.saveAiProfile(AiProfile(name = "不会保存", apiKey = "never-write-this")))
        assertTrue(noKeystorePrefs.aiSecretStorageError.value)
        assertFalse(secret.getString("ai_profiles", "").orEmpty().contains("never-write-this"))
    }

}
