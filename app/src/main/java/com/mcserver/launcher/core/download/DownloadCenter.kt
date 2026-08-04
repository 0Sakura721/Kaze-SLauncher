package com.mcserver.launcher.core.download

import com.mcserver.launcher.data.DownloadStatus
import com.mcserver.launcher.data.DownloadTask
import com.mcserver.launcher.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 统一下载中心:全局队列,多任务并发,断点续传,暂停/恢复/取消,多源自动切换。
 */
object DownloadCenter {

    private const val TAG = "DownloadCenter"
    private const val MAX_CONCURRENT = 2

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    private val running = AtomicInteger(0)
    private val cancelFlags = mutableMapOf<String, AtomicBoolean>()

    /** 正在进行的任务数(UI 角标) */
    val activeCount: Int get() = _tasks.value.count { it.isActive }

    /** 注册任务并立即开始 */
    fun enqueue(
        id: String,
        title: String,
        urls: List<String>,
        destFile: File
    ): DownloadTask {
        val task = DownloadTask(id = id, title = title, urls = urls, destFile = destFile)
        _tasks.value = listOf(task) + _tasks.value
        cancelFlags[id] = AtomicBoolean(false)
        pump()
        return task
    }

    fun pause(id: String) {
        update(id) { it.copy(status = DownloadStatus.PAUSED) }
    }

    fun resume(id: String) {
        update(id) { it.copy(status = DownloadStatus.PENDING) }
        pump()
    }

    fun cancel(id: String) {
        cancelFlags[id]?.set(true)
    }

    fun remove(id: String) {
        cancel(id)
        _tasks.value = _tasks.value.filterNot { it.id == id }
        cancelFlags.remove(id)
    }

    private fun update(id: String, transform: (DownloadTask) -> DownloadTask) {
        _tasks.value = _tasks.value.map { if (it.id == id) transform(it) else it }
    }

    private fun pump() {
        while (running.get() < MAX_CONCURRENT) {
            val next = _tasks.value.firstOrNull {
                it.status == DownloadStatus.PENDING
            } ?: break
            if (running.incrementAndGet() <= MAX_CONCURRENT) {
                scope.launch { download(next.id) }
            } else {
                running.decrementAndGet()
                break
            }
        }
    }

    private suspend fun download(id: String) {
        val task = _tasks.value.firstOrNull { it.id == id } ?: run { running.decrementAndGet(); return }
        val cancel = cancelFlags[id] ?: AtomicBoolean(false)
        task.destFile.parentFile?.mkdirs()

        try {
            // 断点续传:读取 .part 文件已有大小
            val partFile = File(task.destFile.parentFile, task.destFile.name + ".part")
            var offset = partFile.length()
            if (offset > 0) update(id) { it.copy(status = DownloadStatus.DOWNLOADING, downloadedBytes = offset) }
            else update(id) { it.copy(status = DownloadStatus.DOWNLOADING) }

            var lastError: Exception? = null
            for (url in task.urls) {
                if (cancel.get()) break
                try {
                    offset = downloadFromUrl(id, url, partFile, offset, cancel)
                    // 全部下载完成
                    if (cancel.get()) {
                        update(id) { it.copy(status = DownloadStatus.CANCELED) }
                        running.decrementAndGet()
                        return
                    }
                    // 校验非空后落盘
                    if (partFile.length() > 0) {
                        partFile.renameTo(task.destFile)
                        update(id) { it.copy(status = DownloadStatus.COMPLETED, progress = 1f, downloadedBytes = partFile.length(), totalBytes = partFile.length()) }
                        Logger.i("下载完成: ${task.title}")
                        running.decrementAndGet()
                        return
                    }
                } catch (e: Exception) {
                    lastError = e
                    Logger.w("下载失败 $url: ${e.message}")
                    // 切换源时从头开始
                    offset = 0
                    if (partFile.exists()) partFile.delete()
                }
                if (cancel.get()) break
            }
            update(id) { it.copy(status = DownloadStatus.FAILED, error = lastError?.message) }
        } catch (e: Exception) {
            Logger.e("download failed", e)
            update(id) { it.copy(status = DownloadStatus.FAILED, error = e.message) }
        } finally {
            cancelFlags.remove(id)
            running.decrementAndGet()
            pump()
        }
    }

    private fun downloadFromUrl(
        id: String,
        urlStr: String,
        partFile: File,
        initialOffset: Long,
        cancel: AtomicBoolean
    ): Long {
        var conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 15000
        conn.readTimeout = 60000
        if (initialOffset > 0) {
            conn.setRequestProperty("Range", "bytes=$initialOffset-")
        }
        var redirects = 0
        while (redirects < 5 && conn.responseCode in listOf(301, 302, 303, 307, 308)) {
            val loc = conn.getHeaderField("Location") ?: break
            conn.disconnect()
            conn = URL(loc).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            if (initialOffset > 0) conn.setRequestProperty("Range", "bytes=$initialOffset-")
            redirects++
        }
        if (conn.responseCode != HttpURLConnection.HTTP_OK && conn.responseCode != 206) {
            throw RuntimeException("HTTP ${conn.responseCode}")
        }
        val contentLength = conn.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
        val total = if (contentLength > 0) initialOffset + contentLength else -1L
        if (total > 0) update(id) { it.copy(totalBytes = total) }

        val buffer = ByteArray(64 * 1024)
        var downloaded = initialOffset
        val startTime = System.currentTimeMillis()
        var lastUpdate = startTime
        var lastBytes = downloaded

        conn.inputStream.use { input ->
            FileOutputStream(partFile, initialOffset > 0).use { out ->
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    if (cancel.get()) throw CancellationException()
                    out.write(buffer, 0, read)
                    downloaded += read
                    val now = System.currentTimeMillis()
                    if (now - lastUpdate >= 200) {
                        val speed = (downloaded - lastBytes) * 1000 / (now - lastUpdate).coerceAtLeast(1)
                        val progress = if (total > 0) downloaded.toFloat() / total else 0f
                        update(id) {
                            it.copy(
                                progress = progress,
                                downloadedBytes = downloaded,
                                totalBytes = total,
                                speedBytesPerSec = speed
                            )
                        }
                        lastUpdate = now
                        lastBytes = downloaded
                    }
                }
            }
        }
        // 源切换时重置 offset
        return if (cancel.get()) downloaded else partFile.length()
    }

    private class CancellationException : Exception()
}
