package com.kaze.newage.core.bedrock

import com.kaze.newage.data.model.ServerInstance
import com.kaze.newage.util.Downloader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.Locale

/** 一个可下载的基岩版服务端版本 */
data class BedrockRelease(
    val version: String,
    /** 支持的基岩版客户端版本，例如 1.26.60 */
    val minecraftVersion: String,
    val jarUrl: String,
    val sizeBytes: Long,
    val sha256: String,
    val publishedAt: String,
    val notes: String = "",
) {
    val fileName: String get() = jarUrl.substringAfterLast('/').ifBlank { "powernukkitx.jar" }
}

/**
 * 基岩版服务端：**PowerNukkitX**（Java 实现，因此能跑在本应用的 proot + Java 环境里）。
 *
 * 为什么不直接用 Mojang 官方的 Bedrock Dedicated Server：
 * 官方 BDS 只提供 **Linux x86_64 / Windows x86_64** 二进制，手机上（ARM64）根本执行不了
 * —— 它不是"下载了就能跑"的东西，下载选项摆出来只会让人白下载 100 MB。
 * PowerNukkitX 是社区维护的 Java 版基岩服务端，走的是本应用已有的 Java 运行时，
 * 在 ARM 手机上能真正启动，这才是可用的"基岩版开服"。
 *
 * 版本信息取自 GitHub Releases（公开 API、无需密钥）；下载地址指向 release 资产，
 * 用应用内已有的多线程下载器（带进度、校验 ZIP 魔数）。
 */
object BedrockServer {

    private const val API = "https://api.github.com/repos/PowerNukkitX/PowerNukkitX/releases/latest"

    /** 仓库主页：界面上的"更多信息"入口 */
    const val HOMEPAGE = "https://github.com/PowerNukkitX/PowerNukkitX"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 取最新正式版（`/releases/latest` 已排除预发布版） */
    fun latest(): BedrockRelease {
        val body = Downloader.downloadText(API, timeoutMs = 20000)
        val root = json.parseToJsonElement(body).jsonObject
        val tag = root["tag_name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val assets = root["assets"]?.jsonArray?.map { it.jsonObject }.orEmpty()
        val jar = assets.firstOrNull { a ->
            val name = a["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
            name.endsWith(".jar", ignoreCase = true)
        } ?: throw RuntimeException("这个 release 里没有 jar 资产")
        val url = jar["browser_download_url"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val size = jar["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
        val digest = jar["digest"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val notes = root["body"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return BedrockRelease(
            version = tag.ifBlank { "unknown" },
            minecraftVersion = parseMinecraftVersion(notes),
            jarUrl = url,
            sizeBytes = size,
            sha256 = digest.removePrefix("sha256:"),
            publishedAt = root["published_at"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            notes = notes,
        )
    }

    /**
     * 从 release 说明里抠出 "Minecraft Version | 1.26.60" 这一行。
     * 取不到就返回空串 —— 界面显示"未知"，不影响下载与创建实例。
     */
    internal fun parseMinecraftVersion(notes: String): String {
        // release 说明是 Markdown 表格：`| **Minecraft Version** | 1.26.60 |`
        val match = Regex(
            """(?i)Minecraft\s*Version\**\s*\|?\s*\**\s*([0-9]+(?:\.[0-9]+){1,3})""",
        ).find(notes)
        if (match != null) return match.groupValues[1]
        // 退路：说明里偶尔只写了 "Add 1.26.60 support"
        return Regex("""(?i)(?:add|support(?:s|ed)?)\s+([0-9]+\.[0-9]+\.[0-9]+)""")
            .find(notes)?.groupValues?.getOrNull(1).orEmpty()
    }

    /**
     * 下载服务端 jar 到实例目录。
     *
     * @return 下载后的 jar 文件（实例的启动 jar）
     */
    fun download(
        instance: ServerInstance,
        release: BedrockRelease,
        onProgress: (Float, String) -> Unit = { _, _ -> },
    ): File {
        if (release.jarUrl.isBlank()) throw RuntimeException("下载地址为空")
        val dest = File(instance.dir, "powernukkitx.jar")
        val part = File(instance.dir, ".powernukkitx.jar.part")
        onProgress(0f, "下载 PowerNukkitX ${release.version}…")
        Downloader.download(
            release.jarUrl,
            part,
            onProgress = { done, total ->
                val p = if (total > 0) done.toFloat() / total else 0f
                onProgress(p, "下载中 ${human(done)} / ${human(total)}")
            },
            validate = { Downloader.isZip(it) },
        )
        if (dest.exists()) dest.delete()
        if (!part.renameTo(dest)) {
            part.copyTo(dest, overwrite = true)
            part.delete()
        }
        onProgress(1f, "已下载 ${dest.name}")
        return dest
    }

    /** 基岩版的默认端口是 19132（UDP），不是 Java 版的 25565 */
    const val DEFAULT_PORT = 19132

    private fun human(bytes: Long): String = when {
        bytes < 0 -> "?"
        bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.2f GB", bytes / 1024f / 1024f / 1024f)
        bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024f / 1024f)
        bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024f)
        else -> "$bytes B"
    }
}
