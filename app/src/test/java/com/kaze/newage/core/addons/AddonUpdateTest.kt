package com.kaze.newage.core.addons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 插件/模组更新判定测试。
 *
 * 这块逻辑判错的后果很具体：
 *  - 有新版报"已是最新" → 用户永远升不了；
 *  - 没新版报"有更新" → 白下载，还可能把好好的插件换掉；
 *  - 把**降级**当成更新 → 用户以为升级，实际装回旧版（beta → 稳定版号更小就是这个场景）。
 * 所以三个方向都测。
 */
class AddonUpdateTest {

    private fun file(name: String, primary: Boolean = true) =
        ModrinthFile(filename = name, url = "https://cdn/$name", primary = primary)

    private fun ver(id: String, num: String, fileName: String = "x-$id.jar") = ModrinthVersion(
        id = id,
        project_id = "P1",
        version_number = num,
        files = listOf(file(fileName)),
    )

    private fun entry(versionId: String, num: String = "1.0") = AddonRegistry.Entry(
        fileName = "x.jar",
        projectId = "P1",
        versionId = versionId,
        versionNumber = num,
    )

    // ══════════ 挑下一个版本 ══════════

    @Test
    fun `当前已是最新时返回 null`() {
        val versions = listOf(ver("v3", "3.0"), ver("v2", "2.0"), ver("v1", "1.0"))
        assertNull(AddonUpdate.pickNext(versions, "v3"))
    }

    @Test
    fun `当前是旧版时给出最新的那个`() {
        val versions = listOf(ver("v3", "3.0"), ver("v2", "2.0"), ver("v1", "1.0"))
        assertEquals("v3", AddonUpdate.pickNext(versions, "v1")!!.id)
    }

    @Test
    fun `不会把降级当成更新`() {
        // 场景：用户装的是 beta（v2b），上游列表里稳定版（v3s）在前、beta 在后。
        // 当前在列表中间 → 应该给列表第一个（更新），而不是给"比它旧"的。
        val versions = listOf(ver("v3s", "3.0"), ver("v2b", "2.0-beta"), ver("v1", "1.0"))
        val next = AddonUpdate.pickNext(versions, "v2b")
        assertEquals("v3s", next!!.id)
    }

    @Test
    fun `当前版本已被上游删除时取最新`() {
        val versions = listOf(ver("v3", "3.0"), ver("v2", "2.0"))
        assertEquals("v3", AddonUpdate.pickNext(versions, "gone")!!.id)
    }

    @Test
    fun `没记录版本号时取最新`() {
        val versions = listOf(ver("v3", "3.0"), ver("v2", "2.0"))
        assertEquals("v3", AddonUpdate.pickNext(versions, "")!!.id)
    }

    @Test
    fun `版本列表为空时返回 null（不误报有更新）`() {
        assertNull(AddonUpdate.pickNext(emptyList(), "v3"))
        assertNull(AddonUpdate.pickNext(emptyList(), ""))
    }

    // ══════════ 主文件选择 ══════════

    @Test
    fun `优先取 primary 文件`() {
        val v = ModrinthVersion(
            id = "v1", project_id = "P1", version_number = "1.0",
            files = listOf(file("a.jar", primary = false), file("b.jar", primary = true)),
        )
        assertEquals("b.jar", AddonUpdate.primaryFile(v)!!.filename)
    }

    @Test
    fun `没有 primary 时取第一个`() {
        val v = ModrinthVersion(
            id = "v1", project_id = "P1", version_number = "1.0",
            files = listOf(file("a.jar", primary = false), file("b.jar", primary = false)),
        )
        assertEquals("a.jar", AddonUpdate.primaryFile(v)!!.filename)
    }

    @Test
    fun `没有文件时返回 null`() {
        val v = ModrinthVersion(id = "v1", project_id = "P1", version_number = "1.0", files = emptyList())
        assertNull(AddonUpdate.primaryFile(v))
    }

    // ══════════ 单个判定 ══════════

    @Test
    fun `认不出来源时给出 Unknown 而不是谎报最新`() {
        val v = AddonUpdate.judge("x.jar", null, listOf(ver("v3", "3.0")))
        assertTrue(v is AddonUpdate.Verdict.Unknown)
        assertTrue((v as AddonUpdate.Verdict.Unknown).reason.isNotBlank())
    }

    @Test
    fun `上游没有兼容版本时给出 NoCompatible`() {
        val v = AddonUpdate.judge("x.jar", entry("v1"), emptyList())
        assertTrue(v is AddonUpdate.Verdict.NoCompatible)
        assertEquals("1.0", (v as AddonUpdate.Verdict.NoCompatible).version)
    }

    @Test
    fun `有新版时给出 Update 且带上要下载的文件`() {
        val versions = listOf(ver("v2", "2.0", "x-2.0.jar"), ver("v1", "1.0", "x-1.0.jar"))
        val v = AddonUpdate.judge("x.jar", entry("v1", "1.0"), versions)
        assertTrue(v is AddonUpdate.Verdict.Update)
        v as AddonUpdate.Verdict.Update
        assertEquals("v2", v.next.id)
        assertEquals("x-2.0.jar", v.nextFile.filename)
        assertEquals("1.0", v.currentVersion)
    }

    @Test
    fun `已是最新时给出 Latest`() {
        val versions = listOf(ver("v2", "2.0"), ver("v1", "1.0"))
        val v = AddonUpdate.judge("x.jar", entry("v2", "2.0"), versions)
        assertTrue(v is AddonUpdate.Verdict.Latest)
        assertEquals("2.0", (v as AddonUpdate.Verdict.Latest).version)
    }

    @Test
    fun `版本有文件缺失时降级成 NoCompatible 而不是崩溃`() {
        val broken = ModrinthVersion(id = "v2", project_id = "P1", version_number = "2.0", files = emptyList())
        val v = AddonUpdate.judge("x.jar", entry("v1"), listOf(broken))
        assertTrue(v is AddonUpdate.Verdict.NoCompatible)
    }

    // ══════════ 汇总 ══════════

    @Test
    fun `汇总会分开计数（认不出来源的不能被算成已检查）`() {
        val versions = listOf(ver("v2", "2.0"), ver("v1", "1.0"))
        val verdicts = listOf(
            AddonUpdate.judge("a.jar", entry("v1", "1.0"), versions),      // Update
            AddonUpdate.judge("b.jar", entry("v2", "2.0"), versions),      // Latest
            AddonUpdate.judge("c.jar", null, versions),                    // Unknown
            AddonUpdate.judge("d.jar", entry("v9"), emptyList()),          // NoCompatible
        )
        val s = AddonUpdate.summarize(verdicts)
        assertEquals(1, s.updates.size)
        assertEquals(1, s.latest.size)
        assertEquals(1, s.unknown.size)
        assertEquals(1, s.noCompatible.size)
        assertEquals(4, s.total)
        assertTrue(s.hasAny)
    }

    @Test
    fun `全都已是最新时 hasAny 为 false`() {
        val versions = listOf(ver("v1", "1.0"))
        val s = AddonUpdate.summarize(listOf(AddonUpdate.judge("a.jar", entry("v1"), versions)))
        assertTrue(!s.hasAny)
        assertNotNull(s.latest.firstOrNull())
    }
}