package com.kaze.newage.core.addons

import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.zip.ZipFile

/**
 * 模组图标缓存：从 jar 里抽出图标字节，落到磁盘，界面只读小文件。
 *
 * 为什么不在列表里直接 `ZipFile(jar).getInputStream(icon)`：
 *  - 列表每次重组（滚动、开关、主题变化）都会重新打开 jar、解压、解码 —— 几十个模组时可感知卡顿；
 *  - jar 可能有几百 MB，而图标只有几 KB，把它单独存一份代价极小。
 *
 * 缓存文件名由 (jar 路径, 大小, 修改时间) 算出：文件被替换（重新下载/手动覆盖）后
 * 名字自然变掉，不会出现"换了新版本、图标还是旧的"。旧文件由 [prune] 按最后访问时间清掉。
 */
object ModIconCache {

    /** 图标字节上限：4 MB。超过这个尺寸的就不可能是模组图标（多是误把资源包当图标） */
    private const val MAX_ICON_BYTES = 4L * 1024 * 1024

    /** 缓存文件数上限 */
    private const val MAX_FILES = 300

    @Volatile
    private var dir: File? = null

    /** 由 AppContainer 在启动时调用；未初始化时 [fileFor] 一律返回 null（界面回落到默认图标） */
    fun init(cacheDir: File) {
        dir = File(cacheDir, "mod-icons").apply { runCatching { mkdirs() } }
    }

    /** 已初始化的缓存目录（诊断/清理用） */
    fun cacheDir(): File? = dir

    /**
     * 取该 jar 的图标文件；没有图标 / 读失败返回 null。
     *
     * 命中缓存时只做一次 stat，不解压、不打开 jar。
     */
    fun fileFor(jar: File): File? {
        val base = dir ?: return null
        if (!jar.isFile) return null
        val out = File(base, keyOf(jar) + ".img")
        if (out.isFile && out.length() > 0) return out
        val bytes = readIconBytes(jar) ?: return null
        return runCatching {
            val tmp = File(base, out.name + ".tmp")
            tmp.writeBytes(bytes)
            if (out.exists()) out.delete()
            if (!tmp.renameTo(out)) {
                tmp.copyTo(out, overwrite = true)
                tmp.delete()
            }
            prune(base)
            out
        }.getOrNull()
    }

    /**
     * 读出图标原始字节（PNG/JPEG/GIF/WEBP），供「详情大图」或测试用。
     * 校验魔数而不是只信文件扩展名：jar 里常见把 .png 写成 HTML 错误页的畸形条目。
     */
    fun readIconBytes(jar: File): ByteArray? {
        return runCatching {
            val meta = ModMetadataReader.read(jar)
            ZipFile(jar).use { zip ->
                val names = Collections.list(zip.entries()).map { it.name }
                val entryName = meta.iconEntry?.takeIf { it in names }
                    ?: ModMetadataReader.findIconEntry(meta.modId, names)
                    ?: return@runCatching null
                val entry = zip.getEntry(entryName) ?: return@runCatching null
                if (entry.size > MAX_ICON_BYTES) return@runCatching null
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                if (bytes.isEmpty() || bytes.size > MAX_ICON_BYTES) return@runCatching null
                if (!isImage(bytes)) return@runCatching null
                bytes
            }
        }.getOrNull()
    }

    /** 清空缓存（「设置 → 清理缓存」或模组被删除后） */
    fun clear() {
        val d = dir ?: return
        runCatching { d.listFiles()?.forEach { it.delete() } }
    }

    private fun isImage(b: ByteArray): Boolean {
        if (b.size < 12) return false
        // PNG
        if (b[0] == 0x89.toByte() && b[1] == 0x50.toByte() && b[2] == 0x4E.toByte() && b[3] == 0x47.toByte()) {
            return true
        }
        // JPEG
        if (b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte()) return true
        // GIF
        if (b[0] == 0x47.toByte() && b[1] == 0x49.toByte() && b[2] == 0x46.toByte()) return true
        // WEBP（RIFF....WEBP）
        if (b[0] == 0x52.toByte() && b[1] == 0x49.toByte() && b[2] == 0x46.toByte() && b[3] == 0x46.toByte() &&
            b[8] == 0x57.toByte() && b[9] == 0x45.toByte() && b[10] == 0x42.toByte() && b[11] == 0x50.toByte()
        ) {
            return true
        }
        return false
    }

    private fun keyOf(jar: File): String {
        val raw = "${jar.absolutePath}|${jar.length()}|${jar.lastModified()}"
        return runCatching {
            MessageDigest.getInstance("SHA-1").digest(raw.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }.getOrElse { Integer.toHexString(raw.hashCode()) }
    }

    /** 超过 [MAX_FILES] 就按最后修改时间删掉最旧的一批（留 80%，避免每次导入都全量扫） */
    private fun prune(base: File) {
        val files = base.listFiles() ?: return
        if (files.size <= MAX_FILES) return
        files.filter { it.name.endsWith(".img") }
            .sortedBy { it.lastModified() }
            .take(files.size - MAX_FILES + MAX_FILES / 5)
            .forEach { runCatching { it.delete() } }
    }
}
