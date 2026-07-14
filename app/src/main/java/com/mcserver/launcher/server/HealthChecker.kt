package com.mcserver.launcher.server

import android.content.Context
import com.mcserver.launcher.McApplication
import com.mcserver.launcher.data.ServerConfig
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.jar.JarFile

/**
 * 服务器启动前健康检查 / 诊断工具。
 * 借鉴 Pterodactyl、MCSManager 和 PufferPanel 的启动前验证逻辑。
 *
 * 新增检查项（v0.5.0）：
 * - Termux 环境完整性检查
 * - Java 二进制可执行性验证
 * - 系统资源综合评估
 * - 推荐配置建议
 */
object HealthChecker {

    private val context: Context get() = McApplication.instance
    private val serverDir: File get() = TermuxManager.serverDir(context)

    data class HealthResult(
        val passed: Boolean,
        val checks: List<HealthCheck>,
        val warnings: List<String> = emptyList(),
        val recommendations: List<String> = emptyList()
    )

    data class HealthCheck(
        val name: String,
        val passed: Boolean,
        val message: String,
        val severity: Severity = Severity.ERROR,
        val detail: String = ""
    )

    enum class Severity { INFO, WARNING, ERROR }

    /**
     * 执行完整的启动前健康检查。
     */
    fun runAllChecks(config: ServerConfig): HealthResult {
        val checks = mutableListOf<HealthCheck>()
        val warnings = mutableListOf<String>()
        val recommendations = mutableListOf<String>()

        // 1. Termux 环境检查
        checks.add(checkTermux())

        // 2. JAR 文件检查
        checks.add(checkJarFile(config))

        // 3. 端口占用检查
        checks.add(checkPortAvailable(config.serverPort))

        // 4. 磁盘空间检查
        val diskCheck = checkDiskSpace()
        checks.add(diskCheck)
        if (diskCheck.severity == Severity.WARNING) warnings.add(diskCheck.message)

        // 5. 内存分配检查
        val memCheck = checkMemoryAllocation(config)
        checks.add(memCheck)
        if (memCheck.severity == Severity.WARNING) warnings.add(memCheck.message)

        // 6. EULA 检查
        checks.add(checkEula())

        // 7. Java 版本兼容性
        val javaCheck = checkJavaCompatibility(config)
        if (javaCheck.severity == Severity.WARNING) warnings.add(javaCheck.message)
        checks.add(javaCheck)

        // 8. 系统资源评估 + 推荐配置
        val sysCheck = checkSystemResources(config)
        checks.add(sysCheck)
        if (sysCheck.severity != Severity.ERROR) {
            recommendations.addAll(generateRecommendations(config))
        }

        // 9. 服务器目录权限
        checks.add(checkDirectoryWritable())

        val allPassed = checks.none { !it.passed && it.severity == Severity.ERROR }

        return HealthResult(
            passed = allPassed,
            checks = checks,
            warnings = warnings,
            recommendations = recommendations
        )
    }

    // ─── 新增检查项 ───

    /** 检查 Termux 环境是否可用 */
    private fun checkTermux(): HealthCheck {
        val installed = TermuxManager.isTermuxInstalled(context)
        if (!installed) {
            return HealthCheck("Termux 环境", false,
                "未检测到 Termux。Minecraft 服务器需要 Termux 提供的 Linux 环境。",
                detail = "请从 F-Droid 安装 Termux: https://f-droid.org/packages/com.termux/")
        }
        val bash = File(TermuxManager.getTermuxBashPath())
        if (!bash.exists() || !bash.canExecute()) {
            return HealthCheck("Termux 环境", false,
                "Termux 已安装但 bash 不可执行，请确认 Termux 已正确初始化。",
                detail = "请打开 Termux 应用一次以完成初始化。")
        }
        val state = TermuxManager().checkState()
        return when (state) {
            TermuxState.READY -> HealthCheck("Termux 环境", true,
                "Termux 环境已就绪（Java 已安装）", Severity.INFO)
            TermuxState.JAVA_MISSING -> HealthCheck("Termux 环境", true,
                "Termux 已安装但 Java 未安装，启动前会自动检测。",
                Severity.WARNING,
                detail = "请在 Termux 中运行: pkg install openjdk-21")
            TermuxState.INSTALLED -> HealthCheck("Termux 环境", true,
                "Termux 已安装", Severity.INFO)
            else -> HealthCheck("Termux 环境", false,
                "Termux 状态异常", detail = "请确认 Termux 已从 F-Droid 安装并初始化。")
        }
    }

