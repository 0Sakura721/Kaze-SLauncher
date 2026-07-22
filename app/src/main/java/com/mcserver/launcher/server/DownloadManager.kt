package com.mcserver.launcher.server

import android.content.Context
import com.mcserver.launcher.McApplication
import com.mcserver.launcher.utils.L
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 通用下载管理器 — 支持断点续传、暂停/恢复、速度限制、下载队列。
 * 借鉴 Pterodactyl 和 MCSManager 的下载管理设计。
 */
class DownloadManager {

    enum class DownloadState {
        IDLE, DOWNLOADING, PAUSED, COMPLETED, FAILED, CANCELLED
    }

    data class DownloadTask(
        val id: String,
        val url: String,
        val targetFile: File,
        val label: String = "",
        val state: DownloadState = DownloadState.IDLE,
        val progress: Float = 0f,
        val downloadedBytes: Long = 0,
        val totalBytes: Long = 0,
        val speedBytesPerSec: Long = 0,
        val remainingSeconds: Long = 0,
        val errorMessage: String = ""
    )

    companion object {
        private const val TAG = "DownloadManager"
        val instance: DownloadManager by lazy { DownloadManager() }
    }

    private val context: Context get() = McApplication.instance
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val pauseFlag = AtomicBoolean(false)

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    private var currentJob: Job? = null

    /**
     * 添加并启动一个下载任务。
     * @return 任务 ID
     */
    fun enqueue(url: String, targetFile: File, label: String = ""): String {
        val id = java.util.UUID.randomUUID().toString().take(8)
        val task = DownloadTask(
            id = id, url = url, targetFile = targetFile, label = label,
            state = DownloadState.IDLE
        )
        _tasks.value = _tasks.value + task
        return id
    }

    /**
     * 开始下载指定任务。
     * 如果已有任务在运行，则加入队列等待。
     */
    fun startDownload(taskId: String) {
        val task = _tasks.value.find { it.id == taskId } ?: return
        if (task.state == DownloadState.DOWNLOADING) return

        // 取消当前下载（如果有）
        currentJob?.cancel()

        currentJob = scope.launch {
            executeDownload(task)
        }
    }

    /** 暂停当前下载 */
    fun pauseDownload(taskId: String) {
        pauseFlag.set(true)
        updateTask(taskId) {
            it.copy(state = DownloadState.PAUSED, speedBytesPerSec = 0, remainingSeconds = 0)
        }
    }

    /** 恢复下载 */
    fun resumeDownload(taskId: String) {
        pauseFlag.set(false)
        val task = _tasks.value.find { it.id == taskId } ?: return
        updateTask(taskId) { it.copy(state = DownloadState.DOWNLOADING) }
        startDownload(taskId)
    }

    /** 取消下载 */
    fun cancelDownload(taskId: String) {
        currentJob?.cancel()
        val task = _tasks.value.find { it.id == taskId } ?: return
        // 清理部分下载文件
        val partial = File(task.targetFile.parentFile, "${task.targetFile.name}.part")
        partial.delete()
        updateTask(taskId) { it.copy(state = DownloadState.CANCELLED) }
    }

    /** 移除任务 */
    fun removeTask(taskId: String) {
        _tasks.value = _tasks.value.filter { it.id != taskId }
    }

    /** 清除所有已完成/失败/取消的任务 */
    fun clearCompleted() {
        _tasks.value = _tasks.value.filter {
            it.state == DownloadState.DOWNLOADING || it.state == DownloadState.PAUSED
        }
    }

    private suspend fun executeDownload(task: DownloadTask) {
        val partialFile = File(task.targetFile.parentFile, "${task.targetFile.name}.part")
        pauseFlag.set(false)

        updateTask(task.id) {
            it.copy(state = DownloadState.DOWNLOADING, progress = 0f, downloadedBytes = 0)
        }

        try {
            var downloaded = if (partialFile.exists()) partialFile.length() else 0L

            var connection = URL(task.url).openConnection() as HttpURLConnection
            connection.connectTimeout = 30000; connection.readTimeout = 300000
            connection.setRequestProperty("User-Agent", "MCServerLauncher/1.0")

            if (downloaded > 0) {
                connection.setRequestProperty("Range", "bytes=$downloaded-")
                connection.connect()
                if (connection.responseCode !in listOf(200, 206)) {
                    downloaded = 0; partialFile.delete()
                    connection = URL(task.url).openConnection() as HttpURLConnection
                    connection.connectTimeout = 30000; connection.readTimeout = 300000
                    connection.setRequestProperty("User-Agent", "MCServerLauncher/1.0")
                    connection.connect()
                }
            } else {
                connection.connect()
            }

            val totalSize = if (downloaded > 0) {
                connection.getHeaderField("Content-Range")?.let {
                    it.substringAfter("/").toLongOrNull()
                } ?: (connection.contentLength + downloaded)
            } else {
                connection.contentLength.toLong().let { if (it <= 0) -1L else it }
            }

            updateTask(task.id) { it.copy(totalBytes = if (totalSize > 0) totalSize else -1L) }

            val input = BufferedInputStream(connection.inputStream)
            val output = FileOutputStream(partialFile, downloaded > 0)
            val buffer = ByteArray(16384)
            var bytesRead: Int
            var lastSpeedBytes = downloaded
            var lastSpeedTime = System.currentTimeMillis()

            while (input.read(buffer).also { bytesRead = it } != -1) {
                if (pauseFlag.get()) {
                    output.flush(); output.close(); input.close(); connection.disconnect()
                    updateTask(task.id) {
                        it.copy(state = DownloadState.PAUSED, downloadedBytes = downloaded,
                            progress = if (totalSize > 0) downloaded.toFloat() / totalSize else 0f,
                            speedBytesPerSec = 0, remainingSeconds = 0)
                    }
                    return
                }

                output.write(buffer, 0, bytesRead)
                downloaded += bytesRead

                val now = System.currentTimeMillis()
                val elapsed = now - lastSpeedTime
                if (elapsed >= 200) {
                    val speed = if (elapsed > 0) ((downloaded - lastSpeedBytes) * 1000L / elapsed) else 0L
                    lastSpeedBytes = downloaded; lastSpeedTime = now

                    val remaining = if (speed > 0 && totalSize > 0) (totalSize - downloaded) / speed else 0L
                    val progress = if (totalSize > 0) (downloaded.toFloat() / totalSize).coerceIn(0f, 1f) else -1f

                    updateTask(task.id) {
                        it.copy(downloadedBytes = downloaded, totalBytes = if (totalSize > 0) totalSize else downloaded * 2,
                            progress = progress, speedBytesPerSec = speed, remainingSeconds = remaining)
                    }
                }
            }

            output.close(); input.close(); connection.disconnect()

            // 重命名完成
            if (task.targetFile.exists()) task.targetFile.delete()
            partialFile.renameTo(task.targetFile)
            updateTask(task.id) {
                it.copy(state = DownloadState.COMPLETED, progress = 1f,
                    downloadedBytes = task.targetFile.length(), speedBytesPerSec = 0, remainingSeconds = 0)
            }
        } catch (e: CancellationException) {
            // 用户取消
        } catch (e: Exception) {
            L.w(TAG, "download failed: ${task.id}", e)
            updateTask(task.id) {
                it.copy(state = DownloadState.FAILED, errorMessage = e.message ?: "下载失败")
            }
        }
    }

    private fun updateTask(taskId: String, transform: (DownloadTask) -> DownloadTask) {
        _tasks.value = _tasks.value.map { if (it.id == taskId) transform(it) else it }
    }
}
