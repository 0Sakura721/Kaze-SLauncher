package com.mcserver.launcher.server

import android.content.Context
import com.mcserver.launcher.McApplication
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * 服务器崩溃报告自动分析器。
 * 借鉴 Pterodactyl 的 crash detection 和 MCSManager 的诊断系统。
 *
 * 功能：
 * - 解析 crash-reports 目录下的报告文件
 * - 提取关键错误信息（异常类型、堆栈跟踪、受影响模组）
 * - 识别常见问题模式（OOM、模组冲突、Java版本不兼容等）
 * - 给出修复建议
 */
object CrashAnalyzer {

    private val context: Context get() = McApplication.instance

    data class CrashReport(
        val file: File,
        val fileName: String,
        val crashTime: Long = 0,
        val crashTimeStr: String = "",
        val description: String = "",
        val exceptionType: String = "",
        val exceptionMessage: String = "",
        val stackTrace: List<String> = emptyList(),
        val affectedMods: List<String> = emptyList(),
        val suspectedCause: CrashCause = CrashCause.UNKNOWN,
        val suggestion: String = "",
        val severity: CrashSeverity = CrashSeverity.MEDIUM
    )

    private fun validateFileName(name: String) {
        require(name.isNotBlank()) { "文件名不能为空" }
        require(!name.contains("/") && !name.contains("\\") && !name.contains("..") && !name.contains(":")) {
            "文件名包含非法字符: $name"
        }
    }

    private fun validatePathInsideRoot(file: File, root: File): Boolean {
        return try {
            file.canonicalFile.startsWith(root.canonicalFile)
        } catch (_: Exception) {
            false
        }
    }

    enum class CrashCause(val label: String) {
        OUT_OF_MEMORY("内存不足 (OOM)"),
        MOD_CONFLICT("模组冲突"),
        JAVA_VERSION("Java 版本不兼容"),
        CORRUPTED_WORLD("世界数据损坏"),
        PLUGIN_ERROR("插件错误"),
        CONFIG_ERROR("配置错误"),
        DISK_FULL("磁盘空间不足"),
        PERMISSION("权限问题"),
        NATIVE_LIBRARY("原生库加载失败"),
        UNKNOWN("未知原因")
    }

    enum class CrashSeverity(val label: String) {
        LOW("低"),
        MEDIUM("中"),
        HIGH("高"),
        CRITICAL("严重")
    }

    /** 获取 crash-reports 目录（使用实际服务器运行目录） */
    private fun getCrashReportsDir(): File {
        val baseDir = ProotServerManager.serverDir(context)
        return File(baseDir, "crash-reports")
    }

    /** 扫描并解析所有崩溃报告 */
    fun scanCrashReports(): List<CrashReport> {
        val reportsDir = getCrashReportsDir()
        if (!reportsDir.exists() || !reportsDir.isDirectory) return emptyList()

        return reportsDir.listFiles()
            ?.filter { it.isFile && it.extension == "txt" }
            ?.sortedByDescending { it.lastModified() }
            ?.mapNotNull { parseCrashReport(it) }
            ?: emptyList()
    }

    /** 获取最新的崩溃报告 */
    fun getLatestCrashReport(): CrashReport? {
        return scanCrashReports().firstOrNull()
    }

    /** 获取崩溃报告数量 */
    fun getCrashReportCount(): Int {
        val dir = getCrashReportsDir()
        return dir.listFiles()?.count { it.extension == "txt" } ?: 0
    }

    /** 解析单个崩溃报告文件 */
    private fun parseCrashReport(file: File): CrashReport? {
        return try {
            val content = file.readText()
            if (content.isBlank()) return null

            // 提取时间
            val timePattern = Regex("Time:\\s*(.+)")
            val timeStr = timePattern.find(content)?.groupValues?.get(1)?.trim() ?: ""
            val crashTime = parseTime(timeStr)

            // 提取描述
            val descPattern = Regex("Description:\\s*(.+)")
            val description = descPattern.find(content)?.groupValues?.get(1)?.trim() ?: ""

            // 提取异常类型和消息
            val exceptionType: String
            val exceptionMessage: String
            val stackTrace = mutableListOf<String>()

            // 常见格式: "java.lang.OutOfMemoryError: Java heap space"
            val exceptionPattern = Regex("^([\\w.]+(?:Error|Exception))(?::\\s*(.+))?$", RegexOption.MULTILINE)
            val exceptionMatch = exceptionPattern.find(content)
            if (exceptionMatch != null) {
                exceptionType = exceptionMatch.groupValues[1]
                exceptionMessage = exceptionMatch.groupValues.getOrElse(2) { "" }
            } else {
                exceptionType = ""
                exceptionMessage = ""
            }

            // 提取堆栈跟踪
            val stackSection = content.substringAfter("Stacktrace:", "")
                .substringBefore("-- System Details --")
                .substringBefore("A detailed walkthrough")
            if (stackSection.isNotBlank()) {
                stackTrace.addAll(
                    stackSection.lines()
                        .map { it.trim() }
                        .filter { it.startsWith("at ") || it.startsWith("... ") }
                        .take(30)
                )
            }

            // 提取受影响的模组
            val modSection = content.substringAfter("-- Mod List --", "")
                .substringBefore("-- System Details --")
            val affectedMods = if (modSection.isNotBlank()) {
                modSection.lines()
                    .filter { it.contains("|") && !it.contains("Mod List") }
                    .map { it.trim() }
                    .take(20)
            } else emptyList()

            // 分析崩溃原因
            val (cause, suggestion, severity) = analyzeCause(
                exceptionType, exceptionMessage, description, content, stackTrace
            )

            CrashReport(
                file = file,
                fileName = file.name,
                crashTime = crashTime,
                crashTimeStr = timeStr,
                description = description,
                exceptionType = exceptionType,
                exceptionMessage = exceptionMessage,
                stackTrace = stackTrace,
                affectedMods = affectedMods,
                suspectedCause = cause,
                suggestion = suggestion,
                severity = severity
            )
        } catch (_: Exception) {
            null
        }
    }

