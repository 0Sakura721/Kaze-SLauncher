package com.kaze.newage.core.ai

import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * AI 的文件工具（读 / 列 / 写），全部在本机执行。
 *
 * 权限边界（刻意收紧的设计，不是实现限制）：
 *  - **读**：实例目录（server.properties 所在的根）+ 启动器应用私有目录
 *    （`app:` 前缀，只读 —— 诊断报告、应用日志）；
 *  - **写**：**仅实例目录**，且调用方必须先经用户确认（UI 层职责，见 AppViewModel），
 *    覆盖已有文件前自动留 `同名.bak` 备份；
 *  - **路径**：一律规范化后校验必须在对应根目录内部 —— `..`、绝对路径、软链逃逸全部拒绝；
 *  - **类型**：二进制后缀（jar/zip/世界文件/图片等）读写全禁；文本按后缀白名单，
 *    写 .jar 被显式拒绝（装模组请走「插件/模组」页面，不经过 AI）；
 *  - **体量**：读取单文件截断到 [MAX_READ_BYTES]，写入单文件上限 [MAX_WRITE_BYTES]，
 *    防止一次工具调用把上下文或磁盘撑爆。
 */
object AiFileTools {

    /** 单次读取的最大字节数（超出部分截断并注明） */
    const val MAX_READ_BYTES = 64 * 1024

    /** 单次写入的最大字符数 */
    const val MAX_WRITE_BYTES = 256 * 1024

    /** 目录列表最多展示的条目数 */
    private const val MAX_LIST = 200

    /** 启动器应用私有目录的虚拟前缀（只读） */
    const val APP_PREFIX = "app:"

    /** 禁止读写（AI 视角）的二进制后缀：世界存档、压缩包、可执行、图片媒体等 */
    private val FORBIDDEN_EXTS = setOf(
        "jar", "zip", "gz", "tar", "7z", "apk",
        "mca", "mcr", "nbt", "dat", "litematic",
        "png", "jpg", "jpeg", "gif", "webp", "ico", "bmp", "tga", "psd",
        "mp3", "wav", "ogg", "flac", "mp4", "mkv", "mov", "avi",
        "dll", "so", "class", "bin", "exe", "dylib", "iso",
    )

    /** 允许读写的文本后缀白名单（服务端配置/日志/脚本的常见类型） */
    private val TEXT_EXTS = setOf(
        "txt", "log", "properties", "json", "json5", "yml", "yaml", "toml",
        "cfg", "conf", "ini", "md", "csv", "tsv", "mcmeta", "mcfunction",
        "sh", "bat", "ps1", "py", "xml", "html", "js", "ts", "css",
        "args", "list", "url", "lang", "bak", "old", "disabled", "done",
    )

    /**
     * 解析相对路径 → 根目录内的规范文件。
     *
     * `app:` 前缀走应用私有目录（只读），其余相对实例目录；规范化后必须在
     * 对应根内部（含等于根本身，供 list_dir 用 "."），否则视为越界直接拒绝。
     */
    internal fun resolve(instanceDir: File, appFilesDir: File?, rawPath: String, forWrite: Boolean): File {
        val p = rawPath.trim().trimStart('/')
        if (p.isEmpty()) throw IllegalArgumentException("路径为空")
        if (p.startsWith(APP_PREFIX)) {
            if (forWrite) throw IllegalArgumentException("app: 是启动器应用目录，只读不允许写入")
            val appRoot = appFilesDir ?: throw IllegalArgumentException("应用目录不可用")
            return childOf(appRoot, p.removePrefix(APP_PREFIX).ifBlank { "." })
        }
        return childOf(instanceDir, p)
    }

    private fun childOf(root: File, rel: String): File {
        val f = File(root, rel.trim().trimStart('/'))
        val canon = f.canonicalFile
        val rootCanon = root.canonicalFile
        if (canon.path != rootCanon.path && !canon.path.startsWith(rootCanon.path + File.separator)) {
            throw IllegalArgumentException("路径越界：只能访问实例目录或 app: 应用目录内部")
        }
        return canon
    }

