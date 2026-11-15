package com.kaze.newage.core.addons

import com.kaze.newage.data.model.CoreType
import com.kaze.newage.data.model.ServerInstance

/**
 * 模组与实例的**兼容性提示**（只提示，不拦截）。
 *
 * 为什么不直接拒绝导入：Minecraft 的兼容性从来不是单选题 ——
 * Quilt 能跑 Fabric 模组、NeoForge 1.20.1 能吃一部分 Forge 模组、
 * 有些模组根本不写 mcversion 却能跨版本跑。用"猜测"去阻止用户导入，
 * 比让他导进去、启动时自己看崩溃日志更糟（至少后者信息更全）。
 *
 * 所以这里只产出一组 [Note]：界面用黄字列出来，让人知道"为什么它可能跑不起来"。
 */
object ModCompatibility {

    enum class Level { Info, Warn }

    data class Note(val text: String, val level: Level = Level.Warn)

    /** 加载器与当前核心的冲突说明；null = 没有可断言的冲突 */
    fun loaderConflict(coreType: CoreType, loader: ModLoader): String? {
        if (loader == ModLoader.UNKNOWN) return null
        return when (coreType) {
            CoreType.FABRIC -> when (loader) {
                ModLoader.FABRIC, ModLoader.QUILT -> null
                else -> "当前实例是 Fabric，${loader.label} 模组不能加载"
            }
            CoreType.FORGE -> when (loader) {
                ModLoader.FORGE -> null
                ModLoader.NEOFORGE -> "NeoForge 模组通常不能在 Forge 上运行（1.20.1 之后两者 API 已分叉）"
                else -> "当前实例是 Forge，${loader.label} 模组不能加载"
            }
            CoreType.NEOFORGE -> when (loader) {
                ModLoader.NEOFORGE -> null
                ModLoader.FORGE -> "Forge 模组在 NeoForge 上大多不兼容（只有少数 1.20.1 模组可以）"
                else -> "当前实例是 NeoForge，${loader.label} 模组不能加载"
            }
            CoreType.VANILLA, CoreType.PAPER, CoreType.PURPUR, CoreType.SPIGOT ->
                "当前核心（${coreType.displayName}）不读取 mods/ 目录，装进去也不会生效" +
                    if (coreType == CoreType.VANILLA) {
                        "；原版服务端只支持数据包（datapacks）"
                    } else {
                        "；这类服务端请用 plugins/"
                    }
            // 导入的 jar 认不出核心：不做断言
            CoreType.CUSTOM -> null
        }
    }

    /** 一组面向界面的提示（加载器 + MC 版本） */
    fun check(instance: ServerInstance, kind: AddonKind, meta: ModMetadata): List<Note> {
        val notes = mutableListOf<Note>()
        if (kind == AddonKind.MOD) {
            loaderConflict(instance.coreType, meta.loader)?.let { notes += Note(it) }
        }
        if (meta.gameVersionRange.isNotBlank() && instance.mcVersion.isNotBlank() &&
            !rangeMatches(meta.gameVersionRange, instance.mcVersion)
        ) {
            notes += Note(
                "模组声明适配 ${meta.gameVersionRange}，当前实例是 MC ${instance.mcVersion}" +
                    "（判不准的写法一律放行，所以这只是提示）",
            )
        }
        if (meta.loader == ModLoader.UNKNOWN && meta.source == ModMetadata.SOURCE_FILENAME) {
            notes += Note("读不出加载器信息（jar 里没有 fabric.mod.json / mods.toml）", Level.Info)
        }
        return notes
    }

    // ── 版本范围匹配 ──

    /**
     * 判断 [version] 是否落在 [range] 里。
     *
     * 支持的写法（都是 Modrinth / 各加载器真实会出现的）：
     *  - `*`、空串 → 永远匹配（没声明 = 不做断言）
     *  - 空格/逗号分隔的比较式：`>=1.21 <1.22`、`>1.20.1 <=1.21`
     *  - Maven 区间：`[1.20.1,1.21)`、`(1.20,1.21]`
     *  - 通配：`1.20.x`、`1.20.*`、`1.20`
     *  - 多选：`1.20.1 || 1.20.4`（任一命中即可）
     *
     * **判不出来时返回 true**（宁可放行也不误报）：模组版本号里带 `-beta.3`、
     * `+build.5`、甚至 `1.21.1-pre1` 这种尾巴，解析器猜错一次就会给出错误警告，
     * 而错误警告会让用户删掉本来能用的模组。
     */
    fun rangeMatches(range: String, version: String): Boolean {
        val r = range.trim()
        val v = version.trim()
        if (r.isEmpty() || v.isEmpty()) return true
        if (r == "*" || r.equals("any", ignoreCase = true)) return true
        val alternatives = r.split("||")
        return alternatives.any { alternativeMatches(it.trim(), v) }
    }

