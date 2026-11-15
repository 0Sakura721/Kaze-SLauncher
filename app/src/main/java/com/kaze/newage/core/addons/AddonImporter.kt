package com.kaze.newage.core.addons

import com.kaze.newage.data.model.ServerInstance
import com.kaze.newage.util.Downloader
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipFile

/**
 * 本地导入模组 / 插件：把用户从浏览器、QQ、U 盘拿到的 jar 放进实例目录。
 *
 * 存在的理由：Modrinth 搜索只在联网、且上游有该模组时有用。
 * 国内玩家大量模组来自群文件 / 网盘 / 整合包，V0.9 之前完全没有入口 ——
 * 只能靠文件管理器手动塞进 `Android/data` 里的实例目录（多数机型根本进不去）。
 *
 * 三条硬规则：
 *  1. **永不越界写**：目标文件名只取纯文件名，且最终路径必须在 `mods/`、`plugins/` 之内；
 *  2. **先校验再落盘**：ZIP 魔数（拦 HTML 错误页 / 下载一半的文件）+ 大小上限；
 *  3. **不静默覆盖**：同名不同内容默认跳过并在结果里标成"冲突"，
 *     由界面问过用户之后再用 `overwrite = true` 重跑。
 *
 * 压缩包（`.zip` / `.mrpack`）会被展开：整合包把模组放在 `mods/` 或 `overrides/mods/`，
 * 逐个导入的效果远好于让用户自己解压再选几十次。
 */
object AddonImporter {

    /** 单个文件上限（模组本体极少超过 200 MB；1 GB 是防"误选了系统镜像"的安全阀） */
    private const val MAX_SINGLE_BYTES = 1024L * 1024 * 1024

    /** 一次最多从压缩包里展开多少个 jar */
    private const val MAX_ARCHIVE_ENTRIES = 500

    data class Imported(
        val fileName: String,
        val replaced: Boolean,
        val meta: ModMetadata,
        val notes: List<ModCompatibility.Note> = emptyList(),
    )

    /**
     * 没导入的文件及原因。
     * [conflict] = 只是"同名文件已存在"，用户确认后可以覆盖 —— 界面据此弹确认框，
     * 与真正的失败（损坏、超大、越界）区分开。
     */
    data class Skipped(
        val sourceName: String,
        val reason: String,
        val conflict: Boolean = false,
    )

    data class Report(
        val imported: List<Imported> = emptyList(),
        val skipped: List<Skipped> = emptyList(),
        val failed: List<Skipped> = emptyList(),
    ) {
        val okCount: Int get() = imported.size
        val isEmpty: Boolean get() = imported.isEmpty() && skipped.isEmpty() && failed.isEmpty()

        /** 有同名冲突：界面应该问一句"要替换吗" */
        val hasConflict: Boolean get() = skipped.any { it.conflict }

        fun merge(other: Report): Report = Report(
            imported = imported + other.imported,
            skipped = skipped + other.skipped,
            failed = failed + other.failed,
        )
    }

    /** 是否是单个模组 jar（`.jar` 或已禁用的 `.jar.disabled`） */
    fun isJarName(name: String): Boolean {
        val lower = name.lowercase(Locale.US)
        return lower.endsWith(".jar") || lower.endsWith(".jar.disabled")
    }

    private fun isArchiveName(name: String): Boolean {
        val lower = name.lowercase(Locale.US)
        return lower.endsWith(".zip") || lower.endsWith(".mrpack")
    }

    /**
     * 导入一个**已在磁盘上**的文件（测试、从实例内部选文件、以及 SAF 落地后的临时文件都走这里）。
     *
     * 阻塞式（复制几百 MB 会花几秒到几十秒）：调用方负责放到 IO 线程上。
     */
    fun importFromFile(
        instance: ServerInstance,
        kind: AddonKind,
        source: File,
        overwrite: Boolean = false,
        onProgress: (Float, String) -> Unit = { _, _ -> },
    ): Report {
        onProgress(0f, source.name)
        return when {
            isJarName(source.name) -> importOne(instance, kind, source.name, source, overwrite, onProgress)
            isArchiveName(source.name) || Downloader.isZip(source) ->
                expandArchive(instance, kind, source, overwrite, onProgress)
            else -> Report(
                failed = listOf(
                    Skipped(source.name, "不是 .jar 模组文件，也不是 .zip / .mrpack 整合包"),
                ),
            )
        }
    }

