package com.mcserver.launcher.server

/**
 * Minecraft 版本兼容性工具 — 判断 JAR 需要的 Java 版本、提供版本相关信息。
 * 借鉴 Pterodactyl Egg 中的版本兼容性矩阵。
 */
object McVersionCompat {

    /**
     * MC 版本 → 所需最低 Java 版本
     * 数据来源: https://minecraft.wiki/w/Java_Edition_version_history
     */
    private val versionJavaMap = mapOf(
        // 1.18+ 需要 Java 17
        "1.18" to 17, "1.19" to 17, "1.20" to 17,
        // 1.21+ 需要 Java 21
        "1.21" to 21, "1.22" to 21, "1.23" to 21, "1.24" to 21, "1.25" to 21,
        // 1.17 需要 Java 16
        "1.17" to 16,
        // 1.12-1.16.5 需要 Java 8
        "1.12" to 8, "1.13" to 8, "1.14" to 8, "1.15" to 8, "1.16" to 8
    )

    /**
     * 根据 JAR 文件名或路径推断所需的最低 Java 版本
     */
    fun getRequiredJavaVersion(jarPath: String): Int {
        val lower = jarPath.lowercase()

        // 尝试匹配版本号
        for ((mcVer, javaVer) in versionJavaMap) {
            if (lower.contains(mcVer)) return javaVer
        }

        // 匹配模式: 1.XX.X 或 1.XX
        val versionPattern = Regex("""1\.(\d+)""")
        val match = versionPattern.find(lower)
        if (match != null) {
            val minor = match.groupValues[1].toIntOrNull() ?: return 17
            return when {
                minor >= 21 -> 21
                minor >= 18 -> 17
                minor >= 17 -> 16
                else -> 8
            }
        }

        // 默认假设需要 Java 17
        return 17
    }

    /**
     * 检查当前 Java 版本是否满足 JAR 要求
     */
    fun isJavaVersionCompatible(jarPath: String, javaVersion: Int): Boolean {
        return javaVersion >= getRequiredJavaVersion(jarPath)
    }

    /**
     * 获取版本兼容性描述
     */
    fun getCompatibilityDescription(jarPath: String, javaVersion: Int): String {
        val required = getRequiredJavaVersion(jarPath)
        return when {
            javaVersion >= required -> "兼容 (需要 Java $required+)"
            else -> "不兼容 — JAR 需要 Java $required+，当前为 Java $javaVersion"
        }
    }

    /**
     * 根据 MC 版本获取推荐的 Java 版本描述
     */
    fun getRecommendedJavaVersion(jarPath: String): String {
        val required = getRequiredJavaVersion(jarPath)
        return when (required) {
            21 -> "Java 21 (推荐)"
            17 -> "Java 17 (推荐)"
            16 -> "Java 16 (推荐)"
            8 -> "Java 8 (推荐)"
            else -> "Java $required"
        }
    }

    /**
     * 根据 JAR 名称推断服务器类型
     */
    fun guessServerType(jarPath: String): String {
        val lower = jarPath.lowercase()
        return when {
            lower.contains("purpur") -> "Purpur"
            lower.contains("paper") -> "Paper"
            lower.contains("spigot") -> "Spigot"
            lower.contains("fabric") -> "Fabric"
            lower.contains("forge") && lower.contains("neo") -> "NeoForge"
            lower.contains("forge") -> "Forge"
            lower.contains("bukkit") || lower.contains("craftbukkit") -> "CraftBukkit"
            lower.contains("minecraft_server") || lower.contains("server.jar") -> "Vanilla"
            else -> "未知"
        }
    }

    /**
     * 可用的 Minecraft 大版本列表（用于 UI 选择器）
     */
    val availableMajorVersions: List<String> = listOf(
        "1.21", "1.20", "1.19", "1.18", "1.17", "1.16", "1.15", "1.14", "1.13", "1.12"
    )

    /**
     * 最新稳定版 MC 版本号
     */
    const val LATEST_STABLE = "1.21"

    /**
     * 最新快照版 MC 版本号
     */
    const val LATEST_SNAPSHOT = "1.21.5"
}
