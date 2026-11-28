package com.kaze.newage.core.java

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.kaze.newage.core.env.ProotEnvironment
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * `RootfsJavaManager.isGzipTar` —— 下载内容的**魔数**校验。
 *
 * 存在的理由：下载链路会经过多个镜像，返回一个 200 + HTML 错误页是常态
 * （"文件不存在"、"服务维护中"都会被包成 200）。不校验就直接丢给 tar，
 * 报错会是 `tar: not in gzip format` —— 用户完全看不出是网络问题。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN-w411dp-h891dp-xhdpi")
class JavaArchiveMagicTest {

    private lateinit var root: File
    private lateinit var mgr: RootfsJavaManager

    @Before
    fun setUp() {
        root = File(
            ApplicationProvider.getApplicationContext<Context>().cacheDir,
            "java-magic-${System.nanoTime()}",
        ).apply { mkdirs() }
        val env = ProotEnvironment(ApplicationProvider.getApplicationContext()) { root }
        mgr = RootfsJavaManager(env)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun fileOf(bytes: ByteArray, name: String = "x.tar.gz"): File =
        File(root, name).apply { writeBytes(bytes) }

    // ══════════ 合法 gzip ══════════

    @Test
    fun `gzip 魔数 1f8b 被识别`() {
        assertTrue(mgr.isGzipTar(fileOf(byteArrayOf(0x1f, 0x8b.toByte(), 0x08, 0x00))))
    }

    @Test
    fun `只有两个魔数字节也算（最小合法判定）`() {
        assertTrue(mgr.isGzipTar(fileOf(byteArrayOf(0x1f, 0x8b.toByte()))))
    }

    @Test
    fun `魔数之后的内容不影响判定`() {
        val body = ByteArray(1024) { 0x41 }
        assertTrue(mgr.isGzipTar(fileOf(byteArrayOf(0x1f, 0x8b.toByte()) + body)))
    }

    // ══════════ 无效内容 ══════════

    @Test
    fun `HTML 错误页被拒绝`() {
        val html = "<html><body>404 Not Found</body></html>".toByteArray()
        assertFalse("HTML 是镜像最常见的失败形态", mgr.isGzipTar(fileOf(html)))
    }

    @Test
    fun `JSON 错误响应被拒绝`() {
        assertFalse(mgr.isGzipTar(fileOf("""{"error":"rate limited"}""".toByteArray())))
    }

    @Test
    fun `空文件被拒绝`() {
        assertFalse(mgr.isGzipTar(fileOf(ByteArray(0))))
    }

    @Test
    fun `只有一个字节时被拒绝（不越界读）`() {
        assertFalse(mgr.isGzipTar(fileOf(byteArrayOf(0x1f))))
    }

    @Test
    fun `魔数反了就拒绝`() {
        assertFalse(mgr.isGzipTar(fileOf(byteArrayOf(0x8b.toByte(), 0x1f))))
    }

    @Test
    fun `首字节对但第二字节错被拒绝`() {
        assertFalse(mgr.isGzipTar(fileOf(byteArrayOf(0x1f, 0x00))))
    }

    @Test
    fun `普通 tar（未压缩）被拒绝`() {
        // tar 的魔数在偏移 257 处是 "ustar"，开头是文件名 —— 不是 gzip
        val tar = ByteArray(512).apply { "file.txt".toByteArray().copyInto(this, 0) }
        assertFalse(mgr.isGzipTar(fileOf(tar)))
    }

    @Test
    fun `zip 被拒绝（PK 头）`() {
        assertFalse(mgr.isGzipTar(fileOf(byteArrayOf(0x50, 0x4B, 0x03, 0x04))))
    }

    // ══════════ 边界与异常 ══════════

    @Test
    fun `文件不存在时返回 false 而不是抛异常`() {
        assertFalse(mgr.isGzipTar(File(root, "no-such-file.tar.gz")))
    }

    @Test
    fun `传入目录时返回 false 而不是抛异常`() {
        val dir = File(root, "a-dir").apply { mkdirs() }
        assertFalse(mgr.isGzipTar(dir))
    }

    @Test
    fun `大文件只读开头两个字节（不整文件读入）`() {
        // 100MB 全 0 文件：若实现把整个文件读进内存会 OOM。
        // 这里只验它能返回（结果是 false，因为不是 1f8b），不验耗时。
        val big = File(root, "big.bin")
        java.io.RandomAccessFile(big, "rw").use { it.setLength(100L * 1024 * 1024) }
        assertFalse(mgr.isGzipTar(big))
        big.delete()
    }
}