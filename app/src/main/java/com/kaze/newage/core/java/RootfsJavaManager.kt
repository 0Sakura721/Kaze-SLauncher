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

    /** 任务互斥：install/uninstall 共用（原互斥只在 DefaultServerManager 启动链上，
     *  设置的 installJava/uninstallJava 可绕过——与自动安装并发写同一目录/tar 包会损坏） */
    private val taskMutex = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun installed(): List<JavaRuntime> =
        env.installedJdkVersions().map { version ->
            JavaRuntime(
                version = version.toString(),
                home = File(env.javaHomeDir, "java-$version-openjdk-${archSuffix()}"),
                architecture = archSuffix(),
            )
        }

    override suspend fun install(
        majorVersion: Int,
        onProgress: (Float, String) -> Unit,
        shouldCancel: () -> Boolean,
    ): JavaRuntime {
        acquireMutex(shouldCancel)
        try {
            return doInstall(majorVersion, onProgress, shouldCancel)
        } finally {
            taskMutex.set(false)
        }
    }

    private suspend fun doInstall(
        majorVersion: Int,
        onProgress: (Float, String) -> Unit,
        shouldCancel: () -> Boolean,
    ): JavaRuntime {
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
        val directResult = installFromAdoptium(majorVersion, shouldCancel) { progress, message ->
            onProgress(0.3f + progress * 0.65f, message)
        }
        if (directResult != null) {
            onProgress(1f, "Java $majorVersion 安装完成")
            return directResult
        }
        if (shouldCancel()) throw InterruptedException("安装已取消")

        // 回退：apt 安装
        onProgress(0.35f, "直接下载失败，回退 apt 安装 Java $majorVersion（约 100MB）…")
        if (shouldCancel()) throw InterruptedException("安装已取消")
        val probe = env.runCommand("command -v apt-get", timeoutMs = 30_000)
        if (probe.isFailure) {
            android.util.Log.e("KazeSLauncher", "apt probe failed", probe.exceptionOrNull())
            throw RuntimeException("环境内 apt 探测失败：${probe.exceptionOrNull()?.message}")
        }
        env.runCommand("DEBIAN_FRONTEND=noninteractive apt-get update -qq", timeoutMs = 600_000)
            .onFailure { /* 忽略 update 失败，直接尝试安装 */ }
        if (shouldCancel()) throw InterruptedException("安装已取消")
        val result = env.runCommand(
            "DEBIAN_FRONTEND=noninteractive apt-get install -y -qq openjdk-$majorVersion-jdk-headless",
            timeoutMs = 900_000,
        )
        if (result.isFailure) {
            throw RuntimeException("apt 安装失败：${result.exceptionOrNull()?.message}")
        }
        if (!env.isJdkInstalled(majorVersion)) {
            throw RuntimeException("Java $majorVersion 安装完成但未找到可执行文件")
        }
        onProgress(1f, "Java $majorVersion 安装完成（apt）")
        return installed().first { it.version == majorVersion.toString() }
    }

    override suspend fun uninstall(majorVersion: Int) {
        acquireMutex(shouldCancel = { false })
        try {
            doUninstall(majorVersion)
        } finally {
            taskMutex.set(false)
        }
    }

    private suspend fun doUninstall(majorVersion: Int) {
        val jvmDir = File(env.javaHomeDir, "java-$majorVersion-openjdk-${archSuffix()}")
        runCatching { jvmDir.deleteRecursively() }
        // 清理下载残留（tar 包与临时解压目录）
        runCatching { File(env.javaHomeDir, "openjdk-$majorVersion.tar.gz").delete() }
        runCatching { File(env.javaHomeDir, "openjdk-$majorVersion-tmp").deleteRecursively() }
        // 卸载通过 apt 兜底；失败时静默
        if (env.isReady) {
            env.runCommand("DEBIAN_FRONTEND=noninteractive apt-get purge -y -qq openjdk-$majorVersion-jdk-headless", timeoutMs = 300_000)
        }
    }

    /**
     * Adoptium 直连下载 OpenJDK 并解压到 /usr/lib/jvm/java-N-openjdk-arm64。
     * 多源探测（GitHub 官方 + TUNA/华为镜像，自动选最快）+ 断点续传 + 断网自动重试。
     * 成功返回 JavaRuntime；失败（含用户取消）返回 null（交给调用方回退 apt）。
     */
    private suspend fun installFromAdoptium(
        majorVersion: Int,
        shouldCancel: () -> Boolean,
        onProgress: (Float, String) -> Unit,
    ): JavaRuntime? = withContext(Dispatchers.IO) {
        if (archSuffix() != "arm64") {
            // Adoptium 官方只出 aarch64/amd64 等；32 位 ARM 无官方包，TUNA/华为镜像同源。
            // 直接走 install() 的 apt 兜底（Ubuntu ports 有 openjdk-*-jdk-headless armhf）
            onProgress(0.05f, "32 位设备：官方包仅 aarch64，改用 apt 安装…")
            return@withContext null
        }
        val targetDir = File(env.javaHomeDir, "java-$majorVersion-openjdk-${archSuffix()}")
        val tmpDir = File(env.javaHomeDir, "openjdk-$majorVersion-tmp")
        try {
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
                    copyDirChecked(legacyExt, targetDir, shouldCancel)
                } catch (e: InterruptedException) {
                    runCatching { targetDir.deleteRecursively() }
                    return@withContext null
                } catch (_: Exception) { }
                if (File(targetDir, "bin/java").exists()) {
                    File(targetDir, "bin/java").setExecutable(true)
                    return@withContext JavaRuntime(majorVersion.toString(), targetDir, archSuffix())
                }
            }

            // 1. 解析元数据：拿到官方包文件名（供镜像构造 URL）。
            // 优先 TUNA 目录页（国内直连稳定、列出镜像上实际存在的版本）；
            // 官方 Adoptium API 在国内可能被墙/超时，仅作补充（拿 GitHub 直链）。
            val api = "https://api.adoptium.net/v3/assets/latest/$majorVersion/hotspot" +
                "?architecture=aarch64&image_type=jdk&os=linux&vendor=eclipse"
            var fileName: String? = null
            var githubLink: String? = null
            fileName = runCatching {
                val html = Downloader.downloadText(
                    "https://mirrors.tuna.tsinghua.edu.cn/Adoptium/$majorVersion/jdk/aarch64/linux/",
                    timeoutMs = 20_000,
                )
                Regex("""OpenJDK\d+U-jdk_aarch64_linux_hotspot_[^"<>]+\.tar\.gz""")
                    .find(html)?.value
            }.getOrNull()
            try {
                val json = org.json.JSONArray(Downloader.downloadText(api))
                val bin = json.getJSONObject(0).optJSONObject("binary") ?: org.json.JSONObject()
                val pkg = bin.optJSONObject("package") ?: org.json.JSONObject()
                fileName = pkg.optString("name").takeIf { it.isNotBlank() } ?: fileName
                githubLink = pkg.optString("link").takeIf { it.isNotBlank() }
            } catch (_: Exception) { }
            if (fileName.isNullOrBlank()) {
                // 兜底：华为目录页（格式与 TUNA 不同，仅解析文件名）
                fileName = runCatching {
                    val html = Downloader.downloadText(
                        "https://mirrors.huaweicloud.com/adoptium/$majorVersion/jdk/aarch64/linux/",
                        timeoutMs = 15_000,
                    )
                    Regex("""OpenJDK\d+U-jdk_aarch64_linux_hotspot_[^"<>]+\.tar\.gz""")
                        .find(html)?.value
                }.getOrNull()
            }

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
                shouldCancel = shouldCancel,
                // 内容校验：部分镜像对不存在的大文件返回 200+HTML 错误页，仅凭 HTTP 码无法识别
                validate = { f -> f.length() > 1_000_000 && isGzipTar(f) },
            )
            if (used == null) return@withContext null
            onProgress(1f, "下载完成（源：$used），解压中…")

            // 解压到临时目录，再移到目标（Adoptium tar 内有一层 jdk-xx.y+z/ 目录）
            if (tmpDir.exists()) tmpDir.deleteRecursively()
            tmpDir.mkdirs()
            onProgress(1f, "解压 OpenJDK $majorVersion…")
            // 主方案：系统 tar（久经验证，Termux/pkg 同款路径）；失败回退内置 TarExtractor（带进度）
            val systemTarOk = runCatching {
                val proc = Runtime.getRuntime().exec(
                    arrayOf("/system/bin/sh", "-c", "tar xf '${tarFile.absolutePath}' -C '${tmpDir.absolutePath}'")
                )
                // 轮询等待 + 可取消：裸 waitFor() 无超时不可中断——FUSE 卡死会永久
                // 占住 Java 互斥锁，后续所有实例全排队超时
                var done = false
                while (!done) {
                    if (proc.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                        done = true
                    } else if (shouldCancel()) {
                        proc.destroyForcibly()
                        throw InterruptedException("解压已取消")
                    }
                }
                proc.exitValue() == 0
            }.getOrDefault(false)
            if (!systemTarOk) {
                if (shouldCancel()) return@withContext null
                onProgress(1f, "系统 tar 不可用，回退内置解压…")
                try {
                    TarExtractor.extract(tarFile, tmpDir) { done, total, speed ->
                        onProgress(if (total > 0) done.toFloat() / total else 0f, "解压 OpenJDK：${done / 1024 / 1024}MB${if (total > 0) " / ${total / 1024 / 1024}MB" else ""}（${speed / 1024}KB/s）")
                    }
                } catch (e: Exception) {
                    onProgress(0f, "解压失败：${e.message}")
                    tmpDir.deleteRecursively()
                    return@withContext null
                }
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
        } catch (e: InterruptedException) {
            // 取消清理半成品（tar 包保留用于断点续传）
            runCatching { tmpDir.deleteRecursively() }
            runCatching { targetDir.deleteRecursively() }
            null
        } catch (e: Exception) {
            // 失败/取消清理：半成品解压目录与目标目录删除（tar 包保留用于断点续传）
            runCatching { tmpDir.deleteRecursively() }
            if (shouldCancel() || File(targetDir, "bin/java").exists().not()) {
                runCatching { targetDir.deleteRecursively() }
            }
            null
        }
    }

    private fun archSuffix(): String =
        if (android.os.Build.SUPPORTED_ABIS.any { it.contains("arm64-v8a", true) || it.contains("aarch64", true) }) "arm64" else "armhf"

    /** 任务互斥等锁：期间检查取消（取消不等锁，直接抛出由调用方退出） */
    private suspend fun acquireMutex(shouldCancel: () -> Boolean) {
        while (!taskMutex.compareAndSet(false, true)) {
            if (shouldCancel()) throw InterruptedException("操作已取消")
            kotlinx.coroutines.delay(300)
        }
    }

    /** 可取消目录复制：每 ~8MB 检一次取消标志（copyRecursively 全量拷贝无法中断） */
    private fun copyDirChecked(src: File, dst: File, shouldCancel: () -> Boolean) {
        val buf = ByteArray(256 * 1024)
        var sinceCheck = 0L
        src.walkTopDown().forEach { f ->
            val out = File(dst, f.relativeTo(src).path)
            if (f.isDirectory) out.mkdirs()
            else {
                out.parentFile?.mkdirs()
                java.io.FileInputStream(f).use { ins ->
                    java.io.FileOutputStream(out).use { o ->
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n)
                            sinceCheck += n
                            if (sinceCheck > 8_000_000) {
                                sinceCheck = 0
                                if (shouldCancel()) throw InterruptedException("迁移已取消")
                            }
                        }
                    }
                }
            }
        }
    }

    /** gzip 魔数（0x1f 0x8b）检查：拦截 HTML 错误页等无效下载内容 */
    private fun isGzipTar(f: File): Boolean = try {
        java.io.RandomAccessFile(f, "r").use { raf ->
            raf.readUnsignedByte() == 0x1f && raf.readUnsignedByte() == 0x8b
        }
    } catch (_: Exception) {
        false
    }
}
