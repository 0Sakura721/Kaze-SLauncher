package com.kaze.newage.core.bedrock

import com.kaze.newage.data.model.ServerInstance
import com.kaze.newage.util.Downloader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipFile

/** 基岩版附加包（.mcpack / .mcaddon 解出来的一个包） */
data class BedrockPack(
    val name: String,
    val uuid: String,
    /** 版本数组（写进 world_*_packs.json 要用 [1,0,0] 形式） */
    val version: List<Int>,
    val kind: Kind,
    val dir: File,
    val iconFile: File?,
    val description: String = "",
    val minEngine: String = "",
    val active: Boolean = false,
) {
    enum class Kind(val label: String, val dirName: String) {
        RESOURCE("资源包", "resource_packs"),
        BEHAVIOR("行为包", "behavior_packs"),
    }

    val versionText: String get() = version.joinToString(".")
}

/**
 * 基岩版附加包管理。
 *
 * 与 Java 版模组的根本差别：基岩版没有"丢进 mods/ 就加载"这回事。
 *  - 包是 `.mcpack`（zip，含 `manifest.json` + `pack_icon.png`）；
 *  - 安装 = 解压到 `resource_packs/` 或 `behavior_packs/`（看 manifest 里 modules 的 type）；
 *  - **启用** = 把 pack_id + 版本写进世界目录的 `world_resource_packs.json` /
 *    `world_behavior_packs.json`。只解压不写这两个 JSON 的话，服务器根本不会用这个包 ——
 *    这是很多教程里最容易漏的一步。
 *
 * 图标直接读包里的 `pack_icon.png`（基岩版规范要求有这个文件），
 * 所以列表里每个包都有真实图标。
 */
object BedrockPacks {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private const val MAX_ENTRIES = 100_000
    private const val MAX_PACK_BYTES = 512L * 1024 * 1024

    data class Failure(val source: String, val reason: String)

    data class ImportReport(val imported: List<BedrockPack> = emptyList(), val failed: List<Failure> = emptyList()) {
        fun merge(other: ImportReport) = ImportReport(imported + other.imported, failed + other.failed)
    }

    fun packs(instance: ServerInstance): List<BedrockPack> {
        val activeIds = activePackIds(instance)
        return Kind2.all.flatMap { kind ->
            val dir = File(instance.dir, kind.dirName)
            dir.listFiles()?.filter { it.isDirectory }?.mapNotNull { sub ->
                readPackDir(instance, kind, sub, activeIds)
            }.orEmpty()
        }.sortedWith(compareBy({ it.kind.ordinal }, { it.name.lowercase(Locale.US) }))
    }

    /** `resource_packs/` 与 `behavior_packs/` 两个目录 */
    fun dirs(instance: ServerInstance): List<File> =
        Kind2.all.map { File(instance.dir, it.dirName).apply { mkdirs() } }

    private object Kind2 {
        val all = BedrockPack.Kind.entries
    }

    private fun readPackDir(
        instance: ServerInstance,
        kind: BedrockPack.Kind,
        dir: File,
        activeIds: Set<String>,
    ): BedrockPack? {
        val manifest = runCatching { parseManifest(File(dir, "manifest.json")) }.getOrNull() ?: return null
        val icon = File(dir, "pack_icon.png").takeIf { it.isFile }
            ?: File(dir, "pack_icon.jpg").takeIf { it.isFile }
        return BedrockPack(
            name = manifest.name,
            uuid = manifest.uuid,
            version = manifest.version,
            kind = kind,
            dir = dir,
            iconFile = icon,
            description = manifest.description,
            minEngine = manifest.minEngine,
            active = manifest.uuid.isNotBlank() && manifest.uuid.lowercase(Locale.US) in activeIds,
        )
    }

    // ── 导入 ──

