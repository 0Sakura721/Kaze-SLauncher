package com.mcserver.launcher.server

import android.content.Context
import android.os.Build
import com.mcserver.launcher.McApplication
import com.mcserver.launcher.data.ServerConfig
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.jar.JarFile

/**
 * 服务器启动前健康检查 / 诊断工具。
 * 借鉴 Pterodactyl、MCSManager 和 PufferPanel 的启动前验证逻辑。
 *
 * 新增检查项（v0.5.0）：
 * - Linux 环境完整性检查
 * - Java 二进制可执行性验证
 * - 系统资源综合评估
 * - 推荐配置建议
 */
object HealthChecker {

    private val context: Context get() = McApplication.instance
    private val serverDir: File get() = ProotServerManager.serverDir(context)

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

        // 1. Linux 环境检查
        checks.add(checkLinuxEnv())

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

    /** 检查内置 Linux 环境是否可用 */
    private fun checkLinuxEnv(): HealthCheck {
        val ready = LinuxEnvironmentManager.isEnvironmentReady()
        if (!ready) {
            return HealthCheck("Linux 环境", false,
                "未检测到 Linux 运行环境。Minecraft 服务器需要内置的 proot+Ubuntu 环境。",
                detail = "请在设置中初始化 Linux 环境")
        }
        val hasJava = listOf(21, 17, 11, 8).any { LinuxEnvironmentManager.isJdkInstalled(it) }
        return if (hasJava) {
            HealthCheck("Linux 环境", true,
                "Linux 环境已就绪（Java 已安装）", Severity.INFO)
        } else {
            HealthCheck("Linux 环境", false,
                "Linux 环境已就绪但 Java 未安装",
                detail = "请在应用内安装 JDK",
                severity = Severity.WARNING)
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
            val serverSocket = ServerSocket(port)
            serverSocket.close()
            HealthCheck("端口 $port", true, "端口 $port 可用", Severity.INFO)
        } catch (e: Exception) {
            HealthCheck("端口 $port", false,
                "端口 $port 已被占用，请更换端口或关闭占用程序",
                detail = "可在「服务器配置」中修改端口号。")
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

        val requiredVersion = McVersionCompat.getRequiredJavaVersion(config.jarPath)
        val serverType = McVersionCompat.guessServerType(config.jarPath)

        // 获取实际将用于运行服务器的 Java 版本：
        // 优先从 JreManager（内置 JRE），回退到 LinuxEnvironmentManager（proot+Ubuntu JDK），
        // 最后才用内置 Linux 环境的 Java 版本。
        val currentJavaVersion = when {
            ServerManager.instance.selectedJreVersion.toIntOrNull() != null ->
                ServerManager.instance.selectedJreVersion.toIntOrNull() ?: 17
            LinuxEnvironmentManager.isEnvironmentReady() -> {
                // 从 McVersionCompat 推断所需版本并检查是否已安装
                val candidate = listOf(21, 17, 11, 8).firstOrNull {
                    LinuxEnvironmentManager.isJdkInstalled(it)
                }
                candidate ?: (ServerManager.instance.selectedJreVersion.toIntOrNull() ?: 17)
            }
            else -> {
                // 回退：检查内置 Linux 环境中的 Java 版本
                listOf(21, 17, 11, 8).forEach { ver ->
                    if (LinuxEnvironmentManager.isJdkInstalled(ver)) {
                        val javaPath = LinuxEnvironmentManager.getJavaPath(ver)
                        val proc = ProcessBuilder(javaPath, "-version").redirectErrorStream(true).start()
                        val output = proc.inputStream.bufferedReader().readText()
                        if (proc.waitFor() == 0) {
                            val versionMatch = Regex("version \"(\\d+)").find(output)
                            if (versionMatch != null) {
                                return HealthCheck("Java 版本", true,
                                    "Java ${versionMatch.groupValues[1]} (Linux 环境内置)",
                                    Severity.INFO)
                            }
                        }
                    }
                }
                17
            }
        }

        return if (currentJavaVersion >= requiredVersion) {
            HealthCheck("Java 兼容性", true,
                "Java $currentJavaVersion 满足 $serverType 核心要求 (Java $requiredVersion+)",
                Severity.INFO)
        } else {
            HealthCheck("Java 兼容性", true,
                "$serverType 核心需要 Java $requiredVersion+，当前为 Java $currentJavaVersion",
                Severity.WARNING,
                detail = "请在应用内安装更高版本 JDK")
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

    /**
     * 生成完整的系统诊断报告（借鉴 MCSManager 诊断面板）。
     * 包含设备信息、系统资源、服务器配置摘要等。
     */
    fun generateDiagnosticReport(config: ServerConfig): String {
        val sb = StringBuilder()
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

        sb.appendLine("========================================")
        sb.appendLine("  MCServer Launcher 诊断报告")
        sb.appendLine("  生成时间: $now")
        sb.appendLine("========================================")
        sb.appendLine()

        // 设备信息
        sb.appendLine("--- 设备信息 ---")
        sb.appendLine("  设备型号: ${Build.MODEL}")
        sb.appendLine("  制造商: ${Build.MANUFACTURER}")
        sb.appendLine("  Android 版本: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("  CPU 架构: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
        sb.appendLine("  CPU 核心数: ${Runtime.getRuntime().availableProcessors()}")
        sb.appendLine()

        // 内存信息
        val runtime = Runtime.getRuntime()
        val totalMem = runtime.maxMemory() / (1024 * 1024)
        val freeMem = runtime.freeMemory() / (1024 * 1024)
        val usedMem = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        sb.appendLine("--- 内存信息 ---")
        sb.appendLine("  应用可用最大内存: ${totalMem}MB")
        sb.appendLine("  当前已用: ${usedMem}MB")
        sb.appendLine("  当前空闲: ${freeMem}MB")
        sb.appendLine()

        // 存储信息
        val serverDir = serverDir
        val totalSpace = serverDir.totalSpace / (1024 * 1024)
        val freeSpace = serverDir.freeSpace / (1024 * 1024)
        val usableSpace = serverDir.usableSpace / (1024 * 1024)
        sb.appendLine("--- 存储信息 ---")
        sb.appendLine("  服务器目录: ${serverDir.absolutePath}")
        sb.appendLine("  分区总空间: ${formatFileSize(totalSpace * 1024 * 1024)}")
        sb.appendLine("  可用空间: ${formatFileSize(usableSpace * 1024 * 1024)}")
        sb.appendLine("  空闲空间: ${formatFileSize(freeSpace * 1024 * 1024)}")
        sb.appendLine()

        // Linux 环境状态
        sb.appendLine("--- Linux 环境 ---")
        sb.appendLine("  环境就绪: ${LinuxEnvironmentManager.isEnvironmentReady()}")
        listOf(21, 17, 11, 8).forEach { ver ->
            sb.appendLine("  JDK $ver: ${if (LinuxEnvironmentManager.isJdkInstalled(ver)) "已安装" else "未安装"}")
        }
        sb.appendLine()

        // Java 信息（报告实际将用于运行服务器的 Java 版本）
        sb.appendLine("--- Java 运行时 ---")
        val actualJavaVersion = when {
            ServerManager.instance.selectedJreVersion.toIntOrNull() != null ->
                ServerManager.instance.selectedJreVersion
            LinuxEnvironmentManager.isEnvironmentReady() -> {
                val installed = listOf(21, 17, 11, 8).filter {
                    LinuxEnvironmentManager.isJdkInstalled(it)
                }
                installed.joinToString(", ") { "Java $it" }.ifEmpty { "未知" }
            }
            else -> "系统: ${System.getProperty("java.version", "未知")}"
        }
        sb.appendLine("  Java 版本: $actualJavaVersion")
        sb.appendLine("  JRE 管理器: ${ServerManager.instance.selectedJreVersion}")
        sb.appendLine("  系统架构: ${System.getProperty("os.arch", "未知")}")
        sb.appendLine()

        // 服务器配置
        sb.appendLine("--- 服务器配置 ---")
        sb.appendLine("  名称: ${config.name}")
        sb.appendLine("  JAR 路径: ${config.jarPath}")
        sb.appendLine("  分配内存: ${config.allocatedMemoryMB}MB")
        sb.appendLine("  端口: ${config.serverPort}")
        sb.appendLine("  游戏模式: ${config.gamemode}")
        sb.appendLine("  难度: ${config.difficulty}")
        sb.appendLine("  最大玩家: ${config.maxPlayers}")
        sb.appendLine("  正版验证: ${config.onlineMode}")
        sb.appendLine("  PVP: ${config.pvp}")
        sb.appendLine("  白名单: ${config.whiteList}")
        sb.appendLine("  视距: ${config.viewDistance}")
        sb.appendLine("  自动重启: ${config.autoRestart}")
        sb.appendLine("  最大重启次数: ${config.maxRestarts}")
        sb.appendLine("  重启冷却: ${config.restartCooldownSec}秒")
        sb.appendLine("  RCON: ${if (config.rconEnabled) "启用 (端口 ${config.rconPort})" else "禁用"}")
        sb.appendLine("  JVM 参数: ${config.additionalArgs}")
        sb.appendLine()

        // 服务器目录内容
        sb.appendLine("--- 服务器目录 ---")
        if (serverDir.exists()) {
            serverDir.listFiles()?.sortedBy { it.name }?.forEach { file ->
                val type = when {
                    file.isDirectory -> "[DIR]"
                    file.extension == "jar" -> "[JAR]"
                    file.extension == "log" -> "[LOG]"
                    else -> "[FILE]"
                }
                sb.appendLine("  $type ${file.name} (${formatFileSize(file.length())})")
            }
        } else {
            sb.appendLine("  (目录不存在)")
        }
        sb.appendLine()

        // 健康检查结果
        sb.appendLine("--- 健康检查 ---")
        val health = runAllChecks(config)
        health.checks.forEach { check ->
            val status = if (check.passed) "PASS" else "FAIL"
            sb.appendLine("  [$status] ${check.name}: ${check.message}")
        }
        if (health.warnings.isNotEmpty()) {
            sb.appendLine("  注意事项:")
            health.warnings.forEach { sb.appendLine("    - $it") }
        }
        if (health.recommendations.isNotEmpty()) {
            sb.appendLine("  推荐优化:")
            health.recommendations.forEach { sb.appendLine("    - $it") }
        }
        sb.appendLine()

        sb.appendLine("========================================")
        sb.appendLine("  报告结束")
        sb.appendLine("========================================")

        return sb.toString()
    }

    /**
     * 导出诊断报告到文件。
     * @return 报告文件路径
     */
    fun exportDiagnosticReport(config: ServerConfig): File {
        val report = generateDiagnosticReport(config)
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val reportDir = File(serverDir, "diagnostics")
        reportDir.mkdirs()
        val reportFile = File(reportDir, "diagnostic_$timestamp.txt")
        reportFile.writeText(report)
        return reportFile
    }
}
