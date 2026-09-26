package com.kaze.newage.util

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 轻量 HTTP 下载器：自动跟随重定向（最多 5 跳）、断点续传（Range + 追加）、
 * 多源探测（自动选最快镜像）与多源回退、进度回调。
 */
object Downloader {

    private const val MAX_REDIRECTS = 5
    private const val USER_AGENT = "KazeSLauncher/3.0 (Android; Minecraft Server Launcher)"

    /**
     * 计算文件 SHA-1（十六进制小写）；失败返回 null。
     * 用于校验官方清单已给出哈希的下载物（例如 vanilla 的 `downloads.server.sha1`）。
     */
    fun sha1Of(file: File): String? = digestOf(file, "SHA-1")

    /**
     * 计算文件 SHA-256（十六进制小写）；失败返回 null。
     * 用于校验更新包——GitHub 的 release asset 带 `digest: sha256:…`，
     * 而 APK 实际是从多个第三方加速镜像下载的，见 [com.kaze.newage.core.update.UpdateInstaller]。
     */
    fun sha256Of(file: File): String? = digestOf(file, "SHA-256")

    private fun digestOf(file: File, algorithm: String): String? = try {
        val md = java.security.MessageDigest.getInstance(algorithm)
        file.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
        null
    }

    /**
     * 是否为 ZIP/JAR 归档（前 4 字节 `PK\x03\x04`）。
     *
     * 所有 Minecraft 服务端核心都是 jar（即 zip），而 Spigot/Fabric/Forge 这类
     * **没有官方哈希可对**的来源此前只校验文件大小——镜像返回一个 >1MB 的 HTML 错误页
     * 就能混过去。加一道魔数校验，代价极低。
     */
    fun isZip(file: File): Boolean = try {
        file.inputStream().use { ins ->
            val head = ByteArray(4)
            ins.read(head) == 4 &&
                head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() &&
                head[2] == 0x03.toByte() && head[3] == 0x04.toByte()
        }
    } catch (_: Exception) {
        false
    }