    /** 分析崩溃原因 */
    private fun analyzeCause(
        exceptionType: String,
        exceptionMessage: String,
        description: String,
        fullContent: String,
        stackTrace: List<String>
    ): Triple<CrashCause, String, CrashSeverity> {
        val lowerContent = fullContent.lowercase()
        val lowerException = exceptionType.lowercase()
        val lowerMsg = exceptionMessage.lowercase()

        // 内存不足
        if (lowerException.contains("outofmemory") ||
            lowerMsg.contains("heap space") ||
            lowerMsg.contains("metaspace") ||
            lowerMsg.contains("gc overhead") ||
            lowerContent.contains("java heap space")
        ) {
            val memType = when {
                lowerMsg.contains("heap space") -> "堆内存"
                lowerMsg.contains("metaspace") -> "元空间 (Metaspace)"
                lowerMsg.contains("gc overhead") -> "GC开销过大"
                else -> "内存"
            }
            return Triple(
                CrashCause.OUT_OF_MEMORY,
                "${memType}不足导致崩溃。建议：\n" +
                "1. 在服务器设置中增加分配的内存（当前建议至少 2GB）\n" +
                "2. 减少同时加载的区块数 (view-distance)\n" +
                "3. 检查是否有模组/插件导致内存泄漏\n" +
                "4. 使用 Aikar's Flags JVM 参数优化 GC\n" +
                "5. 考虑使用优化模组如 Lithium、Phosphor",
                CrashSeverity.CRITICAL
            )
        }

        // Java 版本不兼容
        if (lowerException.contains("unsupportedclassversion") ||
            lowerException.contains("classnotfound") && lowerContent.contains("java") ||
            lowerMsg.contains("has been compiled by a more recent version") ||
            lowerContent.contains("class file version")
        ) {
            return Triple(
                CrashCause.JAVA_VERSION,
                "Java 版本与服务器/Minecraft 版本不兼容。建议：\n" +
                "1. MC 1.21+ 需要 Java 21，MC 1.18-1.20 需要 Java 17\n" +
                "2. 在 JRE 管理页面下载匹配的 Java 版本\n" +
                "3. Forge 旧版本可能需要 Java 8",
                CrashSeverity.HIGH
            )
        }

        // 模组冲突
        if (lowerContent.contains("modloading") && (
                lowerContent.contains("failed") || lowerContent.contains("error") ||
                lowerContent.contains("conflict") || lowerContent.contains("incompatible"))
        ) {
            return Triple(
                CrashCause.MOD_CONFLICT,
                "模组加载时出现冲突或错误。建议：\n" +
                "1. 检查 crash-reports 中列出的受影响模组\n" +
                "2. 确保所有模组版本与 Minecraft 和模组加载器版本匹配\n" +
                "3. 尝试逐个禁用模组以定位问题模组\n" +
                "4. 检查模组之间的依赖关系是否满足",
                CrashSeverity.HIGH
            )
        }

        // 插件错误
        if (lowerContent.contains("plugin") && (
                lowerContent.contains("error") || lowerContent.contains("exception") ||
                lowerContent.contains("could not load"))
        ) {
            return Triple(
                CrashCause.PLUGIN_ERROR,
                "服务器插件出现错误。建议：\n" +
                "1. 检查插件是否与当前服务端核心兼容\n" +
                "2. 更新插件到最新版本\n" +
                "3. 尝试禁用最近添加的插件\n" +
                "4. 查看日志获取具体的插件错误信息",
                CrashSeverity.MEDIUM
            )
        }

        // 世界数据损坏
        if (lowerContent.contains("corrupted") && lowerContent.contains("chunk") ||
            lowerContent.contains("region") && lowerContent.contains("error") ||
            lowerException.contains("ioexception") && lowerContent.contains("region")
        ) {
            return Triple(
                CrashCause.CORRUPTED_WORLD,
                "世界数据可能已损坏。建议：\n" +
                "1. 从最近的备份中恢复世界数据\n" +
                "2. 使用 Region Fixer 等工具修复损坏的区块\n" +
                "3. 检查磁盘是否有坏道\n" +
                "4. 考虑删除问题区块（会丢失该区块数据）",
                CrashSeverity.HIGH
            )
        }

        // 磁盘空间不足
        if (lowerMsg.contains("no space") || lowerContent.contains("disk full") ||
            lowerContent.contains("no space left on device")
        ) {
            return Triple(
                CrashCause.DISK_FULL,
                "磁盘空间不足。建议：\n" +
                "1. 清理不需要的世界、备份和日志文件\n" +
                "2. 在文件管理页面检查磁盘使用情况\n" +
                "3. 删除旧的崩溃报告释放空间",
                CrashSeverity.CRITICAL
            )
        }

        // 权限问题
        if (lowerException.contains("accessdenied") ||
            lowerException.contains("permission") ||
            lowerMsg.contains("permission denied")
        ) {
            return Triple(
                CrashCause.PERMISSION,
                "文件权限不足。建议：\n" +
                "1. 检查应用是否有存储权限\n" +
                "2. 确认服务器目录可读写\n" +
                "3. 确认应用存储权限已授予",
                CrashSeverity.HIGH
            )
        }

        // 原生库
        if (lowerException.contains("unsatisfiedlink") ||
            lowerContent.contains("native library") ||
            lowerContent.contains(".so") && lowerContent.contains("load")
        ) {
            return Triple(
                CrashCause.NATIVE_LIBRARY,
                "原生库加载失败。建议：\n" +
                "1. 确认 Linux 环境中安装了必要的依赖\n" +
                "2. 在应用内安装 JDK 21\n" +
                "3. 某些模组可能不兼容 ARM 架构",
                CrashSeverity.MEDIUM
            )
        }

        // 配置错误
        if (lowerContent.contains("invalid") && (
                lowerContent.contains("config") || lowerContent.contains("property") ||
                lowerContent.contains("setting"))
        ) {
            return Triple(
                CrashCause.CONFIG_ERROR,
                "配置文件可能存在错误。建议：\n" +
                "1. 检查 server.properties 配置是否正确\n" +
                "2. 验证端口号未被占用\n" +
                "3. 检查 eula.txt 是否已同意",
                CrashSeverity.MEDIUM
            )
        }

        // 默认
        val suggestion = if (exceptionType.isNotBlank()) {
            "服务器因 $exceptionType 崩溃。\n建议查看完整崩溃报告获取详细信息。"
        } else {
            "无法自动分析崩溃原因。\n请查看完整崩溃报告文件获取详细信息。"
        }

        return Triple(CrashCause.UNKNOWN, suggestion, CrashSeverity.MEDIUM)
    }

