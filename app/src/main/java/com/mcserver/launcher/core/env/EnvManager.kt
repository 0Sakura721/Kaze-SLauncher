package com.mcserver.launcher.core.env

import android.content.Context
import android.os.Build
import com.mcserver.launcher.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/** 环境状态 */
enum class EnvState { NOT_INITIALIZED, SETTING_UP, READY, ERROR }

/**
 * 环境引擎:proot + Ubuntu 24.04 + 可选 JDK。
 * 运行 MC 服务端的唯一硬依赖;Java 支持按需安装(不强制全部)。
 */
object EnvManager {

    private const val TAG = "EnvManager"
    private lateinit var appContext: Context
    private val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    private val _state = MutableStateFlow(EnvState.NOT_INITIALIZED)
    val state: StateFlow<EnvState> = _state.asStateFlow()

    /** 部署阶段日志(UI 显示) */
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    /** 单个部署项(进度列表) */
    data class SetupItem(
        val id: String,
        val name: String,
        val desc: String,
        val done: Boolean = false,
        val phase: String = "",               // 等待中/提取中/下载中/解压中/安装中
        val progress: Float = 0f,             // 0..1
        val processedBytes: Long = 0,         // 已处理字节(下载或解压)
        val totalBytes: Long = 0,             // 总字节
        val speedBytes: Long = 0              // 处理速度(字节/秒)
    )
    private val _items = MutableStateFlow<List<SetupItem>>(emptyList())
    val items: StateFlow<List<SetupItem>> = _items.asStateFlow()

    private val isSetupRunning = AtomicBoolean(false)

    // ── 路径 ──
    private val linuxDir: File get() = File(appContext.filesDir, "linux").apply { mkdirs() }
    private val prootHomeDir: File get() = File(linuxDir, "proot-home").apply { mkdirs() }
    private val prootBinary: File get() = File(prootHomeDir, "bin/proot")
    private val prootLoader: File get() = File(prootHomeDir, "libexec/loader")
    private val prootLibDir: File get() = File(prootHomeDir, "lib")
    val rootfsDir: File get() = File(linuxDir, "rootfs")
    val javaHomeDir: File get() = File(rootfsDir, "usr/lib/jvm")
    private val serverBaseDir: File get() = File(appContext.getExternalFilesDir(null), "instances").apply { mkdirs() }