    /**
     * 下载文件（支持断点续传：目标文件已有部分时用 Range 追加；
     * 服务器不支持 Range 时自动从头开始）。
     * @param urlStr 下载地址
     * @param dest 目标文件
     * @param onProgress (downloadedBytes, totalBytes) —— total 可能为 -1（未知）
     * @param shouldCancel 返回 true 时中止下载（抛 InterruptedException，部分文件保留可续传）
     * @param validate 下载完成后校验文件内容；false 时删除文件并抛异常（触发调用方换源/重试）。
     *                 部分镜像对不存在的大文件会返回 200+HTML 错误页，必须靠内容校验拦截。
     */
    @Throws(Exception::class)
    fun download(
        urlStr: String,
        dest: File,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        shouldCancel: () -> Boolean = { false },
        validate: (File) -> Boolean,
    ) {
        dest.parentFile?.mkdirs()
        var url: URL? = null
        var redirects = 0
        var current = urlStr
        var downloaded = 0L

        // 断点续传：已有部分大小
        if (dest.exists()) downloaded = dest.length()

        while (true) {
            val conn = URL(current).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            if (downloaded > 0) conn.setRequestProperty("Range", "bytes=$downloaded-")

            when (val code = conn.responseCode) {
                in 301..308 -> {
                    val loc = conn.getHeaderField("Location") ?: throw RuntimeException("重定向无 Location")
                    conn.disconnect()
                    if (++redirects > MAX_REDIRECTS) throw RuntimeException("重定向过多")
                    current = if (loc.startsWith("http")) {
                        loc
                    } else {
                        val base = url ?: URL(current)
                        URL(base, loc).toString()
                    }
                    if (url == null) url = URL(current)
                    continue
                }
                206 -> { /* 断点续传成功 */ }
                200 -> downloaded = 0 // 服务器不支持 Range，从头下载
                416 -> {
                    // Range 超出文件范围（部分文件损坏/比源大）：丢弃重下
                    conn.disconnect()
                    dest.delete()
                    downloaded = 0
                    continue
                }
                else -> {
                    conn.disconnect()
                    throw RuntimeException("HTTP $code")
                }
            }

            val total = conn.contentLengthLong.let { if (it >= 0) it + downloaded else -1L }
            FileOutputStream(dest, downloaded > 0).use { out ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    var lastUpdate = System.currentTimeMillis()
                    while (true) {
                        if (shouldCancel()) throw InterruptedException("下载已取消")
                        val n = input.read(buf)
                        if (n == -1) break
                        out.write(buf, 0, n)
                        downloaded += n
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate >= 150) {
                            onProgress(downloaded, total)
                            lastUpdate = now
                        }
                    }
                }
                try {
                    out.fd.sync() // 强制落盘
                } catch (_: Exception) { }
            }
            if (downloaded == 0L && dest.length() == 0L && total != 0L) {
                throw RuntimeException("下载内容为空（Content-Length=$total）")
            }
            // 提前 FIN 对账：实收 < 总长视为截断（弱网下连接可能正常关闭而非抛错），
            // 头部魔数校验抓不到尾部缺损；抛出走外层换源/重试，断点保留续传补完
            if (total > 0 && downloaded < total) {
                throw RuntimeException("下载不完整（$downloaded/$total 字节，源提前断开）")
            }
            onProgress(downloaded, downloaded)
            conn.disconnect()
            // 内容校验：部分镜像对不存在的大文件返回 200+HTML 错误页，仅靠 HTTP 码无法识别
            if (!validate(dest)) {
                dest.delete()
                throw RuntimeException("返回内容不是有效文件（镜像可能下架了该版本），已换源重试")
            }
            return
        }
    }

    /** 下载小文本（元数据接口等） */
    @Throws(Exception::class)
    fun downloadText(urlStr: String, timeoutMs: Int = 15000): String {
        var redirects = 0
        var current = urlStr
        while (true) {
            val conn = URL(current).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("User-Agent", USER_AGENT)
            when (val code = conn.responseCode) {
                in 301..308 -> {
                    val loc = conn.getHeaderField("Location") ?: throw RuntimeException("重定向无 Location")
                    conn.disconnect()
                    if (++redirects > MAX_REDIRECTS) throw RuntimeException("重定向过多")
                    // 相对 Location 基于当前 URL 解析（与 download() 同款写法）
                    current = if (loc.startsWith("http")) {
                        loc
                    } else {
                        URL(URL(current), loc).toString()
                    }
                    continue
                }
                200 -> {
                    val text = conn.inputStream.bufferedReader().use { it.readText() }
                    conn.disconnect()
                    return text
                }
                else -> {
                    conn.disconnect()
                    throw RuntimeException("HTTP $code")
                }
            }
        }
    }

    /**
     * 并发探测候选源，返回**吞吐最好**的那个；全部失败返回 null。
     *
     * ## 为什么按"下载量/耗时"排而不是按"首字节耗时"排
     * 旧实现只发 1KB Range 请求、按「连接+首字节」耗时排序。问题在于
     * **直连 github.com 的首字节很快、但吞吐只有 ~40 KB/s**（同一台机器实测：
     * 直连 40 KB/s，`github.ednovas.xyz` 890 KB/s、`github.boki.moe` 706 KB/s）——
     * 于是每次探测都是直连胜出，30 MB 的包要下十几分钟，用户感受就是"更新特别慢"。
     *
     * 现在读最多 [PROBE_BYTES]（256 KB）并记录**实际读到的字节数 / 耗时**，
     * 按吞吐排序。对不支持 Range 的源会返回 200 + 完整内容 —— 那也没关系，
     * 我们只读到 256 KB 就断开，测的仍然是真实吞吐。
     * 额外嗅探响应内容：部分镜像对不存在的大文件返回 200+HTML 错误页
     *（HTTP 码正常但内容无效），以 "<html"/"<?xml" 开头视为错误页排除。
     */
    /** 探测时最多读多少字节来评估吞吐（256 KB：足够区分 40 KB/s 与 900 KB/s） */
    private const val PROBE_READ_BYTES = 256 * 1024

    fun probeFastest(urls: List<String>, probeTimeoutMs: Int = 8000): String? {
        val candidates = urls.filter { it.isNotBlank() }.distinct()
        if (candidates.isEmpty()) return null
        // key = url, value = 吞吐（字节/毫秒，越大越好）
        val results = java.util.concurrent.ConcurrentHashMap<String, Double>()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(minOf(candidates.size, 4))
        try {
            val futures = candidates.map { u ->
                pool.submit {
                    try {
                        val start = System.currentTimeMillis()
                        val conn = URL(u).openConnection() as HttpURLConnection
                        conn.instanceFollowRedirects = true
                        conn.connectTimeout = probeTimeoutMs
                        conn.readTimeout = probeTimeoutMs
                        conn.setRequestProperty("User-Agent", USER_AGENT)
                        conn.setRequestProperty("Range", "bytes=0-$PROBE_READ_BYTES")
                        val code = conn.responseCode
                        if (code !in 200..299) {
                            conn.disconnect()
                            return@submit
                        }
                        var read = 0
                        val buf = ByteArray(16 * 1024)
                        var head = ""
                        conn.inputStream.use { ins ->
                            while (read < PROBE_READ_BYTES) {
                                val n = ins.read(buf, 0, minOf(buf.size, PROBE_READ_BYTES - read))
                                if (n <= 0) break
                                if (read == 0) head = String(buf, 0, minOf(n, 64), Charsets.ISO_8859_1).trimStart()
                                read += n
                            }
                        }
                        conn.disconnect()
                        // 内容嗅探：HTML/XML 错误页视为不可用（镜像"假 200"）
                        if (head.startsWith("<html", ignoreCase = true) || head.startsWith("<?xml", ignoreCase = true)) {
                            return@submit
                        }
                        val ms = (System.currentTimeMillis() - start).coerceAtLeast(1)
                        // 至少读到 8KB 才算这个源可用，避免"连上了但没数据"被当成最快
                        if (read >= 8 * 1024) results[u] = read.toDouble() / ms
                    } catch (_: Exception) {
                        // 该源不可达，跳过
                    }
                }
            }
            futures.forEach { it.get() }
        } finally {
            pool.shutdownNow()
        }
        // 注意是 maxByOrNull：value 现在是**吞吐**（字节/毫秒），越大越好。
        // 旧实现按"首字节耗时"排，那里确实该取 min —— 语义变了，这里必须跟着改，
        // 否则会挑中最慢的源（而它恰好是直连 GitHub）。
        return results.entries.maxByOrNull { it.value }?.key
    }

    /**
     * 多源下载：先探测最快源，按序尝试；任一源失败自动回退下一个
     * （断点续传贯穿：已下载部分保留，换源后继续追加）。
     *
     * 断网场景对策：单源失败重试 [perSourceRetries] 次（间隔 [retryDelayMs]）；
     * 全部源都失败后等待 [roundDelayMs] 再整体重来，共 [maxRounds] 轮——
     * 下载中途断网会自动等待网络恢复后从断点续传，不会白白丢弃已下载数据。
     * 仍失败返回 null 时，目标文件保留部分内容供下次续传。
     *
     * @param onSourceError 单个源失败时回调（源 URL, 错误消息），用于界面展示诊断
     * @return 实际使用的 URL；全部失败返回 null
     */
    fun downloadFromSources(
        urls: List<String>,
        dest: File,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        onSourceError: (String, String) -> Unit = { _, _ -> },
        shouldCancel: () -> Boolean = { false },
        validate: (File) -> Boolean,
        maxRounds: Int = 4,
        roundDelayMs: Long = 5000,
        perSourceRetries: Int = 2,
        retryDelayMs: Long = 2000,
    ): String? {
        val candidates = urls.filter { it.isNotBlank() }.distinct()
        if (candidates.isEmpty()) return null
        // 断点归属：部分内容假定只属于某一个源。换源即清除——
        // 不同镜像可能残留不同的历史构建/内容，跨源续传会拼出前后矛盾的半成品
        // （魔数校验挡不住"半 A 半 B"），宁可重下不可拼错。
        var partialOwner: String? = null
        for (round in 1..maxRounds) {
            // 每轮重新探测最快源：断网恢复后最优镜像可能变化
            val best = probeFastest(candidates)
            val ordered = (listOfNotNull(best) + candidates.filter { it != best })
            for (u in ordered) {
                if (partialOwner != u) {
                    if (partialOwner != null && dest.exists() && dest.length() > 0L) {
                        runCatching { dest.delete() }
                    }
                    partialOwner = u
                }
                var attempt = 0
                while (attempt <= perSourceRetries) {
                    if (shouldCancel()) return null
                    try {
                        download(u, dest, onProgress, shouldCancel, validate)
                        return u
                    } catch (e: InterruptedException) {
                        return null // 用户取消
                    } catch (e: Exception) {
                        attempt++
                        onSourceError(u, e.message ?: "未知错误")
                        if (attempt <= perSourceRetries && !shouldCancel()) {
                            try { Thread.sleep(retryDelayMs) } catch (_: InterruptedException) { return null }
                        }
                    }
                }
                // 回退下一源（dest 的部分内容已保留，续传继续）
            }
            if (round < maxRounds) {
                onSourceError("", "所有源不可用（可能断网），${roundDelayMs / 1000} 秒后自动重试（第 ${round + 1}/$maxRounds 轮）…")
                try { Thread.sleep(roundDelayMs) } catch (_: InterruptedException) { return null }
            }
        }
        return null
    }
}
