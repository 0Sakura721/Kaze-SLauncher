package com.mcserver.launcher.server

import android.content.Context
import android.os.Build
import com.mcserver.launcher.data.JreInfo
import com.mcserver.launcher.data.JreStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * JRE/JDK 管理器：检测、下载、管理 ARM 架构的 Java 运行时
 * 支持断点续传、暂停/继续
 */
class JreManager(private val context: Context) {

    private val _jreInfo = MutableStateFlow(JreInfo())
    val jreInfo: StateFlow<JreInfo> = _jreInfo.asStateFlow()

    var selectedVersion: String = "21"
    var selectedPackage: String = "jdk"

    var customBaseUrl: String = ""
        set(value) { field = value; savePrefs() }

    /** 暂停标记，跨协程使用 */
    private val pauseFlag = AtomicBoolean(false)

    /** 恢复标记：表示有暂停的下载可以继续 */
    private var pendingResume = false
    private var pendingPartialFile: File? = null

    private val prefsFile: File get() = File(context.filesDir, "jre_prefs.txt")

    /** 部分下载文件 */
    private fun partialFile(): File = File(context.cacheDir, "java_download.partial")

    private fun jreDirFor(version: String): File = File(context.filesDir, "java_$version")
    private fun javaExecutableFor(version: String): File = File(jreDirFor(version), "bin/java")

    val currentJavaPath: String? get() {
        val exe = javaExecutableFor(selectedVersion)
        return if (exe.exists() && exe.canExecute()) exe.absolutePath else null
    }

    companion object {
        private const val ADOPTIUM_API = "https://api.adoptium.net/v3"
        private const val AVAILABLE_RELEASES = "$ADOPTIUM_API/info/available_releases"

        fun getDeviceArch(): String {
            if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty() &&
                Build.SUPPORTED_64_BIT_ABIS[0].contains("arm64")
            ) return "aarch64"
            return "arm"
        }

