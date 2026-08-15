package com.kaze.newage.core.env

import android.content.Context
import android.os.Build
import android.util.Log
import com.kaze.newage.util.Downloader
import com.kaze.newage.util.TarExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * proot + Ubuntu rootfs 的 Linux 环境实现（主方案）。
 *
 * 非 root、自包含：proot 运行时随 APK 内置（assets/bundled），
 * rootfs 首次部署时从 Ubuntu 官方源下载（可替换为内置资产）。
 * 全部逻辑源自 v2 已验证实现（自有代码）。
 *
 * 状态机：NOT_INITIALIZED → SETTING_UP(分步) → READY / ERROR
 */
class ProotEnvironment(
    private val context: Context,
    /** Linux 环境根目录提供者（默认内部存储；设置里可切换外部存储） */
    private val linuxBase: () -> File = { File(context.filesDir, "linux") },
) : LinuxEnvironment {

    enum class State { NOT_INITIALIZED, SETTING_UP, READY, ERROR }

    /** 部署分步项（UI 进度列表用） */
    data class SetupItem(
        val id: String,
        val name: String,
        val desc: String,
        val done: Boolean = false,
        val phase: String = "",
        val progress: Float = 0f,
        val processedBytes: Long = 0,
        val totalBytes: Long = 0,
        val speedBytes: Long = 0,
    )

    private val _state = MutableStateFlow(State.NOT_INITIALIZED)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _items = MutableStateFlow<List<SetupItem>>(emptyList())
    val items: StateFlow<List<SetupItem>> = _items.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val isSetupRunning = AtomicBoolean(false)

    init {
        // 进程重启后恢复状态：文件已就绪则直接标记 READY（避免 UI 一直显示「未部署」）
        if (isReady) _state.value = State.READY
    }

    // ── 路径 ──
    private val linuxDir: File get() = linuxBase().apply { mkdirs() }
    private val prootHomeDir: File get() = File(linuxDir, "proot-home").apply { mkdirs() }
    private val prootBinary: File get() = File(prootHomeDir, "bin/proot")
    private val prootLoader: File get() = File(prootHomeDir, "libexec/loader")
    private val prootLibDir: File get() = File(prootHomeDir, "lib")
    override val rootfsDir: File get() = File(linuxDir, "rootfs")
    val javaHomeDir: File get() = File(rootfsDir, "usr/lib/jvm")

    private val isAarch64: Boolean
        get() = Build.SUPPORTED_ABIS.any { it.contains("arm64-v8a", ignoreCase = true) || it.contains("aarch64", ignoreCase = true) }
    private val archName: String get() = if (isAarch64) "aarch64" else "armhf"
    private val rootfsArch: String get() = if (isAarch64) "arm64" else "armhf"

    // ── 状态 ──
    override val isReady: Boolean
        get() {
            val checks = listOf(
                "prootBinary" to prootBinary.exists(),
                "prootLoader" to prootLoader.exists(),
                "rootfsDir" to rootfsDir.exists(),
                "dash" to File(rootfsDir, "usr/bin/dash").exists(),
                "sh" to File(rootfsDir, "usr/bin/sh").exists(),
            )
            val failed = checks.filter { !it.second }.map { it.first }
            if (failed.isNotEmpty()) {
                Log.w("KazeEnv", "isReady=false，缺失: ${failed.joinToString(", ")}")
            }
            return failed.isEmpty()
        }

    fun isJdkInstalled(version: Int): Boolean {
        val javaBin = File(javaHomeDir, "java-$version-openjdk-$rootfsArch/bin/java")
        return javaBin.exists() && javaBin.canExecute()
    }

    fun installedJdkVersions(): List<Int> = listOf(8, 11, 17, 21).filter { isJdkInstalled(it) }

    /** rootfs 内 java 路径（供启动脚本使用） */
    fun getJavaPath(version: Int): String = "/usr/lib/jvm/java-$version-openjdk-$rootfsArch/bin/java"

    fun resolveJavaPath(preferred: Int?): String? {
        val candidates = (listOfNotNull(preferred) + listOf(21, 17, 11, 8)).distinct()
        for (v in candidates) if (isJdkInstalled(v)) return getJavaPath(v)
        return null
    }

    // ── 部署 ──
    /**
     * 全量部署：proot 运行时（内置）→ rootfs（内置或下载）→ apt 初始化。
     * Java 安装由 JavaManager 按需调用（apt-get install openjdk-N-jdk-headless）。
     */
    override suspend fun setup(onProgress: (Float, String) -> Unit): Unit = withContext(Dispatchers.IO) {
        if (isSetupRunning.get()) {
            onProgress(0f, "部署正在进行中")
            return@withContext
        }
        if (isReady) {
            _state.value = State.READY
            onProgress(1f, "环境已就绪")
            return@withContext
        }
        isSetupRunning.set(true)
        _state.value = State.SETTING_UP

        fun log(msg: String) { _log.value = _log.value + msg }

        try {
            // 阶段 1：proot 运行时（内置资产）
            log(">>> 阶段 1/3：获取 proot 运行时 ($archName)")
            _items.value = listOf(SetupItem("proot", "proot 运行时", "内置，解压即用", phase = "提取中"))
            if (!prootBinary.exists() || !prootLoader.exists()) {
                val prootTarball = File(linuxDir, "proot.tar.gz")
                if (extractBundledAsset("proot-$archName.tar.gz", prootTarball)) {
                    updateItem("proot") { item -> item.copy(phase = "解压中") }
                    TarExtractor.extract(prootTarball, prootHomeDir) { processed, total, speed ->
                        updateItem("proot") { it.copy(phase = "解压中", progress = if (total > 0) processed.toFloat() / total else 0f, processedBytes = processed, totalBytes = total, speedBytes = speed) }
                    }
                    prootTarball.delete()
                    updateItem("proot") { item -> item.copy(done = true, phase = "") }
                    log("  ✓ 内置提取成功")
                    fixProotSonameLinks()
                } else {
                    // 网络回退：termux/proot 官方 releases
                    val url = if (isAarch64)
                        "https://github.com/termux/proot/releases/download/v5.1.107.86/proot-aarch64.tar.gz"
                    else "https://github.com/termux/proot/releases/download/v5.1.107.86/proot-armhf.tar.gz"
                    log("  内置不可用，网络下载…")
                    updateItem("proot") { item -> item.copy(phase = "下载中") }
                    Downloader.download(url, prootTarball) { done, total ->
                        updateItem("proot") { it.copy(phase = "下载中", progress = if (total > 0) done.toFloat() / total else 0f, processedBytes = done, totalBytes = total) }
                    }
                    updateItem("proot") { item -> item.copy(phase = "解压中") }
                    TarExtractor.extract(prootTarball, prootHomeDir)
                    prootTarball.delete()
                    updateItem("proot") { item -> item.copy(done = true, phase = "") }
                    log("  ✓ 网络下载成功")
                }
            } else {
                updateItem("proot") { item -> item.copy(done = true, phase = "") }
                log("  ✓ 已就绪，跳过")
            }
            prootBinary.setExecutable(true)
            prootLoader.setExecutable(true)
            File(prootHomeDir, "libexec/loader32").takeIf { f -> f.exists() }?.setExecutable(true)

            // 阶段 2：Ubuntu rootfs
            log(">>> 阶段 2/3：获取 Ubuntu 24.04 rootfs")
            _items.value = listOf(
                SetupItem("proot", "proot 运行时", "内置，解压即用", done = true),
                SetupItem("rootfs", "Ubuntu 24.04", "下载/解压，约 200MB", phase = "准备中"),
            )
            if (!File(rootfsDir, "usr/bin/dash").exists()) {
                val rootfsTarball = File(linuxDir, "rootfs.tar.gz")
                if (extractBundledAsset("ubuntu-base-24.04-$rootfsArch.tar.gz", rootfsTarball)) {
                    log("  ✓ 内置提取成功")
                } else {
                    log("  内置不可用，网络下载…")
                    updateItem("rootfs") { item -> item.copy(phase = "下载中") }
                    Downloader.download(
                        "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-$rootfsArch.tar.gz",
                        rootfsTarball,
                    ) { done, total ->
                        updateItem("rootfs") { it.copy(phase = "下载中", progress = if (total > 0) done.toFloat() / total else 0f, processedBytes = done, totalBytes = total) }
                    }
                }
                log("  解压 rootfs（约 200MB，请耐心等待）…")
                if (rootfsDir.exists()) rootfsDir.deleteRecursively()
                rootfsDir.mkdirs()
                updateItem("rootfs") { item -> item.copy(phase = "解压中", totalBytes = rootfsTarball.length()) }
                TarExtractor.extract(rootfsTarball, rootfsDir) { processed, total, speed ->
                    updateItem("rootfs") { it.copy(phase = "解压中", progress = if (total > 0) processed.toFloat() / total else 0f, processedBytes = processed, totalBytes = total, speedBytes = speed) }
                }
                rootfsTarball.delete()
                if (!File(rootfsDir, "usr/bin/dash").exists()) {
                    throw RuntimeException("rootfs 解压不完整（usr/bin/dash 缺失），请重试")
                }
                updateItem("rootfs") { item -> item.copy(done = true, phase = "") }
                log("  ✓ rootfs 就绪")
            } else {
                updateItem("rootfs") { item -> item.copy(done = true, phase = "") }
                log("  ✓ 已就绪，跳过")
            }
            // rootfs 符号链接修复（usrmerge：bin/lib/sbin 软链 + usr/bin/sh）
            // 必须在 rootfs 解压之后执行——沙箱上软链可能建不出来，这里做兜底
            repairRootfsLinks()

            // 阶段 3：apt 初始化
            log(">>> 阶段 3/3：初始化 apt 包管理器")
            _items.value = _items.value.map { if (it.id == "rootfs") it.copy(done = true, phase = "") else it } +
                SetupItem("apt", "apt 包管理器", "更新软件源索引", phase = "初始化中")
            setupAptSources()
            runCommand("apt-get update -qq")
            updateItem("apt") { item -> item.copy(done = true, phase = "") }
            log("  ✓ apt 就绪")

            _items.value = _items.value.map { it.copy(done = true, phase = "") }
            _state.value = State.READY
            onProgress(1f, "环境初始化完成")
            log(">>> 环境初始化完成！已安装 Java：${installedJdkVersions()}")
        } catch (e: Exception) {
            Log.w("KazeSLauncher", "env setup failed", e)
            _state.value = State.ERROR
            log("> 错误：${e.message}")
            onProgress(0f, "部署失败：${e.message}")
        } finally {
            isSetupRunning.set(false)
        }
    }

    // ── 命令执行 ──
    /**
     * 启动进程（不阻塞）。stdin 为 PIPE（可注入命令），stdout/stderr 合并。
     * 失败返回 null。
     */
    override fun launch(
        command: List<String>,
        workDir: File?,
        env: Map<String, String>,
    ): Process? {
        if (!isReady) return null
        val pb = buildProotCommand(command, workDir)
        pb.environment().putAll(env)
        return startProot(pb)
    }

    /**
     * 在环境内执行命令，实时回调每行输出。
     * @param command 环境内命令（例如 ["/usr/lib/jvm/java-17-openjdk-arm64/bin/java", "-jar", "server.jar"]）
     * @param workDir 工作目录（host 路径，自动绑定进 proot）
     */
    override suspend fun execute(
        command: List<String>,
        workDir: File?,
        env: Map<String, String>,
        onLine: (String) -> Unit,
    ): Int? = withContext(Dispatchers.IO) {
        val proc = launch(command, workDir, env) ?: return@withContext null
        try {
            proc.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line -> onLine(line) }
            }
            proc.waitFor()
            proc.exitValue()
        } catch (e: Exception) {
            Log.w("KazeSLauncher", "execute failed", e)
            proc.destroyForcibly()
            null
        }
    }

    /** 在环境内执行命令并收集完整输出（apt 等一次性命令用） */
    suspend fun runCommand(command: String, timeoutMs: Long = 900_000): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val pb = buildProotCommand(listOf("/bin/sh", "-c", command), null)
                val proc = startProot(pb) ?: return@withContext Result.failure(RuntimeException("无法启动 proot"))
                val output = proc.inputStream.bufferedReader().readText()
                val exited = proc.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (!exited) {
                    proc.destroyForcibly()
                    Result.failure(RuntimeException("命令超时"))
                } else if (proc.exitValue() == 0) Result.success(output)
                else Result.failure(RuntimeException("退出码 ${proc.exitValue()}：${output.take(300)}"))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    // ── proot 进程构造 ──
    private fun prootEnvironment(): Map<String, String> {
        val env = mutableMapOf<String, String>()
        env["PROOT_LOADER"] = prootLoader.absolutePath
        val existing = System.getenv("LD_LIBRARY_PATH") ?: ""
        env["LD_LIBRARY_PATH"] = if (existing.isEmpty()) prootLibDir.absolutePath else "${prootLibDir.absolutePath}:$existing"
        env["PROOT_NO_SECCOMP"] = "1"
        // proot 需要可写临时目录做 glue rootfs/f2fs 探测，否则启动即失败
        env["PROOT_TMP_DIR"] = File(rootfsDir, "tmp").absolutePath
        env["TMPDIR"] = File(rootfsDir, "tmp").absolutePath
        return env
    }

    private fun buildProotCommand(command: List<String>, workDir: File?): ProcessBuilder {
        val args = mutableListOf(
            prootBinary.absolutePath, "-0",
            "-r", rootfsDir.absolutePath,
            "-b", "/dev:/dev",
            "-b", "/proc:/proc",
            "-b", "/sys:/sys",
        )
        if (workDir != null && workDir.exists()) {
            args.add("-b"); args.add("${workDir.absolutePath}:${workDir.absolutePath}")
        }
        // Ubuntu 24.04 usrmerge 兼容：bin/lib/sbin 是符号链接，Android 沙箱无法创建，
        // 用 proot 绑定将 usr 子目录映射到根目录
        if (File(rootfsDir, "usr/bin").exists() && !File(rootfsDir, "bin/sh").exists()) {
            args.add("-b"); args.add("${File(rootfsDir, "usr/bin").absolutePath}:/bin")
            args.add("-b"); args.add("${File(rootfsDir, "usr/lib").absolutePath}:/lib")
            args.add("-b"); args.add("${File(rootfsDir, "usr/sbin").absolutePath}:/sbin")
        }
        // 包装：cd 到工作目录后执行
        val wrapped = buildList {
            add("/bin/sh")
            add("-c")
            val cd = workDir?.let { "cd '$it' && " } ?: ""
            add(cd + command.joinToString(" ") { if (it.contains(' ')) "'$it'" else it })
        }
        args.addAll(wrapped)
        val pb = ProcessBuilder(args).redirectErrorStream(true)
        pb.environment().putAll(prootEnvironment())
        return pb
    }

    /**
     * 启动 proot 进程。优先直接 exec（模拟器/翻译层兼容），
     * 若被系统拒绝（Android 15+/厂商 ROM 禁止 exec 应用目录 ELF，execve EACCES），
     * 自动退回用 /system/bin/linker64 加载（proot 类 App 的标准做法）。
     */
    private fun startProot(pb: ProcessBuilder): Process? {
        return try {
            pb.start()
        } catch (e: java.io.IOException) {
            Log.w("KazeSLauncher", "直接 exec proot 失败(${e.message})，退回 linker64 加载")
            try {
                val args = mutableListOf("/system/bin/linker64") + pb.command()
                val pb2 = ProcessBuilder(args).redirectErrorStream(true)
                pb2.environment().putAll(pb.environment())
                pb2.start()
            } catch (e2: Exception) {
                Log.e("KazeSLauncher", "linker64 加载也失败", e2)
                null
            }
        }
    }

    // ── 内部工具 ──
    private fun updateItem(id: String, transform: (SetupItem) -> SetupItem) {
        _items.value = _items.value.map { if (it.id == id) transform(it) else it }
    }

    private fun extractBundledAsset(assetName: String, dest: File): Boolean {
        if (dest.exists() && dest.length() > 0) return true
        val candidates = if (assetName.endsWith(".gz")) {
            listOf(assetName, assetName.removeSuffix(".gz"))
        } else listOf(assetName)
        for (candidate in candidates) {
            try {
                dest.parentFile?.mkdirs()
                context.assets.open("bundled/$candidate").use { input ->
                    FileOutputStream(dest).use { out -> input.copyTo(out) }
                }
                return true
            } catch (_: Exception) { /* 尝试下一个候选 */ }
        }
        return false
    }

    /** 修复 proot 库 soname 链接（沙箱无法建链接 → 用文件副本） */
    private fun fixProotSonameLinks() {
        try {
            val libDir = File(prootHomeDir, "lib")
            libDir.listFiles()?.forEach { f ->
                if (f.isDirectory) {
                    val real = libDir.listFiles()?.firstOrNull {
                        it.isFile && it.name != f.name && it.name.startsWith(f.name)
                    }
                    if (real != null) {
                        f.deleteRecursively()
                        real.copyTo(File(libDir, f.name), overwrite = true)
                    }
                }
            }
        } catch (_: Exception) { }
    }

    /**
     * rootfs 解压后的符号链接修复（必须在解压完成后调用）：
     *  1. usr/bin/sh → dash 文件副本（proot 命令入口 /bin/sh 必需，Ubuntu 24.04 中 sh 是指向 dash 的软链）
     *  2. usrmerge 顶层目录（bin/sbin/lib 等）尝试建真符号链接；
     *     失败则保持空目录，运行时由 buildProotCommand 的 -b 绑定映射到 usr 目录
     */
    private fun repairRootfsLinks() {
        // 1) usr/bin/sh（若被提取为空目录或缺失）
        try {
            val sh = File(rootfsDir, "usr/bin/sh")
            val dash = File(rootfsDir, "usr/bin/dash")
            if (dash.isFile && (sh.isDirectory || !sh.exists())) {
                if (sh.isDirectory) sh.deleteRecursively()
                dash.copyTo(sh, overwrite = true)
                sh.setExecutable(true)
                _log.value = _log.value + "  ✓ 修复 usr/bin/sh → dash"
            }
        } catch (_: Exception) { }

        // 2) usrmerge 顶层目录：真符号链接（失败则运行时 -b 绑定兜底）
        val mergeDirs = listOf(
            "bin" to "usr/bin",
            "sbin" to "usr/sbin",
            "lib" to "usr/lib",
            "lib32" to "usr/lib32",
            "lib64" to "usr/lib64",
            "libx32" to "usr/libx32",
        )
        for ((name, relTarget) in mergeDirs) {
            val link = File(rootfsDir, name)
            val targetDir = File(rootfsDir, relTarget)
            if (targetDir.isDirectory && (link.isDirectory || !link.exists())) {
                try {
                    if (link.exists()) link.deleteRecursively()
                    android.system.Os.symlink(relTarget, link.absolutePath)
                    _log.value = _log.value + "  ✓ 符号链接 $name → $relTarget"
                } catch (_: Exception) {
                    // 沙箱禁止建链接：保留空目录，buildProotCommand 的 -b 绑定负责映射
                }
            }
        }
    }

    /** Ubuntu 24.04 使用 deb822 源格式（ports.ubuntu.com） */
    private fun setupAptSources() {
        val sourceFile = File(rootfsDir, "etc/apt/sources.list.d/ubuntu.sources")
        if (!sourceFile.exists()) {
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
}