    /** 检查服务器目录是否可写 */
    private fun checkDirectoryWritable(): HealthCheck {
        return try {
            val testFile = File(serverDir, ".healthcheck_test")
            testFile.writeText("test")
            testFile.delete()
            HealthCheck("目录权限", true,
                "服务器目录可读写 (${serverDir.absolutePath})", Severity.INFO)
        } catch (e: Exception) {
            HealthCheck("目录权限", false,
                "服务器目录不可写：${e.message}",
                detail = "请检查存储权限是否已授予。")
        }
    }

    /** 系统资源综合评估 */
    private fun checkSystemResources(config: ServerConfig): HealthCheck {
        val runtime = Runtime.getRuntime()
        val deviceTotalMB = runtime.maxMemory() / (1024 * 1024)
        val freeStorage = serverDir.freeSpace / (1024 * 1024)
        val cpuCores = runtime.availableProcessors()

        val issues = mutableListOf<String>()

        if (deviceTotalMB < 2048) {
            issues.add("设备总内存仅 ${deviceTotalMB}MB，可能不足以稳定运行服务器")
        }
        if (freeStorage < 1024) {
            issues.add("可用存储空间仅 ${freeStorage}MB，建议至少保留 1GB")
        }
        if (cpuCores < 2) {
            issues.add("设备 CPU 核心数较少（${cpuCores}），可能影响性能")
        }

        return if (issues.isEmpty()) {
            HealthCheck("系统资源", true,
                "系统资源充足：${deviceTotalMB}MB 内存, ${freeStorage / 1024}GB 存储, ${cpuCores} 核心 CPU",
                Severity.INFO)
        } else {
            HealthCheck("系统资源", true,
                issues.joinToString("; "),
                Severity.WARNING,
                detail = "建议降低内存分配或关闭其他应用以释放资源。")
        }
    }

    /** 根据设备和配置生成推荐设置 */
    private fun generateRecommendations(config: ServerConfig): List<String> {
        val recs = mutableListOf<String>()
        val runtime = Runtime.getRuntime()
        val deviceTotalMB = runtime.maxMemory() / (1024 * 1024)

        // 内存推荐
        val recommendedMem = when {
            deviceTotalMB >= 8192 -> 4096
            deviceTotalMB >= 6144 -> 3072
            deviceTotalMB >= 4096 -> 2048
            deviceTotalMB >= 2048 -> 1024
            else -> 512
        }
        if (config.allocatedMemoryMB > recommendedMem + 512) {
            recs.add("建议将内存分配从 ${config.allocatedMemoryMB}MB 降至 ${recommendedMem}MB，以保证系统稳定性")
        }

        // 自动重启建议
        if (!config.autoRestart && config.maxRestarts > 0) {
            recs.add("建议开启「自动重启」功能，防止服务器崩溃后需要手动重启")
        }

        // 备份建议
        if (!config.backupOnStop) {
            recs.add("建议开启「停止时自动备份」，每次关闭服务器前自动保存一份完整备份")
        }

        // 视图距离建议
        if (config.viewDistance > 16 && config.allocatedMemoryMB < 4096) {
            recs.add("视距 ${config.viewDistance} 较高，内存不足时建议降至 10-12")
        }

        return recs
    }

    // ─── 原有检查项 ───

    private fun checkJarFile(config: ServerConfig): HealthCheck {
        if (config.jarPath.isBlank()) {
            return HealthCheck("JAR 文件", false, "未选择服务器 JAR 文件，请先在配置页选择或下载核心",
                detail = "前往「管理 → 下载核心」可下载 Paper/Purpur/Fabric 等")
        }

        val jarFile = File(config.jarPath)
        if (!jarFile.exists()) {
            val name = jarFile.name
            val inServerDir = File(serverDir, name)
            if (inServerDir.exists() && inServerDir.isFile) {
                return checkJarIntegrity(inServerDir)
            }
            return HealthCheck("JAR 文件", false, "JAR 文件不存在：${config.jarPath}",
                detail = "请确认文件未被移动或删除，然后重新选择。")
        }

        return checkJarIntegrity(jarFile)
    }

    private fun checkJarIntegrity(jarFile: File): HealthCheck {
        return try {
            JarFile(jarFile).use { jar ->
                val manifest = jar.manifest
                val mainClass = manifest?.mainAttributes?.getValue("Main-Class")
                if (mainClass != null) {
                    HealthCheck("JAR 完整性", true,
                        "JAR 有效，主类: $mainClass (${formatFileSize(jarFile.length())})",
                        Severity.INFO)
                } else {
                    val hasClasses = jar.entries().asSequence().any {
                        it.name.endsWith(".class") && !it.isDirectory
                    }
                    if (hasClasses) {
                        HealthCheck("JAR 完整性", true,
                            "JAR 有效 (${jarFile.name}, ${formatFileSize(jarFile.length())})",
                            Severity.INFO)
                    } else {
                        HealthCheck("JAR 完整性", false,
                            "JAR 文件似乎不包含 Java 类，可能已损坏",
                            detail = "建议重新下载服务器核心。")
                    }
                }
            }
        } catch (e: Exception) {
            HealthCheck("JAR 完整性", false,
                "JAR 文件无法读取，可能已损坏：${e.message}",
                detail = "请重新下载或选择其他 JAR 文件。")
        }
    }

