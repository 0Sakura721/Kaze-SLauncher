package com.kaze.newage.core.server

import java.io.File
import java.util.zip.ZipFile

/**
 * 从用户导入的 jar 里**猜**它是什么（核心类型 / MC 版本 / 需要的 Java 版本）。
 *
 * ## 为什么要在导入时做这件事
 * 导入路径原本一律登记成 `CUSTOM` + 时间戳名字，于是：
 *  - 实例列表里只有「导入-1006-1530」，用户不知道这是哪个服；
 *  - MC 版本空着，和插件/模组的版本匹配、备份命名、界面提示都用不上；
 *  - 选错文件（比如选了个插件 jar）也能"导入成功"，直到启动才炸。
 *
 * 这些都是**在复制之前就能看出来**的：MC 服务端 jar 的元数据相当固定。
 *
 * ## 判据（按可靠度排序）
 * 1. **`version.json`（根目录）**：Paper / Purpur / Folia 等 paperclip 系直接带
 *    `{"id":"1.21.4",…}` —— 这就是 MC 版本，最可靠。
 * 2. **`META-INF/versions.list` + `META-INF/libraries.list`**：现代 NeoForge 的安装包形态。
 * 3. **`META-INF/MANIFEST.MF` 的 `Main-Class` / `Implementation-Title`**：
 *    `io.papermc.paperclip.Main`、`org.bukkit.craftbukkit.Main`、
 *    `net.minecraft.bundler.Main`（官方 1.18+）、`net.fabricmc.loader.impl.launch.server.*`。
 * 4. **类路径特征**：`net/minecraft/server/MinecraftServer.class`（vanilla/patched）、
 *    `org/bukkit/craftbukkit/Main.class`（CraftBukkit 系）。
 * 5. **`META-INF/download-context`**（旧版 paperclip）：`<sha256>\t<url>\t<fileName>`，
 *    文件名里通常带 MC 版本。
 *
 * 猜不出 **MC 版本**时返回空串（界面显示「未知」），而不是编一个 —— 版本填错会导致
 * 插件过滤、备份命名全错，比空着更糟。
 *
 * 纯函数式解析（只读 zip），可在 JVM 单测里直接验（见 `JarInspectorTest`）。
 */
object JarInspector {

    /** 识别结果 */
    data class Info(
        /** 看起来是不是 MC 服务端（含 paperclip / bundler 这类"先下载再跑"的启动器） */
        val looksLikeServerJar: Boolean,
        /** 核心类型（用现有 [CoreType] 的 name，认不出就是 CUSTOM） */
        val coreTypeName: String,
        /** MC 版本（形如 `1.21.4`）；认不出是空串 */
        val mcVersion: String,
        /** 需要的 Java 大版本（8/17/21/25）；认不出是 17（与旧行为一致的保守默认） */
        val javaMajor: Int,
        /** 为什么这么判（写进日志/界面，便于用户核对） */
        val evidence: String,
    ) {
        companion object {
            val UNKNOWN = Info(false, "CUSTOM", "", 17, "无法识别")
        }
    }

    /** 单文件上限：再大就不是「玩家手选的 jar」了（多半选错了目录） */
    private const val MAX_JAR_BYTES = 512L * 1024 * 1024

    /**
     * 只做**快速体检**（不读全包）：给导入流程在复制前用，避免把明显不是 jar 的文件
     * 复制几十 MB 之后才发现。
     */
    fun quickCheck(file: File): Info {
        if (!file.isFile) return Info.UNKNOWN.copy(evidence = "不是文件")
        if (file.length() == 0L) return Info.UNKNOWN.copy(evidence = "文件为空")
        if (file.length() > MAX_JAR_BYTES) {
            return Info.UNKNOWN.copy(evidence = "文件过大（${file.length() / 1024 / 1024} MB）")
        }
        // zip 魔数 "PK"
        val magic = runCatching {
            file.inputStream().use { ins ->
                val b = ByteArray(2)
                if (ins.read(b) < 2) null else b
            }
        }.getOrNull()
        if (magic == null || magic[0] != 0x50.toByte() || magic[1] != 0x4B.toByte()) {
            return Info.UNKNOWN.copy(evidence = "不是 zip/jar 文件")
        }
        return inspect(file)
    }

