package com.mcserver.launcher.core.linux

import com.mcserver.launcher.util.KLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File

/** JDK 安装状态条目 */
data class JdkInfo(
    val feature: Int,      // 8 / 11 / 17 / 21
    val installed: Boolean,
    val busy: Boolean,     // 安装/卸载进行中
)

/**
 * 多版本 JDK/JRE 管理（在 rootfs 内通过 apk 包管理在线安装/卸载，模仿 Termux 的 pkg 方式）：
 * - 安装：apk add openjdk{8|11|17|21}（JRE）或 openjdk{8|11|17|21}-jdk（完整 JDK）
 * - 卸载：apk del openjdk{...}
 * - 不同 MC 版本用不同 JDK：1.8-1.16 → JDK8/11，1.17-1.20 → JDK17，1.20.5+ → JDK21
 */
object JdkManager {

    /** 支持的版本特性号（对应 Alpine openjdk 包名） */
    val SUPPORTED = listOf(8, 11, 17, 21)

    /** MC 版本 → 推荐 JDK 特性号 */
    fun recommendFor(mcVersion: String): Int {
        val minor = mcVersion.substringAfter('.', "").substringBefore('.').toIntOrNull() ?: 0
        val major = mcVersion.substringBefore('.').toIntOrNull() ?: 1
        return when {
            major >= 1 && minor >= 20 -> 21   // 1.20.5+ 需要 21
            major >= 1 && minor >= 17 -> 17
            major >= 1 && minor >= 13 -> 11
            else -> 8
        }
    }

    private val _jdks = MutableStateFlow<List<JdkInfo>>(SUPPORTED.map { JdkInfo(it, false, false) })
    val jdks: StateFlow<List<JdkInfo>> = _jdks

    fun refresh() {
        _jdks.value = SUPPORTED.map { f ->
            JdkInfo(
                feature = f,
                installed = isInstalled(f),
                busy = _jdks.value.firstOrNull { it.feature == f }?.busy ?: false,
            )
        }
    }

    fun isInstalled(feature: Int): Boolean {
        val rootfs = LinuxEnv.rootfs() ?: return false
        return File(rootfs, "usr/lib/jvm/java-$feature-openjdk/bin/java").exists() ||
            File(rootfs, "usr/lib/jvm/java-$feature-openjdk/jre/bin/java").exists() ||
            File(rootfs, "usr/lib/jvm/default-jvm/bin/java").let { it.exists() && it.canonicalPath.contains("java-$feature") }
    }

    /** 已安装中选一个（优先最高版本），返回 guest 内 java 路径 */
    fun pickJavaInGuest(preferred: Int = 0): String? {
        val installed = SUPPORTED.filter { isInstalled(it) }.sortedDescending()
        val feature = if (preferred in installed) preferred else installed.firstOrNull()
        return feature?.let { javaPathInGuest(it) }
    }

    fun javaPathInGuest(feature: Int): String {
        val rootfs = LinuxEnv.rootfs()
        return if (rootfs != null && File(rootfs, "usr/lib/jvm/java-$feature-openjdk/bin/java").exists())
            "/usr/lib/jvm/java-$feature-openjdk/bin/java"
        else
            "/usr/lib/jvm/java-$feature-openjdk/jre/bin/java"
    }

    private fun setBusy(feature: Int, busy: Boolean) {
        _jdks.value = _jdks.value.map { if (it.feature == feature) it.copy(busy = busy) else it }
    }

    /** 在线安装（apk add openjdkX[ -jdk]） */
    suspend fun install(feature: Int, jdk: Boolean = false, onLog: ((String) -> Unit)? = null): Result<Unit> =
        withContext(Dispatchers.IO) {
            if (feature !in SUPPORTED) return@withContext Result.failure(Exception("不支持的版本"))
            if (!LinuxEnv.isReady()) return@withContext Result.failure(Exception("Linux 环境未就绪"))
            setBusy(feature, true)
            try {
                val pkg = if (jdk) "openjdk$feature-jdk" else "openjdk$feature"
                onLog?.invoke("$ apk add $pkg")
                val r = LinuxEnv.exec(listOf("/sbin/apk", "add", "--no-cache", pkg), onLog = onLog)
                if (r.isSuccess) {
                    KLog.i("JDK$feature 安装完成")
                    Result.success(Unit)
                } else {
                    Result.failure(r.exceptionOrNull() ?: Exception("apk add 失败"))
                }
            } finally {
                setBusy(feature, false)
                refresh()
            }
        }

    /** 在线卸载（apk del openjdkX[ -jdk]） */
    suspend fun uninstall(feature: Int, onLog: ((String) -> Unit)? = null): Result<Unit> =
        withContext(Dispatchers.IO) {
            if (feature !in SUPPORTED) return@withContext Result.failure(Exception("不支持的版本"))
            if (!LinuxEnv.isReady()) return@withContext Result.failure(Exception("Linux 环境未就绪"))
            setBusy(feature, true)
            try {
                val rootfs = LinuxEnv.rootfs()
                // 只删除实际已装的包（JRE 与 JDK 包名不同）
                val names = mutableListOf<String>()
                if (rootfs != null && File(rootfs, "usr/lib/jvm/java-$feature-openjdk/bin/java").exists()) {
                    names.add("openjdk$feature-jdk")
                }
                if (rootfs != null && File(rootfs, "usr/lib/jvm/java-$feature-openjdk/jre/bin/java").exists()) {
                    names.add("openjdk$feature")
                }
                if (names.isEmpty()) return@withContext Result.failure(Exception("该版本未安装"))
                onLog?.invoke("$ apk del ${names.joinToString(" ")}")
                val r = LinuxEnv.exec(listOf("/sbin/apk", "del") + names, onLog = onLog)
                if (r.isSuccess) {
                    KLog.i("JDK$feature 已卸载")
                    Result.success(Unit)
                } else {
                    Result.failure(r.exceptionOrNull() ?: Exception("apk del 失败"))
                }
            } finally {
                setBusy(feature, false)
                refresh()
            }
        }
}