package com.mcserver.launcher.core.linux

import com.mcserver.launcher.core.download.DownloadManager
import com.mcserver.launcher.data.AppPaths
import com.mcserver.launcher.util.KLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

enum class LinuxStatus { NONE, DOWNLOADING, EXTRACTING, READY, ERROR }

/**
 * 内置 Linux 环境（类似 Termux 的 proot 方案）：
 * - proot 二进制（termux/proot，按架构下载）
 * - Alpine minirootfs（musl libc，极小体积）
 * - Liberica musl JDK 21（解压到 rootfs 可见的 /opt/jdk）
 *
 * 服务端在该环境内以完整 Linux 用户态运行，可驱动所有 MC Java 服务端。
 * 下载均走多镜像回退；解压为手写 gzip+tar（零依赖）。
 */
object LinuxEnv {

    private val _status = MutableStateFlow(LinuxStatus.NONE)
    val status: StateFlow<LinuxStatus> = _status

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress

    private val _detail = MutableStateFlow("")
    val detail: StateFlow<String> = _detail

    private val prootFile get() = File(AppPaths.linuxDir, "proot")
    private val rootfsDir get() = File(AppPaths.linuxDir, "rootfs")
    private val jdkDir get() = File(AppPaths.linuxDir, "jdk")
    private val jdkArchive get() = File(AppPaths.downloadsDir, "jdk21-linux-musl.tar.gz")

    fun isReady(): Boolean =
        prootFile.exists() && prootFile.length() > 100_000 &&
            File(rootfsDir, "etc/alpine-release").exists() &&
            File(jdkDir, "bin/java").exists()

    fun prootBinary(): File? = if (prootFile.exists()) prootFile else null

    fun rootfs(): File? = if (File(rootfsDir, "etc/alpine-release").exists()) rootfsDir else null

    /** guest 内 JDK 的 java 路径（linuxDir 绑定到 /opt） */
    fun javaBinaryInGuest(): String? =
        if (File(jdkDir, "bin/java").exists()) "/opt/jdk/bin/java" else null

    fun refresh() {
        _status.value = if (isReady()) LinuxStatus.READY else LinuxStatus.NONE
    }