    /** 解析时间字符串 */
    private fun parseTime(timeStr: String): Long {
        return try {
            // 尝试多种格式
            val formats = listOf(
                "yyyy-MM-dd HH:mm:ss",
                "yyyy/MM/dd HH:mm:ss",
                "MM/dd/yy HH:mm",
                "EEE MMM dd HH:mm:ss zzz yyyy"
            )
            for (format in formats) {
                try {
                    return SimpleDateFormat(format, Locale.US).parse(timeStr)?.time ?: continue
                } catch (_: Exception) {}
            }
            0L
        } catch (_: Exception) { 0L }
    }

    /** 删除崩溃报告 */
    fun deleteReport(fileName: String): Boolean {
        return try {
            validateFileName(fileName)
            val dir = getCrashReportsDir()
            val file = File(dir, fileName)
            if (!validatePathInsideRoot(file, dir)) {
                throw SecurityException("文件路径越界: $fileName")
            }
            file.delete()
        } catch (_: Exception) { false }
    }

    /** 删除所有崩溃报告 */
    fun deleteAllReports(): Boolean {
        return try {
            getCrashReportsDir().listFiles()?.forEach { it.delete() }
            true
        } catch (_: Exception) { false }
    }

    /** 获取崩溃报告内容 */
    fun getReportContent(fileName: String): String? {
        return try {
            validateFileName(fileName)
            val dir = getCrashReportsDir()
            val file = File(dir, fileName)
            if (!validatePathInsideRoot(file, dir)) {
                throw SecurityException("文件路径越界: $fileName")
            }
            if (file.exists()) file.readText() else null
        } catch (_: Exception) { null }
    }
}
