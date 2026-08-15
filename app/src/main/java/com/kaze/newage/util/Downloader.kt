package com.kaze.newage.util

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 轻量 HTTP 下载器：自动跟随重定向（最多 5 跳）、断点续传、进度回调。
 * 来源：v2 EnvManager.downloadToFile 思路重构（自有代码）。
 */
object Downloader {

    private const val MAX_REDIRECTS = 5
    private const val USER_AGENT = "KazeSLauncher/3.0 (Android; Minecraft Server Launcher)"

    /**
     * 下载文件。
     * @param urlStr 下载地址
     * @param dest 目标文件
     * @param onProgress (downloadedBytes, totalBytes) —— total 可能为 -1（未知）
     */
    @Throws(Exception::class)
    fun download(
        urlStr: String,
        dest: File,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
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
                    var lastBytes = downloaded
                    while (true) {
                        val n = input.read(buf)
                        if (n == -1) break
                        out.write(buf, 0, n)
                        downloaded += n
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate >= 150) {
                            onProgress(downloaded, total)
                            lastUpdate = now
                            lastBytes = downloaded
                        }
                    }
                }
                try {
                    out.fd.sync() // 强制落盘（模拟器异步写层）
                } catch (_: Exception) { }
            }
            onProgress(downloaded, downloaded)
            conn.disconnect()
            return
        }
    }
}