    /** 一键安装：proot → rootfs → JDK（已存在的组件跳过） */
    suspend fun install(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            _status.value = LinuxStatus.DOWNLOADING
            _progress.value = 0f

            // 1. proot
            if (!prootFile.exists() || prootFile.length() < 100_000) {
                _detail.value = "正在获取 proot…"
                val ok = downloadProot()
                if (!ok) {
                    _status.value = LinuxStatus.ERROR
                    return@withContext Result.failure(Exception("proot 下载失败"))
                }
                prootFile.setExecutable(true, true)
            }

            // 2. rootfs
            if (!File(rootfsDir, "etc/alpine-release").exists()) {
                _detail.value = "正在下载 Linux 根文件系统…"
                val archive = File(AppPaths.downloadsDir, "alpine-minirootfs.tar.gz")
                val ok = downloadRootfs(archive)
                if (!ok) {
                    _status.value = LinuxStatus.ERROR
                    return@withContext Result.failure(Exception("rootfs 下载失败"))
                }
                _status.value = LinuxStatus.EXTRACTING
                _detail.value = "正在解压根文件系统…"
                rootfsDir.mkdirs()
                extractTarGz(archive, rootfsDir)
                archive.delete()
                // 补 DNS 配置
                val resolv = File(rootfsDir, "etc/resolv.conf")
                if (!resolv.exists()) {
                    resolv.parentFile?.mkdirs()
                    resolv.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
                }
            }

            // 3. JDK（musl 版，适配 Alpine）
            if (!File(jdkDir, "bin/java").exists()) {
                _detail.value = "正在下载 Linux JDK 21…"
                _progress.value = 0.55f
                val ok = downloadJdk()
                if (!ok) {
                    _status.value = LinuxStatus.ERROR
                    return@withContext Result.failure(Exception("JDK 下载失败"))
                }
                _status.value = LinuxStatus.EXTRACTING
                _detail.value = "正在解压 JDK…"
                jdkDir.mkdirs()
                extractTarGz(jdkArchive, jdkDir)
                jdkArchive.delete()
            }

            _progress.value = 1f
            _detail.value = ""
            _status.value = if (isReady()) LinuxStatus.READY else LinuxStatus.ERROR
            if (_status.value == LinuxStatus.READY) Result.success(Unit)
            else Result.failure(Exception("环境校验未通过"))
        } catch (e: Exception) {
            KLog.e("Linux 环境安装失败", e)
            _status.value = LinuxStatus.ERROR
            Result.failure(e)
        }
    }

    // ── 下载 ──

    private suspend fun downloadProot(): Boolean {
        val asset = if (is64Bit()) "proot-android-aarch64" else "proot-android-arm"
        val urls = listOf(
            "https://ghfast.top/https://github.com/termux/proot/releases/latest/download/$asset",
            "https://github.com/termux/proot/releases/latest/download/$asset",
        )
        return tryUrls(urls, prootFile)
    }

    private suspend fun downloadRootfs(dest: File): Boolean {
        val arch = if (is64Bit()) "aarch64" else "armv7"
        val yamlUrls = listOf(
            "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/$arch/latest-releases.yaml",
            "https://mirrors.tuna.tsinghua.edu.cn/alpine/latest-stable/releases/$arch/latest-releases.yaml",
            "https://mirrors.ustc.edu.cn/alpine/latest-stable/releases/$arch/latest-releases.yaml",
        )
        val yaml = fetchText(yamlUrls) ?: return false
        val name = Regex("file: (alpine-minirootfs-[0-9.]+-$arch\\.tar\\.gz)").find(yaml)
            ?.groupValues?.get(1) ?: return false
        val fileUrls = listOf(
            "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/$arch/$name",
            "https://mirrors.tuna.tsinghua.edu.cn/alpine/latest-stable/releases/$arch/$name",
            "https://mirrors.ustc.edu.cn/alpine/latest-stable/releases/$arch/$name",
        )
        return tryUrls(fileUrls, dest)
    }

    private suspend fun downloadJdk(): Boolean {
        val arch = if (is64Bit()) "aarch64" else "arm"
        // 1) 动态解析 Liberica 版本
        val api = "https://api.bell-sw.com/v1/liberica/core/releases" +
            "?version-feature=21&os=linux&arch=$arch&libc=musl&bundle-type=jdk&package-type=tar.gz&bitness=64"
        try {
            val text = fetchText(listOf(api))
            if (text != null) {
                val url = Regex("\"downloadUrl\"\\s*:\\s*\"([^\"]+)\"").find(text)
                    ?.groupValues?.get(1)
                if (url != null && tryUrls(listOf(url), jdkArchive)) return true
            }
        } catch (e: Exception) {
            KLog.w("Liberica API 解析失败: ${e.message}")
        }
        // 2) 回退直链（版本可能失效，仅兜底）
        return tryUrls(
            listOf(
                "https://download.bell-sw.com/java/21.0.7+11/bellsoft-jdk21.0.7+11-linux-$arch-musl.tar.gz",
            ),
            jdkArchive,
        )
    }

    private suspend fun tryUrls(urls: List<String>, dest: File): Boolean {
        dest.parentFile?.mkdirs()
        var last: Throwable? = null
        for ((i, u) in urls.withIndex()) {
            try {
                KLog.i("下载镜像(${i + 1}/${urls.size}): $u")
                val r = DownloadManager.download(u, dest) { done, total ->
                    if (total > 0) _progress.value = (done.toFloat() / total).coerceIn(0f, 1f)
                }
                if (r.isSuccess && dest.exists() && dest.length() > 0) return true
                last = r.exceptionOrNull()
            } catch (e: Exception) {
                last = e
            }
            dest.delete()
        }
        KLog.w("全部镜像下载失败: ${last?.message}")
        return false
    }

    private fun fetchText(urls: List<String>): String? {
        for (u in urls) {
            try {
                val conn = URL(u).openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                conn.setRequestProperty("User-Agent", "Kaze-SLauncher/2.0")
                if (conn.responseCode == 200) {
                    val text = conn.inputStream.bufferedReader().readText()
                    conn.disconnect()
                    if (text.isNotBlank()) return text
                } else conn.disconnect()
            } catch (e: Exception) {
                KLog.w("请求失败: $u ${e.message}")
            }
        }
        return null
    }

    private fun is64Bit(): Boolean =
        android.os.Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()

    // ── 手写 tar.gz 解压（标准 ustar + 老式 tar + 符号链接） ──

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
                    '0'.code, '7'.code, 0 -> {
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

                    '5'.code -> File(destDir, name).mkdirs()

                    '2'.code -> {
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