    /**
     * 从输入流导入（SAF 的 `content://` 只能给出流，拿不到真实路径）。
     *
     * 先落到实例目录里的临时文件再走 [importFromFile]：这样校验、去重、原子换入
     * 全都复用同一套逻辑，不需要为流单独实现一遍（也就不会出现两套规则不一致）。
     * [displayName] 是 SAF 给的展示名（可能没有扩展名），用来决定目标文件名。
     */
    fun importFromStream(
        instance: ServerInstance,
        kind: AddonKind,
        displayName: String,
        input: InputStream,
        overwrite: Boolean = false,
        onProgress: (Float, String) -> Unit = { _, _ -> },
    ): Report {
        val dir = AddonManager.addonDir(instance, kind)
        val hinted = displayName.ifBlank { "imported.jar" }
        // 临时文件必须**带着正确的名字**落到磁盘：目标文件名是从「source.name」推出来的，
        // 用 `.tmp` 之类的名字会让后续判定（.jar? .zip? 存档?）全走错分支。
        val work = File(dir, ".import-" + UUID.randomUUID().toString().take(8)).apply { mkdirs() }
        val tmp = File(work, safeImportName(hinted, kind))
        return try {
            tmp.outputStream().use { out ->
                val buf = ByteArray(128 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    total += n
                    if (total > MAX_SINGLE_BYTES) {
                        return Report(
                            failed = listOf(Skipped(hinted, "文件超过 1 GB，已中止")),
                        )
                    }
                    out.write(buf, 0, n)
                    onProgress(-1f, hinted)
                }
            }
            importFromFile(instance, kind, tmp, overwrite, onProgress).let { report ->
                // 结果里的来源名换成用户能认出来的展示名（临时名对用户没有意义）
                report.copy(
                    skipped = report.skipped.map { it.copy(sourceName = hinted) },
                    failed = report.failed.map {
                        it.copy(sourceName = if (it.sourceName == tmp.name) hinted else it.sourceName)
                    },
                )
            }
        } catch (e: Exception) {
            Report(failed = listOf(Skipped(hinted, e.message ?: "读取文件失败")))
        } finally {
            runCatching { work.deleteRecursively() }
        }
    }

    // ── 单个 jar ──

    private fun importOne(
        instance: ServerInstance,
        kind: AddonKind,
        rawName: String,
        source: File,
        overwrite: Boolean,
        onProgress: (Float, String) -> Unit,
    ): Report {
        val displayName = File(rawName).name
        if (!source.isFile) {
            return Report(failed = listOf(Skipped(displayName, "文件不存在")))
        }
        if (source.length() <= 0L) {
            return Report(failed = listOf(Skipped(displayName, "文件是空的（0 字节）")))
        }
        if (source.length() > MAX_SINGLE_BYTES) {
            return Report(failed = listOf(Skipped(displayName, "文件超过 1 GB，已中止")))
        }
        if (!Downloader.isZip(source)) {
            return Report(
                failed = listOf(Skipped(displayName, "不是有效的 jar（可能是 HTML 错误页或下载不完整）")),
            )
        }

        val dir = AddonManager.addonDir(instance, kind)
        val targetName = sanitizeJarName(displayName, kind)
        val target = File(dir, targetName)
        val disabledTwin = File(dir, "$targetName.disabled")
        val existing = when {
            target.isFile -> target
            disabledTwin.isFile -> disabledTwin
            else -> null
        }

        val sha1 = Downloader.sha1Of(source)
            ?: return Report(failed = listOf(Skipped(displayName, "计算文件哈希失败（读不出来）")))

        // 内容完全相同的副本：不论叫什么名字都不重复导入（玩家常把同一个模组拖两次）
        findDuplicate(dir, sha1, source.length(), exclude = existing)?.let { dup ->
            return Report(
                skipped = listOf(Skipped(displayName, "与已安装的「${dup.name}」内容相同，已跳过")),
            )
        }

        val replaced = existing != null
        if (replaced) {
            val sameContent = Downloader.sha1Of(existing) == sha1
            if (sameContent) {
                return Report(
                    skipped = listOf(Skipped(displayName, "「${existing.name}」已经是这个文件，无需导入")),
                )
            }
            if (!overwrite) {
                return Report(
                    skipped = listOf(
                        Skipped(
                            displayName,
                            "同名文件已存在：${existing.name}（内容不同）",
                            conflict = true,
                        ),
                    ),
                )
            }
        }

        onProgress(0.6f, "写入 $targetName")
        // 先写 .part 再换入：复制中断不会留下半个 jar（半个 jar 会让服务端启动直接崩）
        val part = File(dir, ".$targetName.part")
        try {
            source.copyTo(part, overwrite = true)
            if (!Downloader.isZip(part)) {
                part.delete()
                return Report(failed = listOf(Skipped(displayName, "复制后的文件校验失败")))
            }
            existing?.delete()
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
        } catch (e: Exception) {
            runCatching { part.delete() }
            return Report(failed = listOf(Skipped(displayName, e.message ?: "写入失败")))
        }

        // 换掉同名文件后，旧的来源台账（projectId/versionId）已经不成立了 —— 清掉，
        // 否则「检查更新」会拿旧版本号去比新文件，报出莫名其妙的"可更新"
        if (replaced) runCatching { AddonRegistry.forget(instance, kind, target.name) }

        val meta = ModMetadataReader.read(target)
        val notes = if (kind == AddonKind.MOD) {
            ModCompatibility.check(instance, kind, meta)
        } else {
            emptyList()
        }
        onProgress(1f, targetName)
        return Report(
            imported = listOf(
                Imported(fileName = target.name, replaced = replaced, meta = meta, notes = notes),
            ),
        )
    }

