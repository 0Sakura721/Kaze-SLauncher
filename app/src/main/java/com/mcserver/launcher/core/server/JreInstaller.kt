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

    /** 已安装 Java 版本列表(导入/下载完成后刷新,驱动 UI) */
    private val _installedVersions = MutableStateFlow(EnvManager.installedJdkVersions())
    val installedVersions: StateFlow<List<Int>> = _installedVersions.asStateFlow()

    private fun refreshInstalled() {
        _installedVersions.value = EnvManager.installedJdkVersions()
    }

    /** 显示提示消息(UI 调用) */
    fun notifyMessage(msg: String) { _message.value = msg }

    fun init(context: Context) { appContext = context.applicationContext }

    private fun jreDirFor(version: String): File = File(appContext.filesDir, "java_$version")

    private val isAarch64: Boolean
        get() = android.os.Build.SUPPORTED_ABIS.any { it.contains("arm64-v8a") || it.contains("aarch64") }

    /** App 私有目录中已安装的版本 */
    fun installedLocalVersions(): List<String> =
        appContext.filesDir.listFiles()?.filter { it.name.startsWith("java_") && File(it, "bin/java").exists() }
            ?.map { it.name.removePrefix("java_") } ?: emptyList()

    /** 下载并安装指定版本(自动同步到 rootfs) */
    suspend fun install(version: String, pkg: String = "jdk"): Result<Unit> = withContext(Dispatchers.IO) {
        if (_busy.value != null) return@withContext Result.failure(RuntimeException("已有安装任务进行中"))
        _busy.value = version
        _message.value = "准备下载 Java $version..."
        try {
            // Adoptium 架构:arm64 → aarch64;其余(armhf rootfs)→ arm 32 位(仅 Java 8/11)
            val arch = if (isAarch64) {
                "aarch64"
            } else {
                val v = version.toIntOrNull() ?: 0
                if (v > 11) {
                    _message.value = "当前设备为 32 位 ARM 架构,仅支持安装 Java 8/11(Adoptium 无 17/21 的 32 位构建);请使用 arm64 设备"
                    return@withContext Result.failure(RuntimeException("32 位 ARM 不支持 Java $version"))
                }
                "arm"
            }
            val official = "https://api.adoptium.net/v3/binary/latest/$version/ga/linux/$arch/$pkg/hotspot/normal/eclipse"
            // 阿里云镜像备选(Adoptium 二进制镜像)
            val aliyunArch = if (arch == "aarch64") "aarch64" else "arm"
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

            _message.value = "解压 Java $version..."
            val targetDir = jreDirFor(version)
            if (targetDir.exists()) targetDir.deleteRecursively()
            targetDir.mkdirs()

            // tar 解压(自动检测 gzip),Adoptium 顶层带版本目录
            val isGzip = tarFile.readBytes().take(2).toByteArray().let { it.size == 2 && it[0] == 0x1f.toByte() && it[1] == 0x8b.toByte() }
            val proc = ProcessBuilder()
                .command("tar", if (isGzip) "xzf" else "xf", tarFile.absolutePath, "-C", targetDir.absolutePath)
                .redirectErrorStream(true).start()
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

            // 同步到 rootfs,服务器才能真正使用
            val synced = EnvManager.syncJavaToRootfs(version, targetDir)
            _message.value = if (synced) "Java $version 安装完成并已同步到服务器环境"
                             else "Java $version 已安装(环境未就绪,未同步)"
            refreshInstalled()
            Logger.i("Java $version installed, synced=$synced")
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
        val dir = jreDirFor(version)
        if (dir.exists()) dir.deleteRecursively()
        // 同时移除 rootfs 中的副本
        val suffix = if (isAarch64) "arm64" else "armhf"
        val rootfsJdk = File(EnvManager.javaHomeDir, "java-$version-openjdk-$suffix")
        if (rootfsJdk.exists()) rootfsJdk.deleteRecursively()
        _message.value = "已删除 Java $version"
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
        _message.value = "正在导入 Java $version(本地文件,不消耗流量)..."
        try {
            // ELF 架构校验:导入的 JDK 必须与 rootfs 架构一致
            val elfArch = detectElfArch(javaBin)
            val expected = if (isAarch64) "aarch64" else "arm"
            if (elfArch != null && elfArch != expected) {
                _message.value = "JDK 架构($elfArch)与设备($expected)不匹配,无法用于服务器"
                return@withContext Result.failure(RuntimeException("架构不匹配: $elfArch != $expected"))
            }
            // 复制到 App 私有目录
            val targetDir = jreDirFor(version)
            if (targetDir.exists()) targetDir.deleteRecursively()
            targetDir.mkdirs()
            sourceDir.copyRecursively(targetDir)
            File(targetDir, "bin/java").setExecutable(true)
            // 同步到 rootfs,服务器启动才能真正使用
            val synced = EnvManager.syncJavaToRootfs(version, targetDir)
            _message.value = if (synced) "Java $version 导入完成并已同步到服务器环境"
                             else "Java $version 已导入(环境未就绪,未同步)"
            refreshInstalled()
            Logger.i("Java $version imported locally, synced=$synced")
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