    /**
     * 导入 `.mcpack`（单包）或 `.mcaddon`（多包合集）。
     *
     * 处理三种真实形态：
     *  1. 单包 zip 的根目录就有 `manifest.json`；
     *  2. 合集里每个包各占一个文件夹（`packA/manifest.json`）；
     *  3. 合集里嵌了多个 `.mcpack` 文件（少见但存在）。
     */
    fun import(instance: ServerInstance, source: File, displayName: String = source.name): ImportReport {
        if (!Downloader.isZip(source)) {
            return ImportReport(failed = listOf(Failure(displayName, "不是有效的 .mcpack/.mcaddon（zip 校验失败）")))
        }
        if (source.length() > MAX_PACK_BYTES) {
            return ImportReport(failed = listOf(Failure(displayName, "包超过 512 MB，已中止")))
        }
        val work = File(instance.dir, ".kaze-bedrock-" + UUID.randomUUID().toString().take(8)).apply { mkdirs() }
        return try {
            ZipFile(source).use { zip ->
                val entries = zip.entries().asSequence().toList()
                val nested = entries.filter { !it.isDirectory && it.name.lowercase(Locale.US).endsWith(".mcpack") }
                if (nested.isNotEmpty()) {
                    var report = ImportReport()
                    nested.forEachIndexed { idx, entry ->
                        val tmp = File(work, "nested-$idx.mcpack")
                        zip.getInputStream(entry).use { ins ->
                            FileOutputStream(tmp).use { outs -> ins.copyTo(outs, 64 * 1024) }
                        }
                        report = report.merge(import(instance, tmp, "${displayName}/${File(entry.name).name}"))
                    }
                    return report
                }
                val manifests = entries.filter {
                    !it.isDirectory && (it.name == "manifest.json" || it.name.endsWith("/manifest.json"))
                }
                if (manifests.isEmpty()) {
                    return ImportReport(failed = listOf(Failure(displayName, "包里没有 manifest.json，不是基岩版附加包")))
                }
                var report = ImportReport()
                manifests.forEachIndexed { idx, manifestEntry ->
                    val prefix = manifestEntry.name.removeSuffix("manifest.json")
                    val parsed = runCatching {
                        zip.getInputStream(manifestEntry).use { ins ->
                            parseManifest(ins.readBytes().toString(Charsets.UTF_8))
                        }
                    }.getOrElse {
                        report = report.merge(
                            ImportReport(failed = listOf(Failure(displayName, "manifest.json 解析失败：${it.message}"))),
                        )
                        return@forEachIndexed
                    }
                    val kind = parsed.kind
                    val targetBase = File(instance.dir, kind.dirName).apply { mkdirs() }
                    val safe = safeDirName(parsed.name.ifBlank { displayName.removeSuffix(".mcpack") })
                    var target = File(targetBase, safe)
                    var n = 2
                    while (target.exists()) {
                        target = File(targetBase, "$safe-$n")
                        n++
                    }
                    target.mkdirs()
                    var count = 0
                    val canonicalBase = target.canonicalFile
                    entries.forEach { entry ->
                        if (entry.isDirectory) return@forEach
                        if (!entry.name.startsWith(prefix)) return@forEach
                        val rel = entry.name.removePrefix(prefix)
                        if (rel.isBlank()) return@forEach
                        val out = File(target, rel)
                        val canonicalOut = out.canonicalFile
                        if (!canonicalOut.path.startsWith(canonicalBase.path + File.separator)) return@forEach
                        out.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { ins ->
                            FileOutputStream(out).use { outs -> ins.copyTo(outs, 128 * 1024) }
                        }
                        count++
                        if (count > MAX_ENTRIES) throw RuntimeException("包内文件过多")
                    }
                    report = report.merge(
                        ImportReport(
                            imported = listOf(
                                BedrockPack(
                                    name = parsed.name,
                                    uuid = parsed.uuid,
                                    version = parsed.version,
                                    kind = kind,
                                    dir = target,
                                    iconFile = File(target, "pack_icon.png").takeIf { it.isFile },
                                    description = parsed.description,
                                    minEngine = parsed.minEngine,
                                ),
                            ),
                        ),
                    )
                }
                report
            }
        } catch (e: Exception) {
            ImportReport(failed = listOf(Failure(displayName, e.message ?: "导入失败")))
        } finally {
            runCatching { work.deleteRecursively() }
        }
    }

