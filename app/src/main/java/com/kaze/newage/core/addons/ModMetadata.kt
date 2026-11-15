package com.kaze.newage.core.addons

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/** jar 里声明的加载器（决定「这个模组能不能装进当前实例」） */
enum class ModLoader(val label: String) {
    FABRIC("Fabric"),
    QUILT("Quilt"),
    FORGE("Forge"),
    NEOFORGE("NeoForge"),
    /** 老式 jar / 认不出元数据：只在文件名层面认识它，不做兼容性断言 */
    UNKNOWN("未知"),
    ;

    companion object {
        /** 从 `fabric.mod.json` 之外的地方拿到的原始字符串（如 TOML 的 modLoader 字段） */
        fun of(raw: String?): ModLoader {
            val s = raw?.trim()?.lowercase(Locale.US).orEmpty()
            return when {
                s.contains("fabric") -> FABRIC
                s.contains("quilt") -> QUILT
                s.contains("neoforge") -> NEOFORGE
                s.contains("forge") || s.contains("javafml") || s.contains("lowcodefml") -> FORGE
                else -> UNKNOWN
            }
        }
    }
}

/**
 * 从一个 jar 里读出来的模组/插件元数据。
 *
 * 全部字段都**允许为空**：真实世界里的 jar 千奇百怪（老模组只有 `mcmod.info`、
 * 手搓的 jar 连 MANIFEST 都没有）。读不出来时退化成文件名，界面照样能列出、
 * 显示图标与版本，只是不做兼容性断言 —— 不能因为读不到元数据就拒绝导入。
 */
data class ModMetadata(
    /** 模组的命名空间 id（如 `lithium`）；读不到时等于文件名 */
    val modId: String,
    /** 展示名（`fabric.mod.json` 的 name / TOML 的 displayName）；读不到时等于文件名 */
    val name: String,
    val version: String = "",
    val description: String = "",
    val authors: List<String> = emptyList(),
    val loader: ModLoader = ModLoader.UNKNOWN,
    /**
     * 声明的 MC 版本范围原文，例如 `>=1.21 <1.22`、`[1.20.1,1.21)`、`1.20.x`。
     * 空 = 没声明（不做版本断言）。
     */
    val gameVersionRange: String = "",
    /** 硬依赖的其它 modId（fabric 的 depends / forge 的 dependencies，不含 minecraft 与 java） */
    val depends: List<String> = emptyList(),
    /**
     * 声明的运行环境：`client` / `server` / `*`（fabric.mod.json 的 environment）。
     * 空串 = 没声明。`client` 是"客户端专用模组"最可靠的信号之一。
     */
    val environment: String = "",
    /** jar 内图标条目路径；null = 这个 jar 没带图标 */
    val iconEntry: String? = null,
    /** 元数据来自哪个文件（`fabric.mod.json` / `META-INF/mods.toml` / 文件名…）；用于诊断 */
    val source: String = "",
) {
    val displayName: String get() = name.ifBlank { modId }

    val hasMetadata: Boolean get() = source != SOURCE_FILENAME

    /** 作者串（多个用 `, ` 连接），没有就空串 */
    val authorText: String get() = authors.joinToString(", ")

    companion object {
        /** [source] 的哨兵值：元数据完全读不出来、只剩文件名 */
        const val SOURCE_FILENAME = "文件名"
    }
}

/**
 * 模组 jar 元数据读取器。
 *
 * 支持四种真实存在的元数据格式（按加载器各自的历史）：
 *  1. `fabric.mod.json`（Fabric）—— JSON
 *  2. `quilt.mod.json`（Quilt）—— JSON，包一层 `quilt_loader`
 *  3. `META-INF/mods.toml` / `META-INF/neoforge.mods.toml`（Forge / NeoForge）—— TOML
 *  4. `mcmod.info`（1.12 及更早的 Forge）—— JSON（存在数组/对象两种写法）
 *
 * 为什么不用第三方 TOML 库：需要读的只有 `[[mods]]` 块里的五六个标量键，
 * 为一个字段引入整套 TOML 解析器不划算（APK 体积在本项目是硬约束）。
 *
 * **结果按 (路径, 大小, 修改时间) 缓存**：模组列表在滚动时会被反复重组，
 * 每次重组都重开 zip + 解析 JSON 会让列表明显发涩（几十个模组时尤其明显）。
 * 文件被替换（大小或时间变了）时缓存自动失效。
 */
