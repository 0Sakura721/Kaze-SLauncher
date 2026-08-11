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

    // ── Android 版 JRE(自包含运行时,核心引擎) ──
    // 关键:JRE 是 Android ELF(interpreter=/system/bin/linker64),
    // Android 15+/16 禁止 exec "interpreter 在应用数据目录" 的 ELF(glibc 程序),
    // 但 interpreter 为宿主 linker64 的 ELF 可以直接 exec —— 这就是
    // Termux/FCL 在 Android 16 上仍能运行的原因,也是本引擎的根基。
    // 不再需要 proot + Ubuntu rootfs(旧方案在 Android 16 上被系统 exec 限制封死)。
    val jreHomeDir: File get() = File(appContext.filesDir, "jre21").apply { mkdirs() }
    val jreLibDir: File get() = File(jreHomeDir, "lib")
    val appTmpDir: File get() = File(appContext.filesDir, "tmp").apply { mkdirs() }

    /** JRE 是否就绪:java 可执行 + 模块镜像存在 */
    fun isJreReady(): Boolean =
        File(jreHomeDir, "bin/java").exists() && File(jreHomeDir, "lib/modules").exists()

    /**
     * 环境自愈(public):修复符号链接降级 + 补 tmp 目录。
     * 服务器启动前与 startProot 内部都会调用,防御 rootfs 损坏/旧部署。
     */
    fun selfHeal() {
        fixProotSonameLinks()
        try {
            File(rootfsDir, "tmp").mkdirs()
        } catch (_: Exception) { }
    }

    /** 修复 proot 库的 soname 链接:Android 沙箱无法创建符号链接,解压器
     *  会把 symlink 条目降级为目录,导致 linker 找不到 libtalloc.so.2。
     *  此处把同名目录替换为真实文件的副本。 */
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
        // rootfs 内关键符号链接同样会被降级成目录(usrmerge 的 /bin→usr/bin、
        // sh→dash 等),proot 要求启动程序是真实文件,这里修复最关键的 /usr/bin/sh
        try {
            val sh = File(rootfsDir, "usr/bin/sh")
            val dash = File(rootfsDir, "usr/bin/dash")
            if (sh.isDirectory && dash.isFile) {
                sh.deleteRecursively()
                if (sh.exists()) {
                    // 删除失败(目录有残留):逐项清空后重删
                    sh.listFiles()?.forEach { it.deleteRecursively() }
                    sh.delete()
                }
                if (!sh.exists()) {
                    dash.copyTo(sh, overwrite = true)
                    sh.setExecutable(true, false)
                }
            }
        } catch (_: Exception) { }
        // usrmerge 根符号链接(bin→usr/bin 等):降级成空目录后,
        // 会触发 proot 的 -b 绑定方案,而绑定(glue)在部分设备上
        // 会导致 proot stat 异常(文件报 Is a directory/No such file)。
        // 首选方案:直接创建真实符号链接(Android app 目录允许),
        // 符号链接正常后 usrmerge 绑定条件(bin/sh 不存在)不再满足,
        // proot 走 22:15 验证过的无绑定路径
        try {
            val usrmerge = listOf(
                "bin" to "usr/bin",
                "sbin" to "usr/sbin",
                "lib" to "usr/lib",
                "lib64" to "usr/lib64"
            )
            for ((name, target) in usrmerge) {
                val f = File(rootfsDir, name)
                val t = File(rootfsDir, target)
                if (!t.exists()) continue
                if (!f.exists()) {
                    try {
                        java.nio.file.Files.createSymbolicLink(
                            f.toPath(),
                            java.nio.file.Paths.get(target)
                        )
                    } catch (_: Exception) { }
                } else if (f.isDirectory) {
                    // 空目录降级:删除后重建符号链接;非空目录(有内容)则跳过
                    if (f.listFiles()?.isEmpty() == true) {
                        if (f.delete()) {
                            try {
                                java.nio.file.Files.createSymbolicLink(
                                    f.toPath(),
                                    java.nio.file.Paths.get(target)
                                )
                            } catch (_: Exception) { }
                        }
                    }
                }
            }
        } catch (_: Exception) { }
    }
    private val prootBinary: File get() = File(prootHomeDir, "bin/proot")
    private val prootLoader: File get() = File(prootHomeDir, "libexec/loader")
    private val prootLibDir: File get() = File(prootHomeDir, "lib")
    val rootfsDir: File get() = File(linuxDir, "rootfs")
    val javaHomeDir: File get() = File(rootfsDir, "usr/lib/jvm")
    private val serverBaseDir: File get() = File(appContext.getExternalFilesDir(null), "instances").apply { mkdirs() }

    /** 架构判定:优先原生 64 位 ABI。x86_64 设备(MuMu 等模拟器)用 amd64,
     *  arm64 设备(真机)用 aarch64,其余回退 armhf。不再依赖 ARM 翻译层。 */
    private val primaryAbi: String
        get() = Build.SUPPORTED_64_BIT_ABIS.firstOrNull() ?: (Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a")
    private val isAarch64: Boolean get() = primaryAbi.contains("arm64") || primaryAbi.contains("aarch64")
    private val isX8664: Boolean get() = primaryAbi.contains("x86_64")
    private val archName: String get() = if (isX8664) "x86_64" else if (isAarch64) "aarch64" else "armhf"
    private val jdkArchSuffix: String get() = if (isX8664) "amd64" else if (isAarch64) "arm64" else "armhf"

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        // 每次启动都自愈环境:修复符号链接降级(usr/bin/sh→dash、proot 库 soname)
        // 并补齐 rootfs/tmp(proot 临时文件目录,缺失则启动即失败)
        selfHeal()
        _state.value = if (isEnvironmentReady()) EnvState.READY else EnvState.NOT_INITIALIZED
    }

    // ── 状态 ──
    /** 环境就绪:优先 Android JRE 直跑模式;旧 proot+rootfs 环境作为回退 */
    fun isEnvironmentReady(): Boolean =
        isJreReady() || (
            prootBinary.exists() && prootBinary.canExecute() &&
                prootLoader.exists() && prootLoader.canExecute() &&
                rootfsDir.exists() && File(rootfsDir, "usr/bin/dash").exists()
            )

    /** 任意可用 Java(优先 JRE 直跑,其次 rootfs 内的 JDK) */
    fun resolveJavaPath(preferred: Int?): String? {
        // 指定版本:优先已导入的 Android 版 JRE(java_<version>)
        preferred?.let { v ->
            val dir = File(appContext.filesDir, "java_$v")
            val bin = File(dir, "bin/java")
            if (bin.exists() && detectJavaKind(bin) == "android") return bin.absolutePath
        }
        if (isJreReady()) return File(jreHomeDir, "bin/java").absolutePath
        val candidates = (listOfNotNull(preferred) + listOf(21, 17, 11, 8)).distinct()
        for (v in candidates) if (isJdkInstalled(v)) return getJavaPath(v)
        return null
    }

    /** Java 运行时类型检测:android(可直接运行)/ glibc(需旧 proot 环境)/ unknown */
    fun detectJavaKind(javaBin: File): String {
        return try {
            val bytes = javaBin.inputStream().use { it.readBytes() }.take(2 * 1024 * 1024)
            val text = String(bytes.toByteArray(), Charsets.ISO_8859_1)
            when {
                text.contains("/system/bin/linker64") -> "android"
                text.contains("ld-linux") -> "glibc"
                else -> "unknown"
            }
        } catch (_: Exception) { "unknown" }
    }

    /** 已安装 Java 运行时清单(Android JRE 直跑模式) */
    data class InstalledJava(
        val id: String,          // 唯一标识,如 "builtin-21" / "import-17"
        val name: String,        // 显示名,如 "Java 21(内置)"
        val version: String,     // 版本号字符串
        val home: File,          // JRE 根目录
        val isBuiltin: Boolean,  // 是否 APK 内置
        val kind: String         // android / glibc / unknown
    )

    fun installedJavas(): List<InstalledJava> {
        val result = mutableListOf<InstalledJava>()
        if (isJreReady()) {
            result += InstalledJava("builtin-21", "Java 21(内置)", "21", jreHomeDir, true, "android")
        }
        appContext.filesDir.listFiles()?.filter { it.name.startsWith("java_") }?.sortedByDescending { it.name }?.forEach { dir ->
            val bin = File(dir, "bin/java")
            if (bin.exists()) {
                val version = dir.name.removePrefix("java_")
                result += InstalledJava("import-$version", "Java $version(导入)", version, dir, false, detectJavaKind(bin))
            }
        }
        return result
    }

    /** 删除导入的 Java 运行时(内置不可删)。返回是否成功 */
    fun deleteImportedJava(version: String): Boolean {
        val dir = File(appContext.filesDir, "java_$version")
        return if (dir.exists()) dir.deleteRecursively() else false
    }

    fun isJdkInstalled(version: Int): Boolean {
        val javaBin = File(javaHomeDir, "java-$version-openjdk-$jdkArchSuffix/bin/java")
        return javaBin.exists() && javaBin.canExecute()
    }

    /** 已安装的 Java 版本列表 */
    fun installedJdkVersions(): List<Int> =
        listOf(8, 11, 17, 21).filter { isJdkInstalled(it) }

    fun getJavaPath(version: Int): String =
        "/usr/lib/jvm/java-$version-openjdk-$jdkArchSuffix/bin/java"

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

    /** 检查 assets 中是否存在某资源（分架构 APK 可能不含全部架构的 rootfs） */
    private fun assetExists(assetName: String): Boolean {
        val candidates = if (assetName.endsWith(".gz")) {
            listOf(assetName, assetName.removeSuffix(".gz"))
        } else listOf(assetName)
        return candidates.any {
            try { appContext.assets.open("bundled/$it").use { _ -> }; true }
            catch (_: Exception) { false }
        }
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
                val isRegular = type == '0' || type == '\u0000'

                when {
                    // 目录条目:tar 目录标记,或以 / 结尾,或根路径(./、空名)
                    type == '5' || path.endsWith("/") || path.isBlank() || path == "." || path == "./" -> {
                        if (path.isNotBlank() && path != "." && path != "./") target.mkdirs()
                    }
                    // 符号链接:目标在 header 的 linkname 字段(offset 157-256),
                    // 数据区 size=0;老式 tar 才把目标放数据区(兼容读取)
                    type == '2' -> {
                        var linkEnd = 157
                        while (linkEnd < 256 && header[linkEnd] != 0.toByte()) linkEnd++
                        var linkTarget = String(header, 157, linkEnd - 157, Charsets.UTF_8)
                        if (linkTarget.isBlank() && size > 0) {
                            // 兼容:目标在数据区
                            val linkBytes = ByteArray(size.toInt().coerceAtMost(4096))
                            var read = 0
                            while (read < linkBytes.size) {
                                val n = rawInput.read(linkBytes, read, linkBytes.size - read)
                                if (n == -1) break
                                read += n
                            }
                            linkTarget = String(linkBytes, 0, read, Charsets.UTF_8)
                        }
                        if (path.contains("/")) target.parentFile?.mkdirs()
                        // 关键:先清理已存在的 target(重新部署时旧目录可能残留,
                        // 不删会导致 createSymbolicLink 报 FileAlreadyExists,
                        // 兜底复制到"已存在的目录"也会失败 → 符号链接降级成目录残留)
                        try {
                            if (target.exists()) {
                                if (target.isDirectory) target.deleteRecursively() else target.delete()
                            }
                        } catch (_: Exception) { }
                        if (linkTarget.isNotBlank()) {
                            try {
                                java.nio.file.Files.createSymbolicLink(
                                    target.toPath(),
                                    java.nio.file.Paths.get(linkTarget)
                                )
                            } catch (e: Exception) {
                                android.util.Log.w("KazeSLauncher", "symlink 失败 path=$path target=$linkTarget: ${e}")
                                // 符号链接创建失败(沙箱限制)时,复制链接目标文件兜底,
                                // 保证 soname 链接(如 libtalloc.so.2)存在,否则 proot 无法加载库
                                try {
                                    val resolved = File(target.parentFile ?: File("."), linkTarget)
                                    if (resolved.isFile && !target.exists()) {
                                        resolved.copyTo(target, overwrite = true)
                                    }
                                } catch (_: Exception) { }
                            }
                        }
                    }
                    isRegular -> {
                        if (path.contains("/")) target.parentFile?.mkdirs()
                        var remaining = size
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
                    else -> {
                        // 其他类型(如 'L' 长文件名、'x' 扩展头):跳过数据
                        var remaining = size
                        while (remaining > 0) {
                            val n = rawInput.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                            if (n == -1) break
                            remaining -= n
                        }
                    }
                }
                // 统一补齐到 512 对齐(数据已读/跳过,只补 padding)
                val padded = (size + 511) / 512 * 512
                var pad = padded - size
                while (pad > 0) {
                    val n = rawInput.read(buffer, 0, minOf(buffer.size.toLong(), pad).toInt())
                    if (n == -1) break
                    pad -= n
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

    // ── proot 命令 ──
    private fun prootEnvironment(): Map<String, String> {
        val env = mutableMapOf<String, String>()
        env["PROOT_LOADER"] = prootLoader.absolutePath
        val existing = System.getenv("LD_LIBRARY_PATH") ?: ""
        env["LD_LIBRARY_PATH"] = if (existing.isEmpty()) prootLibDir.absolutePath else "${prootLibDir.absolutePath}:$existing"
        env["PROOT_NO_SECCOMP"] = "1"
        // proot 需要可写临时目录做 glue rootfs/f2fs 探测,否则启动即失败
        env["PROOT_TMP_DIR"] = File(rootfsDir, "tmp").absolutePath
        env["TMPDIR"] = File(rootfsDir, "tmp").absolutePath
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
        // Ubuntu 24.04 usrmerge 兼容:bin/lib/sbin 是符号链接,Android 沙箱无法创建,
        // 用 proot 绑定将 usr 子目录映射到根目录。
        // 注意:proot 首次绑定会在 rootfs 创建空的挂载点目录(权限 000),
        // 不能用 !isDirectory 判断(空目录会被误判为真实目录),改用 bin/sh 是否存在
        if (File(rootfsDir, "usr/bin").exists() && !File(rootfsDir, "bin/sh").exists()) {
            args.add("-b"); args.add("${File(rootfsDir, "usr/bin").absolutePath}:/bin")
            args.add("-b"); args.add("${File(rootfsDir, "usr/lib").absolutePath}:/lib")
            args.add("-b"); args.add("${File(rootfsDir, "usr/sbin").absolutePath}:/sbin")
            if (File(rootfsDir, "usr/lib64").exists()) {
                args.add("-b"); args.add("${File(rootfsDir, "usr/lib64").absolutePath}:/lib64")
            }
        }
        // 终极兜底:仅当 usr/bin/sh 仍是目录(修复失败)时,
        // 才把真实 dash 绑定为 /bin/sh;正常情况下(sh 已是文件)
        // 不加此绑定,避免 proot 文件绑定在部分设备上异常
        val shCheck = File(rootfsDir, "usr/bin/sh")
        val dash = File(rootfsDir, "usr/bin/dash")
        if (!shCheck.isFile && dash.isFile) {
            args.add("-b"); args.add("${dash.absolutePath}:/bin/sh")
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

    /**
     * 启动 proot 进程。优先直接 exec(模拟器/翻译层兼容),
     * 若被系统拒绝(Android 15+/厂商 ROM 禁止 exec 应用目录 ELF, execve EACCES),
     * 自动退回用 /system/bin/linker64 加载(proot 类 App 的标准做法,不触发 exec 限制)。
     */
    fun startProot(command: String, workDir: String = "/root", bindExtra: List<Pair<String, String>> = emptyList()): Process {
        // 启动前自愈(可能在上次部署后 rootfs 被降级/损坏):
        // 1) usr/bin/sh 若被解压成目录 → 用 dash 副本修复(否则 proot 报 Is a directory)
        // 2) rootfs/tmp 必须存在(proot 的 glue/f2fs 探测临时文件,否则 chmod 报错)
        selfHeal()
        val pb = buildProotCommand(command, workDir, bindExtra)
        return try {
            pb.start()
        } catch (e: java.io.IOException) {
            android.util.Log.w("KazeSLauncher", "直接 exec proot 失败(${e.message}),退回 linker64 加载")
            val args = mutableListOf("/system/bin/linker64") + pb.command()
            val pb2 = ProcessBuilder(args).redirectErrorStream(true)
            pb2.environment().putAll(pb.environment())
            pb2.start()
        }
    }

    /**
     * ★ JRE 直跑模式:用宿主 /system/bin/sh 执行脚本(不经 proot)。
     * 环境变量注入 Android JRE 的库路径(linker 默认不搜 JRE lib 目录,
     * 缺 LD_LIBRARY_PATH 会报 libz.so.1 / libandroid-shmem.so not found)。
     */
    fun startShell(scriptPath: String): Process {
        val pb = ProcessBuilder("/system/bin/sh", scriptPath).redirectErrorStream(true)
        val env = pb.environment()
        env["LD_LIBRARY_PATH"] = jreLibDir.absolutePath
        env["TMPDIR"] = appTmpDir.absolutePath
        return pb.start()
    }

    // ── 部署 ──
    /**
     * 全量部署。
     *
     * 新架构(Android 16 兼容):优先部署内置 Android 版 JRE 21 —— 解压 assets
     * 中的 jre21-arm64.tar.gz 到 files/jre21,零下载、即装即用,无需 proot/rootfs。
     * 旧资源(proot + Ubuntu rootfs)路径保留用于兼容,但已不再是主路径。
     *
     * @param jdkVersions 保留参数(旧架构按需安装多个 JDK;新架构 JRE 直跑忽略)
     */
    suspend fun runFullSetup(jdkVersions: List<Int> = listOf(21)): Result<Unit> = withContext(Dispatchers.IO) {
        if (isSetupRunning.get()) return@withContext Result.failure(RuntimeException("部署正在进行中"))
        // 服务器运行中禁止重新部署(会删除正在使用的 JRE)
        if (com.mcserver.launcher.core.server.ServerManager.isRunning) {
            return@withContext Result.failure(RuntimeException("服务器运行中,请先停止服务器再重新部署"))
        }
        isSetupRunning.set(true)
        _state.value = EnvState.SETTING_UP

        fun log(msg: String) { _log.value = _log.value + msg }

        try {
            // ── ★ JRE 直跑模式(主路径) ──
            if (isJreReady()) {
                log("✓ Java 21 运行时已就绪,无需部署")
                _items.value = listOf(SetupItem("jre", "Java 21 运行时", "已就绪", done = true))
                _state.value = EnvState.READY
                return@withContext Result.success(Unit)
            }
            // 按设备架构动态选择内置 JRE 资源(各自架构优化);
            // 优先当前架构,回退 arm64(兼容 universal 仅含 arm64 的情况)
            val jreCandidates = listOf(
                "jre21-${jdkArchSuffix}.tar.gz",
                "jre21-${jdkArchSuffix}.tar"
            ) + if (jdkArchSuffix != "arm64") {
                listOf("jre21-arm64.tar.gz", "jre21-arm64.tar")
            } else {
                emptyList()
            }
            val jreAsset = jreCandidates.firstOrNull { assetExists(it) }
            if (jreAsset != null) {
                log(">>> 部署内置 Java 21 运行时(Android 版,~160MB)")
                _items.value = listOf(SetupItem("jre", "Java 21 运行时", "内置,解压即用", phase = "提取中"))
                val jreTarball = File(appContext.filesDir, "jre21.tar")
                if (extractBundledAsset(jreAsset, jreTarball)) {
                    log("  ✓ 内置提取成功")
                    if (jreHomeDir.exists()) jreHomeDir.deleteRecursively()
                    jreHomeDir.mkdirs()
                    updateItem("jre", SetupItem("jre", "Java 21 运行时", "内置,解压即用", phase = "解压中", totalBytes = jreTarball.length()))
                    extractTarWithProgress(jreTarball, jreHomeDir) { processed, total, speed ->
                        updateItem("jre") {
                            it.copy(
                                phase = "解压中",
                                progress = if (total > 0) processed.toFloat() / total else 0f,
                                processedBytes = processed,
                                totalBytes = total,
                                speedBytes = speed
                            )
                        }
                    }
                    jreTarball.delete()
                    File(jreHomeDir, "bin/java").setExecutable(true)
                    if (!isJreReady()) throw RuntimeException("Java 运行时解压不完整(bin/java 或 lib/modules 缺失)")
                    updateItem("jre", SetupItem("jre", "Java 21 运行时", "已就绪", done = true))
                    log("  ✓ Java 21 运行时就绪")
                    _state.value = EnvState.READY
                    return@withContext Result.success(Unit)
                }
                error("内置 Java 运行时提取失败")
            }
            // 分架构 APK 无内置 JRE:明确报错,绝不静默网络下载(用户流量)
            error("当前 APK 不含内置 Java 运行时,请安装完整版 arm64v8 安装包,或在设置页本地导入 Android 版 JRE")
        } catch (e: Exception) {
            Logger.e("runFullSetup failed", e)
            _state.value = EnvState.ERROR
            log("> 错误:${e.message}")
            Result.failure(e)
        } finally {
            isSetupRunning.set(false)
        }
    }
}
