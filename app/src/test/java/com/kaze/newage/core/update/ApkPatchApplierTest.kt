package com.kaze.newage.core.update

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.Test

/**
 * APK 增量补丁的往返测试。
 *
 * 分两段：
 *  1. **格式往返**（CI 每次都跑）：用与 `tools/make_apk_patch.py` 相同的格式，
 *     在测试里造两个 zip、生成补丁、再用 [ApkPatchApplier] 拼装，断言结果与目标**逐字节相同**。
 *  2. **真实补丁互通**（给定 `-Dkaze.patch.dir=` 时才跑）：直接吃 Python 生成器产出的
 *     真实补丁，验的是"生成端与拼装端确实同一个格式"—— 这是最容易出错的地方。
 */
// 必须用 Robolectric：ApkPatchApplier 用 org.json 解析补丁元数据，而纯 JVM 单测里
// android.jar 的 org.json 只是 stub（链式 put 返回 null，直接 NPE）。
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ApkPatchApplierTest {

    private fun sha256(b: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun sha256(f: File): String = ApkPatchApplier.sha256Of(f)

    /** 造一个 zip：entries 是有序的 name → 内容 */
    private fun makeZip(f: File, entries: List<Pair<String, ByteArray>>) {
        ZipOutputStream(f.outputStream().buffered()).use { z ->
            entries.forEach { (name, data) ->
                z.putNextEntry(ZipEntry(name))
                z.write(data)
                z.closeEntry()
            }
        }
    }

    private fun big(seed: Int, size: Int) = ByteArray(size) { ((it * 31 + seed) % 251).toByte() }

    /**
     * 测试用的补丁生成器 —— 逻辑与 `tools/make_apk_patch.py` 严格一致：
     * 逐条比对**本地记录**的哈希，相同走 copy、不同走 add；尾部整段带上。
     */
    private fun buildPatch(base: File, target: File, out: File) {
        // 复用拼装器的中央目录解析：这样测试同时也在测解析本身
        fun cdOf(f: File): Map<String, Pair<Long, Int>> = ApkPatchApplier.readCentralDirectory(f)

        fun record(f: File, cd: Pair<Long, Int>): ByteArray =
            RandomAccessFile(f, "r").use { raf ->
                raf.seek(cd.first)
                ByteArray(cd.second).also { raf.readFully(it) }
            }

        val baseCd = cdOf(base)
        val targetCd = cdOf(target)
        val baseHashes = baseCd.mapValues { sha256(record(base, it.value)) }

        val order = targetCd.entries.sortedBy { it.value.first }
        val entries = ArrayList<JSONObject>()
        val adds = ArrayList<Pair<String, ByteArray>>()
        order.forEachIndexed { idx, (name, cd) ->
            val raw = record(target, cd)
            if (baseHashes[name] == sha256(raw)) {
                entries.add(JSONObject().put("name", name).put("mode", "copy"))
            } else {
                val rp = "r/%04d.bin".format(idx)
                adds.add(rp to raw)
                entries.add(JSONObject().put("name", name).put("mode", "add").put("record", rp))
            }
        }
        val tailStart = targetCd.values.maxOf { it.first + it.second }
        val tail = RandomAccessFile(target, "r").use { raf ->
            raf.seek(tailStart)
            ByteArray((target.length() - tailStart).toInt()).also { raf.readFully(it) }
        }

        val meta = JSONObject()
            .put("format", ApkPatchApplier.FORMAT)
            .put("baseSha256", sha256(base))
            .put("targetSha256", sha256(target))
            .put("toVersion", "9.9.9")
            .put("tail", "tail.bin")
            .put("entries", JSONArray(entries))

        ZipOutputStream(out.outputStream().buffered()).use { z ->
            z.putNextEntry(ZipEntry("meta.json")); z.write(meta.toString().toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("tail.bin")); z.write(tail); z.closeEntry()
            adds.forEach { (rp, raw) ->
                z.putNextEntry(ZipEntry(rp)); z.write(raw); z.closeEntry()
            }
        }
    }

    @Test
    fun `补丁拼装结果与目标包逐字节相同`() {
        val dir = createTempDirectory("kaze-patch").toFile()
        val base = File(dir, "base.apk")
        val target = File(dir, "target.apk")

        // 一个"旧包"：3 个不变的大块 + 1 个会变的 dex
        makeZip(base, listOf(
            "lib/arm64-v8a/libproot.so" to big(1, 300_000),
            "classes.dex" to big(2, 40_000),
            "resources.arsc" to big(3, 20_000),
            "assets/keep.bin" to big(4, 10_000),
        ))
        // "新包"：dex 变了、多了一个条目，原生库完全没动
        makeZip(target, listOf(
            "lib/arm64-v8a/libproot.so" to big(1, 300_000),
            "classes.dex" to big(99, 44_000),
            "resources.arsc" to big(3, 20_000),
            "assets/keep.bin" to big(4, 10_000),
            "res/new.xml" to big(7, 5_000),
        ))

        val patch = File(dir, "patch.zip")
        buildPatch(base, target, patch)

        // 补丁应当远小于整包（这里大头是没变的原生库）
        assertTrue("补丁应明显小于整包", patch.length() < target.length() / 2)

        val out = File(dir, "out.apk")
        val got = ApkPatchApplier.apply(base, patch, out)

        assertEquals("返回的 sha256 应等于目标", sha256(target), got)
        assertArrayEquals("拼装结果必须与目标包逐字节相同", target.readBytes(), out.readBytes())
    }

    @Test
    fun `基线不匹配时拒绝打补丁（用户装的是别的包）`() {
        val dir = createTempDirectory("kaze-patch").toFile()
        val base = File(dir, "base.apk")
        val target = File(dir, "target.apk")
        makeZip(base, listOf("a.bin" to big(1, 1000)))
        makeZip(target, listOf("a.bin" to big(2, 1000)))
        val patch = File(dir, "p.zip")
        buildPatch(base, target, patch)

        // 拿另一个完全不同的包当 base
        val other = File(dir, "other.apk")
        makeZip(other, listOf("a.bin" to big(3, 1000)))

        val e = runCatching { ApkPatchApplier.apply(other, patch, File(dir, "o.apk")) }.exceptionOrNull()
        assertTrue("应抛 PatchException，实际：$e", e is ApkPatchApplier.PatchException)
        assertTrue("错误信息应点名基线不一致", e!!.message!!.contains("基线"))
    }

    @Test
    fun `补丁被篡改时拼不出正确结果（校验 sha256 兜底）`() {
        val dir = createTempDirectory("kaze-patch").toFile()
        val base = File(dir, "base.apk")
        val target = File(dir, "target.apk")
        makeZip(base, listOf("a.bin" to big(1, 50_000), "b.bin" to big(2, 5_000)))
        makeZip(target, listOf("a.bin" to big(9, 50_000), "b.bin" to big(2, 5_000)))
        val patch = File(dir, "p.zip")
        buildPatch(base, target, patch)

        // 篡改补丁里的记录（把 add 条目的原始本地记录改掉一个字节）
        val tampered = File(dir, "tampered.zip")
        ZipFile(patch).use { z ->
            ZipOutputStream(tampered.outputStream().buffered()).use { out ->
                z.entries().asSequence().forEach { e ->
                    val data = z.getInputStream(e).use { it.readBytes() }
                    if (e.name.startsWith("r/") && data.size > 40) data[40] = (data[40] + 1).toByte()
                    out.putNextEntry(ZipEntry(e.name))
                    out.write(data)
                    out.closeEntry()
                }
            }
        }

        val e = runCatching { ApkPatchApplier.apply(base, tampered, File(dir, "o.apk")) }.exceptionOrNull()
        assertTrue("篡改必须被发现，实际：$e", e is ApkPatchApplier.PatchException)
    }

    /**
     * 真实补丁互通：`-Dkaze.patch.dir=<目录>` 且目录里有 base.apk / target.apk / *.zip。
     * 这条跑的是 Python 生成器产出的补丁，验的是两端格式一致。
     */
    @Test
    fun `真实生成器产出的补丁能被拼装_需要提供补丁目录`() {
        val dirPath = System.getProperty("kaze.patch.dir")
        if (dirPath.isNullOrBlank()) {
            println("跳过：未提供 -Dkaze.patch.dir")
            return
        }
        val dir = File(dirPath)
        val base = File(dir, "base.apk")
        val target = File(dir, "target.apk")
        val patch = dir.listFiles { f -> f.name.endsWith(".zip") }?.firstOrNull()
        assertTrue("目录里应有 base.apk / target.apk / 补丁 zip", base.isFile && target.isFile && patch != null)

        val out = File(dir, "out.apk")
        val got = ApkPatchApplier.apply(base, patch!!, out)
        assertEquals("真实补丁的拼装结果 sha256 应等于目标包", sha256(target), got)
        assertArrayEquals("真实补丁必须拼出逐字节相同的包", target.readBytes(), out.readBytes())
    }
}
