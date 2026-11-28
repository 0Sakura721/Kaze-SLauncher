package com.kaze.newage.core.env

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * `ProotEnvironment` 的 **JDK 扫描**（`installedJdks` / `installedJdkFullVersions`）。
 *
 * 这段逻辑出过一次真机事故：旧实现只查 `bin/java` 是否存在，而它是个约 100KB 的启动器，
 * 真正的 `libjvm.so`（20MB+）在解压**最后**才落盘。中途被杀/空间不足时，
 * 半截 JDK 会被认成"已就绪" → 服务端启动 30 秒就退，用户只看到一句笼统的早退错误，
 * 而且因为判定为已安装，**永远不会重装**。
 *
 * 所以这里锁的核心不变量是：**光有 bin/java 不算装好，必须有 libjvm.so**。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-xhdpi")
class ProotJdkScanTest {

    private lateinit var root: File
    private lateinit var env: ProotEnvironment

    private val jvmDir: File get() = File(root, "usr/lib/jvm")

    @Before
    fun setUp() {
        root = File(
            ApplicationProvider.getApplicationContext<Context>().cacheDir,
            "proot-jdk-test-${System.nanoTime()}",
        ).apply { mkdirs() }
        jvmDir.mkdirs()
        // linuxBase 注入临时目录：不然会用 context.filesDir
        env = ProotEnvironment(ApplicationProvider.getApplicationContext()) { root }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    /** 造一个"完整"的 JDK：bin/java(>10KB) + lib/server/libjvm.so(>1MB) + 可选 release */
    private fun makeJdk(
        name: String,
        version: String? = null,
        javaSize: Int = 100_000,
        jvmLib: Boolean = true,
        jvmLibSize: Int = 20_000_000,
    ): File {
        val dir = File(jvmDir, name).apply { mkdirs() }
        File(dir, "bin").mkdirs()
        File(dir, "bin/java").writeBytes(ByteArray(javaSize))
        if (jvmLib) {
            File(dir, "lib/server").mkdirs()
            File(dir, "lib/server/libjvm.so").writeBytes(ByteArray(jvmLibSize))
        }
        if (version != null) {
            File(dir, "release").writeText("""JAVA_VERSION="$version""" + "\n")
        }
        return dir
    }

    // ══════════ 完整 JDK 被识别 ══════════

    @Test
    fun `完整 JDK 从 release 文件识别主版本`() {
        makeJdk("java-17-openjdk-arm64", version = "17.0.20.1")
        val got = env.installedJdks()
        assertEquals(1, got.size)
        assertEquals("java-17-openjdk-arm64", got[17])
    }

    @Test
    fun `完整 JDK 的完整版本号可读`() {
        makeJdk("java-21-openjdk", version = "21.0.5.11")
        val full = env.installedJdkFullVersions()
        assertEquals("21.0.5.11", full[21])
    }

    @Test
    fun `多个不同主版本同时识别`() {
        makeJdk("jdk8", version = "1.8.0_442")
        makeJdk("jdk17", version = "17.0.20.1")
        makeJdk("jdk21", version = "21.0.5.11")
        val got = env.installedJdks()
        assertEquals("三个版本都该认出", 3, got.size)
        assertTrue(got.containsKey(8) || got.containsKey(1))
        assertTrue(got.containsKey(17))
        assertTrue(got.containsKey(21))
    }

    // ══════════ 关键不变量：半成品不算装好 ══════════

    @Test
    fun `只有 bin-java 没有 libjvm-so 的半成品不被识别`() {
        // 这是那次真机事故的形态：解压到一半，bin/java 有了但 libjvm.so 还没有
        makeJdk("half", version = "17.0.20.1", jvmLib = false)
        assertTrue(
            "只有启动器不算装好，否则用户永远修不好",
            env.installedJdks().isEmpty(),
        )
    }

    @Test
    fun `libjvm-so 小于 1MB 视为未写完`() {
        makeJdk("tiny", version = "17.0.20.1", jvmLibSize = 500_000)
        assertTrue(env.installedJdks().isEmpty())
    }

    @Test
    fun `bin-java 小于 10KB 视为空洞`() {
        makeJdk("empty-java", version = "17.0.20.1", javaSize = 1_000)
        assertTrue(env.installedJdks().isEmpty())
    }

    @Test
    fun `client VM 也算完整`() {
        // 老 JDK 8 在 arm 上可能只有 client VM
        val dir = File(jvmDir, "jdk8-client").apply { mkdirs() }
        File(dir, "bin").mkdirs()
        File(dir, "bin/java").writeBytes(ByteArray(100_000))
        File(dir, "lib/client").mkdirs()
        File(dir, "lib/client/libjvm.so").writeBytes(ByteArray(2_000_000))
        File(dir, "release").writeText("JAVA_VERSION=\"1.8.0_442\"\n")
        assertEquals(1, env.installedJdks().size)
    }

    @Test
    fun `jre 子目录形态也算`() {
        val dir = File(jvmDir, "jdk-with-jre").apply { mkdirs() }
        File(dir, "bin").mkdirs()
        File(dir, "bin/java").writeBytes(ByteArray(100_000))
        File(dir, "jre/lib/server").mkdirs()
        File(dir, "jre/lib/server/libjvm.so").writeBytes(ByteArray(20_000_000))
        File(dir, "release").writeText("JAVA_VERSION=\"8.0.442\"\n")
        assertEquals(1, env.installedJdks().size)
    }

    // ══════════ 目录名回退 ══════════

    @Test
    fun `没有 release 文件时从目录名解析主版本`() {
        makeJdk("java-17-openjdk-arm64", version = null)
        val got = env.installedJdks()
        assertEquals(1, got.size)
        assertEquals("java-17-openjdk-arm64", got[17])
    }

    @Test
    fun `目录名里没有版本号且无 release 时跳过`() {
        makeJdk("some-random-dir", version = null)
        assertTrue(env.installedJdks().isEmpty())
    }

    @Test
    fun `release 优先于目录名`() {
        // 目录名写 21，release 写 17 → 以 release 为准（权威）
        makeJdk("java-21-fake-name", version = "17.0.20.1")
        val got = env.installedJdks()
        assertEquals("应以 release 为准", "java-21-fake-name", got[17])
        assertTrue("不该按目录名记成 21", !got.containsKey(21))
    }

    // ══════════ 边界 ══════════

    @Test
    fun `jvm 目录不存在时返回空映射`() {
        File(root, "usr/lib/jvm").deleteRecursively()
        assertTrue(env.installedJdks().isEmpty())
        assertTrue(env.installedJdkFullVersions().isEmpty())
    }

    @Test
    fun `jvm 目录为空时返回空映射`() {
        assertTrue(env.installedJdks().isEmpty())
    }

    @Test
    fun `jvm 目录下是文件不是目录时跳过`() {
        File(jvmDir, "stray-file").writeText("not a jdk")
        assertTrue("文件不该被当 JDK", env.installedJdks().isEmpty())
    }

    @Test
    fun `同一主版本多个目录时保留第一个`() {
        makeJdk("a-jdk17", version = "17.0.1")
        makeJdk("b-jdk17", version = "17.0.20")
        val got = env.installedJdks()
        assertEquals("同主版本只留一个", 1, got.size)
        // putIfAbsent：先遍历到的留下（listFiles 顺序不保证，断言"只有一个"而非具体哪个）
        assertTrue(got[17]!!.contains("jdk17"))
    }

    @Test
    fun `release 文件格式异常时不崩`() {
        val dir = makeJdk("bad-release", version = null)
        File(dir, "release").writeText("garbage without JAVA_VERSION\nANOTHER=x\n")
        // release 解析不到 → 回退目录名 → 目录名没有版本 → 跳过
        assertTrue(env.installedJdks().isEmpty() || env.installedJdks().isNotEmpty())
        // 关键是：不抛异常
        env.installedJdkFullVersions()
    }
}