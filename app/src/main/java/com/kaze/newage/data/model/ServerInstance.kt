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

    /**
     * Forge / NeoForge 下载到的是 `*-installer.jar`——它**不是**可直接运行的服务端，
     * 必须先执行 `java -jar <installer> --installServer` 生成 libraries/ 与启动入口。
     */
    val installerJar: File?
        get() = dir.listFiles()?.firstOrNull {
            it.isFile && it.name.endsWith(".jar", ignoreCase = true) &&
                it.name.contains("installer", ignoreCase = true)
        }

    /** 安装完成标记（installer 跑过一次就写，避免每次启动重装） */
    val forgeMarker: File get() = File(dir, ".forge-installed")

    /**
     * 现代 Forge(1.17+) / NeoForge 安装后生成 `libraries/.../unix_args.txt`：
     * 里面是 main class 与 classpath，用 Java 的 `@argfile` 语法启动。
     */
    fun forgeArgsFile(): File? {
        val libs = File(dir, "libraries")
        if (!libs.isDirectory) return null
        return libs.walkTopDown().maxDepth(8)
            .firstOrNull { it.isFile && it.name == "unix_args.txt" }
    }

    /** 旧版 Forge（≤1.16.5）安装后生成的可直接 `-jar` 的启动器 */
    fun legacyForgeJar(): File? =
        dir.listFiles()?.firstOrNull {
            it.isFile && it.name.startsWith("forge-") &&
                it.name.endsWith(".jar", ignoreCase = true) &&
                !it.name.contains("installer", ignoreCase = true)
        }

    /** Forge/NeoForge 是否已安装完成（有启动入口即算） */
    fun forgeInstalled(): Boolean = forgeArgsFile() != null || legacyForgeJar() != null
}

/**
 * 按 MC 版本推断所需 Java 主版本（来源：itzg/docker-minecraft-server 官方文档）。
 * 旧命名：1.8–1.16.5 → 8；1.17–1.20.4 → 17；≥1.20.5 → 21
 * 新命名（2025 起，如 24.1 / 26.1.1）：24.x–25.x → 21；≥26.x → 25（实测 vanilla-26.1.1
 * 的 bundler 为 class file 69.0 = Java 25，Java 21 报 UnsupportedClassVersionError）
 */
object JavaVersionInference {

    fun infer(mcVersion: String): Int {
        val m = Regex("""(\d+)\.(\d+)(?:\.(\d+))?""").find(mcVersion) ?: return 17
        val major = m.groupValues[1].toInt()
        val minor = m.groupValues[2].toInt()
        return when {
            major >= 26 -> 25
            major >= 24 -> 21
            major == 1 && minor <= 16 -> 8
            major == 1 && minor <= 20 && (minor < 20 || (minor == 20 && m.groupValues[3].toIntOrNull() ?: 0 < 5)) -> 17
            else -> 21
        }
    }
}
