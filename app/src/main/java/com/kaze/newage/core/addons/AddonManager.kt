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

    private fun kindLabel(kind: AddonKind): String = if (kind == AddonKind.PLUGIN) "插件" else "模组"

    /**
     * 文件名来自 Modrinth 返回的 JSON —— **不可信远端数据**。直接拼进路径时，
     * 形如 "../../x" 的名字会被系统解析到 plugins/ 之外，而安装里还有覆盖写；
     * 配合 MANAGE_EXTERNAL_STORAGE 足以覆盖任意共享存储文件。
     * 只取纯文件名（`File(...).name` 会剥掉所有目录部分），越界直接抛。
     */
    @Throws(Exception::class)
    private fun safeFileName(rawName: String, projectId: String): String {
        val base = rawName.ifBlank { "$projectId.jar" }
        val name = File(base).name
        if (name.isBlank() || name == "." || name == ".." ||
            name.any { it == '/' || it == '\\' || it == '\u0000' }
        ) {
            throw RuntimeException("插件文件名不合法：$rawName")
        }
        return name
    }

    /** 按加载器与 MC 版本挑出要装的那个版本（[install] 与 [targetFile] 共用同一套规则） */
    @Throws(Exception::class)
    private fun pickVersion(instance: ServerInstance, kind: AddonKind, projectId: String, gameVersion: String?): ModrinthVersion {
        val loader = loaderFor(instance, kind)
        val wanted = gameVersion?.takeIf { it.isNotBlank() }
        val versions = ModrinthApi.versions(projectId, loader, wanted)
            .ifEmpty {
                if (wanted != null) {
                    throw RuntimeException(
                        "该${kindLabel(kind)}没有适配 MC $wanted + $loader 的版本（换个 MC 版本或换一个项目）"
                    )
                }
                ModrinthApi.versions(projectId, loader)
            }
        return versions.firstOrNull() ?: throw RuntimeException("没有适配 $loader 的版本")
    }

    /**
     * 本次安装会写到的文件（净化文件名 + 越界校验之后）。
     *
     * 界面在真正下载前先用它算一次，才能对"会替换同名文件"这件事给出确认；
     * 规则与 [install] 完全一致，不允许外部把路径直接传进来。
     */
    @Throws(Exception::class)
    fun targetFile(instance: ServerInstance, kind: AddonKind, projectId: String, gameVersion: String?): File {
        val version = pickVersion(instance, kind, projectId, gameVersion)
        val file = version.files.firstOrNull { it.primary } ?: version.files.firstOrNull()
            ?: throw RuntimeException("版本无下载文件")
        val dir = addonDir(instance, kind)
        return File(dir, safeFileName(file.filename, projectId))
    }

    /** 待安装文件在目标目录里已存在（同名 = 会被替换），返回那个文件 */
    fun existingCollision(instance: ServerInstance, kind: AddonKind, target: File): File? =
        target.takeIf { it.isFile }

    /**
     * 下载并安装：取项目最新适配版本的主文件。
     *
     * `gameVersion` 为空 / 实例没写 MC 版本时才退化成"只要该加载器的版本"；
     * **有 MC 版本就必须匹配它**，匹配不到直接报错（见 [pickVersion]）。
     *
     * **同名文件会被替换**：Bukkit / Fabric 的惯例是"同名即同一份插件的新版本"，
     * 改名会让两份 jar 同时被加载（同一个插件加载两遍）。但替换绝不静默 ——
     * 调用方先用 [targetFile] 算出目标路径、用 [existingCollision] 判断是否存在，
     * 存在就先让用户确认，然后才调这里。
     *
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
        val version = pickVersion(instance, kind, projectId, gameVersion)
        val file = version.files.firstOrNull { it.primary } ?: version.files.firstOrNull()
            ?: throw RuntimeException("版本无下载文件")
        val rawName = file.filename.ifBlank { "$projectId.jar" }
        val safeName = safeFileName(file.filename, projectId)
        val dir = addonDir(instance, kind)
        val dest = File(dir, safeName)
        val canonicalDir = runCatching { dir.canonicalFile }.getOrElse { dir.absoluteFile }
        val canonicalDest = runCatching { dest.canonicalFile }.getOrElse { dest.absoluteFile }
        if (canonicalDest.parentFile?.path != canonicalDir.path) {
            throw RuntimeException("插件文件名越界：$rawName")
        }
        onProgress(0f, "下载 ${file.filename}（${version.version_number}）…")
        // 先下到临时名再原子换入：直写最终路径时，续传会把旧文件字节当前缀拼出损坏 jar，
        // 且服务器不认 Range 时会先截掉旧文件——失败后用户原有的可用插件就没了。
        // validate 用 ZIP 魔数（jar 均以 PK\x03\x04 开头），拦镜像 HTML 错误页。
        val part = File(dir, dest.name + ".part")
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
