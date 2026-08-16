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
     * 下载文件（支持断点续传：目标文件已有部分时用 Range 追加；
     * 服务器不支持 Range 时自动从头开始）。
     * @param urlStr 下载地址
     * @param dest 目标文件
     * @param onProgress (downloadedBytes, totalBytes) —— total 可能为 -1（未知）
     * @param shouldCancel 返回 true 时中止下载（抛 InterruptedException，部分文件保留可续传）
     */
    @Throws(Exception::class)
    fun download(
        urlStr: String,
        dest: File,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        shouldCancel: () -> Boolean = { false },
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
            onProgress(downloaded, downloaded)
            conn.disconnect()
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
                    current = loc
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
     * 并发探测候选源：对每个 URL 发起 1KB Range 请求，测「连接+首字节」耗时，
     * 返回最快的 URL；全部失败返回 null。
     */
    fun probeFastest(urls: List<String>, probeTimeoutMs: Int = 4000): String? {
        val candidates = urls.filter { it.isNotBlank() }.distinct()
        if (candidates.isEmpty()) return null
        val results = java.util.concurrent.ConcurrentHashMap<String, Long>()
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
                        conn.setRequestProperty("Range", "bytes=0-1023")
                        val code = conn.responseCode
                        if (code !in 200..299) {
                            conn.disconnect()
                            return@submit
                        }
                        conn.inputStream.use { ins ->
                            val buf = ByteArray(1024)
                            var read = 0
                            while (read < buf.size) {
                                val n = ins.read(buf, read, buf.size - read)
                                if (n == -1) break
                                read += n
                            }
                        }
                        conn.disconnect()
                        results[u] = System.currentTimeMillis() - start
                    } catch (_: Exception) {
                        // 该源不可达，跳过
                    }
                }
            }
            futures.forEach { it.get() }
        } finally {
            pool.shutdownNow()
        }
        return results.entries.minByOrNull { it.value }?.key
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
        maxRounds: Int = 4,
        roundDelayMs: Long = 5000,
        perSourceRetries: Int = 2,
        retryDelayMs: Long = 2000,
    ): String? {
        val candidates = urls.filter { it.isNotBlank() }.distinct()
        if (candidates.isEmpty()) return null
        for (round in 1..maxRounds) {
            // 每轮重新探测最快源：断网恢复后最优镜像可能变化
            val best = probeFastest(candidates)
            val ordered = (listOfNotNull(best) + candidates.filter { it != best })
            for (u in ordered) {
                var attempt = 0
                while (attempt <= perSourceRetries) {
                    if (shouldCancel()) return null
                    try {
                        download(u, dest, onProgress, shouldCancel)
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
