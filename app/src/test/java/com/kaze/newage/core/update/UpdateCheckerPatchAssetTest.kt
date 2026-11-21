package com.kaze.newage.core.update

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 增量补丁的**资产匹配**（决定"要不要走补丁那条路"）。
 *
 * 这里错一次的代价是"静默降级"：匹配不到就永远下整包（用户看不出来），
 * 或者匹配到不存在的 URL（白白拖慢更新、失败原因还看不出来）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UpdateCheckerPatchAssetTest {

    private fun assets(vararg names: String): JSONArray {
        val arr = JSONArray()
        names.forEach { n ->
            arr.put(
                JSONObject()
                    .put("name", n)
                    .put("browser_download_url", "https://example.com/$n")
                    .put("size", 1000),
            )
        }
        return arr
    }

    @Test
    fun `json 与 zip 配对才算一份可用补丁`() {
        val got = UpdateChecker.pickPatchAssets(
            assets(
                "Kaze-SLauncher-v0.3.1-fix-arm64-v8a.apk",
                "patch-v0.3.0-to-v0.3.1-fix-arm64-v8a.json",
                "patch-v0.3.0-to-v0.3.1-fix-arm64-v8a.zip",
            ),
        )
        assertEquals(1, got.size)
        assertEquals("patch-v0.3.0-to-v0.3.1-fix-arm64-v8a.json", got[0].name)
        assertTrue(got[0].jsonUrl.endsWith(".json"))
        assertTrue(got[0].zipUrl.endsWith(".zip"))
    }

    @Test
    fun `只有 json 没有 zip 时不算（否则会去下一个不存在的 URL）`() {
        val got = UpdateChecker.pickPatchAssets(
            assets("patch-v0.3.0-to-v0.3.1-fix-arm64-v8a.json"),
        )
        assertTrue("缺 zip 应整份丢弃", got.isEmpty())
    }

    @Test
    fun `两个 ABI 的补丁都会被收集_该用哪份交给 baseSha256 判断`() {
        val got = UpdateChecker.pickPatchAssets(
            assets(
                "patch-v0.3.0-to-v0.3.1-fix-arm64-v8a.json",
                "patch-v0.3.0-to-v0.3.1-fix-arm64-v8a.zip",
                "patch-v0.3.0-to-v0.3.1-fix-armeabi-v7a.json",
                "patch-v0.3.0-to-v0.3.1-fix-armeabi-v7a.zip",
            ),
        )
        assertEquals("两个 ABI 都应保留，由 baseSha256 决定用哪份", 2, got.size)
    }

    @Test
    fun `多基线多补丁全部收集_由 baseSha256 决定用哪份`() {
        // 发布流程会对最近 N 个历史版本各生成一份补丁（见 release.yml 的 MAX_BASELINES）。
        // 这里确认：全部被收集、不会互相顶掉 —— 「该用哪份」由下载后比对 meta 的
        // baseSha256 决定（UpdateInstaller.tryPatchUpdate 逐个试）。
        val got = UpdateChecker.pickPatchAssets(
            assets(
                "Kaze-SLauncher-v0.11.0-arm64-v8a.apk",
                "patch-v0.10.2-to-v0.11.0-arm64-v8a.json",
                "patch-v0.10.2-to-v0.11.0-arm64-v8a.zip",
                "patch-v0.10.1-to-v0.11.0-arm64-v8a.json",
                "patch-v0.10.1-to-v0.11.0-arm64-v8a.zip",
                "patch-v0.10.0-to-v0.11.0-arm64-v8a.json",
                "patch-v0.10.0-to-v0.11.0-arm64-v8a.zip",
                "patch-v0.10.2-to-v0.11.0-armeabi-v7a.json",
                "patch-v0.10.2-to-v0.11.0-armeabi-v7a.zip",
            ),
        )
        assertEquals("三份 arm64 基线 + 一份 v7a 都要收进来", 4, got.size)
        // 每份都有配对的 zip（缺 zip 的那份要剔除，这里全都配对）
        got.forEach { a ->
            assertTrue("$a 应有 zip", a.zipUrl.endsWith(".zip"))
            assertTrue("$a 应有 json", a.jsonUrl.endsWith(".json"))
        }
        // 不按 ABI 过滤：两个架构都在（该用哪份看本机已装包的 sha256）
        assertTrue(got.any { it.name.contains("arm64-v8a") })
        assertTrue(got.any { it.name.contains("armeabi-v7a") })
    }

    @Test
    fun `多基线里缺 zip 的那份被剔除其余保留`() {
        val got = UpdateChecker.pickPatchAssets(
            assets(
                "patch-v0.10.2-to-v0.11.0-arm64-v8a.json",
                "patch-v0.10.2-to-v0.11.0-arm64-v8a.zip",
                "patch-v0.10.1-to-v0.11.0-arm64-v8a.json",   // 只有 json，没 zip
            ),
        )
        assertEquals(1, got.size)
        assertTrue(got[0].name.contains("v0.10.2-to"))
    }

    @Test
    fun `无关资产不会混进来`() {
        val got = UpdateChecker.pickPatchAssets(
            assets(
                "Kaze-SLauncher-v0.3.1-fix-arm64-v8a.apk",
                "source.zip",
                "patch.json",              // 不带 patch- 前缀
                "notes-patch-x.json",      // 不以此开头
            ),
        )
        assertTrue(got.isEmpty())
    }

    @Test
    fun `空资产列表安全`() {
        assertTrue(UpdateChecker.pickPatchAssets(JSONArray()).isEmpty())
    }
}
