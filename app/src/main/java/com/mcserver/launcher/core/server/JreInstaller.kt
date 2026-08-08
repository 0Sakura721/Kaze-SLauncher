package com.mcserver.launcher.core.server

import android.content.Context
import com.mcserver.launcher.core.env.EnvManager
import com.mcserver.launcher.core.download.DownloadCenter
import com.mcserver.launcher.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Java 运行时管理:优先本地导入(不消耗流量),下载作为备选。
 * 导入的 JDK 复制到 App 私有目录 → 同步到 Ubuntu rootfs。
 */
object JreInstaller {

    private const val TAG = "JreInstaller"
    private lateinit var appContext: Context

    private val _busy = MutableStateFlow<String?>(null) // 正在安装的版本
    val busy: StateFlow<String?> = _busy.asStateFlow()

    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message.asStateFlow()

    /** 已安装 Java 运行时清单(内置 + 导入,驱动 UI) */
    private val _installedJavas = MutableStateFlow(EnvManager.installedJavas())
    val installedJavas: StateFlow<List<EnvManager.InstalledJava>> = _installedJavas.asStateFlow()

    private fun refreshInstalled() {
        _installedJavas.value = EnvManager.installedJavas()
    }

    /** 兼容旧引用:返回版本号列表(含内置 21) */
    fun installedVersions(): List<String> = EnvManager.installedJavas().map { it.version }.distinct()

    /** 显示提示消息(UI 调用) */
    fun notifyMessage(msg: String) { _message.value = msg }

    fun init(context: Context) { appContext = context.applicationContext }

    private fun jreDirFor(version: String): File = File(appContext.filesDir, "java_$version")

