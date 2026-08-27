package com.kaze.newage.util

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.zip.GZIPInputStream

/**
 * 手写 tar/tar.gz 解压（带进度/速度回调），不依赖第三方库。
 *
 * 来源：v2 EnvManager 同款实现（本仓库自有代码，LGPL-3.0 → GPL-3.0 兼容）。
 * 特性：
 *  - 自动识别 gzip（魔数 0x1f 0x8b）
 *  - 支持普通文件/目录/符号链接条目
 *  - Android 沙箱无法创建符号链接 → 创建失败时复制链接目标文件兜底（soname 场景关键）
 *  - 严格处理 512 字节对齐 padding
 */
object TarExtractor {

    fun extract(
        tarFile: File,
        destDir: File,
        onProgress: (Long, Long, Long) -> Unit = { _, _, _ -> },
    ) {
        destDir.mkdirs()
        val total = tarFile.length()
        val startTime = System.currentTimeMillis()
        var processed = 0L
        var lastUpdate = startTime
        var lastBytes = 0L

        fun report() {
            val now = System.currentTimeMillis()
            if (now - lastUpdate >= 150) {
                val speed = (processed - lastBytes) * 1000 / (now - lastUpdate).coerceAtLeast(1)
                onProgress(processed, total, speed)
                lastUpdate = now
                lastBytes = processed
            }
        }

        val isGzip = try {
            RandomAccessFile(tarFile, "r").use { it.readUnsignedByte() == 0x1f && it.readUnsignedByte() == 0x8b }
        } catch (_: Exception) { false }

        val fileStream = FileInputStream(tarFile)
        val rawInput = if (isGzip) GZIPInputStream(fileStream) else fileStream

        val header = ByteArray(512)

        /** 待解析的符号链接（两遍处理：第一遍收普通文件，第二遍统一建链/复制，保证目标必然存在） */
        data class PendingLink(val target: File, val linkRel: String)

        val pendingLinks = mutableListOf<PendingLink>()

        fun readHeader(): Boolean {
            var read = 0
            while (read < 512) {
                val n = rawInput.read(header, read, 512 - read)
                if (n == -1) return false
                read += n
            }
            processed += 512
            // 全零块 = tar 结束
            return header.any { it != 0.toByte() }
        }

        fun octal(bytes: ByteArray): Long {
            var v = 0L
            for (b in bytes) {
                if (b.toInt() in '0'.code..'7'.code) v = v * 8 + (b - '0'.code)
            }
            return v
        }

        fun name(): String {
            var end = 0
            while (end < 100 && header[end] != 0.toByte()) end++
            return String(header, 0, end, Charsets.UTF_8)
        }

        val destCanonical = destDir.canonicalPath + File.separator

        /** 条目名 → 目标文件（路径穿越防护：../ 或拼进绝对路径时拒绝写出 destDir） */
        fun resolvedTarget(path: String): File {
            val t = File(destDir, path)
            if (!t.canonicalPath.startsWith(destCanonical)) {
                throw RuntimeException("解压条目路径越界：$path")
            }
            return t
        }

        try {
            val buffer = ByteArray(64 * 1024)
            var pendingLongName: String? = null
            while (readHeader()) {
                val size = octal(header.copyOfRange(124, 136))
                val type = header[156].toInt().toChar()
                val pathName = pendingLongName ?: name()
                pendingLongName = null
                val isRegular = type == '0' || type == '\u0000'

                when {
                    // GNU 长文件名（'L'）：数据区就是下一条目的完整路径（>100 字节的名靠它承载）
                    type == 'L' -> {
                        val n = size.toInt()
                        if (n in 1..4096) {
                            val data = ByteArray(n)
                            var read = 0
                            while (read < n) {
                                val r = rawInput.read(data, read, n - read)
                                if (r == -1) throw RuntimeException("tar 长文件名数据截断")
                                read += r
                            }
                            pendingLongName = String(data, 0, n, Charsets.UTF_8).trimEnd('\u0000').trim()
                        }
                        // 超长/非法值按普通条目跳过，保证流对齐
                    }
                    // 目录条目：tar 目录标记、以 / 结尾、或根路径
                    type == '5' || pathName.endsWith("/") || pathName.isBlank() || pathName == "." || pathName == "./" -> {
                        if (pathName.isNotBlank() && pathName != "." && pathName != "./") {
                            resolvedTarget(pathName).mkdirs()
                        }
                    }
                    // 符号链接：兼容两种 tar 变体——
                    // 标准格式：目标在 header 的 linkname 字段（offset 157），数据区 size=0；
                    // 某些 tarball（如 Canonical 镜像构建器产物）：链接名存在数据区（size=链接名长度）。
                    // 因此数据区必须按 size 读取（保持流对齐），链接名取 header 优先、数据兜底。
                    type == '2' -> {
                        val dataBytes = ByteArray(size.toInt().coerceAtMost(4096))
                        var read = 0
                        while (read < dataBytes.size) {
                            val n = rawInput.read(dataBytes, read, dataBytes.size - read)
                            if (n == -1) break
                            read += n
                        }
                        val headerLink = String(header, 157, 100, Charsets.UTF_8)
                            .trimEnd('\u0000')
                            .trim()
                        val dataLink = String(dataBytes, 0, read, Charsets.UTF_8)
                            .trimEnd('\u0000')
                            .trim()
                        val linkName = headerLink.ifEmpty { dataLink }
                        val target = resolvedTarget(pathName)
                        if (pathName.contains("/")) target.parentFile?.mkdirs()
                        if (linkName.isNotEmpty()) {
                            pendingLinks.add(PendingLink(target, linkName))
                        }
                    }
                    // 硬链接（'1'）：数据 = 归档内另一条目的路径（header linkname），复制为该文件
                    type == '1' -> {
                        val linkName = String(header, 157, 100, Charsets.UTF_8).trimEnd('\u0000').trim()
                        if (linkName.isNotEmpty()) {
                            val src = resolvedTarget(linkName)
                            val tgt = resolvedTarget(pathName)
                            if (pathName.contains("/")) tgt.parentFile?.mkdirs()
                            if (src.exists() && src.absolutePath != tgt.absolutePath) {
                                src.copyTo(tgt, overwrite = true)
                            }
                        }
                    }
                    isRegular -> {
                        val target = resolvedTarget(pathName)
                        if (pathName.contains("/")) target.parentFile?.mkdirs()
                        var remaining = size
                        FileOutputStream(target).use { out ->
                            while (remaining > 0) {
                                val chunk = rawInput.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                if (chunk == -1) {
                                    throw RuntimeException("tar 数据区提前结束：$pathName（归档损坏/截断）")
                                }
                                out.write(buffer, 0, chunk)
                                remaining -= chunk
                            }
                            // 不做逐文件 fsync：真机内部 FUSE passthrough 上逐文件 sync
                            // 极慢且可能触发回刷缺陷；改用提取完成后的全局 sync()。
                        }
                        // 可执行位
                        if (pathName.contains("bin/") || pathName.contains("libexec/")) target.setExecutable(true)
                    }
                    else -> {
                        // 其他类型（'x' 扩展头等）：跳过数据
                        var remaining = size
                        while (remaining > 0) {
                            val n = rawInput.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                            if (n == -1) break
                            remaining -= n
                        }
                    }
                }
                // 统一补齐到 512 对齐 padding
                val padded = (size + 511) / 512 * 512
                var pad = padded - size
                while (pad > 0) {
                    val n = rawInput.read(buffer, 0, minOf(buffer.size.toLong(), pad).toInt())
                    if (n == -1) break
                    pad -= n
                }
                processed += padded
                report()
            }

            // 第二遍：统一解析符号链接（此时所有普通文件已就位，复制兜底的目标必然存在）
            for (link in pendingLinks) {
                try {
                    java.nio.file.Files.createSymbolicLink(
                        link.target.toPath(),
                        java.nio.file.Paths.get(link.linkRel)
                    )
                } catch (_: Exception) {
                    // 沙箱限制符号链接时，复制链接目标，保证 soname 存在
                    try {
                        val resolved = File(link.target.parentFile ?: destDir, link.linkRel)
                        // ../ 型链接目标同样禁止逃逸
                        if (resolved.canonicalPath.startsWith(destCanonical)) {
                            when {
                                resolved.isFile -> resolved.copyTo(link.target, overwrite = true)
                                resolved.isDirectory -> {
                                    // 目录型链接（usrmerge 的 bin/lib/sbin 等）：文件副本代价过高，
                                    // 交上层修复（Os.symlink 尝试或运行时 proot -b 绑定）
                                }
                            }
                        }
                    } catch (_: Exception) { }
                }
            }

            onProgress(total, total, 0)

            // 全局 sync：真机内部 FUSE 会丢目录项（文件数据 fsync 后 dentry 仍可能丢失，
            // 症状=解压后 usr/bin 目录空）。系统级 sync() 强制 FUSE/F2FS 全量回刷。
            try {
                val p = Runtime.getRuntime().exec(arrayOf("sync"))
                p.waitFor()
            } catch (_: Exception) { }
        } finally {
            rawInput.close()
            fileStream.close()
        }
    }
}
