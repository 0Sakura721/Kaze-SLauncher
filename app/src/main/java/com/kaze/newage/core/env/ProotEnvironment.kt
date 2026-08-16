package com.kaze.newage.core.env

import android.content.Context
import android.os.Build
import android.util.Log
import com.kaze.newage.util.Downloader
import com.kaze.newage.util.TarExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
    /**
     * proot 运行时 = 修补版二进制（oonid/pr 的 proot fork，GPL-2.0-or-later，THIRD_PARTY_NOTICES 已记）。
     * 必须从 **nativeLibraryDir** 直接运行：
     *   - targetSdk>=29 的应用进程受 Android W^X 限制，无法 exec /data/data/... 下的 ELF；
     *   - nativeLibraryDir（/data/app/.../lib/arm64）是唯一允许 untrusted_app execve 的位置；
     *   - 修补版 proot 内置 SIGSYS 处理器，模拟被 zygote seccomp 拦截的 chdir/chmod/getcwd 等系统调用；
     *   - PROOT_LOADER 指向同目录的 loader，由它 mmap 加载 guest ELF（不触发 W^X）。
     */
    private val prootBinary: File get() = File(context.applicationInfo.nativeLibraryDir, "libproot.so")
    private val prootLoader: File get() = File(context.applicationInfo.nativeLibraryDir, "libproot-loader.so")
    override val rootfsDir: File get() = File(linuxDir, "rootfs")
    val javaHomeDir: File get() = File(rootfsDir, "usr/lib/jvm")

    private val isAarch64: Boolean
        get() = Build.SUPPORTED_ABIS.any { it.contains("arm64-v8a", ignoreCase = true) || it.contains("aarch64", ignoreCase = true) }
    private val archName: String get() = if (isAarch64) "aarch64" else "armhf"
    private val rootfsArch: String get() = if (isAarch64) "arm64" else "armhf"

    // ── 状态 ──
    /**
     * rootfs 健康检查：真实打开并读取关键文件一个字节。
     * 不能只用 exists()——真机内部 FUSE 会向应用返回陈旧 dentry 缓存
     *（磁盘上文件已被丢弃，exists() 仍为 true），必须触发真实读取。
     * 检查项：/usr/bin/dash（解压完整性）、/usr/bin/sh（usrmerge 链）、
     * /usr/bin/apt-get（apt 可用性）——任一缺失视为环境损坏，触发自动重建。
     */
    private fun readable(path: String): Boolean = try {
        File(rootfsDir, path).inputStream().use { it.read() >= 0 }
    } catch (_: Exception) { false }

    private fun rootfsHealthy(): Boolean =
        readable("usr/bin/dash") && readable("usr/bin/sh") && readable("usr/bin/apt-get")

    override val isReady: Boolean
        get() {
            val checks = listOf(
                "prootBinary" to prootBinary.exists(),
                "prootLoader" to prootLoader.exists(),
                "rootfs" to rootfsHealthy(),
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

    fun installedJdkVersions(): List<Int> = listOf(8, 11, 17, 21, 25).filter { isJdkInstalled(it) }

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
            // 阶段 1：proot 运行时（jniLibs 内置，无需解压）
            log(">>> 阶段 1/3：检查 proot 运行时 ($archName)")
            _items.value = listOf(SetupItem("proot", "proot 运行时", "内置，开箱即用", phase = "检查中"))
            if (!prootBinary.exists() || !prootLoader.exists()) {
                throw RuntimeException("proot 运行时缺失（nativeLibraryDir 无 libproot.so）")
            }
            _items.value = listOf(SetupItem("proot", "proot 运行时", "内置，开箱即用", done = true))
            log("  ✓ proot 就绪（nativeLibraryDir）")
            // rootfs 内建 .l2s（link2symlink 元数据目录，与 rootfs 同文件系统，apt/dpkg 硬链接需要）
            File(rootfsDir, ".l2s").mkdirs()
            // /sys/fs/selinux 空绑定（Android 上无 selinuxfs，避免 guest 访问报错）
            File(rootfsDir, "sys/.empty").mkdirs()

            // 阶段 2：Ubuntu rootfs
            log(">>> 阶段 2/3：获取 Ubuntu 24.04 rootfs")
            _items.value = listOf(
                SetupItem("proot", "proot 运行时", "内置，解压即用", done = true),
                SetupItem("rootfs", "Ubuntu 24.04", "下载/解压，约 200MB", phase = "准备中"),
            )
            if (!rootfsHealthy()) {
                val rootfsTarball = File(linuxDir, "rootfs.tar.gz")
                if (extractBundledAsset("ubuntu-base-24.04-$rootfsArch.tar.gz", rootfsTarball)) {
                    log("  ✓ 内置提取成功")
                    // 校验资产拷贝完整性：内部存储 FUSE 会静默截断批量写
                    val assetName = "ubuntu-base-24.04-$rootfsArch.tar"
                    val expected = context.assets.open("bundled/$assetName").use { it.available().toLong() }
                    var copyTries = 0
                    while (rootfsTarball.length() != expected && copyTries < 3) {
                        copyTries++
                        log("  ! 资产拷贝不完整（${rootfsTarball.length()}/$expected），重拷 $copyTries")
                        rootfsTarball.delete()
                        extractBundledAsset(assetName, rootfsTarball)
                    }
                    if (rootfsTarball.length() != expected) {
                        throw RuntimeException("rootfs 资产拷贝不完整（${rootfsTarball.length()}/$expected）")
                    }
                } else {
                    log("  内置不可用，网络下载…")
                    updateItem("rootfs") { item -> item.copy(phase = "下载中") }
                    val basePath = "ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-$rootfsArch.tar.gz"
                    val sources = listOf(
                        "https://cdimage.ubuntu.com/$basePath",
                        "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/$basePath",
                        "https://mirrors.huaweicloud.com/ubuntu-cdimage/$basePath",
                    )
                    val used = Downloader.downloadFromSources(
                        sources,
                        rootfsTarball,
                        onProgress = { done, total ->
                            updateItem("rootfs") { it.copy(phase = "下载中", progress = if (total > 0) done.toFloat() / total else 0f, processedBytes = done, totalBytes = total) }
                        },
                        onSourceError = { src, err -> log("  ✗ 源失败 ${src.take(70)}：$err") },
                        // 内容校验：镜像对不存在的文件返回 200+HTML 错误页，靠 gzip 魔数拦截
                        validate = { f -> f.length() > 1_000_000 && isGzipTar(f) },
                    ) ?: throw RuntimeException("rootfs 下载失败（全部源不可用或内容无效）")
                    log("  ✓ 下载完成（源：$used）")
                }
                log("  解压 rootfs（约 200MB，请耐心等待）…")
                // 解压 + 校验（内部存储批量写可能被丢，重试直到 rootfs 关键文件落盘）
                var extractTries = 0
                while (true) {
                    extractTries++
                    if (rootfsDir.exists()) rootfsDir.deleteRecursively()
                    rootfsDir.mkdirs()
                    updateItem("rootfs") { item -> item.copy(phase = "解压中（第 $extractTries 次）", totalBytes = rootfsTarball.length()) }
                    extractViaSystemTar(rootfsTarball, rootfsDir)
                    if (rootfsHealthy()) break
                    if (extractTries >= 4) throw RuntimeException("rootfs 解压多次仍不完整（关键文件缺失）")
                    log("  ! 解压不完整（关键文件不可读），重试…")
                }
                rootfsTarball.delete()
                updateItem("rootfs") { item -> item.copy(done = true, phase = "") }
                log("  ✓ rootfs 就绪")
            } else {
                updateItem("rootfs") { item -> item.copy(done = true, phase = "") }
                log("  ✓ 已就绪，跳过")
            }
            // rootfs 符号链接修复（usrmerge：bin/lib/sbin 软链 + usr/bin/sh）
            // 必须在 rootfs 解压之后执行——沙箱上软链可能建不出来，这里做兜底
            repairRootfsLinks()
            // 重建内建目录（阶段 1 创建、若本环境重建 rootfs 会被解压覆盖删除）：
            // .l2s（link2symlink 元数据）与 sys/.empty（selinux 空绑定）
            File(rootfsDir, ".l2s").mkdirs()
            File(rootfsDir, "sys/.empty").mkdirs()

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

    /** 在环境内执行命令并收集完整输出（apt 等一次性命令用）。
     *  注意：先 waitFor(timeout) 再取输出——避免子进程不退时 readText 永久阻塞导致超时失效。 */
    suspend fun runCommand(command: String, timeoutMs: Long = 900_000): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val pb = buildProotCommand(listOf("/bin/sh", "-c", command), null)
                val proc = startProot(pb) ?: return@withContext Result.failure(RuntimeException("无法启动 proot"))
                // 输出在独立协程读取（防止管道写满死锁子进程）
                val readJob = async(Dispatchers.IO) {
                    runCatching { proc.inputStream.bufferedReader().use { it.readText() } }.getOrDefault("")
                }
                val exited = proc.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (!exited) {
                    proc.destroyForcibly()
                    Result.failure(RuntimeException("命令超时（${timeoutMs / 1000}s）：$command"))
                } else {
                    val output = readJob.await()
                    if (proc.exitValue() == 0) Result.success(output)
                    else Result.failure(RuntimeException("退出码 ${proc.exitValue()}：${output.take(300)}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    // ── proot 进程构造 ──
    private fun prootEnvironment(): Map<String, String> {
        val env = mutableMapOf<String, String>()
        env["PROOT_LOADER"] = prootLoader.absolutePath
        // 修补版为静态链接，nativeLibraryDir 兜底
        val libDir = context.applicationInfo.nativeLibraryDir
        val existing = System.getenv("LD_LIBRARY_PATH") ?: ""
        env["LD_LIBRARY_PATH"] = if (existing.isEmpty()) libDir else "$libDir:$existing"
        // 只关 proot 自己的 seccomp；zygote 过滤器由修补版 SIGSYS 处理器接管（oonid/pr 方案）
        env["PROOT_NO_SECCOMP"] = "1"
        // glue rootfs/f2fs 探测临时目录：内部 cacheDir 实测唯一可靠位置
        //（外部缓存 glue 导致 execve EACCES；rootfs/tmp 刚解压 dentry 陈旧会 ENOENT）
        val tmpCandidate = context.cacheDir.also { it.mkdirs() }
        env["PROOT_TMP_DIR"] = tmpCandidate.absolutePath
        env["TMPDIR"] = tmpCandidate.absolutePath
        // link2symlink 元数据目录必须与 rootfs 同文件系统（dpkg/apt 硬链接需要，oonid/pr 经验）
        val l2s = File(rootfsDir, ".l2s").apply { mkdirs() }
        env["PROOT_L2S_DIR"] = l2s.absolutePath
        // guest 基础环境
        env["HOME"] = "/root"
        env["LANG"] = "C.UTF-8"
        env["TERM"] = "xterm"
        return env
    }

    private fun buildProotCommand(command: List<String>, workDir: File?): ProcessBuilder {
        val args = mutableListOf(
            prootBinary.absolutePath,
            "--rootfs=${rootfsDir.absolutePath}",
            "--cwd=/",
            "--change-id=0:0",
            "--kill-on-exit",
            "--link2symlink",
            "--kernel-release=6.17.0-pr",
            "-b", "/dev",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "/proc/self/fd:/dev/fd",
            "-b", "/dev/urandom:/dev/random",
            "-b", "${File(rootfsDir, "sys/.empty").absolutePath}:/sys/fs/selinux",
            "-b", "${context.cacheDir.absolutePath}:/tmp",
            "-b", "${File(rootfsDir, "tmp").absolutePath}:/dev/shm",
        )
        if (workDir != null && workDir.exists()) {
            // 工作目录绑定到 guest 的 /mnt（真实存在的目录，避免在 glue 里物化
            // 深层 /storage/... 路径；外部 FUSE 上深层路径 sanitize 曾失败）
            args.add("-b"); args.add("${workDir.absolutePath}:/mnt")
        }
        // 包装：cd 到工作目录（绑定在 /mnt）后执行
        val wrapped = buildList {
            add("/bin/sh")
            add("-c")
            val cd = if (workDir != null && workDir.exists()) "cd '/mnt' && " else ""
            add(cd + command.joinToString(" ") { if (it.contains(' ')) "'$it'" else it })
        }
        args.addAll(wrapped)
        val pb = ProcessBuilder(args).redirectErrorStream(true)
        pb.environment().putAll(prootEnvironment())
        return pb
    }

    /**
     * 启动 proot 进程。直接 exec（nativeLibraryDir 允许 untrusted_app execve）。
     * 若被系统拒绝，退回用 /system/bin/linker64 加载。
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

    /**
     * 用系统 tar 二进制解压（前人经验：Termux pkg / proot-distro 均走系统 tar，
     * 久经验证，避免手写解压器在厂商 FUSE 上的兼容问题；toybox tar 自动识别 gzip）。
     */
    private fun extractViaSystemTar(tarFile: File, destDir: File) {
        destDir.mkdirs()
        val proc = Runtime.getRuntime().exec(
            arrayOf("/system/bin/sh", "-c", "tar xf '${tarFile.absolutePath}' -C '${destDir.absolutePath}'")
        )
        val code = proc.waitFor()
        if (code != 0) {
            val err = proc.errorStream.bufferedReader().readText().take(300)
            throw RuntimeException("系统 tar 解压失败（exit=$code）：$err")
        }
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

    /** gzip 魔数（0x1f 0x8b）检查：拦截镜像返回的 HTML 错误页 */
    private fun isGzipTar(f: File): Boolean = try {
        java.io.RandomAccessFile(f, "r").use { raf ->
            raf.readUnsignedByte() == 0x1f && raf.readUnsignedByte() == 0x8b
        }
    } catch (_: Exception) {
        false
    }
}
