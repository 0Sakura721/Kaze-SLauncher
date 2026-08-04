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
 * Java 运行时管理:下载 Adoptium JDK → 解压到 App 私有目录 → 同步到 Ubuntu rootfs。
 * 用户按需安装,不强制全部。
 */
object JreInstaller {

    private const val TAG = "JreInstaller"
    private lateinit var appContext: Context

    private val _busy = MutableStateFlow<String?>(null) // 正在安装的版本
    val busy: StateFlow<String?> = _busy.asStateFlow()

    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message.asStateFlow()

    fun init(context: Context) { appContext = context.applicationContext }

    private fun jreDirFor(version: String): File = File(appContext.filesDir, "java_$version")

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
            val arch = if (android.os.Build.SUPPORTED_64_BIT_ABIS.isNotEmpty() &&
                android.os.Build.SUPPORTED_64_BIT_ABIS[0].contains("arm64")) "aarch64" else "arm"
            val url = "https://api.adoptium.net/v3/binary/latest/$version/ga/linux/$arch/$pkg/hotspot/normal/eclipse"
            val partFile = File(appContext.cacheDir, "java_${version}_$pkg.partial")
            val tarFile = File(appContext.cacheDir, "java_${version}_$pkg.tar.gz")

            val taskId = "jre-$version-$pkg"
            DownloadCenter.enqueue(taskId, "Java $version ($pkg)", listOf(url), tarFile)
            // 等待下载完成
            while (true) {
                val task = DownloadCenter.tasks.value.firstOrNull { it.id == taskId }
                if (task == null) return@withContext Result.failure(RuntimeException("任务不存在"))
                when (task.status) {
                    com.mcserver.launcher.data.DownloadStatus.COMPLETED -> break
                    com.mcserver.launcher.data.DownloadStatus.FAILED -> return@withContext Result.failure(RuntimeException(task.error ?: "下载失败"))
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
        val suffix = if (android.os.Build.SUPPORTED_ABIS.any { it.contains("arm64-v8a") || it.contains("aarch64") }) "arm64" else "armhf"
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
}