object ModMetadataReader {

    /** 单个元数据文件读入上限：5 MB 已经远超任何真实 mods.toml / fabric.mod.json */
    private const val MAX_META_BYTES = 5L * 1024 * 1024

    /** 缓存条目上限：超过就整体清空（模组目录里不会有几百个以上同时活跃的文件） */
    private const val MAX_CACHE = 512

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private data class CacheKey(val path: String, val size: Long, val mtime: Long)

    private val cache = ConcurrentHashMap<CacheKey, ModMetadata>()

    /**
     * 读取元数据；**永远返回结果**（读不出来就退化成文件名）。
     *
     * 之所以不返回 null：调用方（列表行、导入预览、客户端整合包清单）都需要
     * 一个可展示的名字。让每个调用点各写一次 `?: file.name` 只会到处出现不一致。
     */
    fun read(file: File): ModMetadata {
        val key = CacheKey(file.absolutePath, file.length(), file.lastModified())
        cache[key]?.let { return it }
        val parsed = runCatching { parse(file) }.getOrNull() ?: fallback(file)
        if (cache.size >= MAX_CACHE) cache.clear()
        cache[key] = parsed
        return parsed
    }

    /** 清空缓存（测试与「导入后立刻刷新」用） */
    fun invalidate() = cache.clear()

    private fun parse(file: File): ModMetadata? {
        if (!file.isFile) return null
        ZipFile(file).use { zip ->
            val names = runCatching { java.util.Collections.list(zip.entries()).map { it.name } }.getOrNull()
                ?: return null
            val nameSet = names.toHashSet()

            parseFabric(zip, nameSet)?.let { return it.withIcon(zip, names) }
            parseQuilt(zip, nameSet)?.let { return it.withIcon(zip, names) }
            parseForgeToml(zip, nameSet, "META-INF/neoforge.mods.toml", ModLoader.NEOFORGE)
                ?.let { return it.withIcon(zip, names) }
            parseForgeToml(zip, nameSet, "META-INF/mods.toml", ModLoader.FORGE)
                ?.let { return it.withIcon(zip, names) }
            parseMcmodInfo(zip, nameSet)?.let { return it.withIcon(zip, names) }

            // 没有元数据：仍然可能带图标（部分手搓 jar / 资源包式插件）
            return null
        }
    }

    /** 元数据缺失时的兜底；图标仍然尽量从 jar 里找（见 [ModIconCache]） */
    fun fallback(file: File): ModMetadata {
        val base = file.name
            .removeSuffix(".disabled")
            .removeSuffix(".jar")
            .ifBlank { file.name }
        val version = VERSION_IN_NAME.find(base)?.value.orEmpty()
        return ModMetadata(
            modId = base,
            name = base,
            version = version,
            source = ModMetadata.SOURCE_FILENAME,
        )
    }

    /** 从文件名尾部猜版本号（`JourneyMap-5.9.7.jar` → `5.9.7`）；猜不到就是空串 */
    private val VERSION_IN_NAME = Regex("""\d+(?:\.\d+){1,3}(?:[-+][0-9A-Za-z.]+)?""")

    // ── Fabric ──

    private fun parseFabric(zip: ZipFile, names: Set<String>): ModMetadata? {
        if ("fabric.mod.json" !in names) return null
        val obj = readJsonObject(zip, "fabric.mod.json") ?: return null
        val id = obj.str("id") ?: return null
        val dependsObj = runCatching { obj["depends"] as? JsonObject }.getOrNull()
        val depends = dependsObj?.keys
            ?.filterNot { it == "minecraft" || it == "java" || it == "fabricloader" }
            ?.toList().orEmpty()
        return ModMetadata(
            modId = id,
            name = obj.str("name") ?: id,
            version = obj.str("version").orEmpty(),
            description = obj.freeText("description"),
            authors = obj.personList("authors"),
            loader = ModLoader.FABRIC,
            gameVersionRange = dependsObj?.get("minecraft")?.let { rangeText(it) }.orEmpty(),
            depends = depends,
            environment = obj.str("environment").orEmpty(),
            iconEntry = iconFromFabricIcon(obj["icon"]),
            source = "fabric.mod.json",
        )
    }