    private fun guardTextType(f: File, forWrite: Boolean) {
        val ext = f.extension.lowercase()
        if (ext in FORBIDDEN_EXTS) {
            throw IllegalArgumentException(
                if (forWrite) ".$ext 是二进制类型，不允许通过 AI 写入（如装模组请用「插件/模组」页面）"
                else ".$ext 是二进制类型，不允许读取"
            )
        }
        if (ext.isNotEmpty() && ext !in TEXT_EXTS) {
            throw IllegalArgumentException(
                if (forWrite) ".$ext 不在可写类型名单里"
                else ".$ext 不在可读类型名单里（文本类：txt/log/properties/json/yml/toml 等）"
            )
        }
    }

    /** 读文本文件；超长截断并注明。路径相对实例目录，`app:` 前缀读应用目录。 */
    fun readFile(instanceDir: File, appFilesDir: File?, path: String): String {
        val f = resolve(instanceDir, appFilesDir, path, forWrite = false)
        if (!f.exists()) throw FileNotFoundException("${f.path}（文件不存在，可先用 list_dir 确认位置）")
        if (f.isDirectory) throw IllegalArgumentException("${f.name} 是目录，请用 list_dir 列出")
        guardTextType(f, forWrite = false)
        val total = f.length()
        if (total <= 0L) return "（空文件）"
        // read(buf) 不保证读满（尤其网络/管道语义下），必须循环读够或到 EOF
        val buf = ByteArray(MAX_READ_BYTES)
        var n = 0
        f.inputStream().use { ins ->
            while (n < buf.size) {
                val r = ins.read(buf, n, buf.size - n)
                if (r < 0) break
                n += r
            }
        }
        val text = String(buf, 0, n, Charsets.UTF_8)
        return if (total > MAX_READ_BYTES) {
            "$text\n…（已截断：文件共 $total 字节，只读取了前 $MAX_READ_BYTES 字节）"
        } else {
            text
        }
    }

    /** 列目录：目录在前、按名排序，带大小；超过 [MAX_LIST] 条注明剩余。 */
    fun listDir(instanceDir: File, appFilesDir: File?, path: String): String {
        val d = resolve(instanceDir, appFilesDir, path, forWrite = false)
        if (!d.exists()) throw FileNotFoundException("${d.path}（目录不存在）")
        if (!d.isDirectory) throw IllegalArgumentException("${d.name} 是文件，请用 read_file 读取")
        val entries = d.listFiles()
            ?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
            ?: throw IOException("无法列出 ${d.path}")
        if (entries.isEmpty()) return "（空目录）"
        val sb = StringBuilder()
        entries.take(MAX_LIST).forEach { e ->
            if (e.isDirectory) {
                sb.append(e.name).append("/\n")
            } else {
                sb.append(e.name).append("  (").append(formatSize(e.length())).append(")\n")
            }
        }
        if (entries.size > MAX_LIST) sb.append("…还有 ${entries.size - MAX_LIST} 项未列出\n")
        return sb.toString().trimEnd()
    }

    /**
     * 写文本文件（调用方必须已获用户确认）。覆盖已有文件前留 `同名.bak`。
     * 仅限实例目录；.jar 等二进制与 app: 前缀一律拒绝。
     */
    fun writeFile(instanceDir: File, path: String, content: String): String {
        if (content.isEmpty()) throw IllegalArgumentException("内容为空（如需清空文件请直接说明）")
        if (content.length > MAX_WRITE_BYTES) {
            throw IllegalArgumentException("内容过大：${content.length} 字符 > 上限 $MAX_WRITE_BYTES")
        }
        val f = resolve(instanceDir, null, path, forWrite = true)
        guardTextType(f, forWrite = true)
        val parent = f.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("无法创建目录：${parent.path}")
        }
        var bakNote = ""
        if (f.exists()) {
            if (!f.isFile) throw IllegalArgumentException("${f.path} 不是普通文件")
            f.copyTo(f.resolveSibling(f.name + ".bak"), overwrite = true)
            bakNote = "，原文件已备份为 ${f.name}.bak"
        }
        f.writeText(content, Charsets.UTF_8)
        return "已写入 ${f.name}（${content.toByteArray(Charsets.UTF_8).size} 字节$bakNote）"
    }

    internal fun formatSize(bytes: Long): String =
        if (bytes < 1024) "$bytes B" else "${(bytes + 1023) / 1024} KB"
}
