package com.mcserver.launcher.core.engine

import com.mcserver.launcher.data.AppPaths
import com.mcserver.launcher.util.KLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream

enum class JreStatus { NONE, DOWNLOADING, EXTRACTING, READY, ERROR }

/**
 * JRE（Java 运行时）管理：
 * - 检测：filesDir/runtime 下查找 bin/java
 * - 下载：Adoptium → 清华 → 阿里多镜像回退
 * - 解压：手写 gzip+tar（零依赖）
 * - 校验：执行 java -version
 * - 导入：本地目录复制（SAF 选中后传入）
 */
object JreManager {

    private val _status = MutableStateFlow(JreStatus.NONE)
    val status: StateFlow<JreStatus> = _status

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress

    private val _versionText = MutableStateFlow<String?>(null)
    val versionText: StateFlow<String?> = _versionText

    fun refresh() {
        val dir = installedDir()
        _status.value = if (dir != null) JreStatus.READY else JreStatus.NONE
        if (dir != null && _versionText.value == null) {
            _versionText.value = probeVersion(dir)
        }
    }

    fun installedDir(): File? {
        val rt = AppPaths.runtimeDir
        rt.listFiles()?.forEach { cand ->
            if (File(cand, "bin/java").exists() || File(cand, "bin/java.sh").exists()) return cand
        }
        return null
    }

    fun currentJavaPath(): String? = installedDir()?.let { AppPaths.javaBinary(it) }

    private fun probeVersion(dir: File): String? = try {
        val pb = ProcessBuilder(AppPaths.javaBinary(dir), "-version").redirectErrorStream(true)
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        out.lineSequence().firstOrNull { it.contains("version") }?.trim()
    } catch (e: Exception) {
        null
    }

    /** 执行 java -version 校验目录是否为可用 JRE */
    fun verify(dir: File): Boolean = try {
        val pb = ProcessBuilder(AppPaths.javaBinary(dir), "-version").redirectErrorStream(true)
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        val code = p.waitFor()
        out.contains("openjdk version") || out.contains("java version") || code == 0
    } catch (e: Exception) {
        KLog.e("JRE 校验失败", e)
        false
    }

    /** 在线下载并安装 JRE 21（按架构） */
    suspend fun downloadAndInstall(): Result<File> = withContext(Dispatchers.IO) {
        _status.value = JreStatus.DOWNLOADING
        _progress.value = 0f
        try {
            val arch = if (is64Bit()) "aarch64" else "arm"
            val urls = buildDownloadUrls(arch)
            val dest = File(AppPaths.downloadsDir, "jre21-$arch.tar.gz")
            var lastErr: Exception? = null
            for ((idx, u) in urls.withIndex()) {
                try {
                    KLog.i("下载 JRE 镜像(${idx + 1}/${urls.size})")
                    val r = com.mcserver.launcher.core.download.DownloadManager.download(
                        url = u, dest = dest
                    ) { done, total ->
                        if (total > 0) _progress.value = (done.toFloat() / total).coerceIn(0f, 1f)
                    }
                    if (r.isSuccess) break
                    lastErr = r.exceptionOrNull()
                } catch (e: Exception) {
                    lastErr = e
                    KLog.w("JRE 镜像失败: ${e.message}")
                    dest.delete()
                }
            }
            if (!dest.exists() || dest.length() < 10_000_000) {
                _status.value = JreStatus.ERROR
                return@withContext Result.failure(lastErr ?: Exception("JRE 下载失败"))
            }
            _status.value = JreStatus.EXTRACTING
            val target = File(AppPaths.runtimeDir, "jre21")
            if (target.exists()) target.deleteRecursively()
            extractTarGz(dest, target)
            val java = File(target, "bin/java")
            val javaSh = File(target, "bin/java.sh")
            if (!java.exists() && !javaSh.exists()) {
                _status.value = JreStatus.ERROR
                return@withContext Result.failure(Exception("解压后缺少 bin/java"))
            }
            java.setExecutable(true, true)
            if (javaSh.exists()) javaSh.setExecutable(true, true)
            dest.delete()
            _versionText.value = probeVersion(target)
            _status.value = JreStatus.READY
            Result.success(target)
        } catch (e: Exception) {
            _status.value = JreStatus.ERROR
            Result.failure(e)
        }
    }

