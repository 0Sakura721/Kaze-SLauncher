package com.kaze.newage.core.update

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.json.JSONArray
import org.json.JSONObject

/**
 * APK 增量补丁的**拼装器**（客户端侧，纯 JVM，不需要 native 库）。
 *
 * ## 为什么可行
 * APK 是 zip，两次构建之间绝大部分条目**逐字节不变**（`libproot.so` 等原生库、
 * 资源文件…）。所以补丁只要携带"变化条目的原始本地记录"+"文件尾部
 * （中央目录 + EOCD + APK 签名块）"，客户端把**本机已安装 APK** 里没变的条目原样拷过来，
 * 就能拼出与官方包**逐字节相同**的新 APK —— 因此签名能验、SHA-256 能对。
 *
 * 与 bsdiff/hdiffpatch 相比：不需要 NDK、不需要把 30MB 包读进内存，
 * 而且因为输出与官方包一致，**签名校验仍然成立**（差分合成的包普通做法验不过签名）。
 *
 * ## 格式（"kaze-apkraw-1"）
 * 补丁 zip：
 * ```
 * meta.json    { format, baseSha256, targetSha256, toVersion, tail, entries[] }
 * tail.bin     目标包的尾部（中央目录 + EOCD + 签名块）
 * r/NNNN.bin   mode=add 的条目的**原始本地记录**（local header + 数据）
 * ```
 * `entries[]` 按**目标包本地记录的先后顺序**排列，每项 `{name, mode: copy|add, record?}`。
 * 生成端见 `tools/make_apk_patch.py` —— **改格式必须两边一起改**。
 *
 * ## 安全
 * 三道校验，缺一不可：
 *  1. 打补丁**前**校验本机 APK 的 sha256 == `baseSha256`（用户装的若是别处来的同签名包，
 *     或版本对不上，直接走整包）；
 *  2. 拼装**后**校验 sha256 == `targetSha256`（补丁被篡改/传输损坏都会在这里被拦下）；
 *  3. 交给安装器之前，仍由 [UpdateInstaller] 比对**签名证书**。
 */
object ApkPatchApplier {

    const val FORMAT = "kaze-apkraw-1"

    private const val SIG_EOCD = 0x06054b50
    private const val SIG_CD = 0x02014b50

    /** EOCD 固定 22 字节，后面可能跟最多 64KB 注释 */
    private const val MAX_EOCD_SCAN = 22 + 0xFFFF

    data class Meta(
        val format: String,
        val baseSha256: String,
        val targetSha256: String,
        val toVersion: String,
        val tailName: String,
        val entries: List<Entry>,
    ) {
        data class Entry(val name: String, val mode: String, val record: String?)
    }

    class PatchException(message: String) : Exception(message)

    /** 读补丁 zip 里的 meta.json */
    fun readMeta(patchZip: File): Meta {
        ZipFile(patchZip).use { z ->
            val entry = z.getEntry("meta.json")
                ?: throw PatchException("补丁缺少 meta.json")
            val text = z.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
            return parseMeta(text)
        }
    }