    fun delete(pack: BedrockPack): Boolean = runCatching { pack.dir.deleteRecursively() }.getOrDefault(false)

    /**
     * 把「当前启用集合」写进世界目录。传入的列表里带 active=true 的包会被启用，
     * 其余从 json 里移除。没有启用的行为包/资源包时写空数组（而不是删文件）——
     * 空数组表达"明确不使用任何包"，删文件在部分服务端上会被当成"沿用上次"。
     */
    fun applyActive(instance: ServerInstance, packs: List<BedrockPack>): Boolean {
        val worldName = com.kaze.newage.core.world.WorldManager.activeLevelName(instance)
        val worldDir = File(instance.dir, worldName)
        if (!worldDir.isDirectory) return false
        val byKind = packs.groupBy { it.kind }
        return BedrockPack.Kind.entries.all { kind ->
            val list = byKind[kind].orEmpty()
            val body = list.joinToString(",", prefix = "[", postfix = "]") { p ->
                """{"pack_id":"${p.uuid}","version":[${p.version.joinToString(",")}]}"""
            }
            val file = File(worldDir, if (kind == BedrockPack.Kind.RESOURCE) {
                "world_resource_packs.json"
            } else {
                "world_behavior_packs.json"
            })
            runCatching {
                val tmp = File(worldDir, file.name + ".tmp")
                tmp.writeText(body)
                if (file.exists()) file.delete()
                if (!tmp.renameTo(file)) {
                    tmp.copyTo(file, overwrite = true)
                    tmp.delete()
                }
                true
            }.getOrDefault(false)
        }
    }

    private fun activePackIds(instance: ServerInstance): Set<String> {
        val worldDir = File(instance.dir, com.kaze.newage.core.world.WorldManager.activeLevelName(instance))
        val ids = mutableSetOf<String>()
        listOf("world_resource_packs.json", "world_behavior_packs.json").forEach { name ->
            val file = File(worldDir, name)
            if (!file.isFile) return@forEach
            runCatching {
                json.parseToJsonElement(file.readText()).jsonArray.forEach { el ->
                    (el as? JsonObject)?.get("pack_id")?.jsonPrimitive?.contentOrNull?.let { ids += it.lowercase(Locale.US) }
                }
            }
        }
        return ids
    }

    // ── manifest 解析 ──

    private data class Manifest(
        val name: String,
        val uuid: String,
        val version: List<Int>,
        val description: String,
        val minEngine: String,
        val kind: BedrockPack.Kind,
    )

    private fun parseManifest(file: File): Manifest =
        file.inputStream().use { parseManifest(it.readBytes().toString(Charsets.UTF_8)) }

    private fun parseManifest(text: String): Manifest {
        val root = json.parseToJsonElement(text).jsonObject
        val header = root["header"]?.jsonObject ?: throw RuntimeException("manifest 缺少 header")
        val modules = root["modules"]?.jsonArray?.map { it.jsonObject }.orEmpty()
        // 有 resources 模块 → 资源包；否则（data/script）→ 行为包。
        // 一个包同时含两类模块时按资源包处理（PNX 会把它当资源包加载，行为要另拆一个包）。
        val kind = if (modules.any { it["type"]?.jsonPrimitive?.contentOrNull == "resources" }) {
            BedrockPack.Kind.RESOURCE
        } else {
            BedrockPack.Kind.BEHAVIOR
        }
        return Manifest(
            name = header["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            uuid = header["uuid"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            version = versionList(header["version"]),
            description = header["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            minEngine = versionList(header["min_engine_version"]).joinToString("."),
            kind = kind,
        )
    }

    private fun versionList(el: kotlinx.serialization.json.JsonElement?): List<Int> =
        runCatching { el!!.jsonArray.map { it.jsonPrimitive.content.toInt() } }.getOrElse { listOf(1, 0, 0) }

    private fun safeDirName(raw: String): String {
        val cleaned = raw.trim()
            .replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_")
            .replace(Regex("""\s+"""), "_")
            .take(48)
        return cleaned.ifBlank { "pack-" + System.currentTimeMillis() % 100000 }
    }
}
