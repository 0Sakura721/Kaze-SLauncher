package com.kaze.newage.core.java

import com.kaze.newage.core.env.ProotEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * rootfs 内 apt 安装 OpenJDK（主方案）。
 *
 * 依赖 ProotEnvironment（glibc JRE，与 rootfs 内其它软件天然兼容）。
 * 安装路径：/usr/lib/jvm/java-N-openjdk-arm64（Ubuntu 24.04 命名）
 */
class RootfsJavaManager(private val env: ProotEnvironment) : JavaManager {

    override fun installed(): List<JavaRuntime> =
        env.installedJdkVersions().map { version ->
            JavaRuntime(
                version = version.toString(),
                home = File(env.javaHomeDir, "java-$version-openjdk-${archSuffix()}"),
                architecture = archSuffix(),
            )
        }

    override suspend fun install(majorVersion: Int, onProgress: (Float, String) -> Unit): JavaRuntime {
        if (env.isJdkInstalled(majorVersion)) {
            onProgress(1f, "Java $majorVersion 已安装")
            return installed().first { it.version == majorVersion.toString() }
        }
        if (!env.isReady) {
            env.setup { progress, message ->
                onProgress(progress * 0.3f, "准备环境：$message")
            }
            if (!env.isReady) throw RuntimeException("Linux 环境部署失败")
        }
        onProgress(0.35f, "正在安装 Java $majorVersion（apt 下载约 100MB，请耐心等待）…")
        // 先 apt-get update（新 rootfs 或长时间未用后索引过期）
        env.runCommand("DEBIAN_FRONTEND=noninteractive apt-get update -qq", timeoutMs = 600_000)
            .onFailure { /* 忽略 update 失败，直接尝试安装 */ }
        val result = env.runCommand(
            "DEBIAN_FRONTEND=noninteractive apt-get install -y -qq openjdk-$majorVersion-jdk-headless",
            timeoutMs = 900_000,
        )
        if (result.isFailure) {
            throw RuntimeException("Java $majorVersion 安装失败：${result.exceptionOrNull()?.message}")
        }
        if (!env.isJdkInstalled(majorVersion)) {
            throw RuntimeException("Java $majorVersion 安装完成但未找到可执行文件")
        }
        onProgress(1f, "Java $majorVersion 安装完成")
        return installed().first { it.version == majorVersion.toString() }
    }

    override suspend fun uninstall(majorVersion: Int) {
        if (!env.isReady) return
        // 卸载通过 apt；失败时静默（简单删除也可由环境重建覆盖）
        env.runCommand("DEBIAN_FRONTEND=noninteractive apt-get purge -y -qq openjdk-$majorVersion-jdk-headless", timeoutMs = 300_000)
    }

    private fun archSuffix(): String =
        if (android.os.Build.SUPPORTED_ABIS.any { it.contains("arm64-v8a", true) || it.contains("aarch64", true) }) "arm64" else "armhf"
}
