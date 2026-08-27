package com.kaze.newage.core.addons

import com.kaze.newage.data.model.CoreType
import com.kaze.newage.data.model.ServerInstance
import com.kaze.newage.util.Downloader
import java.io.File

/**
 * 附加组件（插件/模组）文件管理：
 *  - 插件目录：plugins/（Paper/Purpur/Spigot 系）
 *  - 模组目录：mods/（Fabric/Forge/NeoForge 系）
 *  - 启用/禁用 = 重命名 <name>.jar ⇄ <name>.jar.disabled（Bukkit/Fabric 惯例）
 */
object AddonManager {

    /** 该实例是否支持某类附加组件 */
    fun supports(instance: ServerInstance, kind: AddonKind): Boolean = when (kind) {
        AddonKind.PLUGIN -> instance.coreType in setOf(CoreType.PAPER, CoreType.PURPUR, CoreType.SPIGOT, CoreType.CUSTOM)
        AddonKind.MOD -> instance.coreType in setOf(CoreType.FABRIC, CoreType.FORGE, CoreType.NEOFORGE, CoreType.CUSTOM)
    }

    /** 对应的加载器名（Modrinth loaders 参数） */
    fun loaderFor(instance: ServerInstance, kind: AddonKind): String = when (kind) {
        AddonKind.PLUGIN -> when (instance.coreType) {
            CoreType.PURPUR -> "purpur"
            CoreType.SPIGOT -> "spigot"
            else -> "paper"
        }
        AddonKind.MOD -> when (instance.coreType) {
            CoreType.FORGE -> "forge"
            CoreType.NEOFORGE -> "neoforge"
            else -> "fabric"
        }
    }

    fun addonDir(instance: ServerInstance, kind: AddonKind): File =
        File(instance.dir, kind.dirName).apply { mkdirs() }

    /** 已安装组件文件（启用在前，禁用在后）。禁用产物 `<name>.jar.disabled` 必须包含在列——
     *  否则点停用后文件从列表蒸发，用户再也无法从界面恢复启用 */
    fun installed(instance: ServerInstance, kind: AddonKind): List<File> =
        addonDir(instance, kind).listFiles { f ->
            f.isFile && (f.name.endsWith(".jar", true) || f.name.endsWith(".jar.disabled", true))
        }?.sortedBy { it.name.endsWith(".jar.disabled", true) }
            ?: emptyList()

    /** 是否启用（未带 .disabled 后缀） */
    fun isEnabled(file: File): Boolean = !file.name.endsWith(".jar.disabled", true)

    /** 启用/禁用切换。目标名冲突时不覆盖（避免悄悄吞掉用户的另一份副本），返回当前实际状态 */
    fun toggleEnabled(file: File): Boolean {
        val enabled = isEnabled(file)
        val newName = if (enabled) file.name + ".disabled" else file.name.removeSuffix(".disabled")
        val target = File(file.parentFile, newName)
        if (target.exists()) return enabled
        return if (file.renameTo(target)) !enabled else enabled
    }

    fun delete(file: File): Boolean = file.delete()

    /**
     * 下载并安装：取项目最新适配版本的主文件。
     * @return 安装后的文件
     */
    @Throws(Exception::class)
    suspend fun install(
        instance: ServerInstance,
        kind: AddonKind,
        projectId: String,
        gameVersion: String?,
        onProgress: (Float, String) -> Unit,
    ): File {
        val loader = loaderFor(instance, kind)
        val versions = ModrinthApi.versions(projectId, loader, gameVersion?.takeIf { it.isNotBlank() })
            .ifEmpty { ModrinthApi.versions(projectId, loader) }
        val version = versions.firstOrNull() ?: throw RuntimeException("没有适配 $loader 的版本")
        val file = version.files.firstOrNull { it.primary } ?: version.files.firstOrNull()
            ?: throw RuntimeException("版本无下载文件")
        val dest = File(addonDir(instance, kind), file.filename.ifBlank { "$projectId.jar" })
        onProgress(0f, "下载 ${file.filename}（${version.version_number}）…")
        // 先下到临时名再原子换入：直写最终路径时，续传会把旧文件字节当前缀拼出损坏 jar，
        // 且服务器不认 Range 时会先截掉旧文件——失败后用户原有的可用插件就没了。
        // validate 用 ZIP 魔数（jar 均以 PK\x03\x04 开头），拦镜像 HTML 错误页。
        val part = File(addonDir(instance, kind), dest.name + ".part")
        Downloader.download(
            file.url,
            part,
            onProgress = { done, total ->
                val progress = if (total > 0) done.toFloat() / total else 0f
                onProgress(progress, "下载中 ${(done / 1024 / 1024)}MB / ${(total / 1024 / 1024)}MB")
            },
            validate = { f -> runCatching {
                f.inputStream().use { ins ->
                    val head = ByteArray(4)
                    ins.read(head) == 4 &&
                        head.contentEquals(byteArrayOf(0x50, 0x4B, 0x03, 0x04))
                }
            }.getOrDefault(false) },
        )
        if (dest.exists()) dest.delete()
        if (!part.renameTo(dest)) {
            part.copyTo(dest, overwrite = true)
            part.delete()
        }
        return dest
    }
}