    private val isAarch64: Boolean
        get() = Build.SUPPORTED_ABIS.any { it.contains("arm64-v8a", ignoreCase = true) || it.contains("aarch64", ignoreCase = true) }
    private val archName: String get() = if (isAarch64) "aarch64" else "armhf"
    private val jdkArchSuffix: String get() = if (isAarch64) "arm64" else "armhf"

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        _state.value = if (isEnvironmentReady()) EnvState.READY else EnvState.NOT_INITIALIZED
    }

    // ── 状态 ──
    fun isEnvironmentReady(): Boolean =
        prootBinary.exists() && prootBinary.canExecute() &&
            prootLoader.exists() && prootLoader.canExecute() &&
            rootfsDir.exists() && File(rootfsDir, "bin/sh").exists()

    fun isJdkInstalled(version: Int): Boolean {
        val javaBin = File(javaHomeDir, "java-$version-openjdk-$jdkArchSuffix/bin/java")
        return javaBin.exists() && javaBin.canExecute()
    }

    /** 已安装的 Java 版本列表 */
    fun installedJdkVersions(): List<Int> =
        listOf(8, 11, 17, 21).filter { isJdkInstalled(it) }

    fun getJavaPath(version: Int): String =
        "/usr/lib/jvm/java-$version-openjdk-$jdkArchSuffix/bin/java"

    /** 任意可用 Java(优先偏好版本) */
    fun resolveJavaPath(preferred: Int?): String? {
        val candidates = (listOfNotNull(preferred) + listOf(21, 17, 11, 8)).distinct()
        for (v in candidates) if (isJdkInstalled(v)) return getJavaPath(v)
        return null
    }

    // ── 资产提取(兼容 AGP 将 .tar.gz 解压为 .tar 的行为) ──
    private fun extractBundledAsset(assetName: String, dest: File): Boolean {
        if (dest.exists() && dest.length() > 0) return true
        val candidates = if (assetName.endsWith(".gz")) {
            listOf(assetName, assetName.removeSuffix(".gz"))
        } else listOf(assetName)
        for (candidate in candidates) {
            try {
                dest.parentFile?.mkdirs()
                appContext.assets.open("bundled/$candidate").use { input ->
                    FileOutputStream(dest).use { out -> input.copyTo(out) }
                }
                return true
            } catch (_: Exception) { /* 尝试下一个候选 */ }
        }
        Logger.w("extractBundledAsset failed: $assetName")
        return false
    }

    /** 更新单个部署项 */
    private fun updateItem(itemId: String, transform: (SetupItem) -> SetupItem) {
        _items.value = _items.value.map { if (it.id == itemId) transform(it) else it }
    }

    private fun updateItem(itemId: String, newItem: SetupItem) {
        _items.value = _items.value.map { if (it.id == itemId) newItem else it }
    }

    // ── tar 解压(手写解析,带进度/速度回调) ──
    /**
     * 解压 tar/tar.gz 到 destDir,实时上报进度与速度。
     * 手写 tar 格式解析(512 字节头 + 数据),不依赖第三方库。
     * @param onProgress (processedBytes, totalBytes, speedBytesPerSec)
     */
    private fun extractTarWithProgress(
        tarFile: File,
        destDir: File,
        onProgress: (Long, Long, Long) -> Unit
    ) {
        destDir.mkdirs()
        val total = tarFile.length()
        val startTime = System.currentTimeMillis()
        var processed = 0L
        var lastUpdate = startTime
        var lastBytes = 0L

        fun report() {
            val now = System.currentTimeMillis()
            if (now - lastUpdate >= 150) {
                val speed = (processed - lastBytes) * 1000 / (now - lastUpdate).coerceAtLeast(1)
                onProgress(processed, total, speed)
                lastUpdate = now
                lastBytes = processed
            }
        }

        val isGzip = try {
            RandomAccessFile(tarFile, "r").use { it.readUnsignedByte() == 0x1f && it.readUnsignedByte() == 0x8b }
        } catch (_: Exception) { false }

        // 输入流:gzip 则解包
        val fileStream = FileInputStream(tarFile)
        val rawInput = if (isGzip) GZIPInputStream(fileStream) else fileStream

        // tar 头解析
        val header = ByteArray(512)
        fun readHeader(): Boolean {
            var read = 0
            while (read < 512) {
                val n = rawInput.read(header, read, 512 - read)
                if (n == -1) return false
                read += n
            }
            processed += 512
            // 全零块 = tar 结束
            return header.any { it != 0.toByte() }
        }
        fun octal(bytes: ByteArray): Long {
            var v = 0L
            for (b in bytes) {
                if (b.toInt() in '0'.code..'7'.code) v = v * 8 + (b - '0'.code)
            }
            return v
        }
        fun name(): String {
            var end = 0
            while (end < 100 && header[end] != 0.toByte()) end++
            return String(header, 0, end, Charsets.UTF_8)
        }

        try {
            val buffer = ByteArray(64 * 1024)
            while (readHeader()) {
                val size = octal(header.copyOfRange(124, 136))
                val type = header[156].toInt().toChar()
                val path = name()
                val target = File(destDir, path)
                when {
                    // 目录条目:tar 目录标记,或以 / 结尾,或根路径(./、空名)
                    type == '5' || path.endsWith("/") || path.isBlank() || path == "." || path == "./" -> {
                        if (path.isNotBlank() && path != "." && path != "./") target.mkdirs()
                    }
                    else -> {
                        if (path.contains("/")) target.parentFile?.mkdirs()
                        // 硬链接/符号链接等特殊类型:跳过内容
                        val dataLen = if (type == '0' || type == '\u0000') size else 0
                        var remaining = dataLen
                        FileOutputStream(target).use { out ->
                            while (remaining > 0) {
                                val chunk = rawInput.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                if (chunk == -1) break
                                out.write(buffer, 0, chunk)
                                remaining -= chunk
                            }
                        }
                        // 可执行位
                        if (path.contains("bin/") || path.contains("libexec/")) target.setExecutable(true)
                    }
                }
                // 跳过数据后补齐到 512 对齐
                val padded = (size + 511) / 512 * 512
                var skip = padded - size
                while (skip > 0) {
                    val n = rawInput.read(buffer, 0, minOf(buffer.size.toLong(), skip).toInt())
                    if (n == -1) break
                    skip -= n
                }
                processed += padded
                report()
            }
            onProgress(total, total, 0)
        } finally {
            rawInput.close()
            fileStream.close()
        }
    }

    /** 简单解压(无进度,内部调用带进度版本) */
    private fun extractTar(tarFile: File, destDir: File) {
        extractTarWithProgress(tarFile, destDir) { _, _, _ -> }
    }

    // ── proot 命令 ──
    private fun prootEnvironment(): Map<String, String> {
        val env = mutableMapOf<String, String>()
        env["PROOT_LOADER"] = prootLoader.absolutePath
        val existing = System.getenv("LD_LIBRARY_PATH") ?: ""
        env["LD_LIBRARY_PATH"] = if (existing.isEmpty()) prootLibDir.absolutePath else "${prootLibDir.absolutePath}:$existing"
        env["PROOT_NO_SECCOMP"] = "1"
        return env
    }

    /** 构造 proot 进程(服务器目录自动绑定) */
    fun buildProotCommand(command: String, workDir: String = "/root", bindExtra: List<Pair<String, String>> = emptyList()): ProcessBuilder {
        val args = mutableListOf(
            prootBinary.absolutePath, "-0",
            "-r", rootfsDir.absolutePath,
            "-b", "/dev:/dev",
            "-b", "/proc:/proc",
            "-b", "/sys:/sys"
        )
        if (serverBaseDir.exists()) {
            args.add("-b")
            args.add("${serverBaseDir.absolutePath}:${serverBaseDir.absolutePath}")
        }
        bindExtra.forEach { (host, guest) ->
            args.add("-b")
            args.add("$host:$guest")
        }
        args.add("/bin/sh")
        args.add("-c")
        args.add("cd $workDir && $command")
        val pb = ProcessBuilder(args).redirectErrorStream(true)
        pb.environment().putAll(prootEnvironment())
        return pb
    }

    /** 在 Ubuntu 内执行命令,收集输出 */
    fun executeCommand(command: String, workDir: String = "/root", timeoutMs: Long = 600_000): Result<String> {
        return try {
            val proc = buildProotCommand(command, workDir).start()
            val output = proc.inputStream.bufferedReader().readText()
            val exited = proc.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!exited) { proc.destroyForcibly(); Result.failure(RuntimeException("命令超时")) }
            else if (proc.exitValue() == 0) Result.success(output)
            else Result.failure(RuntimeException("退出码 ${proc.exitValue()}: ${output.take(300)}"))
        } catch (e: Exception) { Result.failure(e) }
    }

    // ── 部署 ──
    /**
     * 全量部署:proot + rootfs + apt + 用户选择的 JDK。
     * @param jdkVersions 需要安装的 Java 版本(可只选需要的)
     */
    suspend fun runFullSetup(jdkVersions: List<Int> = listOf(8, 11, 17, 21)): Result<Unit> = withContext(Dispatchers.IO) {
        if (isSetupRunning.get()) return@withContext Result.failure(RuntimeException("部署正在进行中"))
        if (_state.value == EnvState.READY && jdkVersions.all { isJdkInstalled(it) }) {
            return@withContext Result.success(Unit)
        }
        isSetupRunning.set(true)
        _state.value = EnvState.SETTING_UP
        val jdkList = jdkVersions.distinct().sorted()
        val totalSteps = 3 + jdkList.size

        fun log(msg: String) { _log.value = _log.value + msg }

        try {
            // 阶段 1:proot
            log(">>> 阶段 1/$totalSteps:获取 proot 运行时($archName)")
            _items.value = listOf(SetupItem("proot", "proot 运行时", "内置,解压即用", phase = "提取中"))
            val prootTarball = File(linuxDir, "proot.tar.gz")
            if (!prootBinary.exists() || !prootLoader.exists()) {
                if (extractBundledAsset("proot-$archName.tar.gz", prootTarball)) {
                    updateItem("proot", SetupItem("proot", "proot 运行时", "内置,解压即用", phase = "解压中"))
                    extractTarWithProgress(prootTarball, prootHomeDir) { processed, total, speed ->
                        updateItem("proot") { it.copy(phase = "解压中", progress = if (total > 0) processed.toFloat() / total else 0f, processedBytes = processed, totalBytes = total, speedBytes = speed) }
                    }
                    prootTarball.delete()
                    updateItem("proot", SetupItem("proot", "proot 运行时", "内置,解压即用", done = true))
                    log("  ✓ 内置提取成功")
                } else {
                    // 网络回退:官方 proot 直链
                    val url = if (isAarch64)
                        "https://github.com/termux/proot/releases/download/v5.1.107.86/proot-aarch64.tar.gz"
                    else "https://github.com/termux/proot/releases/download/v5.1.107.86/proot-armhf.tar.gz"
                    log("  内置不可用,网络下载...")
                    updateItem("proot", SetupItem("proot", "proot 运行时", "网络下载,约 1 MB", phase = "下载中"))
                    downloadToFile(url, prootTarball) { done, total ->
                        updateItem("proot") { it.copy(phase = "下载中", progress = if (total > 0) done.toFloat() / total else 0f, processedBytes = done, totalBytes = total) }
                    }
                    updateItem("proot", SetupItem("proot", "proot 运行时", "内置,解压即用", phase = "解压中"))
                    extractTar(prootTarball, prootHomeDir)
                    prootTarball.delete()
                    updateItem("proot", SetupItem("proot", "proot 运行时", "内置,解压即用", done = true))
                    log("  ✓ 网络下载成功")
                }
            } else {
                updateItem("proot", SetupItem("proot", "proot 运行时", "内置,解压即用", done = true))
                log("  ✓ 已就绪,跳过")
            }
            prootBinary.setExecutable(true)
            prootLoader.setExecutable(true)
            File(prootHomeDir, "libexec/loader32").takeIf { it.exists() }?.setExecutable(true)

            // 阶段 2:Ubuntu rootfs
            log(">>> 阶段 2/$totalSteps:获取 Ubuntu 24.04 rootfs")
            _items.value = listOf(
                SetupItem("proot", "proot 运行时", "内置,解压即用", done = true),
                SetupItem("rootfs", "Ubuntu 24.04", "内置,解压即用")
            )
            val ubuntuArch = if (isAarch64) "arm64" else "armhf"
            val rootfsTarball = File(linuxDir, "rootfs.tar.gz")
            if (!File(rootfsDir, "bin/sh").exists()) {
                if (extractBundledAsset("ubuntu-base-24.04-$ubuntuArch.tar.gz", rootfsTarball)) {
                    log("  ✓ 内置提取成功")
                } else {
                    log("  内置不可用,网络下载...")
                    downloadToFile("https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04-base-$ubuntuArch.tar.gz", rootfsTarball)
                }
                log("  解压 rootfs(~200MB,请耐心等待)...")
                updateItem("rootfs", SetupItem("rootfs", "Ubuntu 24.04", "内置,解压即用", phase = "解压中", totalBytes = rootfsTarball.length()))
                extractTarWithProgress(rootfsTarball, rootfsDir) { processed, total, speed ->
                    updateItem("rootfs") {
                        it.copy(
                            phase = "解压中",
                            progress = if (total > 0) processed.toFloat() / total else 0f,
                            processedBytes = processed,
                            totalBytes = total,
                            speedBytes = speed
                        )
                    }
                }
                rootfsTarball.delete()
                updateItem("rootfs", SetupItem("rootfs", "Ubuntu 24.04", "内置,解压即用", done = true))
                log("  ✓ rootfs 就绪")
            } else log("  ✓ 已就绪,跳过")

            // 阶段 3:apt 初始化
            log(">>> 阶段 3/$totalSteps:初始化 apt 包管理器")
            _items.value = _items.value.map { if (it.id == "rootfs") it.copy(done = true) else it }
            _items.value = _items.value + SetupItem("apt", "apt 包管理器", "初始化中", phase = "初始化中")
            setupAptSources()
            executeCommand("apt-get update -qq")
                .onFailure { log("  ⚠ apt update 失败:${it.message}") }
            updateItem("apt", SetupItem("apt", "apt 包管理器", "已就绪", done = true))
            log("  ✓ apt 就绪")

            // 阶段 4+:JDK(可选)
            for ((index, version) in jdkList.withIndex()) {
                val step = index + 4
                _items.value = _items.value + SetupItem("jdk$version", "Java $version", "需下载,按需安装")
                if (isJdkInstalled(version)) {
                    updateItem("jdk$version", SetupItem("jdk$version", "Java $version", "需下载,按需安装", done = true))
                    log(">>> 阶段 $step/$totalSteps:Java $version 已安装,跳过")
                    continue
                }
                log(">>> 阶段 $step/$totalSteps:在线安装 Java $version(openjdk-$version-jdk-headless)")
                updateItem("jdk$version", SetupItem("jdk$version", "Java $version", "需下载,按需安装", phase = "安装中"))
                val result = executeCommand(
                    "DEBIAN_FRONTEND=noninteractive apt-get install -y -qq openjdk-$version-jdk-headless",
                    timeoutMs = 900_000
                )
                if (result.isFailure) {
                    log("  ⚠ Java $version 安装失败:${result.exceptionOrNull()?.message}")
                } else {
                    updateItem("jdk$version", SetupItem("jdk$version", "Java $version", "需下载,按需安装", done = true))
                    log("  ✓ Java $version 安装完成")
                }
            }

            _items.value = _items.value.map { it.copy(done = true) }
            _state.value = EnvState.READY
            log(">>> 环境初始化完成!已安装 ${installedJdkVersions().size} 个 Java 版本")
            Result.success(Unit)
        } catch (e: Exception) {
            Logger.e("runFullSetup failed", e)
            _state.value = EnvState.ERROR
            log("> 错误:${e.message}")
            Result.failure(e)
        } finally {
            isSetupRunning.set(false)
        }
    }

    /** Ubuntu 24.04 使用 deb822 源格式 */
    private fun setupAptSources() {
        val sourceFile = File(rootfsDir, "etc/apt/sources.list.d/ubuntu.sources")
        if (!sourceFile.exists()) {
            val arch = if (isAarch64) "arm64" else "armhf"
            sourceFile.parentFile?.mkdirs()
            sourceFile.writeText(
                "Types: deb\n" +
                "URIs: http://ports.ubuntu.com/ubuntu-ports/\n" +
                "Suites: noble noble-updates noble-backports\n" +
                "Components: main universe\n" +
                "Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg\n"
            )
        }
    }

    // ── 设置页 Java 同步到 rootfs ──
    fun syncJavaToRootfs(version: String, sourceJdkRoot: File): Boolean {
        if (!isEnvironmentReady() || !sourceJdkRoot.exists() || !File(sourceJdkRoot, "bin/java").exists()) return false
        return try {
            val destDir = File(javaHomeDir, "java-$version-openjdk-$jdkArchSuffix")
            if (destDir.exists()) destDir.deleteRecursively()
            destDir.parentFile?.mkdirs()
            sourceJdkRoot.copyRecursively(destDir)
            File(destDir, "bin/java").setExecutable(true)
            Logger.i("Java $version 已同步到 Ubuntu 环境")
            true
        } catch (e: Exception) { Logger.w("syncJavaToRootfs failed", e); false }
    }

    /** 简单下载(带重定向),返回字节数 */
    private fun downloadToFile(urlStr: String, dest: File, onProgress: ((Long, Long) -> Unit)? = null): Long {
        dest.parentFile?.mkdirs()
        var conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 15000
        conn.readTimeout = 60000
        var redirects = 0
        while (redirects < 5 && conn.responseCode in listOf(301, 302, 303, 307, 308)) {
            val loc = conn.getHeaderField("Location") ?: break
            conn.disconnect()
            conn = URL(loc).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            redirects++
        }
        if (conn.responseCode != HttpURLConnection.HTTP_OK) {
            throw RuntimeException("HTTP ${conn.responseCode}")
        }
        val total = conn.contentLengthLong
        val buffer = ByteArray(64 * 1024)
        var downloaded = 0L
        conn.inputStream.use { input ->
            FileOutputStream(dest).use { out ->
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    out.write(buffer, 0, read)
                    downloaded += read
                    onProgress?.invoke(downloaded, total)
                }
            }
        }
        conn.disconnect()
        return downloaded
    }
}
