package com.kaze.newage.core.update

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 更新判定测试（以**发布日期**为准，不再比较版本号）。
 *
 * 旧版本号比较（isNewer）为 fix 后缀/预发布语义叠了大量特判，全部退场；
 * 现在的规则只有一条：候选 Release 的发布日期**严格晚于**当前运行 Release 的发布日期。
 * 旧测试的场景（fix 修订、预发布转正、装着 fix 不被引导装回原版）在日期语义下自然成立，
 * 这里逐条以日期形式保留。
 */
class UpdateCheckerTest {

    private fun release(tag: String, date: String, prerelease: Boolean = false) = UpdateChecker.ReleaseInfo(
        tag = tag,
        name = tag,
        body = "",
        apkUrl = "https://example.com/$tag.apk",
        publishedAt = Instant.parse(date).toEpochMilli(),
        publishedAtText = date.take(10),
        prerelease = prerelease,
    )

    private val v040 = "2026-09-01T00:00:00Z"
    private val v041 = "2026-10-01T00:00:00Z"
    private val v042 = "2026-10-05T00:00:00Z"

    @Test
    fun `候选发布日期晚于当前版本算更新`() {
        val releases = listOf(release("v0.4.0", v040), release("v0.4.1", v041))
        val up = UpdateChecker.selectUpdate(releases, "preview", "0.4.0")
        assertEquals("v0.4.1", up?.tag)
    }

    @Test
    fun `候选发布日期更早不提示`() {
        // 装着日期更新的修订（fix），不该被引导装回日期更早的原版
        val releases = listOf(release("v0.3.3", v040), release("v0.3.3-fix", v041))
        assertNull(UpdateChecker.selectUpdate(releases, "preview", "0.3.3-fix"))
    }

    @Test
    fun `同日重发不提示`() {
        // 同 tag 删了重发、发布日期刷新成同一天：基线与候选同日 → 不算更新
        val releases = listOf(release("v0.4.1", v041))
        assertNull(UpdateChecker.selectUpdate(releases, "preview", "0.4.1"))
    }

    @Test
    fun `当前版本匹配带 v 前缀与大小写无关`() {
        val releases = listOf(release("v0.4.0", v040), release("V0.4.1", v041))
        val up = UpdateChecker.selectUpdate(releases, "preview", "0.4.0")
        assertEquals("V0.4.1", up?.tag)
    }

    @Test
    fun `当前版本不在列表时用安装时间兜底`() {
        val releases = listOf(release("v0.5.0", v042))
        val oct1 = Instant.parse(v040).toEpochMilli()
        val oct6 = Instant.parse("2026-10-06T00:00:00Z").toEpochMilli()
        // 安装时间早于候选发布日期 → 有更新
        assertEquals("v0.5.0", UpdateChecker.selectUpdate(releases, "preview", "unknown-build", oct1)?.tag)
        // 安装时间晚于候选发布日期（本地构建比所有 Release 都新）→ 不提示
        assertNull(UpdateChecker.selectUpdate(releases, "preview", "unknown-build", oct6))
    }

    @Test
    fun `stable 通道过滤预发布`() {
        val releases = listOf(
            release("v0.5.0-beta.1", v042, prerelease = true),
            release("v0.4.1", v041),
        )
        // stable：预发布即使日期更新也不作为候选
        assertEquals("v0.4.1", UpdateChecker.selectUpdate(releases, "stable", "0.4.0")?.tag)
        // preview：预发布日期更新 → 作为候选
        assertEquals("v0.5.0-beta.1", UpdateChecker.selectUpdate(releases, "preview", "0.4.0")?.tag)
    }

    @Test
    fun `基线日期按当前运行的 Release 计算`() {
        // 用户装着预发布（比最新 stable 新）：stable 通道下不应被引导"降级"
        val releases = listOf(
            release("v0.4.1", v041),
            release("v0.5.0-preview", v042, prerelease = true),
        )
        assertNull(UpdateChecker.selectUpdate(releases, "stable", "0.5.0-preview"))
    }

    @Test
    fun `日期解析失败的候选不选中`() {
        val releases = listOf(
            release("v0.4.0", v040),
            release("v0.4.1", v041).copy(publishedAt = 0), // 日期解析失败的极端情况
        )
        assertEquals("v0.4.0", UpdateChecker.selectUpdate(releases, "preview", "0.4.0")?.tag)
    }

    // ── 日期解析（published_at → epoch）──

    @Test
    fun `GitHub ISO 时间解析`() {
        val ms = UpdateChecker.parseDate("2026-10-02T18:22:27Z")
        assertTrue(ms != null && ms > 0)
        // 带毫秒的变体也能解析
        assertTrue(UpdateChecker.parseDate("2026-10-02T18:22:27.123Z") != null)
        assertNull(UpdateChecker.parseDate(""))
        assertNull(UpdateChecker.parseDate("not-a-date"))
    }

    @Test
    fun `发布日期格式化为 yyyy-MM-dd`() {
        val ms = UpdateChecker.parseDate("2026-10-02T18:22:27Z")!!
        val text = UpdateChecker.formatDate(ms)
        assertTrue(text.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
    }

    @Test
    fun `sameRelease 归一匹配`() {
        assertTrue(UpdateChecker.sameRelease("v0.4.0-fix", "0.4.0-FIX"))
        assertFalse(UpdateChecker.sameRelease("v0.4.0", "0.4.1"))
        // 空版本号（本地构建）永不匹配
        assertFalse(UpdateChecker.sameRelease("v0.4.0", ""))
    }

    // ── digest 解析（更新包完整性校验的入口）──

    private val hex64 = "95959c0de229b83b1cf4d673c3ee4579cf7a16d6f6c70e3c4756c7bc6d78b32b"

    @Test
    fun `解析合法 sha256 digest`() {
        assertEquals(hex64, UpdateChecker.parseSha256("sha256:$hex64"))
        // 前后空白与大小写都应容错（GitHub 目前固定小写，但不依赖它）
        assertEquals(hex64, UpdateChecker.parseSha256("  sha256:$hex64  "))
        assertEquals(hex64, UpdateChecker.parseSha256("SHA256:${hex64.uppercase()}"))
    }

    @Test
    fun `缺失或格式异常时返回 null`() {
        // 空 = 发布方没给（调用方据此退回"魔数 + 体积"校验）
        assertNull(UpdateChecker.parseSha256(""))
        assertNull(UpdateChecker.parseSha256("   "))
        // 非 sha256 算法不认
        assertNull(UpdateChecker.parseSha256("sha512:$hex64"))
        // 长度不足 / 超长
        assertNull(UpdateChecker.parseSha256("sha256:${hex64.dropLast(1)}"))
        assertNull(UpdateChecker.parseSha256("sha256:${hex64}f"))
        // 非十六进制字符
        assertNull(UpdateChecker.parseSha256("sha256:" + "z".repeat(64)))
        // 只有前缀没有值
        assertNull(UpdateChecker.parseSha256("sha256:"))
    }

    @Test
    fun `脏数据不应抛异常`() {
        assertNull(UpdateChecker.parseSha256("sha256"))
        assertNull(UpdateChecker.parseSha256(":"))
        assertNull(UpdateChecker.parseSha256("null"))
    }
}
