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
                // proot 运行时要被解压并 exec，必须校验是 gzip 归档（旧实现用默认 validate = { true }，
                // 等于零校验：镜像返回 HTML 错误页也会被解压，环境从此起不来）
                com.kaze.newage.util.Downloader.download(url, tarball, validate = { isGzipTar(it) })
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
    /**
     * 动态链接器在 guest 内的路径（顶层 soname 软链）。
     * dash/apt 的 PT_INTERP 指向它；缺失时 execve 返回 ENOENT，
     * 而"文件都存在"的健康检查会误判为正常（真机实锤踩过）。
     */
    private val interpLink: File
        get() = File(rootfsDir, if (isAarch64) "usr/lib/ld-linux-aarch64.so.1" else "usr/lib/ld-linux-armhf.so.3")

    private fun rootfsHealthy(): Boolean =
        readable("usr/bin/dash") && readable("usr/bin/sh") && readable("usr/bin/apt-get") &&
            readable("usr/lib/apt/apt-helper") &&
            runCatching { interpLink.exists() }.getOrDefault(false)

    /**
     * toybox tar 丢链兜底（幂等）：Ubuntu multiarch 顶层 soname 软链
     *（usr/lib/ld-linux-armhf.so.3、libc.so.6 等 → arm-linux-gnueabihf/）在部分设备
     * 解压时丢失——dash/apt 的 PT_INTERP 解析不到就 execve ENOENT（v7a 真机实锤）。
     * 挂在 isReady 上：已部署过的老安装也能自愈。
     */
    private fun ensureMultiarchLinks() {
        try {
            val topLib = File(rootfsDir, "usr/lib")
            val base = if (isAarch64) "aarch64-linux-gnu" else "arm-linux-gnueabihf"
            // 必须按 base 取目录：此前这里硬编码 arm-linux-gnueabihf，而 arm64 的 rootfs 里
            // 只有 aarch64-linux-gnu —— 函数第一行就 return，于是 usr/lib/ld-linux-aarch64.so.1
            // 等顶层 soname 软链在 arm64 上**从未创建**，dash 的 PT_INTERP 解析不到，
            // proot 执行任何 guest 程序都失败（vivo Android 16 真机实锤：execve ENOENT）。
            val multiarch = File(topLib, base)
            if (!multiarch.isDirectory) return
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

    /**
     * 补齐 rootfs 里缺失的执行位（幂等，成功一次后写标记文件）。
     *
     * 老版本解压时只给 bin/ 与 libexec/ 设了 +x，而 Java 创建文件默认 0600 ——
     * usr/lib 下的共享库（包括 ld-linux-aarch64.so.1 这个 ELF 解释器）都没有执行位，
     * execve 直接 ENOENT，proot 一步都跑不动。这里对**已经解压过**的 rootfs 做补偿，
     * 用户不必删掉重新部署几十万个文件。
     */
    private fun repairExecPermissions() {
        val marker = File(linuxDir, ".perm-ok")
        if (marker.exists()) return
        var fixed = 0
        try {
            val roots = listOf("usr/lib", "usr/bin", "usr/sbin", "usr/libexec")
                .map { File(rootfsDir, it) }
                .filter { it.isDirectory }
            roots.forEach { root ->
                root.walkTopDown().take(200_000).forEach { f ->
                    if (!f.isFile) return@forEach
                    // 共享库（含动态链接器）与可执行目录下的文件都需要 x 位
                    val isLib = f.name.endsWith(".so") || f.name.contains(".so.")
                    if (!isLib && root.name != "bin" && root.name != "libexec") return@forEach
                    if (f.canExecute()) return@forEach
                    if (runCatching { android.system.Os.chmod(f.absolutePath, 0b111_101_101) }.isSuccess) fixed++
                }
            }
            runCatching { marker.writeText("ok") }
        } catch (e: Exception) {
            Log.w("KazeEnv", "repairExecPermissions 失败", e)
        }
        if (fixed > 0) {
            _log.value = _log.value + "  ✓ 补齐 $fixed 个文件的执行位（老版本解压缺 x）"
            Log.w("KazeSLauncher", "repairExecPermissions: fixed=$fixed")
        }
    }
    override val isReady: Boolean
        get() {
            // 自愈：解压丢链的老安装（先尝试修复再看状态）
            ensureMultiarchLinks()
            ensureAptCpuTable()
            // 权限自愈（有标记文件，首次之后零开销）
            repairExecPermissions()
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

    /**
     * rootfs 里实际装着的 JDK：主版本 → 目录名。
     *
     * 不用「`java-N-openjdk-<arch>/bin/java` 且 >1MB」那种写死判定，两个坑都踩过：
     *  1. OpenJDK 的 `bin/java` 只是个约 100KB 的启动器（真正的大头是 lib/server/libjvm.so，20MB+），
     *     1MB 阈值把**正常安装**判成"未安装"——真机上服务端用 Java 17 跑得好好的，
     *     设置页却写着「Java 17 未安装」。
     *  2. 目录名不统一：apt 装出来是 `java-1.17.0-openjdk-arm64`，Adoptium 解压出来是
     *     `java-17-openjdk-arm64`，写死名字必然漏判。
     * 改为扫描 usr/lib/jvm 下的每个子目录，优先读 release 文件里的 JAVA_VERSION（权威），
     * 读不到再从目录名解析，这样任何来源装的 JDK 都能认出来。
     * （注意别在这段注释里写出斜杠加星号的路径通配——Kotlin 的块注释**可嵌套**，
     *   那会开一个嵌套注释，整个 KDoc 再也闭合不了，后面所有代码都被注释掉。）
     */
    fun installedJdks(): Map<Int, String> {
        val root = javaHomeDir
        if (!root.isDirectory) return emptyMap()
        val found = mutableMapOf<Int, String>()
        runCatching {
            root.listFiles()?.forEach { dir ->
                if (!dir.isDirectory) return@forEach
                val javaBin = File(dir, "bin/java")
                // 只要不是空壳就认（0 字节/几 KB 的是半成品；不用 canExecute，FUSE 上不可靠）
                if (!javaBin.isFile || javaBin.length() < 10_000L) return@forEach
                // 但**光有 bin/java 不算装好**：它是个约 100KB 的启动器，而真正的虚拟机
                // libjvm.so（20MB+）在解压/安装的**最后**才落盘。中途被杀/空间不足时
                // （toybox tar 会静默丢条目的那个坑），只查 bin/java 会把半截 JDK 认成
                // "已就绪"——服务端启动 30 秒就退，用户只看到一句笼统的早退错误，
                // 而且因为判定为已安装，永远不会重装。
                if (!hasJvmLibrary(dir)) return@forEach
                val major = readJavaMajor(dir) ?: parseMajorFromName(dir.name) ?: return@forEach
                // 同一主版本有多个目录时保留第一个（通常是先前装的那个）
                found.putIfAbsent(major, dir.name)
            }
        }
        return found
    }

    /** JDK 是否真的完整：必须存在虚拟机本体 libjvm.so（server 或 client VM） */
    private fun hasJvmLibrary(jdkDir: File): Boolean {
        val minSize = 1_000_000L
        return listOf("lib/server/libjvm.so", "lib/client/libjvm.so", "jre/lib/server/libjvm.so")
            .any { rel -> File(jdkDir, rel).let { it.isFile && it.length() > minSize } }
    }

    /**
     * 主版本 → release 文件里的完整版本号（如 "17.0.20.1"）。
     * 设置页显示"已安装 · 17.0.20.1"，比只写"已安装"更有用（用户能看到确切是哪个小版本）。
     */
    fun installedJdkFullVersions(): Map<Int, String> {
        val root = javaHomeDir
        if (!root.isDirectory) return emptyMap()
        val out = mutableMapOf<Int, String>()
        runCatching {
            root.listFiles()?.forEach { dir ->
                if (!dir.isDirectory) return@forEach
                val major = readJavaMajor(dir) ?: parseMajorFromName(dir.name) ?: return@forEach
                val full = readJavaVersionString(dir) ?: return@forEach
                out.putIfAbsent(major, full)
            }
        }
        return out
    }

    /** 读 JDK 的 release 文件里 JAVA_VERSION 的原始值，拿不到返回 null */
    private fun readJavaVersionString(dir: File): String? = runCatching {
        val release = File(dir, "release")
        if (!release.isFile) return@runCatching null
        release.useLines { lines -> lines.firstOrNull { it.startsWith("JAVA_VERSION=") } }
            ?.substringAfter('=')?.trim()?.trim('"')
    }.getOrNull()

    /** 读 JDK 的 release 文件（`JAVA_VERSION="17.0.20.1"`），拿不到返回 null */
    private fun readJavaMajor(dir: File): Int? = runCatching {
        val release = File(dir, "release")
        if (!release.isFile) return@runCatching null
        val raw = release.useLines { lines ->
            lines.firstOrNull { it.startsWith("JAVA_VERSION=") }
        } ?: return@runCatching null
        majorOf(raw.substringAfter('=').trim().trim('"'))
    }.getOrNull()

    /** 从目录名解析主版本：java-17-openjdk-arm64 / java-1.17.0-openjdk-arm64 / jdk-17.0.20.1+1 / jdk8u402 */
    private fun parseMajorFromName(name: String): Int? {
        Regex("""(?:^|[-_])1\.(\d+)""").find(name)?.let { return it.groupValues[1].toIntOrNull() }
        Regex("""(?:jdk|java)[-_]?(\d+)""", RegexOption.IGNORE_CASE).find(name)?.let {
            return it.groupValues[1].toIntOrNull()
        }
        return null
    }

    /** "17.0.20.1" → 17；"1.8.0_402" → 8 */
    private fun majorOf(version: String): Int? {
        val v = version.trim().trim('"').removePrefix("jdk-")
        return if (v.startsWith("1.")) {
            v.substring(2).substringBefore('.').toIntOrNull()
        } else {
            v.substringBefore('.').toIntOrNull()
        }
    }

    fun isJdkInstalled(version: Int): Boolean = installedJdks().containsKey(version)

    /** 实际检测到的版本（不再写死 8/11/17/21/25，装了什么就报什么） */
    fun installedJdkVersions(): List<Int> = installedJdks().keys.sorted()

    /** rootfs 内 java 路径（供启动脚本使用）；未检测到时退回传统命名 */
    fun getJavaPath(version: Int): String =
        installedJdks()[version]?.let { "/usr/lib/jvm/$it/bin/java" }
            ?: "/usr/lib/jvm/java-$version-openjdk-$rootfsArch/bin/java"

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
        // 每次部署都先落一份环境自检报告（外部目录，adb 可读）：
        // 真机上"部署失败/启动失败"只有一句笼统提示时，靠它定位到底卡在哪一步。
        runCatching { dumpDiagnostics() }
        // CAS 抢锁（原 get+set 非原子：窗口内两入口可双双进入压榨 rootfs）。
        // 没抢到：等待对方完成——成功直接收工；失败则自己整体重试，不再误报"正在进行"。
        if (!isSetupRunning.compareAndSet(false, true)) {
            onProgress(0f, "另一部署正在进行，等待其完成…")
            val deadline = System.currentTimeMillis() + 900_000
            // timedOut 只在「deadline 真的到期」时置位。
            // 旧写法初始为 true、在循环里每次 delay 后置 false，而 15 分钟的 deadline
            // 不可能在首次迭代前到达 —— 于是超时分支永远不可达：真卡住时只会在
            // 循环结束后静默返回，既不置 ERROR 也不通知调用方，UI 永远停在"部署中"。
            var timedOut = false
            while (isSetupRunning.get()) {
                if (System.currentTimeMillis() >= deadline) {
                    timedOut = true
                    break
                }
                kotlinx.coroutines.delay(500)
            }
            if (timedOut) {
                _state.value = State.ERROR
                onProgress(0f, "等待并发部署超时")
                return@withContext
            }
            if (isReady) {
                ensureAptStage(onProgress)
                if (_state.value == State.ERROR) return@withContext
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
            // rootfs 已解压 ≠ 部署完整：阶段 3（写 apt 源 + apt-get update）可能从未成功。
            // 旧实现无条件短路返回"环境已就绪"，于是部署中途失败后无论重试多少次，
            // apt 都补不上，后续 `apt-get install`（装 Java）全部失败。
            ensureAptStage(onProgress)
            if (_state.value == State.ERROR) {
                isSetupRunning.set(false)
                return@withContext
            }
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
            val aptRes = runCommand("apt-get update -qq")
            if (aptRes.isFailure) {
                // 不能静默继续：没有 apt 索引，后续 `apt-get install` 装 Java 必然失败，
                // 而用户看到的却是"环境已就绪"，只能对着一个装不上 Java 的环境反复重试。
                // 明确报错即可——联网后再点一次「部署」，会走 ensureAptStage 把这一步补上。
                throw IllegalStateException(
                    "apt 初始化失败（${aptRes.exceptionOrNull()?.message ?: "未知错误"}）。" +
                        "请检查网络后重新点「部署」，已解压的环境不会重复下载。"
                )
            }
            markAptInitialized()
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
    /**
     * 环境自检：把 rootfs 关键文件的状态与"能否被 execve"的实测结果写到**外部**目录
     * （/sdcard/Android/data/<pkg>/files/diagnostics.txt），这样在没有 root / run-as 的
     * release 真机上也能直接用 adb 读到，不必靠猜。
     *
     * 实测两项：
     *  1. 直接 execve rootfs 里的 dash —— 区分"策略禁止执行"与"proot 自身问题"
     *  2. 经 proot 跑一条命令 —— 区分"rootfs 不可用"与"调用方式问题"
     */
    suspend fun dumpDiagnostics(): File = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        fun line(s: String) = sb.appendLine(s)
        val info = context.applicationInfo
        line("Kaze SLauncher 环境自检  ${java.util.Date()}")
        line("SDK=${android.os.Build.VERSION.SDK_INT}  targetSdk=${info.targetSdkVersion}  abi=${android.os.Build.SUPPORTED_ABIS.firstOrNull()}")
        line("filesDir=${context.filesDir}")
        line("rootfs=$rootfsDir  exists=${rootfsDir.exists()}")
        line("proot=$prootBinary  exists=${prootBinary.exists()} len=${prootBinary.length()} x=${prootBinary.canExecute()}")
        line("loader=$prootLoader  exists=${prootLoader.exists()} len=${prootLoader.length()} x=${prootLoader.canExecute()}")
        line("cacheDir=${context.cacheDir} exists=${context.cacheDir.exists()}")
        line("")
        for (rel in listOf("usr/bin/sh", "usr/bin/dash", "usr/bin/apt-get", "usr/bin/java", "bin", "usr/bin", "lib", "usr/lib", "usr/lib/ld-linux-aarch64.so.1", "usr/lib/aarch64-linux-gnu", "usr/lib/aarch64-linux-gnu/libc.so.6")) {
            val f = File(rootfsDir, rel)
            val mode = runCatching { android.system.Os.lstat(f.absolutePath).st_mode }.getOrDefault(-1)
            // android.os.SELinux 是 @hide API：反射取，取不到就是 null（不影响自检其余部分）
            val ctx = runCatching {
                val c = Class.forName("android.os.SELinux")
                c.getMethod("getFileContext", String::class.java).invoke(null, f.absolutePath) as? String
            }.getOrNull()
            val xok = runCatching { android.system.Os.access(f.absolutePath, android.system.OsConstants.X_OK); true }.getOrDefault(false)
            line(
                "$rel exists=${f.exists()} link=${isSymlink(f)} file=${f.isFile} dir=${f.isDirectory} " +
                    "mode=${if (mode >= 0) Integer.toOctalString(mode) else "?"} X_OK=$xok ctx=$ctx"
            )
        }
        line("")
        line("── 容器内 DNS（/etc/resolv.conf，每次起 proot 时绑定）──")
        line(
            runCatching {
                val rf = ensureResolvConf()
                val body = rf.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.joinToString(" | ")
                "  ${rf.absolutePath}  ${rf.length()} 字节  →  $body"
            }.getOrElse { "  读取失败：${it.message}" }
        )
        line(
            "  系统 DNS: " + runCatching {
                val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
                val lp = cm?.activeNetwork?.let { cm.getLinkProperties(it) }
                lp?.dnsServers?.joinToString().takeUnless { it.isNullOrBlank() } ?: "无活动网络"
            }.getOrElse { "读取失败：${it.message}" }
        )
        line("")
        val dash = File(rootfsDir, "usr/bin/dash")
        line("实测1 直接 execve dash：")
        line(
            runCatching {
                val p = ProcessBuilder(dash.absolutePath, "-c", "echo DIRECT_OK")
                    .redirectErrorStream(true).start()
                val o = p.inputStream.bufferedReader().use { it.readText() }.trim()
                val rc = p.waitFor()
                "  rc=$rc  out=$o"
            }.getOrElse { "  抛异常 ${it::class.java.simpleName}: ${it.message}" }
        )
        line("实测2 经 proot 执行 echo：")
        line(
            runCatching {
                val r = runCommand("echo PROOT_OK", timeoutMs = 60_000)
                "  isSuccess=${r.isSuccess} out=${r.getOrNull()?.trim()} err=${r.exceptionOrNull()?.message?.take(300)}"
            }.getOrElse { "  抛异常 ${it::class.java.simpleName}: ${it.message}" }
        )
        val out = File(context.getExternalFilesDir(null) ?: context.filesDir, "diagnostics.txt")
        out.parentFile?.mkdirs()
        runCatching { out.writeText(sb.toString()) }
        sb.toString().trimEnd().lines().forEach { _log.value = _log.value + "[自检] $it" }
        out
    }
    suspend fun runCommand(command: String, timeoutMs: Long = 900_000): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val pb = buildProotCommand(listOf("/usr/bin/sh", "-c", command), null)
                val proc = startProot(pb) ?: return@withContext Result.failure(RuntimeException("无法启动 proot"))
                // 输出在独立协程读取（防止管道写满死锁子进程）
                val readJob = async(Dispatchers.IO) {
                    runCatching { proc.inputStream.bufferedReader().use { it.readText() } }.getOrDefault("")
                }
                val exited = proc.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (!exited) {
                    // 超时分支必须**真的返回**。
                    //
                    // readJob 是 withContext 的子协程，结构化并发要求它结束才能返回；
                    // 而 proot 被 kill 后 guest（apt-get / java）可能还活着，并且继承了
                    // proot 的 stdout —— 只 destroyForcibly() 不会让 readText 看到 EOF，
                    // 于是 withContext 一直等 readJob，"有界超时"变成**永久挂起**：
                    // 调用方（Java 安装 / apt）卡死，UI 停在"安装中"，只能杀应用。
                    // 先 SIGTERM 让 --kill-on-exit 有机会回收 guest，再关掉管道让阻塞读
                    // 立刻拿到 EOF，才轮到真正强杀。
                    proc.destroy()
                    runCatching { proc.inputStream.close() }
                    runCatching { proc.errorStream?.close() }
                    readJob.cancel()
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

    /**
     * POSIX 单引号转义：把参数整体包进单引号，参数内部的 ' 写成 '\''（闭合-转义-重开）。
     *
     * 命令最终由 guest 的 `/bin/sh -c` 解释，因此每个参数都必须转义。原实现只对
     * 「含空格」的参数加引号，导致 ' " $ ` ; & | ( ) \ * ~ 等字符裸露在外：
     *  - 正常文件名会直接坏掉，例如 `server(1).jar`、`我的服务端'正式版'.jar` —— sh 语法错误，
     *    服务端秒退且没有任何可读提示；
     *  - 构造的文件名可逃逸引号，以应用身份在 guest 内执行任意命令（jarName 来自实例目录下
     *    第一个 .jar，实例名 sanitize 不过滤这些字符）。
     * 全量加引号不会影响现有调用：execute()/launch() 传的都是字面量 argv（-Xmx、guest 路径等），
     * runCommand() 传入的整条命令字符串本就依赖外层引号包裹。
     */
    private fun shellQuote(arg: String): String = "'" + arg.replace("'", "'\\''") + "'"

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
            // 容器内 DNS：宿主没有可用的 /etc/resolv.conf 可绑，自己写一份带进去。
            // 没有它，容器内联网的程序（apt、Forge 安装器的 JVM）全部 UnknownHostException。
            "-b", "${ensureResolvConf().absolutePath}:/etc/resolv.conf",
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
        // usrmerge 兜底绑定（真机实锤过的问题）：
        // Ubuntu 24.04 的 /bin /lib /sbin 是指向 usr/ 的**符号链接**，而符号链接在部分设备上
        // 建不出来（TarExtractor 对目录型链接无能为力、Os.symlink 也可能失败）。
        // repairRootfsLinks 的注释一直声称"由 buildProotCommand 的 -b 绑定负责映射"，
        // 但绑定列表里从来没有这些条目 —— 于是 proot 直接报
        // `'/bin/sh' not found (root = .../rootfs)`，每次调用都失败、环境永远不可用。
        for ((name, relTarget) in USRMERGE_DIRS) {
            val target = File(rootfsDir, relTarget)
            if (target.isDirectory && !isSymlink(File(rootfsDir, name))) {
                args.add("-b"); args.add("${target.absolutePath}:/$name")
            }
        }
        // 包装：cd 到工作目录（绑定在 /mnt）后执行
        val wrapped = buildList {
            // 用 /usr/bin/sh（isReady/rootfsHealthy 保证它真实可读）而不是 /bin/sh：
            // 后者依赖上面那个可能不存在的符号链接。上面补了绑定后两者都能用，
            // 但用真文件路径不会因为绑定遗漏而再次踩坑。
            add("/usr/bin/sh")
            add("-c")
            val cd = if (workDir != null && workDir.exists()) "cd '/mnt' && " else ""
            add(cd + command.joinToString(" ") { shellQuote(it) })
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
        } catch (e: Exception) {
            _log.value = _log.value + "  ! usr/bin/sh 修复失败：${e.message}"
        }

        // 2) usrmerge 顶层目录：真符号链接（失败则 runtime 由 -b 绑定兜底，见 buildProotCommand）
        for ((name, relTarget) in USRMERGE_DIRS) {
            val link = File(rootfsDir, name)
            val targetDir = File(rootfsDir, relTarget)
            if (!targetDir.isDirectory) continue
            if (isSymlink(link)) continue
            // 注意：这里必须覆盖"存在但不是目录也不是符号链接"的情况（解压可能留下同名普通文件）。
            // 原条件 (link.isDirectory || !link.exists()) 对普通文件为 false → 什么都不做，
            // 而当时又没有 -b 兜底，结果就是 proot 报 '/bin/sh' not found（真机实锤）。
            if (link.exists() && !link.isDirectory && !link.isFile) continue
            try {
                if (link.exists()) link.deleteRecursively()
                android.system.Os.symlink(relTarget, link.absolutePath)
                _log.value = _log.value + "  ✓ 符号链接 $name → $relTarget"
            } catch (e: Exception) {
                // 建不出链接不是致命错误：buildProotCommand 会把 usr/ 目标绑定到 /<name>
                _log.value = _log.value + "  · $name 无法建符号链接（${e.message}），改用绑定映射"
            }
        }

        // 2.5) 容器内联网的基础配置
        ensureGuestNetConfig()

        // 3) multiarch 顶层 soname 软链（ld-linux-aarch64.so.1 / libc.so.6 等）
        //    必须在部署阶段就建：dash、apt 的 PT_INTERP 指向它，缺了 proot 一执行就 ENOENT
        ensureMultiarchLinks()
    }

    /**
     * 容器用的 /etc/resolv.conf。
     *
     * proot 容器里**没有任何 DNS 配置**：ubuntu-base 的 /etc/resolv.conf 通常是指向
     * systemd-resolved 的断链，于是"在容器内联网"的程序一律解析失败 ——
     * apt 静默失败（旧代码忽略退出码，所以一直没被发现），Forge 安装器的 JVM 直接
     * `UnknownHostException` 中止安装（真机实锤：所有 Host 都是 [Unknown]）。
     *
     * 这里用系统当前的 DNS 服务器写一份，并在每次起 proot 时绑定到容器内，
     * 换网络（Wi-Fi ↔ 移动数据）后自动是最新的。
     */
    private fun ensureResolvConf(): File {
        val f = File(linuxDir, "resolv.conf")
        val systemServers = runCatching {
            val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
            val lp = cm?.activeNetwork?.let { cm.getLinkProperties(it) }
            lp?.dnsServers?.mapNotNull { it.hostAddress?.substringBefore('%') } ?: emptyList()
        }.getOrDefault(emptyList())
        // IPv4 优先：容器里不一定有可用的 IPv6 路由，放到后面免得解析卡在 IPv6 上
        val servers = (
            systemServers.filter { !it.contains(':') } + systemServers.filter { it.contains(':') }
            )
            .ifEmpty { listOf("223.5.5.5", "119.29.29.29", "1.1.1.1", "8.8.8.8") }
            .distinct()
        val text = buildString {
            appendLine("# 由 Kaze SLauncher 写入：proot 容器内的 DNS")
            appendLine("# 宿主的 /etc/resolv.conf 在容器里不可用，必须自带一份")
            servers.forEach { appendLine("nameserver $it") }
            appendLine("options timeout:2 attempts:2")
        }
        runCatching {
            f.parentFile?.mkdirs()
            if (!f.isFile || f.readText() != text) f.writeText(text)
        }
        return f
    }

    /**
     * 容器内联网的基础配置：nsswitch.conf（缺了 glibc 不一定查 DNS）与 hosts。
     * 只补缺失的，不覆盖 rootfs 自带的（避免破坏其它解析规则）。
     */
    private fun ensureGuestNetConfig() {
        runCatching {
            val etc = File(rootfsDir, "etc").apply { mkdirs() }
            // /etc/resolv.conf 在 ubuntu-base 里常是指向 systemd-resolved 的**断链**：
            // proot 往断链目标上做 -b 绑定会失败，先换成真实文件占位
            //（内容每次启动容器时由 -b 覆盖，见 buildProotCommand）
            val resolv = File(etc, "resolv.conf")
            if (isSymlink(resolv) || !resolv.exists()) {
                runCatching { resolv.delete() }
                resolv.writeText("# 由 Kaze SLauncher 管理：启动容器时绑定覆盖\n")
            }
            val nsswitch = File(etc, "nsswitch.conf")
            if (!nsswitch.isFile) {
                nsswitch.writeText("passwd: files\ngroup: files\nshadow: files\nhosts: files dns\n")
            }
            val hosts = File(etc, "hosts")
            if (!hosts.isFile) {
                hosts.writeText("127.0.0.1\tlocalhost\n::1\t\tlocalhost ip6-localhost\n")
            }
        }
    }
    /** 判断是否真符号链接：File.exists() 对断链返回 false，必须用 NIO 判断 */
    private fun isSymlink(f: File): Boolean =
        runCatching { java.nio.file.Files.isSymbolicLink(f.toPath()) }.getOrDefault(false)

    companion object {
        /** usrmerge：Ubuntu 24.04 里这些顶层目录是指向 usr/ 的符号链接 */
        private val USRMERGE_DIRS = listOf(
            "bin" to "usr/bin",
            "sbin" to "usr/sbin",
            "lib" to "usr/lib",
            "lib32" to "usr/lib32",
            "lib64" to "usr/lib64",
            "libx32" to "usr/libx32",
        )
    }

    /** apt 阶段（写源 + apt-get update）是否已完成；老安装没有该标记，视为未完成（重跑一次很便宜） */
    private fun aptInitialized(): Boolean = runCatching { File(linuxDir, ".apt-initialized").exists() }.getOrDefault(false)

    private fun markAptInitialized() {
        runCatching { File(linuxDir, ".apt-initialized").writeText("ok") }
    }

    /**
     * rootfs 已就绪但 apt 阶段未完成时补做「阶段 3」。
     *
     * 为什么需要：部署中途失败（用户按返回键退出应用导致协程被取消、apt 源写入失败、磁盘满…）时
     * rootfs 往往已经解压完 → `isReady` 为 true，而 setup() 开头会短路返回"环境已就绪"，
     * 阶段 3 永远不会再执行。之后所有 `apt-get install`（装 Java）都会失败，且用户重试也没用。
     */
    private suspend fun ensureAptStage(onProgress: (Float, String) -> Unit) {
        if (aptInitialized()) return
        onProgress(0.92f, "补齐 apt 初始化…")
        runCatching { setupAptSources() }
        val res = runCommand("apt-get update -qq")
        if (res.isFailure) {
            _state.value = State.ERROR
            onProgress(0f, "apt 初始化失败：${res.exceptionOrNull()?.message ?: "未知错误"}")
            return
        }
        markAptInitialized()
        _items.value = _items.value.map { if (it.id == "apt") it.copy(done = true, phase = "") else it }
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
