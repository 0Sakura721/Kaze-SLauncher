package com.mcserver.launcher.core.linux

import android.content.Context
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

enum class LinuxStatus { NONE, UNPACKING, READY, ERROR }

/**
 * 内置 Linux 环境（模仿 Termux 的 proot 方案）：
 * - proot（静态二进制）与 Alpine minirootfs 直接打包进 APK assets，开箱即用，无需下载
 * - JDK/JRE 不内置：进入 rootfs 后通过 apk 包管理在线安装/卸载（多版本 8/11/17/21）
 *
 * 服务端在该环境内以完整 Linux 用户态运行，可驱动所有 MC Java 服务端。
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

    fun isReady(): Boolean =
        prootFile.exists() && prootFile.length() > 100_000 &&
            File(rootfsDir, "etc/alpine-release").exists()

    fun prootBinary(): File? = if (prootFile.exists()) prootFile else null

    fun rootfs(): File? = if (File(rootfsDir, "etc/alpine-release").exists()) rootfsDir else null

    fun refresh() {
        _status.value = if (isReady()) LinuxStatus.READY else LinuxStatus.NONE
    }

    /** 从 APK assets 解包内置环境（proot + rootfs + CA 证书），秒级完成 */
    suspend fun setup(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            _status.value = LinuxStatus.UNPACKING
            _progress.value = 0f
            val assets = listOf("proot-aarch64", "alpine-minirootfs-aarch64.tar.gz", "ca-certificates-bundle.apk")

            // 1. proot
            if (!prootFile.exists() || prootFile.length() < 100_000) {
                _detail.value = "释放 proot…"
                val ok = copyAsset(context, "linux/proot-aarch64", prootFile)
                if (!ok) {
                    _status.value = LinuxStatus.ERROR
                    return@withContext Result.failure(Exception("内置资源缺失：proot"))
                }
                prootFile.setExecutable(true, true)
            }

            // 2. rootfs
            if (!File(rootfsDir, "etc/alpine-release").exists()) {
                _detail.value = "释放 Linux 根文件系统…"
                rootfsDir.mkdirs()
                val tmp = File(AppPaths.linuxDir, "rootfs-tmp.tar.gz")
                val ok = copyAsset(context, "linux/alpine-minirootfs-aarch64.tar.gz", tmp)
                if (!ok) {
                    _status.value = LinuxStatus.ERROR
                    return@withContext Result.failure(Exception("内置资源缺失：rootfs"))
                }
                extractTarGz(tmp, rootfsDir)
                tmp.delete()
                _progress.value = 0.7f

                // 3. CA 证书（保证 apk 走 HTTPS）
                val caAsset = copyAsset(context, "linux/ca-certificates-bundle.apk", File(AppPaths.linuxDir, "ca.apk"))
                if (caAsset) {
                    try {
                        extractTarGz(File(AppPaths.linuxDir, "ca.apk"), rootfsDir)
                    } catch (e: Exception) {
                        KLog.w("CA 证书包解压失败: ${e.message}")
                    }
                    File(AppPaths.linuxDir, "ca.apk").delete()
                }

                // DNS 配置
                val resolv = File(rootfsDir, "etc/resolv.conf")
                if (!resolv.exists()) {
                    resolv.parentFile?.mkdirs()
                    resolv.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
                }
            }

            _progress.value = 1f
            _detail.value = ""
            _status.value = if (isReady()) LinuxStatus.READY else LinuxStatus.ERROR
            if (_status.value == LinuxStatus.READY) Result.success(Unit)
            else Result.failure(Exception("环境校验未通过"))
        } catch (e: Exception) {
            KLog.e("Linux 环境释放失败", e)
            _status.value = LinuxStatus.ERROR
            Result.failure(e)
        }
    }

    private fun copyAsset(context: Context, assetPath: String, dest: File): Boolean = try {
        dest.parentFile?.mkdirs()
        val ins = context.assets.open(assetPath) ?: return false
        FileOutputStream(dest).use { out -> ins.copyTo(out) }
        true
    } catch (e: Exception) {
        KLog.w("assets 读取失败: $assetPath ${e.message}")
        false
    }

    /**
     * 在 rootfs 内执行命令（proot 包装）。
     * @param args 例如 listOf("/sbin/apk", "add", "--no-cache", "openjdk21")
     * @param env 附加环境变量
     * @param onLog 逐行输出回调（可为 null）
     */
    fun exec(
        args: List<String>,
        env: Map<String, String> = emptyMap(),
        timeoutSec: Long = 900,
        onLog: ((String) -> Unit)? = null,
    ): Result<String> {
        val proot = prootBinary() ?: return Result.failure(Exception("环境未就绪"))
        val rootfs = rootfs() ?: return Result.failure(Exception("环境未就绪"))
        val cmd = mutableListOf<String>()
        cmd.add(proot.absolutePath)
        cmd.add("-0")
        cmd.add("-r"); cmd.add(rootfs.absolutePath)
        cmd.add("-b"); cmd.add("/dev")
        cmd.add("-b"); cmd.add("/proc")
        cmd.add("-b"); cmd.add("/sys")
        cmd.add("-w"); cmd.add("/root")
        cmd.addAll(args)
        return try {
            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            pb.environment()["HOME"] = "/root"
            pb.environment()["TERM"] = "xterm"
            pb.environment()["LANG"] = "C.UTF-8"
            pb.environment()["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
            env.forEach { (k, v) -> pb.environment()[k] = v }
            val p = pb.start()
            val out = StringBuilder()
            val reader = p.inputStream.bufferedReader()
            val thread = Thread {
                try {
                    var line: String?
                    while (true) {
                        line = reader.readLine() ?: break
                        out.appendLine(line)
                        onLog?.invoke(line)
                    }
                } catch (_: Exception) {
                }
            }
            thread.start()
            val code = try {
                if (!p.waitFor(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)) {
                    p.destroyForcibly()
                    137
                } else p.exitValue()
            } catch (e: InterruptedException) {
                p.destroyForcibly()
                130
            }
            thread.join(2000)
            if (code == 0) Result.success(out.toString())
            else Result.failure(Exception("退出码 $code: ${out.toString().takeLast(300)}"))
        } catch (e: Exception) {
            KLog.e("rootfs 执行失败", e)
            Result.failure(e)
        }
    }

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