    private fun alternativeMatches(alt: String, version: String): Boolean {
        if (alt.isEmpty()) return true
        if (alt.startsWith("[") || alt.startsWith("(")) {
            return mavenRangeMatches(alt, version)
        }
        val tokens = alt.split(Regex("[\\s,]+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return true
        return tokens.all { tokenMatches(it, version) }
    }

    private fun mavenRangeMatches(alt: String, version: String): Boolean {
        val last = alt.last()
        if (last != ']' && last != ')') return true      // 写法不完整：不做断言
        val body = alt.substring(1, alt.length - 1)
        val parts = body.split(',')
        if (parts.isEmpty()) return true
        val lower = parts.getOrNull(0)?.trim().orEmpty()
        val upper = parts.getOrNull(1)?.trim().orEmpty()
        val lowerOk = lower.isEmpty() || run {
            val c = compare(version, lower)
            if (alt[0] == '[') c >= 0 else c > 0
        }
        val upperOk = upper.isEmpty() || run {
            val c = compare(version, upper)
            if (last == ']') c <= 0 else c < 0
        }
        return lowerOk && upperOk
    }

    private val OPERATOR = Regex("""^(>=|<=|==|=|>|<|~|\^)?\s*(.+)$""")

    private fun tokenMatches(token: String, version: String): Boolean {
        val m = OPERATOR.matchEntire(token.trim()) ?: return true
        val op = m.groupValues[1]
        val raw = m.groupValues[2].trim()
        if (raw.isEmpty()) return true
        // 完全读不出数字（如 "release"）：放弃断言
        if (comps(raw).isEmpty()) return true

        // 通配：1.20.x / 1.20.*
        if (raw.contains('x') || raw.contains('X') || raw.contains('*')) {
            return prefixMatches(raw.lowercase(), version)
        }
        val target = comps(raw)
        val current = comps(version)
        if (current.isEmpty()) return true
        val c = compareComps(current, target)
        return when (op) {
            "", "=", "==" -> c == 0
            ">" -> c > 0
            ">=" -> c >= 0
            "<" -> c < 0
            "<=" -> c <= 0
            // ~1.20.1 → [1.20.1, 1.21)；^1.20 → [1.20, 2.0)。两者在模组声明里都很常见，
            // 这里统一按"当前段 +1"近似：宁可把边界放宽，也不误报不兼容。
            "~" -> c >= 0 && compareComps(current, bump(target, target.size - 1)) < 0
            "^" -> c >= 0 && compareComps(current, bump(target, 0)) < 0
            else -> true
        }
    }

    /** `1.20.x` 表示"前三段数字相同"；`*` 段不比较 */
    private fun prefixMatches(pattern: String, version: String): Boolean {
        val p = pattern.split('.', '-', '+').map { it.trim() }
        val v = comps(version).map { it.toString() }
        val vRaw = version.split('.', '-', '+').map { it.trim() }
        p.forEachIndexed { idx, part ->
            if (part.isEmpty() || part == "*" || part == "x") return@forEachIndexed
            val expected = Regex("""^\d+""").find(part)?.value ?: return true
            val actual = vRaw.getOrNull(idx)?.let { Regex("""^\d+""").find(it)?.value } ?: return false
            if (actual != expected) return false
        }
        return true
    }

    /** 把 target 的第 [index] 段 +1，后面的段清零（用于 ~ / ^ 的上界） */
    private fun bump(target: List<Int>, index: Int): List<Int> {
        if (index < 0) return target
        val out = target.take(index + 1).toMutableList()
        out[index] = out[index] + 1
        return out
    }

    /** 数字段：`1.21.1-pre1` → [1, 21, 1]；`1.20.4+build` → [1, 20, 4] */
    internal fun comps(v: String): List<Int> = v.trim()
        .split('.', '-', '+', '_', ' ')
        .mapNotNull { part -> Regex("""^\d+""").find(part.trim())?.value?.toIntOrNull() }

    private fun compare(a: String, b: String): Int = compareComps(comps(a), comps(b))

    private fun compareComps(a: List<Int>, b: List<Int>): Int {
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return if (x < y) -1 else 1
        }
        return 0
    }
}