    // ── 压缩包（整合包）──

    private fun expandArchive(
        instance: ServerInstance,
        kind: AddonKind,
        archive: File,
        overwrite: Boolean,
        onProgress: (Float, String) -> Unit,
    ): Report {
        val dir = AddonManager.addonDir(instance, kind)
        val work = File(dir, ".import-" + UUID.randomUUID().toString().take(8))
        work.mkdirs()
        var result = Report()
        try {
            ZipFile(archive).use { zip ->
                val entries = java.util.Collections.list(zip.entries())
                    .filter { !it.isDirectory }
                    .filter { isJarName(it.name) }
                    .filterNot { it.name.startsWith("__MACOSX/") }
                    .filterNot { it.name.contains("..") }
                    // 源码/javadoc 包不是模组本体；导进去只会让服务端多加载一个坏 jar
                    .filterNot {
                        val n = it.name.lowercase(Locale.US)
                        n.endsWith("-sources.jar") || n.endsWith("-javadoc.jar")
                    }
                    .take(MAX_ARCHIVE_ENTRIES)

                if (entries.isEmpty()) {
                    return Report(
                        failed = listOf(Skipped(archive.name, "压缩包里没有找到 .jar 模组文件")),
                    )
                }
                entries.forEachIndexed { index, entry ->
                    val base = File(entry.name).name
                    onProgress(
                        index.toFloat() / entries.size,
                        "展开 ${index + 1}/${entries.size}：$base",
                    )
                    val extracted = File(work, base)
                    val ok = runCatching {
                        zip.getInputStream(entry).use { ins ->
                            extracted.outputStream().use { out -> ins.copyTo(out) }
                        }
                        true
                    }.getOrDefault(false)
                    if (!ok) {
                        result = result.merge(
                            Report(failed = listOf(Skipped(base, "从压缩包读取失败"))),
                        )
                        return@forEachIndexed
                    }
                    result = result.merge(
                        importOne(instance, kind, base, extracted, overwrite) { _, _ -> },
                    )
                    runCatching { extracted.delete() }
                }
            }
        } catch (e: Exception) {
            result = result.merge(
                Report(failed = listOf(Skipped(archive.name, e.message ?: "读取压缩包失败"))),
            )
        } finally {
            runCatching { work.deleteRecursively() }
        }
        onProgress(1f, archive.name)
        return result
    }

    // ── 工具 ──

    /** 目录里是否已有一份内容相同的文件（先按大小过滤，避免每次导入都给几百 MB 的模组算哈希） */
    private fun findDuplicate(dir: File, sha1: String, size: Long, exclude: File?): File? {
        val candidates = dir.listFiles { f ->
            f.isFile && f.length() == size && f != exclude &&
                (isJarName(f.name) || f.name.lowercase(Locale.US).endsWith(".jar.disabled"))
        } ?: return null
        return candidates.firstOrNull { runCatching { Downloader.sha1Of(it) == sha1 }.getOrDefault(false) }
    }

    /**
     * 从 SAF 展示名推出临时文件名：jar 与整合包保持原扩展名，
     * 没有扩展名的（SAF 常见）补 `.jar` —— 否则后续判定会把它当成"不认识的格式"。
     */
    internal fun safeImportName(raw: String, kind: AddonKind): String {
        val base = File(raw).name
            .filterNot { it == '/' || it == '\\' || it == '\u0000' || it.isISOControl() }
            .trim()
        return when {
            base.isBlank() -> if (kind == AddonKind.MOD) "imported-mod.jar" else "imported-plugin.jar"
            isJarName(base) || isArchiveName(base) -> base
            else -> sanitizeJarName(base, kind)
        }
    }

    /**
     * 目标文件名：只取纯文件名、去掉路径分隔符与控制字符，并保证 `.jar` 结尾。
     *
     * 名字来自 SAF 的展示名 —— 属于**外部输入**（恶意文件名可以带 `../`），
     * 与 [AddonManager] 处理 Modrinth 文件名时是同一条防线。
     */
    internal fun sanitizeJarName(raw: String, kind: AddonKind): String {
        var name = File(raw.ifBlank { "imported.jar" }).name
            .filterNot { it == '/' || it == '\\' || it == '\u0000' || it.isISOControl() }
            .trim()
        if (name.isBlank() || name == "." || name == "..") {
            name = if (kind == AddonKind.MOD) "imported-mod.jar" else "imported-plugin.jar"
        }
        // 已禁用的文件按"启用"导入：用户点导入的意图是"装上并生效"
        if (name.lowercase(Locale.US).endsWith(".jar.disabled")) {
            name = name.dropLast(".disabled".length)
        }
        if (!name.lowercase(Locale.US).endsWith(".jar")) name += ".jar"
        return name
    }
}