    private val isAarch64: Boolean
        get() {
            val primary = android.os.Build.SUPPORTED_64_BIT_ABIS.firstOrNull() ?: (android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a")
            return primary.contains("arm64") || primary.contains("aarch64")
        }
    private val isX8664: Boolean
        get() = (android.os.Build.SUPPORTED_64_BIT_ABIS.firstOrNull() ?: "").contains("x86_64")

    /** Adoptium 架构名:arm64 → aarch64;x86_64 → x64;armhf → arm */
    private val adoptiumArch: String
        get() = when {
            isX8664 -> "x64"
            isAarch64 -> "aarch64"
            else -> "arm"
        }

    /** App 私有目录中已安装的版本 */
    fun installedLocalVersions(): List<String> =
        appContext.filesDir.listFiles()?.filter { it.name.startsWith("java_") && File(it, "bin/java").exists() }
            ?.map { it.name.removePrefix("java_") } ?: emptyList()

    /** 下载并安装指定版本(自动同步到 rootfs) */
    suspend fun install(version: String, pkg: String = "jdk"): Result<Unit> = withContext(Dispatchers.IO) {
        if (_busy.value != null) return@withContext Result.failure(RuntimeException("已有安装任务进行中"))
        _busy.value = version
        _message.value = "准备下载 Java $version…"
        try {
            // Adoptium 架构按设备原生 ABI
            val arch = adoptiumArch
            if (arch == "arm") {
                val v = version.toIntOrNull() ?: 0
                if (v > 11) {
                    _message.value = "当前设备为 32 位 ARM 架构,仅支持安装 Java 8/11(Adoptium 无 17/21 的 32 位构建);请使用 arm64 设备"
                    return@withContext Result.failure(RuntimeException("32 位 ARM 不支持 Java $version"))
                }
            }
            val official = "https://api.adoptium.net/v3/binary/latest/$version/ga/linux/$arch/$pkg/hotspot/normal/eclipse"
            // 阿里云镜像备选(Adoptium 二进制镜像)
            val aliyunArch = adoptiumArch
            val aliyun = "https://mirrors.aliyun.com/adoptium/$version/$pkg/$aliyunArch/linux/${version}u-latest_${pkg}_linux-${aliyunArch}_bin.tar.gz"
            val partFile = File(appContext.cacheDir, "java_${version}_$pkg.partial")
            val tarFile = File(appContext.cacheDir, "java_${version}_$pkg.tar.gz")

            val taskId = "jre-$version-$pkg"
            DownloadCenter.enqueue(taskId, "Java $version ($pkg)", listOf(official, aliyun), tarFile)
            // 等待下载完成
            while (true) {
                val task = DownloadCenter.tasks.value.firstOrNull { it.id == taskId }
                if (task == null) return@withContext Result.failure(RuntimeException("任务不存在"))
                when (task.status) {
                    com.mcserver.launcher.data.DownloadStatus.COMPLETED -> break
                    com.mcserver.launcher.data.DownloadStatus.FAILED -> {
                        _message.value = "Java $version 下载失败:${task.error ?: "未知错误"},可稍后重试"
                        return@withContext Result.failure(RuntimeException(task.error ?: "下载失败"))
                    }
                    com.mcserver.launcher.data.DownloadStatus.CANCELED -> return@withContext Result.failure(RuntimeException("已取消"))
                    else -> kotlinx.coroutines.delay(500)
                }
            }

            _message.value = "解压 Java $version…"
            val targetDir = jreDirFor(version)
            if (targetDir.exists()) targetDir.deleteRecursively()
            targetDir.mkdirs()

            // tar 解压(自动检测 gzip),Adoptium 顶层带版本目录
            val isGzip = java.io.RandomAccessFile(tarFile, "r").use { raf ->
                val head = ByteArray(2); raf.readFully(head)
                head[0] == 0x1f.toByte() && head[1] == 0x8b.toByte()
            }
            val proc = ProcessBuilder()
                .command("tar", if (isGzip) "xzf" else "xf", tarFile.absolutePath, "-C", targetDir.absolutePath)
                .redirectErrorStream(true).start()
            // 消费输出防管道阻塞
            proc.inputStream.bufferedReader().use { it.readText() }
            val exit = proc.waitFor()
            tarFile.delete()
            if (exit != 0) return@withContext Result.failure(RuntimeException("解压失败"))

            // 找到 bin/java 根目录(Adoptium 解压后可能多一层目录)
            val jdkRoot = findJdkRoot(targetDir) ?: return@withContext Result.failure(RuntimeException("解压内容无效"))
            if (jdkRoot != targetDir) {
                // 把实际 JDK 根目录提升到 targetDir 顶层
                val tmp = File(appContext.cacheDir, "java_${version}_tmp")
                if (tmp.exists()) tmp.deleteRecursively()
                jdkRoot.renameTo(tmp)
                targetDir.deleteRecursively()
                tmp.renameTo(targetDir)
            }
            File(targetDir, "bin/java").setExecutable(true)

            // 类型检测:Adoptium 是 Linux glibc 版,Android 16 上无法直接运行
            val kind = EnvManager.detectJavaKind(File(targetDir, "bin/java"))
            _message.value = if (kind == "android") {
                "Java $version 安装完成,可直接运行"
            } else {
                "Java $version 已下载(Adoptium Linux 版)。注意:Android 16 系统限制无法直接运行 glibc 程序,推荐使用内置 Java 21 或本地导入 Android 版 JRE"
            }
            refreshInstalled()
            Logger.i("Java $version installed, kind=$kind")
            Result.success(Unit)
        } catch (e: Exception) {
            Logger.e("install failed", e)
            _message.value = "安装失败:${e.message}"
            Result.failure(e)
        } finally {
            _busy.value = null
        }
    }

    fun delete(version: String) {
        // 内置 JRE 不允许删除(重新部署即可恢复),只删导入的
        if (version == "21" && EnvManager.isJreReady()) {
            _message.value = "内置 Java 21 为 APK 自带运行时,不可删除(重新部署可恢复)"
            return
        }
        val ok = EnvManager.deleteImportedJava(version)
        _message.value = if (ok) "已删除 Java $version" else "Java $version 不存在"
        refreshInstalled()
    }

    private fun findJdkRoot(dir: File): File? {
        if (File(dir, "bin/java").exists()) return dir
        dir.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                val found = findJdkRoot(child)
                if (found != null) return found
            }
        }
        return null
    }

    // ── 本地导入(优先,不消耗流量) ──

    /**
     * 从本地目录导入 JDK(HMCL 式本地 Java 管理,不走网络)。
     * @param sourceDir 本地 JDK 根目录(须含 bin/java)
     * @param version 导入后登记的版本号(8/11/17/21)
     */
    suspend fun importJdk(sourceDir: File, version: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (_busy.value != null) return@withContext Result.failure(RuntimeException("已有任务进行中"))
        val javaBin = File(sourceDir, "bin/java")
        if (!javaBin.exists()) {
            _message.value = "所选目录不是有效的 JDK(缺少 bin/java),请选择 JDK 根目录"
            return@withContext Result.failure(RuntimeException("缺少 bin/java"))
        }
        _busy.value = "import:$version"
        _message.value = "正在导入 Java $version(本地文件,不消耗流量)…"
        try {
            // ELF 架构校验:导入的 JDK 必须与设备架构一致
            val elfArch = detectElfArch(javaBin)
            val expected = if (isX8664) "x86_64" else if (isAarch64) "aarch64" else "arm"
            if (elfArch != null && elfArch != expected) {
                _message.value = "JDK 架构($elfArch)与设备($expected)不匹配,无法用于服务器"
                return@withContext Result.failure(RuntimeException("架构不匹配: $elfArch != $expected"))
            }
            // 类型检测:仅 Android 版(interpreter=宿主 linker64)可在 Android 16 直接运行
            val kind = EnvManager.detectJavaKind(javaBin)
            if (kind == "glibc") {
                _message.value = "检测到这是 Linux glibc 版 JDK:Android 16 系统限制无法直接运行 glibc 程序。" +
                        "请导入 Android 版 JRE(如 Termux 的 openjdk、FCL/Pojav 运行时),或使用内置 Java 21"
                return@withContext Result.failure(RuntimeException("glibc 版 JDK 无法在 Android 16 直接运行"))
            }
            // 复制到 App 私有目录
            val targetDir = jreDirFor(version)
            if (targetDir.exists()) targetDir.deleteRecursively()
            targetDir.mkdirs()
            sourceDir.copyRecursively(targetDir)
            File(targetDir, "bin/java").setExecutable(true)
            _message.value = if (kind == "android") {
                "Java $version 导入完成,可直接运行"
            } else {
                "Java $version 已导入(未能确认运行时类型,若启动失败请改用 Android 版 JRE)"
            }
            refreshInstalled()
            Logger.i("Java $version imported locally, kind=$kind")
            Result.success(Unit)
        } catch (e: Exception) {
            Logger.e("importJdk failed", e)
            _message.value = "导入失败:${e.message}"
            Result.failure(e)
        } finally {
            _busy.value = null
        }
    }

    /** 解析 ELF 文件头的架构(e_machine 字段) */
    private fun detectElfArch(file: File): String? {
        return try {
            val bytes = ByteArray(20)
            file.inputStream().use { it.read(bytes) }
            if (bytes[0] != 0x7f.toByte() || bytes[1] != 'E'.code.toByte()) return null // 非 ELF
            val machine = (bytes[18].toInt() and 0xFF) or ((bytes[19].toInt() and 0xFF) shl 8)
            when (machine) {
                183 -> "aarch64" // EM_AARCH64
                62 -> "x86_64"   // EM_X86_64
                40 -> "arm"      // EM_ARM
                else -> null
            }
        } catch (_: Exception) { null }
    }
}