    fun parseMeta(text: String): Meta {
        val o = JSONObject(text)
        val format = o.optString("format", "")
        if (format != FORMAT) throw PatchException("补丁格式不支持：$format")
        val arr: JSONArray = o.optJSONArray("entries") ?: JSONArray()
        val entries = ArrayList<Meta.Entry>(arr.length())
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            val name = e.optString("name", "")
            val mode = e.optString("mode", "")
            if (name.isEmpty()) throw PatchException("entries[$i] 缺少 name")
            if (mode != "copy" && mode != "add") throw PatchException("entries[$i] 未知 mode：$mode")
            entries.add(Meta.Entry(name, mode, e.optString("record", "").ifEmpty { null }))
        }
        val base = o.optString("baseSha256", "").lowercase()
        val target = o.optString("targetSha256", "").lowercase()
        if (base.length != 64 || target.length != 64) throw PatchException("meta 缺少 sha256")
        return Meta(format, base, target, o.optString("toVersion", ""), o.optString("tail", "tail.bin"), entries)
    }

    /**
     * 把 [patchZip] 打到 [baseApk] 上，输出 [outApk]。
     *
     * @return 成功时返回拼装后的 sha256（已与 `targetSha256` 比对过）
     * @throws PatchException 任一步校验失败（调用方应回退整包）
     */
    fun apply(baseApk: File, patchZip: File, outApk: File): String {
        val meta = readMeta(patchZip)

        val baseSha = sha256Of(baseApk)
        if (!baseSha.equals(meta.baseSha256, ignoreCase = true)) {
            throw PatchException("本机 APK 与补丁基线不一致（${baseSha.take(12)}… ≠ ${meta.baseSha256.take(12)}…）")
        }

        val baseCd = readCentralDirectory(baseApk)
        outApk.parentFile?.mkdirs()
        if (outApk.exists()) outApk.delete()

        val digest = MessageDigest.getInstance("SHA-256")
        DigestOutputStream(BufferedOutputStream(FileOutputStream(outApk), 1 shl 16), digest).use { out ->
            RandomAccessFile(baseApk, "r").use { base ->
                ZipFile(patchZip).use { pz ->
                    for (e in meta.entries) {
                        if (e.mode == "copy") {
                            val cd = baseCd[e.name]
                                ?: throw PatchException("本机 APK 里没有条目：${e.name}")
                            copyLocalRecord(base, cd.first, cd.second, out)
                        } else {
                            val rp = e.record ?: throw PatchException("mode=add 缺少 record：${e.name}")
                            val ze = pz.getEntry(rp)
                                ?: throw PatchException("补丁里缺少记录：$rp")
                            pz.getInputStream(ze).use { it.copyTo(out, 1 shl 16) }
                        }
                    }
                    val tailEntry = pz.getEntry(meta.tailName)
                        ?: throw PatchException("补丁里缺少尾部：${meta.tailName}")
                    pz.getInputStream(tailEntry).use { it.copyTo(out, 1 shl 16) }
                }
            }
            out.flush()
        }

        val got = digest.digest().joinToString("") { "%02x".format(it) }
        if (!got.equals(meta.targetSha256, ignoreCase = true)) {
            outApk.delete()
            throw PatchException("拼装结果校验失败（$got ≠ ${meta.targetSha256}）")
        }
        return got
    }

    fun sha256Of(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // ── 下面的解析逻辑与 tools/make_apk_patch.py 一一对应 ──

    /** 条目名 → (本地记录偏移, 本地记录总长)。internal 供测试复用（顺带把解析本身也测到）。 */
    internal fun readCentralDirectory(apk: File): Map<String, Pair<Long, Int>> {
        RandomAccessFile(apk, "r").use { raf ->
            val eocd = findEocd(raf) ?: throw PatchException("不是有效的 zip/APK（找不到 EOCD）")
            val cdOffset = eocd.second
            raf.seek(cdOffset)
            val out = HashMap<String, Pair<Long, Int>>()
            var pos = cdOffset
            val fileLen = raf.length()
            while (pos + 46 <= fileLen) {
                raf.seek(pos)
                // 必须用小端读：zip 的字段全是 little-endian，raf.readInt() 是大端，
                // 拿它比签名会永远不相等，于是中央目录被解析成空表（测试里表现为
                // NoSuchElementException，线上表现为"补丁拼不出东西"）。
                if (readIntLe(raf) != SIG_CD) break
                raf.seek(pos + 20)          // 压缩后大小
                val csize = readIntLe(raf)
                raf.seek(pos + 28)
                val nlen = readShortLe(raf)
                val elen = readShortLe(raf)
                val clen = readShortLe(raf)
                raf.seek(pos + 42)
                val localOff = readIntLe(raf).toLong() and 0xFFFFFFFFL
                raf.seek(pos + 46)
                val nameBytes = ByteArray(nlen)
                raf.readFully(nameBytes)
                val name = String(nameBytes, Charsets.UTF_8)
                // ⚠️ 记录长度必须按**本地头**算，不能拿中央目录的 extra 长度：
                // 两者可以不同（java.util.zip 生成的包就会差），照中央目录算会少拷/多拷字节，
                // 拼出来的包哈希对不上。这里回到 localHeaderOffset 读本地头。
                out[name] = localOff to recordLength(raf, localOff, csize)
                pos += 46 + nlen + elen + clen
            }
            return out
        }
    }

    /**
     * 本地记录总长 = 30(固定头) + 文件名长 + extra 长 + 压缩数据长（+ data descriptor）。
     *
     * 前两个长度必须取**本地头**里的值：中央目录与本地头的 extra 可以不同
     * （java.util.zip 生成的包就会差），照中央目录算会少拷/多拷字节。
     *
     * 另外：本地头 flag 的 bit 3 表示"大小/CRC 写在了数据后面的 descriptor 里"，
     * 这时记录末尾还跟着 12 或 16 字节（带可选签名 0x08074b50），不带上就会缺字节。
     * AGP 打的 APK 不带 descriptor，但别的打包器会带，所以必须处理。
     */
    private fun recordLength(raf: RandomAccessFile, localOffset: Long, csize: Int): Int {
        val save = raf.filePointer
        return try {
            raf.seek(localOffset + 6)
            val flags = readShortLe(raf)
            raf.seek(localOffset + 26)
            val lNlen = readShortLe(raf)
            val lElen = readShortLe(raf)
            var rec = 30 + lNlen + lElen + csize
            if (flags and 0x08 != 0) {
                raf.seek(localOffset + rec)
                val sig = runCatching { readIntLe(raf) }.getOrDefault(0)
                rec += if (sig == 0x08074b50) 16 else 12
            }
            rec
        } finally {
            raf.seek(save)
        }
    }

    /** 返回 (EOCD 偏移, 中央目录偏移) */
    private fun findEocd(raf: RandomAccessFile): Pair<Long, Long>? {
        val len = raf.length()
        val scan = minOf(len, MAX_EOCD_SCAN.toLong()).toInt()
        val buf = ByteArray(scan)
        raf.seek(len - scan)
        raf.readFully(buf)
        for (i in scan - 22 downTo 0) {
            if (buf[i].toInt() and 0xFF == 0x50 && buf[i + 1].toInt() and 0xFF == 0x4B &&
                buf[i + 2].toInt() and 0xFF == 0x05 && buf[i + 3].toInt() and 0xFF == 0x06
            ) {
                val cdOff = (buf[i + 16].toLong() and 0xFF) or
                    ((buf[i + 17].toLong() and 0xFF) shl 8) or
                    ((buf[i + 18].toLong() and 0xFF) shl 16) or
                    ((buf[i + 19].toLong() and 0xFF) shl 24)
                return (len - scan + i) to cdOff
            }
        }
        return null
    }

    private fun copyLocalRecord(base: RandomAccessFile, offset: Long, length: Int, out: java.io.OutputStream) {
        base.seek(offset)
        val buf = ByteArray(1 shl 16)
        var left = length
        while (left > 0) {
            val n = base.read(buf, 0, minOf(left, buf.size))
            if (n <= 0) throw PatchException("读取本机 APK 失败（offset=$offset）")
            out.write(buf, 0, n)
            left -= n
        }
    }

    private fun readIntLe(raf: RandomAccessFile): Int {
        val b = ByteArray(4)
        raf.readFully(b)
        return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8) or
            ((b[2].toInt() and 0xFF) shl 16) or ((b[3].toInt() and 0xFF) shl 24)
    }

    private fun readShortLe(raf: RandomAccessFile): Int {
        val b = ByteArray(2)
        raf.readFully(b)
        return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
    }
}