    /** 完整识别（会读 zip 目录与少量元数据） */
    fun inspect(file: File): Info {
        val zip = runCatching { ZipFile(file) }.getOrElse {
            return Info.UNKNOWN.copy(evidence = "无法打开为 jar：${it.message}")
        }
        zip.use { z ->
            val names = z.entries().asSequence().map { it.name }.toList()
            val nameSet = names.toHashSet()
            val evidence = StringBuilder()

            // ① version.json（paperclip 系最可靠）
            var mcFromVersionJson = ""
            if ("version.json" in nameSet) {
                val text = readEntry(z, "version.json", limit = 64 * 1024)
                mcFromVersionJson = META_ID.find(text)?.groupValues?.get(1).orEmpty()
                if (mcFromVersionJson.isNotEmpty()) evidence.append("version.json id=$mcFromVersionJson; ")
            }

            // ② 旧版 paperclip 的 download-context（文件名里带版本）
            var mcFromDownloadContext = ""
            if ("META-INF/download-context" in nameSet) {
                val text = readEntry(z, "META-INF/download-context", limit = 8 * 1024)
                mcFromDownloadContext = MC_VERSION.find(text)?.value.orEmpty()
                if (mcFromDownloadContext.isNotEmpty()) {
                    evidence.append("download-context 含 $mcFromDownloadContext; ")
                }
            }

            // ③ MANIFEST
            var mainClass = ""
            var implTitle = ""
            if ("META-INF/MANIFEST.MF" in nameSet) {
                val mf = readEntry(z, "META-INF/MANIFEST.MF", limit = 64 * 1024)
                mainClass = MF_MAIN.find(mf)?.groupValues?.get(1)?.trim().orEmpty()
                implTitle = MF_TITLE.find(mf)?.groupValues?.get(1)?.trim().orEmpty()
                if (mainClass.isNotEmpty()) evidence.append("Main-Class=$mainClass; ")
            }
            // manifest 里常带构建版本（Paper 的 Implementation-Version 形如 git-Paper-196）
            var mcFromManifest = ""
            if ("META-INF/MANIFEST.MF" in nameSet) {
                val mf = readEntry(z, "META-INF/MANIFEST.MF", limit = 64 * 1024)
                mcFromManifest = MC_VERSION_IN_MF.find(mf)?.value.orEmpty()
            }

            // ④ 类路径特征
            val hasMcServerClass = "net/minecraft/server/MinecraftServer.class" in nameSet
            val hasCraftBukkitMain = "org/bukkit/craftbukkit/Main.class" in nameSet
            val hasBundlerList = names.any { it.startsWith("META-INF/libraries/") }
            if (hasMcServerClass) evidence.append("含 MinecraftServer.class; ")

            // ⑤ 核心类型
            val core = detectCore(
                mainClass = mainClass,
                implTitle = implTitle,
                names = nameSet,
                hasCraftBukkitMain = hasCraftBukkitMain,
                hasBundlerList = hasBundlerList,
            )

            val mc = mcFromVersionJson
                .ifEmpty { mcFromDownloadContext }
                .ifEmpty { mcFromManifest }
            if (mc.isNotEmpty() && mcFromVersionJson.isEmpty()) evidence.append("版本推断 $mc; ")

            val looksLikeServer = hasMcServerClass || hasCraftBukkitMain || mainClass.isNotEmpty() &&
                SERVER_MAIN_MARKERS.any { mainClass.contains(it) } ||
                core != "CUSTOM" || hasBundlerList

            val java = inferJavaMajor(z, names, mc)

            return Info(
                looksLikeServerJar = looksLikeServer,
                coreTypeName = core,
                mcVersion = mc,
                javaMajor = java,
                evidence = evidence.toString().trimEnd(' ', ';'),
            )
        }
    }

    // ── 核心类型识别 ──

    private val SERVER_MAIN_MARKERS = listOf(
        "paperclip", "craftbukkit", "bundler", "fabricmc", "forge", "neoforge",
        "spigot", "purpur",
    )

    private fun detectCore(
        mainClass: String,
        implTitle: String,
        names: Set<String>,
        hasCraftBukkitMain: Boolean,
        hasBundlerList: Boolean,
    ): String {
        val hay = (mainClass + " " + implTitle).lowercase()
        // 只能用 CoreType 里真实存在的值（VANILLA/PAPER/PURPUR/SPIGOT/FABRIC/FORGE/NEOFORGE/CUSTOM）。
        // Folia / Velocity / BungeeCord 这些没有对应枚举，一律归 CUSTOM —— 界面照样能用，
        // 只是不给它贴一个错误的"官方核心"标签。
        return when {
            hay.contains("purpur") -> "PURPUR"
            hay.contains("paperclip") || hay.contains("papermc") -> "PAPER"
            names.any { it.startsWith("net/neoforged/") } -> "NEOFORGE"
            names.any { it.startsWith("net/minecraftforge/") } -> "FORGE"
            names.any { it.startsWith("net/fabricmc/") } -> "FABRIC"
            hasCraftBukkitMain || hay.contains("craftbukkit") || hay.contains("spigot") -> "SPIGOT"
            // 官方 1.18+ 的服务端是 bundler 形态（先解出 libraries 再跑）
            hasBundlerList -> "VANILLA"
            names.any { it.startsWith("net/minecraft/") } -> "VANILLA"
            else -> "CUSTOM"
        }
    }