    private fun checkPortAvailable(port: Int): HealthCheck {
        return try {
            val socket = Socket()
            socket.connect(InetSocketAddress("127.0.0.1", port), 2000)
            socket.close()
            HealthCheck("端口 $port", false,
                "端口 $port 已被占用，请更换端口或关闭占用程序",
                detail = "可在「服务器配置」中修改端口号。")
        } catch (e: Exception) {
            HealthCheck("端口 $port", true, "端口 $port 可用", Severity.INFO)
        }
    }

    private fun checkDiskSpace(): HealthCheck {
        val freeSpace = serverDir.freeSpace
        val freeMB = freeSpace / (1024 * 1024)
        return when {
            freeMB < 100 -> HealthCheck("磁盘空间", false,
                "可用空间仅 ${freeMB}MB，不足以运行服务器（建议至少 500MB）",
                detail = "请清理设备存储空间后重试。")
            freeMB < 500 -> HealthCheck("磁盘空间", true,
                "可用空间 ${freeMB}MB，空间偏低。世界存档可能会占用数百 MB。",
                Severity.WARNING)
            else -> HealthCheck("磁盘空间", true,
                "可用空间 ${formatFileSize(freeMB * 1024 * 1024)}", Severity.INFO)
        }
    }

    private fun checkMemoryAllocation(config: ServerConfig): HealthCheck {
        val runtime = Runtime.getRuntime()
        val deviceTotalMB = (runtime.maxMemory() / (1024 * 1024)).toInt()
        val requested = config.allocatedMemoryMB

        return when {
            requested > deviceTotalMB -> HealthCheck("内存分配", false,
                "请求内存 ${requested}MB 超过可用内存 ${deviceTotalMB}MB",
                detail = "请将内存分配降低至 ${deviceTotalMB}MB 以下。")
            requested > deviceTotalMB * 0.8 -> HealthCheck("内存分配", true,
                "内存分配 ${requested}MB 偏高（可用 ${deviceTotalMB}MB），可能影响系统稳定性",
                Severity.WARNING,
                detail = "建议分配不超过可用内存的 70%。")
            else -> HealthCheck("内存分配", true,
                "内存分配 ${requested}MB 合理（设备总内存 ${deviceTotalMB}MB）",
                Severity.INFO)
        }
    }

    private fun checkEula(): HealthCheck {
        val eulaFile = File(serverDir, "eula.txt")
        if (!eulaFile.exists()) {
            return HealthCheck("EULA", true,
                "EULA 将在首次启动时自动接受",
                Severity.INFO,
                detail = "启动器会自动创建 eula.txt 并写入 eula=true。")
        }
        val content = eulaFile.readText()
        return if (content.contains("eula=true")) {
            HealthCheck("EULA", true, "EULA 已接受", Severity.INFO)
        } else {
            HealthCheck("EULA", true,
                "EULA 将在启动时自动更新为 true",
                Severity.WARNING,
                detail = "启动器会自动修改 eula.txt 以确保服务器正常启动。")
        }
    }

    private fun checkJavaCompatibility(config: ServerConfig): HealthCheck {
        if (config.jarPath.isBlank()) {
            return HealthCheck("Java 兼容性", true, "未选择 JAR，跳过检查", Severity.INFO)
        }

        val jarName = config.jarPath.lowercase()
        val requiredVersion = when {
            jarName.contains("1.21") || jarName.contains("1.22") || jarName.contains("1.23") ||
            jarName.contains("1.24") || jarName.contains("1.25") -> 21
            jarName.contains("1.18") || jarName.contains("1.19") || jarName.contains("1.20") -> 17
            jarName.contains("1.17") -> 16
            jarName.contains("1.16") -> 8
            else -> 17
        }

        val currentJavaVersion = try {
            (System.getProperty("java.version") ?: "17")
                .substringBefore(".")
                .substringBefore("-")
                .toIntOrNull() ?: 17
        } catch (_: Exception) { 17 }

        return if (currentJavaVersion >= requiredVersion) {
            HealthCheck("Java 兼容性", true,
                "Java $currentJavaVersion 满足最低要求 Java $requiredVersion",
                Severity.INFO)
        } else {
            HealthCheck("Java 兼容性", true,
                "JAR 需要 Java $requiredVersion，当前为 Java $currentJavaVersion",
                Severity.WARNING,
                detail = "请在 Termux 中安装更高版本 Java: pkg install openjdk-21")
        }
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f MB".format(mb)
        return "%.2f GB".format(mb / 1024.0)
    }
}
