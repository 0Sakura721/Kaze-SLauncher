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
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * JRE/JDK 管理器：检测、下载、管理 ARM 架构的 Java 运行时
 * 使用 Adoptium (Eclipse Temurin) 提供的 ARM 构建版本
 */
class JreManager(private val context: Context) {

    private val _jreInfo = MutableStateFlow(JreInfo())
    val jreInfo: StateFlow<JreInfo> = _jreInfo.asStateFlow()

    /** 当前选择的 Java 主版本号，如 "21"、"17" 等 */
    var selectedVersion: String = "21"
    /** JDK 或 JRE */
    var selectedPackage: String = "jre"  // "jdk" or "jre"

    private val prefsFile: File
        get() = File(context.filesDir, "jre_prefs.txt")

    /** 获取 JRE 安装目录（不同版本不同目录） */
    private fun jreDirFor(version: String): File =
        File(context.filesDir, "java_$version")

    private fun javaExecutableFor(version: String): File =
        File(jreDirFor(version), "bin/java")

    /** 当前激活的 java 路径 */
    val currentJavaPath: String? get() {
        val v = selectedVersion
        val exe = javaExecutableFor(v)
        return if (exe.exists() && exe.canExecute()) exe.absolutePath else null
    }

    companion object {
        private const val ADOPTIUM_API = "https://api.adoptium.net/v3"
        private const val AVAILABLE_RELEASES = "$ADOPTIUM_API/info/available_releases"

        /** 获取设备架构字符串 */
        fun getDeviceArch(): String {
            if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty() &&
                Build.SUPPORTED_64_BIT_ABIS[0].contains("arm64")
            ) return "aarch64"
            return "arm"
        }

        /** 构建下载 URL */
        fun buildDownloadUrl(version: String, pkg: String, arch: String): String =
            "$ADOPTIUM_API/binary/latest/$version/ga/linux/$arch/$pkg/hotspot/normal/eclipse?project=jdk"
    }

    init {
        loadPrefs()
        _jreInfo.value = checkJre()
    }

    // ─── 偏好存取 ───

    private fun loadPrefs() {
        try {
            if (prefsFile.exists()) {
                val lines = prefsFile.readLines()
                if (lines.size >= 2) {
                    selectedVersion = lines[0]
                    selectedPackage = lines[1]
                }
            }
        } catch (_: Exception) {}
    }

    private fun savePrefs() {
        try { prefsFile.writeText("$selectedVersion\n$selectedPackage") } catch (_: Exception) {}
    }

    fun setVersionAndPackage(version: String, pkg: String) {
        selectedVersion = version
        selectedPackage = pkg
        savePrefs()
        _jreInfo.value = checkJre()
    }

    // ─── 版本列表 ───

    /** 获取 Adoptium 可用版本列表 */
    suspend fun fetchAvailableVersions(): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val connection = URL(AVAILABLE_RELEASES).openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.connect()

            val json = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
            connection.disconnect()

            val array = JSONArray(json)
            val versions = mutableListOf<String>()
            for (i in 0 until array.length()) {
                versions.add(array.getInt(i).toString())
            }
            // 倒序，最新在前；只保留 Minecraft 常用的 LTS 和最新
            Result.success(versions.sortedByDescending { it.toInt() })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ─── 检查和安装 ───

    fun checkJre(): JreInfo {
        val exe = javaExecutableFor(selectedVersion)
        val installed = exe.exists() && exe.canExecute()
        // 同时检查是否为多版本环境
        val installedVersions = mutableListOf<String>()
        context.filesDir.listFiles()?.forEach { dir ->
            if (dir.name.startsWith("java_")) {
                val v = dir.name.removePrefix("java_")
                val java = File(dir, "bin/java")
                if (java.exists() && java.canExecute()) {
                    installedVersions.add(v)
                }
            }
        }

        return if (installed) {
            JreInfo(
                status = JreStatus.INSTALLED,
                version = selectedVersion,
                path = exe.absolutePath,
                installedVersions = installedVersions
            )
        } else {
            JreInfo(status = JreStatus.NOT_INSTALLED, installedVersions = installedVersions)
        }
    }

    suspend fun downloadAndInstall(
        onProgress: (Float) -> Unit = {}
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            _jreInfo.value = _jreInfo.value.copy(
                status = JreStatus.DOWNLOADING, downloadProgress = 0f
            )
            onProgress(0f)

            val url = buildDownloadUrl(selectedVersion, selectedPackage, getDeviceArch())
            val ext = if (selectedPackage == "jdk") "jdk" else "jre"
            val tempFile = File(context.cacheDir, "java_download_$ext.tar.gz")

            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 30000
            connection.readTimeout = 300000
            connection.connect()

            val contentLength = connection.contentLengthLong
            var downloaded = 0L

            connection.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead
                        if (contentLength > 0) {
                            val progress = (downloaded.toFloat() / contentLength).coerceIn(0f, 1f)
                            _jreInfo.value = _jreInfo.value.copy(downloadProgress = progress)
                            onProgress(progress)
                        }
                    }
                }
            }
            connection.disconnect()

            _jreInfo.value = _jreInfo.value.copy(status = JreStatus.EXTRACTING)
            onProgress(1f)

            val targetDir = jreDirFor(selectedVersion)
            if (targetDir.exists()) targetDir.deleteRecursively()
            targetDir.mkdirs()

            extractTarGz(tempFile, targetDir)

            javaExecutableFor(selectedVersion).setExecutable(true)
            tempFile.delete()

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
            .redirectErrorStream(true)
            .start()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw RuntimeException("无法解压 Java 包，退出码: $exitCode")
        }
    }
}
