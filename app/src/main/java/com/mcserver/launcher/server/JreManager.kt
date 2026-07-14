package com.mcserver.launcher.server

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.mcserver.launcher.MainActivity
import com.mcserver.launcher.McApplication
import com.mcserver.launcher.R
import com.mcserver.launcher.data.JreInfo
import com.mcserver.launcher.data.JreStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class JreManager(private val context: Context) {

    private val _jreInfo = MutableStateFlow(JreInfo())
    val jreInfo: StateFlow<JreInfo> = _jreInfo.asStateFlow()

    var selectedVersion: String = "21"
    var selectedPackage: String = "jdk"

    /** 镜像源: ""=默认Adoptium, "aliyun"=阿里云, "tsinghua"=清华, "ustc"=中科大 */
    var mirror: String = ""
        set(value) { field = value; savePrefs() }

    var customBaseUrl: String = ""
        set(value) { field = value; savePrefs() }

    private val pauseFlag = AtomicBoolean(false)
    private val downloadJob = AtomicReference<Job?>(null)
    private val downloadScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val prefsFile: File get() = File(context.filesDir, "jre_prefs.txt")
    private fun partialFile(): File = File(context.cacheDir, "java_download.partial")
    private fun jreDirFor(version: String): File = File(context.filesDir, "java_$version")
    private fun javaExecutableFor(version: String): File = File(jreDirFor(version), "bin/java")

    val currentJavaPath: String? get() {
        val exe = javaExecutableFor(selectedVersion)
        return if (exe.exists() && exe.canExecute()) exe.absolutePath else null
    }

    companion object {
        internal const val ADOPTIUM_API = "https://api.adoptium.net/v3"
        internal const val ALIYUN_MIRROR = "https://mirrors.aliyun.com/adoptium"
        internal const val TSINGHUA_MIRROR = "https://mirrors.tuna.tsinghua.edu.cn/Adoptium"
        internal const val USTC_MIRROR = "https://mirrors.ustc.edu.cn/adoptium"

        fun getDeviceArch(): String {
            if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty() &&
                Build.SUPPORTED_64_BIT_ABIS[0].contains("arm64")) return "aarch64"
            return "arm"
        }

        val MIRROR_OPTIONS = listOf(
            "" to "Adoptium (官方)",
            "aliyun" to "阿里云镜像",
            "tsinghua" to "清华大学镜像",
            "ustc" to "中科大镜像"
        )
    }

    init {
        loadPrefs()
        _jreInfo.value = checkJre()
        startDownloadService()
    }

    private fun loadPrefs() {
        try {
            if (prefsFile.exists()) {
                val lines = prefsFile.readLines()
                if (lines.size >= 3) { selectedVersion = lines[0]; selectedPackage = lines[1].ifEmpty { "jdk" }; customBaseUrl = lines.getOrElse(2) { "" } }
                if (lines.size >= 4) mirror = lines[3]
            }
        } catch (_: Exception) {}
    }

    private fun savePrefs() {
        try { prefsFile.writeText("$selectedVersion\n$selectedPackage\n$customBaseUrl\n$mirror") } catch (_: Exception) {}
    }

    private fun getApiBase(): String = when {
        customBaseUrl.isNotBlank() -> customBaseUrl
        mirror == "aliyun" -> ALIYUN_MIRROR
        mirror == "tsinghua" -> TSINGHUA_MIRROR
        mirror == "ustc" -> USTC_MIRROR
        else -> ADOPTIUM_API
    }

    fun setVersionAndPackage(version: String, pkg: String) {
        selectedVersion = version; selectedPackage = pkg
        savePrefs(); _jreInfo.value = checkJre()
    }

    // ─── 版本列表 ───

    suspend fun fetchAvailableVersions(): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val apiUrl = "${getApiBase()}/info/available_releases"
            val connection = URL(apiUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 10000; connection.readTimeout = 10000; connection.connect()
            val json = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
            connection.disconnect()
            val arr = JSONArray(json)
            val versions = mutableListOf<String>()
            for (i in 0 until arr.length()) versions.add(arr.getInt(i).toString())
            Result.success(versions.sortedByDescending { it.toInt() })
        } catch (e: Exception) { Result.failure(e) }
    }

    fun checkJre(): JreInfo {
        val exe = javaExecutableFor(selectedVersion)
        val installed = exe.exists() && exe.canExecute()
        val installedVersions = mutableListOf<String>()
        context.filesDir.listFiles()?.forEach { dir ->
            if (dir.name.startsWith("java_")) {
                val v = dir.name.removePrefix("java_")
                if (File(dir, "bin/java").let { it.exists() && it.canExecute() })
                    installedVersions.add(v)
            }
        }
        return if (installed) JreInfo(JreStatus.INSTALLED, selectedVersion, exe.absolutePath,
            installedVersions = installedVersions)
        else JreInfo(JreStatus.NOT_INSTALLED, installedVersions = installedVersions)
    }

    /** 删除某个已安装的 Java 版本 */
    fun deleteInstalledVersion(version: String): Result<Unit> {
        val dir = jreDirFor(version)
        return if (dir.exists()) {
            dir.deleteRecursively()
            _jreInfo.value = checkJre()
            Result.success(Unit)
        } else Result.failure(Exception("目录不存在"))
    }

    private fun buildDownloadUrl(): String {
        val base = getApiBase()
        return "$base/binary/latest/$selectedVersion/ga/linux/${getDeviceArch()}/$selectedPackage/hotspot/normal/eclipse?project=jdk"
    }

    // ─── 暂停/继续/取消 ───

    fun pauseDownload() {
        pauseFlag.set(true)
        _jreInfo.value = _jreInfo.value.copy(status = JreStatus.PAUSED, isPaused = true, downloadSpeedBytesPerSec = 0, remainingSeconds = 0)
        stopDownloadService()
    }

    fun resumeDownload() {
        pauseFlag.set(false)
        _jreInfo.value = _jreInfo.value.copy(status = JreStatus.DOWNLOADING, isPaused = false)
        startDownloadService()
    }

    fun cancelDownload() { cancelAndClean(deletePartial = true, deleteInstalled = false) }

    private fun cancelAndClean(deletePartial: Boolean, deleteInstalled: Boolean) {
        pauseFlag.set(true)
        downloadJob.get()?.cancel()
        if (deletePartial) { partialFile().delete() }
        if (deleteInstalled) { jreDirFor(selectedVersion).deleteRecursively() }
        _jreInfo.value = checkJre()
        stopDownloadService()
    }

    // ─── 前台下载服务 ───

    private fun startDownloadService() {
        val intent = Intent(context, DownloadForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
        else context.startService(intent)
    }

    private fun stopDownloadService() {
        context.stopService(Intent(context, DownloadForegroundService::class.java))
    }

    // ─── 下载（后台、断点续传、暂停、ETA） ───

    suspend fun downloadAndInstall(
        onProgress: (Float, Long, Long) -> Unit = { _, _, _ -> }
    ): Result<String> {
        // 取消之前的下载
        downloadJob.get()?.cancel()
        pauseFlag.set(false)
        startDownloadService()

        val job = downloadScope.launch {
            try {
                val pf = partialFile()
                val initialOffset = if (pf.exists()) pf.length() else 0L

                val initialProgress = if (initialOffset > 0) 0f else 0f
                _jreInfo.value = _jreInfo.value.copy(
                    status = JreStatus.DOWNLOADING, downloadProgress = initialProgress,
                    downloadedBytes = initialOffset, totalBytes = 0, isPaused = false,
                    downloadSpeedBytesPerSec = 0, remainingSeconds = 0
                )
                onProgress(initialProgress, initialOffset, 0)

                val url = buildDownloadUrl()
                var connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 30000; connection.readTimeout = 300000

                if (initialOffset > 0) {
                    connection.setRequestProperty("Range", "bytes=$initialOffset-")
                    connection.connect()
                    val code = connection.responseCode
                    if (code != 206 && code != 200) { pf.delete(); connection = URL(url).openConnection() as HttpURLConnection; connection.connect() }
                    else if (code == 200) pf.delete()
                } else connection.connect()

                val contentLength = connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
                val totalSize: Long = if (contentLength > 0) initialOffset + contentLength else -1L
                if (totalSize > 0) _jreInfo.value = _jreInfo.value.copy(totalBytes = totalSize)

                var downloaded = initialOffset
                val fos = FileOutputStream(pf, initialOffset > 0)
                val bis = BufferedInputStream(connection.inputStream)
                var lastSpeedBytes = downloaded
                var lastSpeedTime = System.currentTimeMillis()
                var currentSpeed: Long = 0

                try {
                    val buffer = ByteArray(16384)
                    var bytesRead: Int
                    while (bis.read(buffer).also { bytesRead = it } != -1) {
                        if (pauseFlag.get()) {
                            fos.flush(); fos.close(); bis.close(); connection.disconnect()
                            _jreInfo.value = _jreInfo.value.copy(
                                status = JreStatus.PAUSED, isPaused = true, downloadedBytes = downloaded,
                                downloadSpeedBytesPerSec = 0, remainingSeconds = 0,
                                downloadProgress = if (totalSize > 0) downloaded.toFloat() / totalSize else 0f
                            )
                            stopDownloadService()
                            return@launch
                        }
                        fos.write(buffer, 0, bytesRead)
                        downloaded += bytesRead

                        // 200ms 速率刷新 + ETA
                        val now = System.currentTimeMillis()
                        val elapsed = now - lastSpeedTime
                        if (elapsed >= 200) {
                            currentSpeed = if (elapsed > 0) ((downloaded - lastSpeedBytes) * 1000L / elapsed) else 0
                            lastSpeedBytes = downloaded; lastSpeedTime = now
                        }

                        val remaining = if (currentSpeed > 0 && totalSize > 0) (totalSize - downloaded) / currentSpeed else 0L
                        val effectiveTotal = if (totalSize > 0) totalSize else downloaded * 2
                        val progress = (downloaded.toFloat() / effectiveTotal).coerceIn(0f, 1f)

                        _jreInfo.value = _jreInfo.value.copy(
                            downloadProgress = progress, downloadedBytes = downloaded,
                            totalBytes = effectiveTotal, downloadSpeedBytesPerSec = currentSpeed,
                            remainingSeconds = remaining
                        )
                        onProgress(progress, downloaded, effectiveTotal)
                    }
                } finally {
                    try { fos.close() } catch (_: Exception) {}
                    try { bis.close() } catch (_: Exception) {}
                    try { connection.disconnect() } catch (_: Exception) {}
                }

                _jreInfo.value = _jreInfo.value.copy(status = JreStatus.EXTRACTING, downloadSpeedBytesPerSec = 0, remainingSeconds = 0)
                onProgress(1f, downloaded, downloaded)

                val targetDir = jreDirFor(selectedVersion)
                if (targetDir.exists()) targetDir.deleteRecursively()
                targetDir.mkdirs()
                extractTarGz(pf, targetDir)
                javaExecutableFor(selectedVersion).setExecutable(true)
                pf.delete()
                _jreInfo.value = checkJre()
                stopDownloadService()
            } catch (e: CancellationException) { /* expected */ } catch (e: Exception) {
                _jreInfo.value = JreInfo(status = JreStatus.ERROR, installedVersions = emptyList())
                stopDownloadService()
            }
        }
        downloadJob.set(job)
        job.join()
        return if (jreInfo.value.status == JreStatus.INSTALLED) Result.success(jreInfo.value.path)
        else Result.success("paused or stopped")
    }

    private fun extractTarGz(tarGzFile: File, destDir: File) {
        ProcessBuilder().command("tar", "xzf", tarGzFile.absolutePath, "-C", destDir.absolutePath, "--strip-components=1")
            .redirectErrorStream(true).start().waitFor().let { if (it != 0) throw RuntimeException("解压失败: $it") }
    }
}