    /**
     * `fabric.mod.json` 的 icon 有两种写法：
     *  - 字符串：`"icon": "assets/x/icon.png"`
     *  - 尺寸表：`"icon": {"32": "assets/x/icon_32.png", "128": "..."}` —— 取**最大**那张
     *    （列表里显示 40dp，但用户点开详情看大图时小图会糊）
     */
    private fun iconFromFabricIcon(el: JsonElement?): String? {
        return when (el) {
            is JsonPrimitive -> el.contentOrNull?.takeIf { it.isNotBlank() }
            is JsonObject -> el.entries
                .mapNotNull { (k, v) ->
                    val size = k.toIntOrNull() ?: (k.substringBefore('x').toIntOrNull() ?: 0)
                    val path = (v as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                    size to path
                }
                .maxByOrNull { it.first }
                ?.second
            else -> null
        }
    }

    // ── Quilt ──

    private fun parseQuilt(zip: ZipFile, names: Set<String>): ModMetadata? {
        if ("quilt.mod.json" !in names) return null
        val root = readJsonObject(zip, "quilt.mod.json") ?: return null
        val loader = root["quilt_loader"] as? JsonObject ?: return null
        val id = loader.str("id") ?: return null
        val meta = loader["metadata"] as? JsonObject
        val dependsObj = (loader["depends"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        val mcRange = dependsObj.firstOrNull { it.str("id") == "minecraft" }
            ?.let { rangeText(it["versions"]) }.orEmpty()
        return ModMetadata(
            modId = id,
            name = meta?.str("name") ?: loader.str("name") ?: id,
            version = loader.str("version").orEmpty(),
            description = meta?.freeText("description").orEmpty(),
            authors = meta?.personList("contributors").orEmpty(),
            loader = ModLoader.QUILT,
            gameVersionRange = mcRange,
            depends = dependsObj.mapNotNull { it.str("id") }
                .filterNot { it == "minecraft" || it == "java" || it == "quilt_loader" },
            iconEntry = meta?.let { iconFromFabricIcon(it["icon"]) },
            source = "quilt.mod.json",
        )
    }

    // ── Forge / NeoForge（TOML）──

    private fun parseForgeToml(
        zip: ZipFile,
        names: Set<String>,
        entry: String,
        loader: ModLoader,
    ): ModMetadata? {
        if (entry !in names) return null
        val text = readText(zip, entry) ?: return null
        val toml = TomlMods.parse(text) ?: return null
        val id = toml.modId ?: return null
        return ModMetadata(
            modId = id,
            name = toml.displayName ?: id,
            version = toml.version.orEmpty(),
            description = toml.description.orEmpty(),
            authors = toml.authors,
            loader = loader,
            gameVersionRange = toml.minecraftRange.orEmpty(),
            depends = toml.depends,
            iconEntry = toml.logoFile,
            source = entry,
        )
    }

    // ── 1.12 及更早的 mcmod.info ──

    private fun parseMcmodInfo(zip: ZipFile, names: Set<String>): ModMetadata? {
        if ("mcmod.info" !in names) return null
        val text = readText(zip, "mcmod.info") ?: return null
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() ?: return null
        val first = when (root) {
            is JsonArray -> root.firstOrNull() as? JsonObject
            is JsonObject -> (root["modList"] as? JsonArray)?.firstOrNull() as? JsonObject
                ?: root.takeIf { it.str("modid") != null }
            else -> null
        } ?: return null
        val id = first.str("modid") ?: first.str("modId") ?: return null
        val authors = first.personList("authorList").ifEmpty {
            first.str("author")?.split(',', ';')?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
        }
        return ModMetadata(
            modId = id,
            name = first.str("name") ?: id,
            version = first.str("version").orEmpty(),
            description = first.freeText("description"),
            authors = authors,
            loader = ModLoader.FORGE,
            gameVersionRange = first.str("mcversion").orEmpty(),
            depends = first.personList("requiredMods").map { it.substringBefore('@') },
            iconEntry = first.str("logoFile")?.takeIf { it.isNotBlank() },
            source = "mcmod.info",
        )
    }

    // ── 图标条目定位 ──

    /** 把声明的图标路径校验成 jar 里真实存在的条目；没有声明时扫一遍常见路径 */
    private fun ModMetadata.withIcon(zip: ZipFile, names: List<String>): ModMetadata {
        val set = names.toHashSet()
        val declared = iconEntry
            ?.removePrefix("/")
            ?.takeIf { it in set }
        if (declared != null) return copy(iconEntry = declared)
        val found = findIconEntry(modId, names)
        return copy(iconEntry = found)
    }

    /**
     * 在 jar 里找图标。优先 `assets/<modId>/{icon,logo}*`，其次任意 `assets/<任意命名空间>/icon*`，
     * 最后根目录的 icon/logo/pack.png。
     *
     * 真实模组里图标路径五花八门（`assets/foo/icon.png`、`assets/foo/textures/icon.png`、
     * `logo.png`），所以这里不是"猜一个路径"，而是按偏好给所有候选排序后取第一个存在的。
     */
    fun findIconEntry(modId: String, names: List<String>): String? {
        val candidates = names.filter { name ->
            (ICON_ENTRY.matches(name) || ROOT_ICON.matches(name)) && !name.contains("/../")
        }
        if (candidates.isEmpty()) return null
        val id = modId.lowercase(Locale.US)
        return candidates.minByOrNull { name ->
            val lower = name.lowercase(Locale.US)
            var score = 100
            if (lower.startsWith("assets/$id/")) score -= 40
            else if (lower.startsWith("assets/")) score -= 10
            else if (!lower.contains('/')) score -= 20          // 根目录 icon.png
            if (lower.contains("icon")) score -= 10
            if (lower.endsWith(".png")) score -= 5
            score + name.count { it == '/' }                     // 浅路径优先
        }
    }

    private val ICON_ENTRY = Regex(
        """^assets/[^/]+/(?:[^/]+/){0,2}(?:icon|logo|pack|mod_?icon)[^/]*\.(?:png|jpg|jpeg)$""",
        RegexOption.IGNORE_CASE,
    )

    /** 根目录的 icon.png / logo.png（很多 Forge 模组把 logo 直接放在 jar 根） */
    private val ROOT_ICON = Regex(
        """^(?:icon|logo|pack|mod_?icon)[^/]*\.(?:png|jpg|jpeg)$""",
        RegexOption.IGNORE_CASE,
    )

    // ── 读 zip 内文本/JSON ──

    private fun readText(zip: ZipFile, entry: String): String? {
        val e = zip.getEntry(entry) ?: return null
        if (e.size > MAX_META_BYTES) return null
        return runCatching {
            zip.getInputStream(e).use { ins ->
                ins.readBytes().toString(Charsets.UTF_8)
            }
        }.getOrNull()
    }

    private fun readJsonObject(zip: ZipFile, entry: String): JsonObject? =
        readText(zip, entry)?.let { text ->
            runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
        }

    // ── JSON 小工具 ──

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    /** 文本字段既可能是字符串，也可能是 `{"en_us": "..."}` 这种按语言分组的表 */
    private fun JsonObject.freeText(key: String): String {
        return when (val el = this[key]) {
            is JsonPrimitive -> el.contentOrNull.orEmpty()
            is JsonObject -> el.str("en_us") ?: el.str("zh_cn") ?: el.str("zh_tw")
                ?: el.entries.firstNotNullOfOrNull { (_, v) -> (v as? JsonPrimitive)?.contentOrNull }
                .orEmpty()
            else -> ""
        }
    }

    /** 作者列表既可能是字符串数组，也可能是 `{name, contact}` 对象数组 */
    private fun JsonObject.personList(key: String): List<String> {
        val el = this[key] ?: return emptyList()
        val arr = when (el) {
            is JsonArray -> el
            is JsonPrimitive -> return el.contentOrNull?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() }
                .orEmpty()
            else -> return emptyList()
        }
        return arr.mapNotNull { item ->
            when (item) {
                is JsonPrimitive -> item.contentOrNull
                is JsonObject -> item.str("name")
                else -> null
            }
        }.filter { it.isNotBlank() }
    }

    /**
     * 依赖/版本字段可能写成字符串，也可能写成数组（quilt 的 `versions`）。
     * 数组按 `||` 连接 —— 语义上是"任一匹配"，交给 [ModCompatibility] 解析。
     */
    private fun rangeText(el: JsonElement?): String {
        return when (el) {
            is JsonPrimitive -> el.contentOrNull.orEmpty()
            is JsonArray -> el.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.joinToString(" || ")
            else -> ""
        }
    }
}