    // ── Java 版本推断 ──

    /**
     * class 文件版本 → Java 大版本；顺带看 manifest 的 `Build-Jdk`。
     *
     * 注意：**bundler / paperclip 的 jar 本身**可能用较新的 javac 编（比如 17），
     * 但它启动后下载的 vanilla 才是真正要跑的 —— 这里保守取"包内最大 class 版本"，
     * 因为 `AI`/`MC` 两侧都要求"能跑就行"，装高版本 Java 总比装低了报
     * UnsupportedClassVersionError 好。
     */
    private fun inferJavaMajor(zip: ZipFile, names: List<String>, mcVersion: String): Int {
        // ① 知道 MC 版本时**以它为准**。
        //    paperclip / bundler 这类 jar 本身只是启动器（常用较低版本 javac 编），
        //    真正要跑的是它下载下来的服务端 —— 那个才决定 Java 版本。
        //    只按包内 class 版本判，1.21.4 的 Paper 会被判成 Java 17，
        //    启动时直接 UnsupportedClassVersionError。
        if (mcVersion.isNotEmpty()) {
            val byMc = javaForMc(mcVersion)
            // MC 版本给出的结论不会低于 jar 内 class 版本（启动器本身也要能跑），取较大者
            val classMajor = jarClassMajor(zip, names)
            val byClass = when {
                classMajor >= 69 -> 25
                classMajor >= 65 -> 21
                classMajor >= 61 -> 17
                else -> 8
            }
            return maxOf(byMc, byClass)
        }
        // ② 不知道 MC 版本：只能按包内 class 版本判
        val major = jarClassMajor(zip, names)
        return when {
            major >= 69 -> 25
            major >= 65 -> 21
            major >= 61 -> 17
            major > 0 -> 8
            else -> 17
        }
    }

    /** 包内"最相关"的 class 版本（优先 MC/Bukkit/加载器包路径，否则任意一个 class） */
    private fun jarClassMajor(zip: ZipFile, names: List<String>): Int {
        val cls = names.firstOrNull {
            it.endsWith(".class") && !it.startsWith("META-INF/") &&
                (it.startsWith("net/minecraft/") || it.startsWith("org/bukkit/") ||
                    it.startsWith("io/papermc/") || it.startsWith("net/fabricmc/") ||
                    it.startsWith("net/minecraftforge/") || it.startsWith("net/neoforged/"))
        } ?: names.firstOrNull { it.endsWith(".class") && !it.startsWith("META-INF/") }
        return cls?.let { classMajorOf(zip, it) } ?: 0
    }

    /** MC 版本 → 官方要求的 Java 大版本（1.20.5+ 要 21，1.18–1.20.4 要 17，更早 8） */
    fun javaForMc(mcVersion: String): Int {
        val m = MC_VERSION.find(mcVersion) ?: return 17
        val parts = m.value.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size < 2) return 17
        val major = parts[0]
        val minor = parts[1]
        val patch = parts.getOrNull(2) ?: 0
        return when {
            major > 1 -> 21
            minor >= 21 -> 21          // 1.21+
            minor >= 20 && patch >= 5 -> 21  // 1.20.5+
            minor >= 18 -> 17          // 1.18 ~ 1.20.4
            else -> 8
        }
    }

    private fun classMajorOf(zip: ZipFile, entryName: String): Int = runCatching {
        val e = zip.getEntry(entryName) ?: return 0
        if (e.size < 8) return 0
        zip.getInputStream(e).use { ins ->
            val b = ByteArray(8)
            if (ins.read(b) < 8) return 0
            ((b[6].toInt() and 0xFF) shl 8) or (b[7].toInt() and 0xFF)
        }
    }.getOrDefault(0)

    // ── 小工具 ──

    private fun readEntry(zip: ZipFile, name: String, limit: Int): String = runCatching {
        val e = zip.getEntry(name) ?: return ""
        if (e.size > limit) return ""
        zip.getInputStream(e).use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrDefault("")

    private val META_ID = Regex(""""id"\s*:\s*"([^"]+)"""")
    private val MC_VERSION = Regex("""1\.\d+(?:\.\d+)?""")
    private val MC_VERSION_IN_MF = Regex("""(?:Implementation-Version|Specification-Version):\s*.*?(1\.\d+(?:\.\d+)?)""",
        RegexOption.IGNORE_CASE)
    private val MF_MAIN = Regex("""(?mi)^Main-Class:\s*(.+)$""")
    private val MF_TITLE = Regex("""(?mi)^Implementation-Title:\s*(.+)$""")
}
