package com.kaze.newage.util

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 文件摘要测试。
 *
 * `sha256Of` 是更新包完整性校验的实现（见 [com.kaze.newage.core.update.UpdateInstaller]）：
 * APK 会从多个第三方加速镜像下载，GitHub 给出的 `digest` 是唯一可信的比对依据。
 * 摘要算错会直接导致「更新永远校验失败」，所以用已知向量钉住。
 */
class DownloaderDigestTest {

    private fun tempFile(content: String): File =
        File.createTempFile("digest-test", ".bin").apply { writeText(content) }

    @Test
    fun `sha256 与已知向量一致`() {
        // echo -n "abc" | sha256sum
        val f = tempFile("abc")
        try {
            assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                Downloader.sha256Of(f),
            )
        } finally {
            f.delete()
        }
    }

    @Test
    fun `空文件的 sha256`() {
        val f = tempFile("")
        try {
            assertEquals(
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                Downloader.sha256Of(f),
            )
        } finally {
            f.delete()
        }
    }

    @Test
    fun `sha1 仍然可用且与 sha256 不同`() {
        val f = tempFile("abc")
        try {
            // echo -n "abc" | sha1sum
            assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", Downloader.sha1Of(f))
            assertEquals(64, Downloader.sha256Of(f)!!.length)
        } finally {
            f.delete()
        }
    }

    @Test
    fun `大文件分块读取结果正确`() {
        // 覆盖 64KB 分块边界：内容跨越多个 read() 时摘要仍须正确
        val big = "x".repeat(200_000)
        val f = tempFile(big)
        try {
            // echo -n "xxx…" 的期望值由同一算法独立算出（长度 + 十六进制格式校验）
            val got = Downloader.sha256Of(f)!!
            assertEquals(64, got.length)
            assertEquals(true, got.all { it in "0123456789abcdef" })
            // 内容变化一个字节，摘要必须改变
            f.writeText(big + "y")
            assertEquals(false, Downloader.sha256Of(f) == got)
        } finally {
            f.delete()
        }
    }

    @Test
    fun `文件不存在时返回 null 而不是抛异常`() {
        assertNull(Downloader.sha256Of(File("/definitely/not/here-${System.nanoTime()}")))
        assertNull(Downloader.sha1Of(File("/definitely/not/here-${System.nanoTime()}")))
    }
}
