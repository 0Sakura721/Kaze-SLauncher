package com.kaze.newage.data.prefs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `SettingsPrefs` 的两个**数据安全相关**的纯逻辑：
 * 凭据迁移（旧明文 → 加密隔离存储）与背景图采样率。
 *
 * 这两处出错都很难被用户察觉：
 *  - 迁移少拷一个 key → 用户的 API Key 静默消失，要重新填；
 *  - 采样率算小 → 背景图先丢分辨率再被放大，直接糊掉（用户以为是图片质量问题）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-xhdpi")
class SettingsPrefsMigrationTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun prefs(name: String) = ctx.getSharedPreferences(name, Context.MODE_PRIVATE)

    // ══════════ 凭据迁移 ══════════

    @Test
    fun `迁移把旧文件里的 ai_profiles 拷到新文件`() {
        val secret = prefs("test_secret_a")
        val legacy = prefs("test_legacy_a")
        legacy.edit().putString("ai_profiles", "ENCRYPTED_BLOB").commit()

        SettingsPrefs.migrateAiSecrets(secret, legacy)

        assertEquals("ENCRYPTED_BLOB", secret.getString("ai_profiles", null))
    }

    @Test
    fun `迁移成功后旧文件里的 key 被清掉（不留明文备份）`() {
        val secret = prefs("test_secret_b")
        val legacy = prefs("test_legacy_b")
        legacy.edit().putString("ai_profiles", "BLOB").commit()

        SettingsPrefs.migrateAiSecrets(secret, legacy)

        assertTrue("旧文件不该再留着 ai_profiles", !legacy.contains("ai_profiles"))
    }

    @Test
    fun `新文件已有该 key 时不覆盖（幂等）`() {
        val secret = prefs("test_secret_c")
        val legacy = prefs("test_legacy_c")
        secret.edit().putString("ai_profiles", "NEWER").commit()
        legacy.edit().putString("ai_profiles", "OLDER").commit()

        SettingsPrefs.migrateAiSecrets(secret, legacy)

        assertEquals("已有值不该被旧值覆盖", "NEWER", secret.getString("ai_profiles", null))
    }

    @Test
    fun `重复调用安全（第二次什么都不做）`() {
        val secret = prefs("test_secret_d")
        val legacy = prefs("test_legacy_d")
        legacy.edit().putString("ai_profiles", "BLOB").commit()

        SettingsPrefs.migrateAiSecrets(secret, legacy)
        SettingsPrefs.migrateAiSecrets(secret, legacy)

        assertEquals("BLOB", secret.getString("ai_profiles", null))
        assertTrue(!legacy.contains("ai_profiles"))
    }

    @Test
    fun `旧文件本来就空时不做任何事`() {
        val secret = prefs("test_secret_e")
        val legacy = prefs("test_legacy_e")

        SettingsPrefs.migrateAiSecrets(secret, legacy)

        assertTrue("不该产生任何 key", secret.all.isEmpty() || !secret.contains("ai_profiles"))
    }

    @Test
    fun `旧版损坏备份的 key 也一并迁移`() {
        val secret = prefs("test_secret_f")
        val legacy = prefs("test_legacy_f")
        // 旧版本的备份 key 名（迁移后应改名成 CORRUPT_BACKUP_KEY）
        legacy.edit().putString("ai_profiles_corrupt_backup", "RAW_CORRUPT").commit()

        SettingsPrefs.migrateAiSecrets(secret, legacy)

        assertEquals(
            "损坏备份应按新 key 名搬过去",
            "RAW_CORRUPT",
            secret.getString(SettingsPrefs.CORRUPT_BACKUP_KEY, null),
        )
        assertTrue("旧 key 应被清掉", !legacy.contains("ai_profiles_corrupt_backup"))
    }

    @Test
    fun `新文件已有损坏备份时不覆盖`() {
        val secret = prefs("test_secret_g")
        val legacy = prefs("test_legacy_g")
        secret.edit().putString(SettingsPrefs.CORRUPT_BACKUP_KEY, "NEW_RAW").commit()
        legacy.edit().putString("ai_profiles_corrupt_backup", "OLD_RAW").commit()

        SettingsPrefs.migrateAiSecrets(secret, legacy)

        assertEquals("NEW_RAW", secret.getString(SettingsPrefs.CORRUPT_BACKUP_KEY, null))
    }

    @Test
    fun `两个 key 同时存在时都迁移`() {
        val secret = prefs("test_secret_h")
        val legacy = prefs("test_legacy_h")
        legacy.edit()
            .putString("ai_profiles", "BLOB")
            .putString("ai_profiles_corrupt_backup", "RAW")
            .commit()

        SettingsPrefs.migrateAiSecrets(secret, legacy)

        assertEquals("BLOB", secret.getString("ai_profiles", null))
        assertEquals("RAW", secret.getString(SettingsPrefs.CORRUPT_BACKUP_KEY, null))
        assertTrue(legacy.edit().clear().commit())  // 清理干净
    }

    @Test
    fun `值与类型保持原样（不破坏加密串）`() {
        val secret = prefs("test_secret_i")
        val legacy = prefs("test_legacy_i")
        // 加密串里可能有冒号、加号、等号 —— 迁移必须是按值拷贝，不能做任何处理
        val tricky = "enc1:AAA+BBB/CCC==:DDD+EEE/FFF=="
        legacy.edit().putString("ai_profiles", tricky).commit()

        SettingsPrefs.migrateAiSecrets(secret, legacy)

        assertEquals(tricky, secret.getString("ai_profiles", null))
    }

    // ══════════ 背景图采样率 ══════════

    @Test
    fun `小图不需要缩小`() {
        // 800x600 已经小于 target(1600)，采样率 1
        assertEquals(1, SettingsPrefs.sampleSizeFor(800, 600, 1600))
    }

    @Test
    fun `正好 2 倍时采样 2`() {
        assertEquals(2, SettingsPrefs.sampleSizeFor(3200, 1000, 1600))
    }

    @Test
    fun `采样后最长边不小于 target（关键不变量）`() {
        // 这条是源码注释里写明的设计：必须"不小于"，
        // 取小了会先丢分辨率再被放大，背景图直接糊掉。
        val cases = listOf(
            Triple(3200, 2400, 1600),
            Triple(4000, 3000, 1600),
            Triple(6000, 4000, 1600),
            Triple(1601, 1600, 1600),
        )
        for ((w, h, t) in cases) {
            val s = SettingsPrefs.sampleSizeFor(w, h, t)
            val longest = maxOf(w, h) / s
            assertTrue("$w x $h / $s = $longest 不应小于 target $t", longest >= t)
        }
    }

    @Test
    fun `采样率永远是 2 的幂`() {
        for (w in listOf(2000, 3000, 5000, 8000)) {
            val s = SettingsPrefs.sampleSizeFor(w, w, 1600)
            assertTrue("$s 应为 2 的幂", s > 0 && (s and (s - 1)) == 0)
        }
    }

    @Test
    fun `正方形与长条形取相同的判定（看最长边）`() {
        // 10000x100 的最长边是 10000，与 10000x10000 采样率应一致
        assertEquals(
            SettingsPrefs.sampleSizeFor(10000, 10000, 1600),
            SettingsPrefs.sampleSizeFor(10000, 100, 1600),
        )
    }

    @Test
    fun `零或负数尺寸不会死循环`() {
        // while 条件是 maxOf(w,h)/(sample*2) >= target，0 会让条件恒假，安全返回 1
        assertEquals(1, SettingsPrefs.sampleSizeFor(0, 0, 1600))
        assertTrue(SettingsPrefs.sampleSizeFor(-1, -1, 1600) >= 1)
    }

    @Test
    fun `target 为 0 或负数时采样率仍为 1（不除零）`() {
        assertEquals(1, SettingsPrefs.sampleSizeFor(800, 600, 0))
        assertEquals(1, SettingsPrefs.sampleSizeFor(800, 600, -100))
    }
}