data class MirrorLatency(val key: String, val name: String, val latencyMs: Long, val isBest: Boolean)

/** 测试所有镜像延迟，返回按延迟排序的列表 */
suspend fun testAllMirrors(): List<MirrorLatency> = withContext(Dispatchers.IO) {
    JreManager.MIRROR_OPTIONS.map { (key, name) ->
        val ms = testUrlLatency(
            when (key) {
                "" -> "${JreManager.ADOPTIUM_API}/info/available_releases"
                "aliyun" -> "${JreManager.ALIYUN_MIRROR}/info/available_releases"
                "tsinghua" -> "${JreManager.TSINGHUA_MIRROR}/info/available_releases"
                "ustc" -> "${JreManager.USTC_MIRROR}/info/available_releases"
                else -> "${JreManager.ADOPTIUM_API}/info/available_releases"
            }
        )
        MirrorLatency(key, name, ms, false)
    }.sortedBy { it.latencyMs }.let { sorted ->
        val best = sorted.firstOrNull()?.latencyMs ?: Long.MAX_VALUE
        sorted.map { it.copy(isBest = it.latencyMs == best) }
    }
}

private fun testUrlLatency(url: String): Long {
    return try {
        val start = System.currentTimeMillis()
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000; conn.readTimeout = 5000
        conn.requestMethod = "HEAD"
        conn.connect()
        conn.responseCode
        conn.disconnect()
        System.currentTimeMillis() - start
    } catch (e: Exception) { Long.MAX_VALUE }
}

// ─── 后台下载前台服务 ───

class DownloadForegroundService : Service() {
    companion object { const val NOTIFICATION_ID = 2001 }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, McApplication.CHANNEL_NOTIFICATIONS)
            .setContentTitle("Java 运行时下载中")
            .setContentText("正在后台下载 Java 运行时")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pi).setOngoing(true).build()
        startForeground(NOTIFICATION_ID, notification)
        return START_STICKY
    }
}
