package com.kaze.newage.core.server

import com.kaze.newage.core.download.CoreBuild
import com.kaze.newage.data.model.CoreType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 核心更新判定测试。
 *
 * 这块逻辑决定「点一下检查更新」给出什么结论 —— 判错了的后果很具体：
 *  - 明明有新版却报"已是最新" → 用户永远升不了，还以为上游没更新；
 *  - 没有新版却报"有更新" → 用户白下载几十 MB，还可能把好好的核心换掉。
 * 所以两个方向都要测。
 */
class CoreUpdateTest {

    private fun b(id: String, name: String = "build $id") = CoreBuild(id = id, name = name)

    // ══════════ 是否支持检查 ══════════

    @Test
    fun `手动导入的核心不支持检查`() {
        assertFalse("CUSTOM 没有上游可对比", CoreUpdate.supports(CoreType.CUSTOM))
    }

    @Test
    fun `内置核心都支持检查`() {
        for (t in CoreType.entries) {
            if (t == CoreType.CUSTOM) continue
            assertTrue("$t 应当支持检查更新", CoreUpdate.supports(t))
        }
    }

    // ══════════ 构建列表比对（Paper / Fabric）══════════

    @Test
    fun `当前已是最新构建时返回 null`() {
        // 列表新→旧，当前就在首位
        val builds = listOf(b("196"), b("195"), b("194"))
        assertNull(CoreUpdate.pickNewer(builds, "196"))
    }

    @Test
    fun `当前是旧构建时给出最新那个`() {
        val builds = listOf(b("196"), b("195"), b("194"))
        val newer = CoreUpdate.pickNewer(builds, "194")
        assertNotNull(newer)
        assertEquals("196", newer!!.id)
    }

    @Test
    fun `构建号比较不区分大小写`() {
        // 用带字母的构建号才能真的测到大小写：纯数字（"196"）没有大小写之分，
        // 原来那条用例写 `"196".lowercase()` 其实什么都没测（CI 抓到的就是这个）。
        val builds = listOf(b("Build-196"), b("Build-195"))
        // 当前装的写成了小写 → 仍应认出"已经在最新"，不该误报有更新
        assertNull(
            "大小写不同但同一构建，应判为已是最新",
            CoreUpdate.pickNewer(builds, "build-196"),
        )
        // 同理，旧构建的小写写法也要能认出来
        assertEquals("Build-196", CoreUpdate.pickNewer(builds, "BUILD-195")!!.id)
    }

    @Test
    fun `当前构建不在列表里时取最新`() {
        // 场景：上游清理了老构建 / 用户手动换过 jar。保守给出最新，让用户自己决定
        val builds = listOf(b("196"), b("195"))
        val newer = CoreUpdate.pickNewer(builds, "100")
        assertEquals("196", newer!!.id)
    }

    @Test
    fun `没记录构建号时取最新（旧实例）`() {
        val builds = listOf(b("196"), b("195"))
        assertEquals("196", CoreUpdate.pickNewer(builds, "")!!.id)
    }

    @Test
    fun `构建列表为空时返回 null（不误报有更新）`() {
        assertNull(CoreUpdate.pickNewer(emptyList(), "196"))
        assertNull(CoreUpdate.pickNewer(emptyList(), ""))
    }

    // ══════════ 地址比对（Purpur / Spigot / Vanilla / Forge）══════════

    @Test
    fun `地址一致时判定已是最新`() {
        val url = "https://example.com/purpur-1.20.1-2060.jar"
        val r = CoreUpdate.compareByUrl(url, url, "purpur-1.20.1-2060.jar", "purpur.jar", null)
        assertTrue(r is CoreUpdate.Result.UpToDate)
        assertEquals("purpur-1.20.1-2060.jar", (r as CoreUpdate.Result.UpToDate).current)
    }

    @Test
    fun `地址变化时判定有新版本`() {
        val r = CoreUpdate.compareByUrl(
            currentUrl = "https://example.com/purpur-1.20.1-2050.jar",
            latestUrl = "https://example.com/purpur-1.20.1-2060.jar",
            label = "purpur-1.20.1-2060.jar",
            fileName = "purpur.jar",
            sha1 = "abc",
        )
        assertTrue(r is CoreUpdate.Result.Available)
        r as CoreUpdate.Result.Available
        assertEquals("purpur-1.20.1-2060.jar", r.label)
        assertEquals("abc", r.sha1)
        assertEquals("旧构建", r.current)
    }

    @Test
    fun `旧实例没记录地址时提示可重新下载而不是谎报已最新`() {
        // 谎报"已是最新"会让用户永远升不了；这里如实说"未知"并给出下载入口
        val r = CoreUpdate.compareByUrl(
            currentUrl = "",
            latestUrl = "https://example.com/paper-1.20.1.jar",
            label = "paper-1.20.1.jar",
            fileName = "paper.jar",
            sha1 = null,
        )
        assertTrue(r is CoreUpdate.Result.Available)
        r as CoreUpdate.Result.Available
        assertTrue("current 要说明是未知", r.current.contains("未知"))
        assertEquals("https://example.com/paper-1.20.1.jar", r.downloadUrl)
    }

    @Test
    fun `上游没给地址时判定为不支持而不是有新版本`() {
        // 否则界面会出现一个"有更新"但没有下载地址的卡片，点下去必然失败
        val r = CoreUpdate.compareByUrl(
            currentUrl = "https://old/x.jar",
            latestUrl = "",
            label = "x.jar",
            fileName = "x.jar",
            sha1 = null,
        )
        assertTrue(r is CoreUpdate.Result.Unsupported)
    }

    @Test
    fun `地址比对会带上构建 id（便于按确切构建下载）`() {
        val r = CoreUpdate.compareByUrl(
            currentUrl = "a",
            latestUrl = "b",
            label = "l",
            fileName = "f.jar",
            sha1 = null,
            buildId = "2060",
        )
        assertEquals("2060", (r as CoreUpdate.Result.Available).buildId)
    }
}