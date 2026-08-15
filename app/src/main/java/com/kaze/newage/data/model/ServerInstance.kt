package com.kaze.newage.data.model

import java.io.File

/**
 * 游戏版本类型（照搬 FCL/HMCL 体系，来源：Mojang version_manifest_v2.json 的 type 字段）。
 */
enum class VersionType(val displayName: String) {
    RELEASE("正式版"),
    SNAPSHOT("快照版"),
    OLD_BETA("远古测试版"),
    OLD_ALPHA("远古预览版"),
}

/** 游戏版本条目（带类型分类） */
data class GameVersion(
    val id: String,
    val type: VersionType = VersionType.RELEASE,
    val stable: Boolean = true,
) {
    val isRelease: Boolean get() = type == VersionType.RELEASE
}

/**
 * 核心类型分类（FCL 式分组：官方 / 性能优化 / 模组加载）。
 */
enum class CoreCategory(val displayName: String) {
    OFFICIAL("官方核心"),
    OPTIMIZED("性能优化"),
    MODDED("模组加载"),
    IMPORT("导入"),
}

/** 服务端核心类型 */
enum class CoreType(val displayName: String, val category: CoreCategory) {
    VANILLA("原版 Vanilla", CoreCategory.OFFICIAL),
    PAPER("Paper", CoreCategory.OPTIMIZED),
    PURPUR("Purpur", CoreCategory.OPTIMIZED),
    SPIGOT("Spigot", CoreCategory.OPTIMIZED),
    FABRIC("Fabric", CoreCategory.MODDED),
    FORGE("Forge", CoreCategory.MODDED),
    NEOFORGE("NeoForge", CoreCategory.MODDED),
    CUSTOM("导入 jar", CoreCategory.IMPORT),
}

/** 服务端实例 */
data class ServerInstance(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val coreType: CoreType = CoreType.CUSTOM,
    val mcVersion: String = "",
    val javaMajor: Int = 17,
    val memoryMb: Int = 1024,
    val nogui: Boolean = true,
    val autoRestart: Boolean = false,
    val maxRestarts: Int = 3,
    val dir: File,          // 实例目录（server.jar 所在）
) {
    /** 实例目录下的 jar 文件 */
    val jarFile: File
        get() = dir.listFiles()
            ?.firstOrNull { it.isFile && it.name.endsWith(".jar") && !it.name.contains("installer", true) }
            ?: File(dir, "server.jar")

    val eulaFile: File get() = File(dir, "eula.txt")
}

/**
 * 按 MC 版本推断所需 Java 主版本（来源：itzg/docker-minecraft-server 官方文档）。
 * 1.8–1.16.5 → 8；1.17 → 17；1.18–1.20.4 → 17；≥1.20.5 → 21
 */
object JavaVersionInference {

    fun infer(mcVersion: String): Int {
        val m = Regex("""(\d+)\.(\d+)(?:\.(\d+))?""").find(mcVersion) ?: return 17
        val major = m.groupValues[1].toInt()
        val minor = m.groupValues[2].toInt()
        return when {
            major == 1 && minor <= 16 -> 8
            major == 1 && minor <= 20 && (minor < 20 || (minor == 20 && m.groupValues[3].toIntOrNull() ?: 0 < 5)) -> 17
            else -> 21
        }
    }
}