    /** 从本地目录导入 JRE */
    suspend fun importFromDir(src: File): Result<File> = withContext(Dispatchers.IO) {
        try {
            val hasJava = File(src, "bin/java").exists() || File(src, "bin/java.sh").exists()
            if (!hasJava) return@withContext Result.failure(Exception("目录中没有 bin/java"))
            val target = File(AppPaths.runtimeDir, "jre-imported")
            if (target.exists()) target.deleteRecursively()
            src.copyRecursively(target)
            File(target, "bin/java").setExecutable(true, true)
            File(target, "bin/java.sh").setExecutable(true, true)
            _versionText.value = probeVersion(target)
            _status.value = JreStatus.READY
            Result.success(target)
        } catch (e: Exception) {
            _status.value = JreStatus.ERROR
            Result.failure(e)
        }
    }

    private fun is64Bit(): Boolean =
        android.os.Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()

    private fun buildDownloadUrls(arch: String): List<String> {
        val ver = "21"
        return listOf(
            // Adoptium 官方 API
            "https://api.adoptium.net/v3/binary/latest/$ver/ga/linux/$arch/jre/hotspot/normal/eclipse",
            // 清华镜像
            "https://mirrors.tuna.tsinghua.edu.cn/Adoptium/$ver/jre/$arch/linux/hotspot/normal/eclipse",
            // 阿里镜像
            "https://mirrors.aliyun.com/adoptium/$ver/jre/$arch/linux/hotspot/normal/eclipse",
        )
    }

    /** 手写 tar.gz 解压（标准 ustar + 老式 tar + 符号链接） */
    private fun extractTarGz(tarGz: File, destDir: File) {
        destDir.mkdirs()
        GZIPInputStream(FileInputStream(tarGz)).use { gz ->
            val header = ByteArray(512)
            while (true) {
                var read = 0
                while (read < 512) {
                    val n = gz.read(header, read, 512 - read)
                    if (n < 0) break
                    read += n
                }
                if (read == 0) break
                if (header.all { it == 0.toByte() }) break

                val name = String(header, 0, 100, Charsets.UTF_8).trimEnd('\u0000')
                val sizeStr = String(header, 124, 12, Charsets.UTF_8).trimEnd('\u0000', ' ')
                val size = sizeStr.trim().toLongOrNull(8) ?: 0L
                val typeFlag = header[156].toInt() and 0xFF
                val linkName = String(header, 157, 100, Charsets.UTF_8).trimEnd('\u0000')

                when (typeFlag) {
                    '0'.code, '7'.code, 0 -> { // 普通文件
                        if (name.isEmpty()) {
                            skip(gz, size + pad(size)); continue
                        }
                        val out = File(destDir, name)
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { fo ->
                            var remaining = size
                            val buf = ByteArray(64 * 1024)
                            while (remaining > 0) {
                                val n = gz.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                                if (n < 0) break
                                fo.write(buf, 0, n)
                                remaining -= n
                            }
                        }
                        skip(gz, pad(size))
                    }

                    '5'.code -> File(destDir, name).mkdirs() // 目录

                    '2'.code -> { // 符号链接
                        val link = File(destDir, name)
                        link.parentFile?.mkdirs()
                        try {
                            ProcessBuilder("ln", "-sf", linkName, link.absolutePath).start().waitFor()
                        } catch (_: Exception) {
                        }
                        skip(gz, pad(size))
                    }

                    else -> skip(gz, size + pad(size))
                }
            }
        }
    }

    private fun pad(size: Long): Long = if (size % 512 == 0L) 0L else 512 - (size % 512)

    private fun skip(gz: GZIPInputStream, count: Long) {
        var remaining = count
        val buf = ByteArray(4096)
        while (remaining > 0) {
            val n = gz.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n < 0) break
            remaining -= n
        }
    }
}