        fun buildAdoptiumUrl(version: String, pkg: String, arch: String): String =
            "$ADOPTIUM_API/binary/latest/$version/ga/linux/$arch/$pkg/hotspot/normal/eclipse?project=jdk"
    }

    init {
        loadPrefs()
        checkPendingPartial()
        _jreInfo.value = checkJre()
    }

    // ─── 偏好 ───

    private fun loadPrefs() {
        try {
            if (prefsFile.exists()) {
                val lines = prefsFile.readLines()
                if (lines.size >= 3) {
                    selectedVersion = lines[0]
                    selectedPackage = lines[1].ifEmpty { "jdk" }
                    customBaseUrl = lines[2]
                }
            }
        } catch (_: Exception) {}
    }

    private fun savePrefs() {
        try { prefsFile.writeText("$selectedVersion\n$selectedPackage\n$customBaseUrl") }
        catch (_: Exception) {}
    }

    // ─── 检查未完成的下载 ───

    private fun checkPendingPartial() {
        val pf = partialFile()
        if (pf.exists() && pf.length() > 0) {
            pendingResume = true
            pendingPartialFile = pf
            _jreInfo.value = _jreInfo.value.copy(
                status = JreStatus.PAUSED,
                downloadedBytes = pf.length(),
                isPaused = true
            )
        }
    }

    // ─── 暂停 / 继续 ───

    fun pauseDownload() {
        pauseFlag.set(true)
        _jreInfo.value = _jreInfo.value.copy(status = JreStatus.PAUSED, isPaused = true)
    }

    fun resumeDownload() {
        pauseFlag.set(false)
        _jreInfo.value = _jreInfo.value.copy(status = JreStatus.DOWNLOADING, isPaused = false)
    }

    fun cancelDownload() {
        pauseFlag.set(true)
        partialFile().delete()
        pendingResume = false
        pendingPartialFile = null
        _jreInfo.value = checkJre()
    }

    // ─── 版本列表 ───

    suspend fun fetchAvailableVersions(): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val apiUrl = if (customBaseUrl.isNotBlank())
                "$customBaseUrl/info/available_releases" else AVAILABLE_RELEASES
            val connection = URL(apiUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 15000; connection.readTimeout = 15000
            connection.connect()
            val json = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
            connection.disconnect()
            val arr = JSONArray(json)
            val versions = mutableListOf<String>()
            for (i in 0 until arr.length()) versions.add(arr.getInt(i).toString())
            Result.success(versions.sortedByDescending { it.toInt() })
        } catch (e: Exception) { Result.failure(e) }
    }

    fun setVersionAndPackage(version: String, pkg: String) {
        selectedVersion = version; selectedPackage = pkg
        savePrefs()
        _jreInfo.value = checkJre()
    }

    // ─── 检查 ───

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

    // ─── 下载 URL ───

    private fun buildDownloadUrl(): String {
        if (customBaseUrl.isNotBlank())
            return customBaseUrl.replace("{version}", selectedVersion)
                .replace("{arch}", getDeviceArch()).replace("{package}", selectedPackage)
        return buildAdoptiumUrl(selectedVersion, selectedPackage, getDeviceArch())
    }

    // ─── 下载（支持断点续传 + 暂停） ───

    suspend fun downloadAndInstall(
        onProgress: (Float, Long, Long) -> Unit = { _, _, _ -> }
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            pauseFlag.set(false)

            val pf = partialFile()
            val initialOffset = if (pf.exists()) pf.length() else 0L

            _jreInfo.value = _jreInfo.value.copy(
                status = JreStatus.DOWNLOADING, downloadProgress = 0f,
                downloadedBytes = initialOffset, totalBytes = 0, isPaused = false
            )
            onProgress(0f, initialOffset, 0)

            val url = buildDownloadUrl()
            var connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 30000; connection.readTimeout = 300000

            // Range 请求实现断点续传
            if (initialOffset > 0) {
                connection.setRequestProperty("Range", "bytes=$initialOffset-")
                connection.connect()
                val responseCode = connection.responseCode
                if (responseCode != 206 && responseCode != 200) {
                    // 服务端不支持，从头开始
                    pf.delete()
                    connection = URL(url).openConnection() as HttpURLConnection
                    connection.connect()
                } else if (responseCode == 200) {
                    // 忽略了 Range，从头开始
                    pf.delete()
                }
            } else {
                connection.connect()
            }

            val contentLengthHeader = connection.getHeaderField("Content-Length")
            val contentLength = contentLengthHeader?.toLongOrNull() ?: -1L
            val totalSize: Long = if (contentLength > 0) initialOffset + contentLength
                else initialOffset + (connection.contentLength.takeIf { it > 0 }?.toLong() ?: -1L)
            if (totalSize <= initialOffset) totalSize.let { /* unknown */ }

            var downloaded = initialOffset

            // 更新总量到 UI
            if (totalSize > 0)
                _jreInfo.value = _jreInfo.value.copy(totalBytes = totalSize)

            // 追加写入
            val fos = FileOutputStream(pf, initialOffset > 0)
            val bis = BufferedInputStream(connection.inputStream)

            // 速率追踪
            var lastSpeedBytes = downloaded
            var lastSpeedTime = System.currentTimeMillis()
            var currentSpeed: Long = 0

            try {
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (bis.read(buffer).also { bytesRead = it } != -1) {
                    // 暂停检查
                    if (pauseFlag.get()) {
                        fos.flush(); fos.close(); bis.close()
                        connection.disconnect()
                        _jreInfo.value = _jreInfo.value.copy(
                            status = JreStatus.PAUSED, isPaused = true,
                            downloadedBytes = downloaded,
                            downloadSpeedBytesPerSec = 0,
                            downloadProgress =
                            if (totalSize > 0) downloaded.toFloat() / totalSize else 0f
                        )
                        pendingResume = true; pendingPartialFile = pf
                        return@withContext Result.success("paused")
                    }

                    fos.write(buffer, 0, bytesRead)
                    downloaded += bytesRead

                    // 每 500ms 更新一次速率
                    val now = System.currentTimeMillis()
                    val elapsed = now - lastSpeedTime
                    if (elapsed >= 500) {
                        currentSpeed = if (elapsed > 0) ((downloaded - lastSpeedBytes) * 1000L / elapsed) else 0
                        lastSpeedBytes = downloaded
                        lastSpeedTime = now
                    }

                    val effectiveTotal = if (totalSize > 0) totalSize else downloaded * 2
                    val progress = if (effectiveTotal > 0)
                        (downloaded.toFloat() / effectiveTotal).coerceIn(0f, 1f) else 0f

                    _jreInfo.value = _jreInfo.value.copy(
                        downloadProgress = progress,
                        downloadedBytes = downloaded,
                        totalBytes = effectiveTotal,
                        downloadSpeedBytesPerSec = currentSpeed
                    )
                    onProgress(progress, downloaded, effectiveTotal)
                }
            } finally {
                try { fos.close() } catch (_: Exception) {}
                try { bis.close() } catch (_: Exception) {}
                try { connection.disconnect() } catch (_: Exception) {}
            }

            // 下载完成后处理
            _jreInfo.value = _jreInfo.value.copy(status = JreStatus.EXTRACTING)
            onProgress(1f, downloaded, downloaded)

            val targetDir = jreDirFor(selectedVersion)
            if (targetDir.exists()) targetDir.deleteRecursively()
            targetDir.mkdirs()

            extractTarGz(pf, targetDir)

            javaExecutableFor(selectedVersion).setExecutable(true)
            pf.delete()
            pendingResume = false; pendingPartialFile = null

            val info = checkJre()
            _jreInfo.value = info
            Result.success(info.path)
        } catch (e: Exception) {
            _jreInfo.value = JreInfo(status = JreStatus.ERROR, installedVersions = emptyList())
            Result.failure(e)
        }
    }

    private fun extractTarGz(tarGzFile: File, destDir: File) {
        val process = ProcessBuilder()
            .command("tar", "xzf", tarGzFile.absolutePath, "-C", destDir.absolutePath, "--strip-components=1")
            .redirectErrorStream(true).start()
        val exitCode = process.waitFor()
        if (exitCode != 0) throw RuntimeException("无法解压，退出码: $exitCode")
    }
}
