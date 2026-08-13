package com.mcserver.launcher.core.download

import com.mcserver.launcher.data.DownloadState
import com.mcserver.launcher.util.KLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * 单任务下载管理器：进度 StateFlow + HTTP Range 断点续传 + 镜像回退。
 * 大文件（核心 jar / JRE 包）统一经此下载。
 */
object DownloadManager {

    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val state: StateFlow<DownloadState> = _state

    @Volatile
    private var cancelled = false

    fun cancel() {
        cancelled = true
    }

    /**
     * 下载文件。
     * @param onProgress 进度回调（doneBytes, totalBytes），total<=0 表示未知
     */
    suspend fun download(
        url: String,
        dest: File,
        mirrors: List<String> = emptyList(),
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ): Result<File> = withContext(Dispatchers.IO) {
        cancelled = false
        _state.value = DownloadState.Idle
        dest.parentFile?.mkdirs()
        val urls = listOf(url) + mirrors
        var lastError: Throwable? = null
        for ((idx, u) in urls.withIndex()) {
            if (cancelled) break
            val r = downloadOne(u, dest, onProgress)
            if (r.isSuccess) {
                _state.value = DownloadState.Done(dest.absolutePath)
                return@withContext r
            }
            lastError = r.exceptionOrNull()
            if (idx < urls.size - 1) {
                KLog.w("下载失败，尝试下一镜像(${idx + 2}/${urls.size}): ${lastError?.message}")
                File(dest.absolutePath + ".part").delete()
            }
        }
        if (cancelled) {
            File(dest.absolutePath + ".part").delete()
            _state.value = DownloadState.Idle
            Result.failure(Exception("已取消"))
        } else {
            _state.value = DownloadState.Failed(lastError?.message ?: "下载失败")
            Result.failure(lastError ?: Exception("下载失败"))
        }
    }

    private fun downloadOne(
        url: String,
        dest: File,
        onProgress: (Long, Long) -> Unit,
    ): Result<File> {
        val part = File(dest.absolutePath + ".part")
        try {
            var conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Kaze-SLauncher/2.0")

            // 断点续传
            val resumeAt = if (part.exists()) part.length() else 0L
            if (resumeAt > 0) conn.setRequestProperty("Range", "bytes=$resumeAt-")

            val code = conn.responseCode
            if (code == 416) { // range not satisfiable：文件其实已完整
                part.delete()
                conn.disconnect()
                return downloadOne(url, dest, onProgress)
            }
            val rangeHeader = conn.getHeaderField("Content-Range")
            val total = if (code == 206 && rangeHeader != null) {
                rangeHeader.substringAfter('/').toLongOrNull() ?: conn.contentLengthLong
            } else conn.contentLengthLong

            if (code != 200 && code != 206) {
                conn.disconnect()
                return Result.failure(Exception("HTTP $code"))
            }

            conn.inputStream.use { input ->
                RandomAccessFile(part, "rw").use { raf ->
                    if (resumeAt > 0) raf.seek(resumeAt)
                    val buf = ByteArray(64 * 1024)
                    var done = resumeAt
                    while (true) {
                        if (cancelled) {
                            conn.disconnect()
                            return Result.failure(Exception("已取消"))
                        }
                        val n = input.read(buf)
                        if (n < 0) break
                        raf.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            _state.value = DownloadState.Progress(done, total)
                            onProgress(done, total)
                        }
                    }
                }
            }
            conn.disconnect()

            if (total > 0 && part.length() != total) {
                return Result.failure(Exception("下载不完整: ${part.length()}/$total"))
            }
            if (dest.exists()) dest.delete()
            if (!part.renameTo(dest)) {
                part.copyTo(dest, overwrite = true)
                part.delete()
            }
            return Result.success(dest)
        } catch (e: Exception) {
            return Result.failure(e)
        }
    }
}