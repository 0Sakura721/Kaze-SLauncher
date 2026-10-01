package com.kaze.newage.util

import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TarExtractor 的防护测试：
 *  - 写出上限：tar 头声明的 size 就是解压后真实大小，超限必须在**写盘之前**被拦下
 *  - 符号链接逃逸：归档里 "../…" 型链接目标一律不得在 destDir 内建出链接
 *
 * tar 字节在测试里手工构造（TarExtractor 是手写解析器，不校验校验和，
 * 只认 name/size/type/linkname 几个字段），不依赖第三方 tar 库。
 */
class TarExtractorTest {

    /** 构造一个最小合法 tar：512 字节头 + 数据（补齐到 512 倍数）+ 结束零块 */
    private fun tarOf(entries: List<Triple<String, Long, Char>>, links: Map<String, String> = emptyMap(), data: Map<String, ByteArray> = emptyMap()): ByteArray {
        val out = ByteArrayOutputStream()
        for ((name, size, type) in entries) {
            val header = ByteArray(512)
            name.toByteArray(Charsets.UTF_8).copyInto(header, 0, 0, minOf(name.length, 100))
            // size（offset 124，12 字节八进制）
            String.format("%011o", size).toByteArray().copyInto(header, 124)
            // typeflag（offset 156）
            header[156] = type.code.toByte()
            // linkname（offset 157，100 字节）
            links[name]?.toByteArray(Charsets.UTF_8)?.copyInto(header, 157, 0, minOf(links[name]!!.length, 100))
            out.write(header)
            data[name]?.let { bytes ->
                out.write(bytes)
                val pad = (512 - (bytes.size % 512)) % 512
                out.write(ByteArray(pad))
            }
        }
        out.write(ByteArray(1024)) // 结束零块
        return out.toByteArray()
    }

    private fun newDir(): File = createTempDir("tar-test").also { it.deleteRecursively(); it.mkdirs() }

    @Test
    fun `解压总量超上限时在写盘前中止`() {
        val dir = newDir()
        try {
            val tar = File(dir.parentFile, "cap.tar")
            tar.writeBytes(
                tarOf(entries = listOf(Triple("big.bin", 4096L, '0')), data = mapOf("big.bin" to ByteArray(4096)))
            )
            var threw = false
            try {
                TarExtractor.extract(tar, dir, maxTotalBytes = 1024L)
            } catch (e: RuntimeException) {
                threw = true
                assertTrue("应提示超上限，实际：${e.message}", e.message!!.contains("上限"))
            }
            assertTrue("4096 字节条目在 1024 上限下必须抛错", threw)
            // 关键：抛错发生在打开输出流**之前**，不能留下半截文件
            assertFalse("超限条目不得写出任何字节", File(dir, "big.bin").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `正常文件在限额内可完整解压`() {
        val dir = newDir()
        try {
            val tar = File(dir.parentFile, "ok.tar")
            val content = "hello kaze".toByteArray()
            tar.writeBytes(tarOf(entries = listOf(Triple("a.txt", content.size.toLong(), '0')), data = mapOf("a.txt" to content)))
            TarExtractor.extract(tar, dir, maxTotalBytes = 1024L)
            assertTrue(File(dir, "a.txt").exists())
            assertTrue(content.contentEquals(File(dir, "a.txt").readBytes()))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `越界相对符号链接被拒绝且不影响其余条目`() {
        val dir = newDir()
        try {
            val tar = File(dir.parentFile, "escape.tar")
            val content = "target".toByteArray()
            tar.writeBytes(
                tarOf(
                    entries = listOf(
                        Triple("target.txt", content.size.toLong(), '0'),
                        Triple("evil", 0L, '2'),
                        Triple("alias", 0L, '2'),
                    ),
                    links = mapOf(
                        "evil" to "../../evil.txt", // 越界
                        "alias" to "target.txt",    // 正常 soname 型相对链接
                    ),
                    data = mapOf("target.txt" to content),
                )
            )
            TarExtractor.extract(tar, dir)
            assertFalse("越界链接不得创建", File(dir, "evil").exists())
            assertTrue("正常条目不受影响", File(dir, "target.txt").exists())
            // 正常链接：能建链就建链；建不了（部分沙箱/文件系统）走复制兜底 —— 两种路径下都必须存在
            assertTrue("正常相对链接应可用（建链或复制兜底）", File(dir, "alias").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `shouldCancel 立即中止解压`() {
        val dir = newDir()
        try {
            val tar = File(dir.parentFile, "cancel.tar")
            tar.writeBytes(tarOf(entries = listOf(Triple("x.bin", 512L, '0')), data = mapOf("x.bin" to ByteArray(512))))
            var threw = false
            try {
                TarExtractor.extract(tar, dir, shouldCancel = { true })
            } catch (e: InterruptedException) {
                threw = true
            }
            assertTrue("取消必须抛 InterruptedException", threw)
            assertFalse("取消后不得写出条目", File(dir, "x.bin").exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
