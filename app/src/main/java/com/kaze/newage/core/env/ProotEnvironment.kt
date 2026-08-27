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
    /** 非 arm64（v7a/模拟器）时的 proot 运行时目录：assets/bundled 的 termux 静态包 */
    private val prootHomeDir: File get() = File(linuxDir, "proot-home")

    private val prootBinary: File get() = if (isAarch64) {
        File(context.applicationInfo.nativeLibraryDir, "libproot.so").takeIf { it.exists() }
            ?: extractNativeProot("libproot.so")
    } else {
        ensureProotRuntime()
        File(prootHomeDir, "bin/proot")
    }
    private val prootLoader: File get() = if (isAarch64) {
        File(context.applicationInfo.nativeLibraryDir, "libproot-loader.so").takeIf { it.exists() }
            ?: extractNativeProot("libproot-loader.so")
    } else {
        ensureProotRuntime()
        File(prootHomeDir, "libexec/loader")
    }
    override val rootfsDir: File get() = File(linuxDir, "rootfs")
    val javaHomeDir: File get() = File(rootfsDir, "usr/lib/jvm")

    /**
     * x86_64 首选设备（如 MuMu：SUPPORTED_ABIS 首选 x86_64 且 APK 含 x86_64 lib 时，
     * 安装只解压 x86_64 目录）不会解压 arm64 lib → nativeLibraryDir 缺 libproot.so。
     * 从 APK zip 内 lib/arm64-v8a/ 提取到 filesDir 兜底（模拟器等无 W^X 设备可 exec；
     * 真机 arm64 首选时 nativeLibraryDir 必有 proot，不走此路径）。
     */
    private fun extractNativeProot(name: String): File {
        val dir = File(context.filesDir, "native-extract").apply { mkdirs() }
        val f = File(dir, name)
        if (!f.exists() || f.length() < 100_000L) {
            try {
                java.util.zip.ZipFile(context.applicationInfo.sourceDir).use { zip ->
                    val entry = zip.getEntry("lib/arm64-v8a/$name") ?: return f
                    zip.getInputStream(entry).use { ins ->
                        f.outputStream().use { ins.copyTo(it) }
                    }
                }
                f.setExecutable(true)
            } catch (_: Exception) { }
        }
        return f
    }

    /**
     * 非 arm64（v7a/模拟器）时从 assets/bundled 解压 termux proot 静态运行时（v3 同款，
     * 已验证）：bin/proot + libexec/loader + lib/{talloc,shmem}。API<29 的 filesDir 无
     * W^X 限制可直接 exec；arm64 设备走 nativeLibraryDir 修补版 .so，不走此路径。
     * 幂等；返回错误消息，成功返回 null。
     */
    @Synchronized
    private fun ensureProotRuntime(): String? {
        val bin = File(prootHomeDir, "bin/proot")
        val loader = File(prootHomeDir, "libexec/loader")
        if (bin.exists() && loader.exists() && bin.length() > 50_000L && loader.length() > 1_000L) {
            return null
        }
        return try {
            prootHomeDir.mkdirs()
            val tarball = File(linuxDir, "proot.tar.gz")
            if (!extractBundledAsset("proot-$archName.tar.gz", tarball)) {
                // 内置缺失回退 termux 官方 release（与 v3 一致）
                val url = "https://github.com/termux/proot/releases/download/v5.1.107.86/proot-$archName.tar.gz"
                com.kaze.newage.util.Downloader.download(url, tarball)
            }
            TarExtractor.extract(tarball, prootHomeDir)
            fixProotSonameLinks()
            File(prootHomeDir, "bin/proot").setExecutable(true)
            File(prootHomeDir, "libexec/loader").setExecutable(true)
            File(prootHomeDir, "libexec/loader32").takeIf { it.exists() }?.setExecutable(true)
            // 解压完即删源归档（断点续传不需要）
            tarball.delete()
            null
        } catch (e: Exception) {
            e.message ?: "未知错误"
        }
    }

    /** 标准 ARM cpuinfo（写一次，供 -b 覆盖 guest /proc/cpuinfo，apt 需要） */
    private fun writeFakeCpuInfo(): File {
        val f = File(linuxDir, "proot-cpuinfo")
        if (!f.exists() || f.length() < 100) {
            f.writeText(
                "processor\t: 0\n" +
                "BogoMIPS\t: 44.44\n" +
                "Features\t: half thumb fastmult vfp edsp neon vfpv3 tls vfpv4 idiva idivt vfpd32 lpae evtstrm aes pmull sha1 sha2 crc32\n" +
                "CPU implementer\t: 0x41\n" +
                "CPU architecture: 7\n" +
                "CPU variant\t: 0x1\n" +
                "CPU part\t: 0xc09\n" +
                "CPU revision\t: 3\n"
            )
        }
        return f
    }

    /** termux proot 包的 soname 目录型条目（tar 提取成目录的软链）换成真文件副本 */
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

    /** rootfs 健康检查：关键文件 + apt 后段条目。
     *  dash/sh/apt-get 位于 tar 前段，解压早期就会落盘——只查它们会在解压完成前误判
     *  "已解压完成"（实锤：toybox tar 丢 usr/share 整目录 → apt "Error reading the CPU table"）。
     *  apt-helper 在 tar 后段，确保流式解压真正走完；usr/share/apt/cpu-table 由
     *  ensureAptCpuTable 生成（tar 无此目录，不能作为健康项） */
    private fun rootfsHealthy(): Boolean =
        readable("usr/bin/dash") && readable("usr/bin/sh") && readable("usr/bin/apt-get") &&
            readable("usr/lib/apt/apt-helper")

    /**
     * toybox tar 丢链兜底（幂等）：Ubuntu multiarch 顶层 soname 软链
     *（usr/lib/ld-linux-armhf.so.3、libc.so.6 等 → arm-linux-gnueabihf/）在部分设备
     * 解压时丢失——dash/apt 的 PT_INTERP 解析不到就 execve ENOENT（v7a 真机实锤）。
     * 挂在 isReady 上：已部署过的老安装也能自愈。
     */
    private fun ensureMultiarchLinks() {
        try {
            val topLib = File(rootfsDir, "usr/lib")
            val multiarch = File(topLib, "arm-linux-gnueabihf")
            if (!multiarch.isDirectory) return
            val base = if (isAarch64) "aarch64-linux-gnu" else "arm-linux-gnueabihf"
            multiarch.listFiles()?.forEach { f ->
                if (f.isFile && Regex("""\.so(\.\d+){1,3}$""").containsMatchIn(f.name)) {
                    val link = File(topLib, f.name)
                    if (!link.exists()) {
                        runCatching {
                            android.system.Os.symlink("$base/${f.name}", link.absolutePath)
                        }
                    }
                }
            }
        } catch (_: Exception) { }
    }

    /**
     * Ubuntu-base 不含 /usr/share/apt/cpu-table（apt 包 postinst 生成物）——
     * apt 读取失败报 "Error reading the CPU table"（v7a 真机实锤）。手动补最小表。
     */
    private fun ensureAptCpuTable() {
        val f = File(rootfsDir, "usr/share/apt/cpu-table")
        if (!f.exists()) {
            try {
                f.parentFile?.mkdirs()
                f.writeText(
                    "armv7\tarmhf\narmv7l\tarmhf\narmv7b\tarmhf\narmv6\tarmhf\n" +
                        "aarch64\tarm64\narmv8\tarm64\narmv8l\tarm64\n"
                )
            } catch (_: Exception) { }
        }
    }

    override val isReady: Boolean
        get() {
            // 自愈：解压丢链的老安装（先尝试修复再看状态）
            ensureMultiarchLinks()
            ensureAptCpuTable()
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
        // 不用 canExecute()：模拟器/FUSE 上该判定不可靠（isReady 早已弃用同类检查），
        // 损坏的半成品也可能带执行位。改为真实体积判定：完整 java 二进制 >1MB
        val javaBin = File(javaHomeDir, "java-$version-openjdk-$rootfsArch/bin/java")
        return try {
            javaBin.length() > 1_000_000L
        } catch (_: Exception) { false }
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
        // CAS 抢锁（原 get+set 非原子：窗口内两入口可双双进入压榨 rootfs）。
        // 没抢到：等待对方完成——成功直接收工；失败则自己整体重试，不再误报"正在进行"。
        if (!isSetupRunning.compareAndSet(false, true)) {
            onProgress(0f, "另一部署正在进行，等待其完成…")
            val deadline = System.currentTimeMillis() + 900_000
            var timedOut = true
            while (isSetupRunning.get()) {
                if (System.currentTimeMillis() >= deadline) break
                kotlinx.coroutines.delay(500)
                timedOut = false
            }
            if (timedOut && isSetupRunning.get()) {
                _state.value = State.ERROR
                onProgress(0f, "等待并发部署超时")
                return@withContext
            }
            if (isReady) {
                _state.value = State.READY
                onProgress(1f, "环境已就绪")
                return@withContext
            }
            // 对方失败/未成功：自己接手重试；抢不到说明又有新的部署进场，交给它
            if (!isSetupRunning.compareAndSet(false, true)) {
                onProgress(0f, "等待并发部署完成…")
                return@withContext
            }
        }
        if (isReady) {
            _state.value = State.READY
            onProgress(1f, "环境已就绪")
            isSetupRunning.set(false)
            return@withContext
        }
        _state.value = State.SETTING_UP

        fun log(msg: String) { _log.value = _log.value + msg }

        try {
            // 阶段 1：proot 运行时（jniLibs 内置，无需解压）
            log(">>> 阶段 1/3：检查 proot 运行时 ($archName)")
            _items.value = listOf(SetupItem("proot", "proot 运行时", "内置，开箱即用", phase = "检查中"))
            // 非 arm64：先确保 assets 的 termux 静态运行时解压就位（arm64 由 nativeLibraryDir 提供）
            if (!isAarch64) {
                ensureProotRuntime()?.let { throw RuntimeException("proot 运行时不可用：$it") }
            }
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
                val bundledName = "ubuntu-base-24.04-$rootfsArch.tar.gz"
                if (extractBundledAsset(bundledName, rootfsTarball)) {
                    log("  ✓ 内置提取成功")
                    // 校验资产拷贝完整性：内部存储 FUSE 会静默截断批量写。
                    // 注意：构建管线把 .tar.gz 资产以 .tar 形态打包进 APK（extractBundledAsset
                    // 的 gz→tar 回退就是为此设计）——校验名必须与解压候选逻辑一致，双候选探测
                    val expected = runCatching {
                        context.assets.open("bundled/$bundledName").use { it.available().toLong() }
                    }.getOrElse {
                        context.assets.open("bundled/${bundledName.removeSuffix(".gz")}").use { it.available().toLong() }
                    }
                    var copyTries = 0
                    while (rootfsTarball.length() != expected && copyTries < 3) {
                        copyTries++
                        log("  ! 资产拷贝不完整（${rootfsTarball.length()}/$expected），重拷 $copyTries")
                        rootfsTarball.delete()
                        extractBundledAsset(bundledName, rootfsTarball)
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
                // 用自研 TarExtractor：E6 等老设备的 toybox tar 处理长路径/扩展头会丢条目
                // 且静默 exit 0（实锤：usr/share 整目录丢失 → apt "Error reading the CPU table"）
                var extractTries = 0
                while (true) {
                    extractTries++
                    if (rootfsDir.exists()) rootfsDir.deleteRecursively()
                    rootfsDir.mkdirs()
                    updateItem("rootfs") { item -> item.copy(phase = "解压中（第 $extractTries 次）", totalBytes = rootfsTarball.length()) }
                    try {
                        TarExtractor.extract(rootfsTarball, rootfsDir) { done, total, speed ->
                            updateItem("rootfs") { it.copy(phase = "解压中（第 $extractTries 次）", progress = if (total > 0) done.toFloat() / total else 0f, processedBytes = done, totalBytes = total, speedBytes = speed) }
                        }
                    } catch (e: Exception) {
                        Log.w("KazeSLauncher", "rootfs extract failed (try $extractTries)", e)
                        throw e
                    }
                    if (rootfsHealthy()) break
                    // 诊断：缺哪些文件 / 解压出多少条目（v7a 设备解压中途丢目录问题）
                    Log.w("KazeSLauncher",
                        "rootfs unhealthy (try $extractTries): dash=${readable("usr/bin/dash")} sh=${readable("usr/bin/sh")} apt=${readable("usr/bin/apt-get")} helper=${readable("usr/lib/apt/apt-helper")} files=${rootfsDir.walkTopDown().count()} bytes=${rootfsDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }}")
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
            // apt 数据补丁：cpu-table（tar 无此目录，apt 读取缺失时报 CPU table 错）
            ensureAptCpuTable()
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
        // 注意：不能显式设 LD_PRELOAD=""——bionic 8.1 对空串预加载的解析会失控，
        // 把 LD_LIBRARY_PATH 目录当文件读导致 CANNOT LINK（v7a 真机二分实测）。
        // 厂商注入的 libdirect-coredump.so 加载失败只是 "ignored" 警告，无害
        // 修补版为静态链接，nativeLibraryDir 兜底；非 arm64 时 termux 包的 lib/（talloc 等）
        // 必须前置，否则 loader 找不到 soname
        val libDir = context.applicationInfo.nativeLibraryDir
        val existing = System.getenv("LD_LIBRARY_PATH") ?: ""
        val extra = if (isAarch64) libDir else "${File(prootHomeDir, "lib").absolutePath}:$libDir"
        env["LD_LIBRARY_PATH"] = if (existing.isEmpty()) extra else "$extra:$existing"
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
        // 关键：guest 必须用 Linux PATH——父进程（zygote）的 PATH 是 Android 的
        // /system/bin..., 不含 /usr/bin → command -v apt-get 直接 127（v7a 真机实锤）
        env["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
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
        // Android 的 /proc/cpuinfo 非标准 Linux 格式（缺 implementer/Features 字段）——
        // Ubuntu 24.04 的 apt 解析失败报 "Error reading the CPU table"（v7a 真机实锤）。
        // 用标准 ARM cpuinfo 覆盖绑定到 guest 的 /proc/cpuinfo（specific 绑定优先于 /proc）
        if (!isAarch64) {
            val fake = writeFakeCpuInfo()
            args.add("-b"); args.add("$fake:/proc/cpuinfo")
        }
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
                // arm64 用 linker64（64 位进程被拒时加载 64 位 ELF）；v7a 用 32 位 linker
                val args = mutableListOf(
                    if (isAarch64) "/system/bin/linker64" else "/system/bin/linker"
                ) + pb.command()
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
     * 输出必须消费：toybox tar 遇到大量符号链接/权限警告（rootfs 内数百条）会写满
     * stderr 管道，无消费线程则进程永久 pipe_wait 卡死（v7a 真机实测 6 分钟不动）。
     */
    private fun extractViaSystemTar(tarFile: File, destDir: File) {
        destDir.mkdirs()
        val pb = ProcessBuilder(
            "/system/bin/sh", "-c", "tar xf '${tarFile.absolutePath}' -C '${destDir.absolutePath}'"
        )
        pb.redirectErrorStream(true)
        val proc = pb.start()
        // 后台消费输出（丢弃即可；纯防管道写满）
        val sink = object : java.io.OutputStream() {
            override fun write(b: Int) {}
            override fun write(b: ByteArray, off: Int, len: Int) {}
        }
        Thread {
            try { proc.inputStream.use { it.copyTo(sink) } } catch (_: Exception) { }
        }.apply { isDaemon = true; name = "tar-sink" }.start()
        // 老设备/坏归档可能极慢：超时强杀，避免部署界面无限挂起
        val exited = proc.waitFor(600_000, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!exited) {
            proc.destroyForcibly()
            throw RuntimeException("系统 tar 解压超时（600s）")
        }
        if (proc.exitValue() != 0) {
            throw RuntimeException("系统 tar 解压失败（exit=${proc.exitValue()}）")
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
