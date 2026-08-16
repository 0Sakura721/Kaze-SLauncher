package com.kaze.newage.core.java

import com.kaze.newage.core.env.ProotEnvironment
import com.kaze.newage.util.Downloader
import com.kaze.newage.util.TarExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * rootfs 内 Java 运行时管理（主方案 = 直接下载 OpenJDK 官方包解压，绕过 apt）。
 *
 * 2026-08-15 改为直连 Adoptium 下载 + TarExtractor 解压：
 * - 真机上 proot+apt 安装 Java 不稳定（apt 在 proot 内可能长时间阻塞/超时，无进度可见）；
 * - 应用自身的 Downloader/TarExtractor 在外部存储上已验证可靠（rootfs 134MB、jar 60MB 均成功）。
 * 失败时回退到 apt 安装。
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

        // 主方案：Adoptium 官方 OpenJDK 直接下载解压
        val directResult = installFromAdoptium(majorVersion) { progress, message ->
            onProgress(0.3f + progress * 0.65f, message)
        }
        if (directResult != null) {
            onProgress(1f, "Java $majorVersion 安装完成")
            return directResult
        }

        // 回退：apt 安装
        onProgress(0.35f, "直接下载失败，回退 apt 安装 Java $majorVersion（约 100MB）…")
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
        onProgress(1f, "Java $majorVersion 安装完成（apt）")
        return installed().first { it.version == majorVersion.toString() }
    }

    override suspend fun uninstall(majorVersion: Int) {
        if (!env.isReady) return
        val jvmDir = File(env.javaHomeDir, "java-$majorVersion-openjdk-${archSuffix()}")
        runCatching { jvmDir.deleteRecursively() }
        // 卸载通过 apt 兜底；失败时静默
        env.runCommand("DEBIAN_FRONTEND=noninteractive apt-get purge -y -qq openjdk-$majorVersion-jdk-headless", timeoutMs = 300_000)
    }

    /**
     * Adoptium 直连下载 OpenJDK 并解压到 /usr/lib/jvm/java-N-openjdk-arm64。
     * 多源探测（GitHub 官方 + TUNA/华为镜像，自动选最快）+ 断点续传。
     * 成功返回 JavaRuntime，失败返回 null（交给调用方回退 apt）。
     */
    private suspend fun installFromAdoptium(
        majorVersion: Int,
        onProgress: (Float, String) -> Unit,
    ): JavaRuntime? = withContext(Dispatchers.IO) {
        try {
            val targetDir = File(env.javaHomeDir, "java-$majorVersion-openjdk-${archSuffix()}")
            if (targetDir.exists() && File(targetDir, "bin/java").exists()) {
                return@withContext JavaRuntime(majorVersion.toString(), targetDir, archSuffix())
            }
            if (targetDir.exists()) targetDir.deleteRecursively()
            targetDir.mkdirs()

            // 迁移：外部存储旧环境的 JDK → 内部（避免重新下载 196MB）
            val legacyExt = File(
                "/storage/emulated/0/Android/data/com.kaze.newage/files/linux/rootfs/usr/lib/jvm/java-$majorVersion-openjdk-${archSuffix()}"
            )
            if (File(legacyExt, "bin/java").exists() && !File(targetDir, "bin/java").exists()) {
                onProgress(0f, "迁移已有 JDK 到内部存储（约 2 分钟）…")
                try {
                    legacyExt.copyRecursively(targetDir, overwrite = true)
                } catch (_: Exception) { }
                if (File(targetDir, "bin/java").exists()) {
                    File(targetDir, "bin/java").setExecutable(true)
                    return@withContext JavaRuntime(majorVersion.toString(), targetDir, archSuffix())
                }
            }

            // 1. 解析元数据：拿到官方包文件名（供镜像构造 URL）
            val api = "https://api.adoptium.net/v3/assets/latest/$majorVersion/hotspot" +
                "?architecture=aarch64&image_type=jdk&os=linux&vendor=eclipse"
            var fileName: String? = null
            var githubLink: String? = null
            try {
                val json = org.json.JSONArray(Downloader.downloadText(api))
                val bin = json.getJSONObject(0).optJSONObject("binary") ?: org.json.JSONObject()
                val pkg = bin.optJSONObject("package") ?: org.json.JSONObject()
                fileName = pkg.optString("name").takeIf { it.isNotBlank() }
                githubLink = pkg.optString("link").takeIf { it.isNotBlank() }
            } catch (_: Exception) { }

            // 2. 候选源：官方 API 直链 + TUNA / 华为镜像
            val urls = buildList {
                if (!githubLink.isNullOrBlank()) add(githubLink)
                if (!fileName.isNullOrBlank()) {
                    add("https://mirrors.tuna.tsinghua.edu.cn/Adoptium/$majorVersion/jdk/aarch64/linux/$fileName")
                    add("https://mirrors.huaweicloud.com/adoptium/$majorVersion/jdk/aarch64/linux/$fileName")
                }
                add("https://api.adoptium.net/v3/binary/latest/$majorVersion/ga/linux/aarch64/jdk/hotspot/normal/eclipse")
            }.distinct()

            val tarFile = File(targetDir.parentFile, "openjdk-$majorVersion.tar.gz")
            if (tarFile.exists() && File(targetDir, "bin/java").exists()) {
                // 已下载解压过：跳过
                return@withContext JavaRuntime(majorVersion.toString(), targetDir, archSuffix())
            }
            onProgress(0f, "探测最快下载源（${urls.size} 个候选）…")
            val used = Downloader.downloadFromSources(
                urls,
                tarFile,
                onProgress = { done, total ->
                    onProgress(if (total > 0) done.toFloat() / total else 0f, "下载 OpenJDK $majorVersion：${done / 1024 / 1024}MB${if (total > 0) " / ${total / 1024 / 1024}MB" else ""}")
                },
                onSourceError = { src, err ->
                    onProgress(0f, "源失败 ${src.take(70)}：$err")
                },
            )
            if (used == null) return@withContext null
            onProgress(1f, "下载完成（源：$used），解压中…")

            // 解压到临时目录，再移到目标（Adoptium tar 内有一层 jdk-xx.y+z/ 目录）
            val tmpDir = File(targetDir.parentFile, "openjdk-$majorVersion-tmp")
            if (tmpDir.exists()) tmpDir.deleteRecursively()
            tmpDir.mkdirs()
            onProgress(1f, "解压 OpenJDK $majorVersion…")
            // 用系统 tar（久经验证，Termux/pkg 同款路径）
            val proc = Runtime.getRuntime().exec(
                arrayOf("/system/bin/sh", "-c", "tar xf '${tarFile.absolutePath}' -C '${tmpDir.absolutePath}'")
            )
            if (proc.waitFor() != 0) {
                throw RuntimeException("tar 解压失败")
            }
            tarFile.delete()

            // 找到解压出的 JDK 根目录并重命名为目标
            val extracted = tmpDir.listFiles()?.firstOrNull { it.isDirectory && File(it, "bin/java").exists() }
                ?: tmpDir.listFiles()?.firstOrNull()
                ?: tmpDir
            if (extracted.absolutePath != targetDir.absolutePath) {
                if (targetDir.exists()) targetDir.deleteRecursively()
                val moved = extracted.renameTo(targetDir)
                if (!moved) {
                    extracted.copyRecursively(targetDir, overwrite = true)
                    extracted.deleteRecursively()
                }
            }
            tmpDir.deleteRecursively()

            val javaBin = File(targetDir, "bin/java")
            if (!javaBin.exists()) return@withContext null
            javaBin.setExecutable(true)
            installed().firstOrNull { it.version == majorVersion.toString() }
                ?: JavaRuntime(majorVersion.toString(), targetDir, archSuffix())
        } catch (e: Exception) {
            null
        }
    }

    private fun archSuffix(): String =
        if (android.os.Build.SUPPORTED_ABIS.any { it.contains("arm64-v8a", true) || it.contains("aarch64", true) }) "arm64" else "armhf"
}
