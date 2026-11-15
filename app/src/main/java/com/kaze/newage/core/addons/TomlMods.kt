package com.kaze.newage.core.addons

/**
 * `mods.toml` / `neoforge.mods.toml` 的**最小** TOML 读取器。
 *
 * 只做一件事：把第一个 `[[mods]]` 块里的标量字段，以及指向 minecraft 的
 * `[[dependencies.<modid>]]` 版本范围读出来。用不上完整 TOML 规范（嵌套表、数组表、
 * 日期时间），引入一整套解析器只为几个字段不划算 —— 本项目 APK 体积是硬约束。
 *
 * 支持的真实写法：
 *  - `key = "str"` / `key = 'str'`
 *  - `key = """多行"""`（`'''` 同理）
 *  - `key = 1.0` / `key = true`（数值/布尔按原文保留）
 *  - 行内注释 `# ...`（引号内的 `#` 不算注释）
 *
 * 读不懂的行**跳过而不是报错**：真实 mods.toml 里常有我们不需要的字段，
 * 因为一个不认识的键就整份判定"元数据损坏"是错误的。
 */
internal object TomlMods {

    data class Mod(
        val modId: String? = null,
        val version: String? = null,
        val displayName: String? = null,
        val description: String? = null,
        val authors: List<String> = emptyList(),
        val logoFile: String? = null,
        /** 指向 minecraft 的 versionRange（原文） */
        val minecraftRange: String? = null,
        /** 其它必装依赖的 modId */
        val depends: List<String> = emptyList(),
    )

    private val SECTION = Regex("""^\[\[?\s*([^\]]+?)\s*\]\]?$""")

    fun parse(text: String): Mod? {
        val lines = text.lines()
        var section = ""
        var i = 0

        var modCount = 0
        var draft: Draft? = null
        var minecraftRange: String? = null
        val depends = LinkedHashSet<String>()

        // 当前依赖块的状态（`[[dependencies.<owner>]]`）
        var depOwner: String? = null
        var depModId: String? = null
        var depRange: String? = null
        var depType: String? = null

        fun flushDep() {
            if (depOwner != null && depModId == "minecraft" && !depRange.isNullOrBlank()) {
                minecraftRange = depRange
            } else if (depOwner != null && depModId != null) {
                // NeoForge 用 type 区分 required/optional；缺省按 required 处理（1.20 的 Forge 没有该字段）
                val t = depType?.lowercase()
                if (t == null || t == "required" || t == "both") depends += depModId!!
            }
            depModId = null
            depRange = null
            depType = null
        }

        while (i < lines.size) {
            val line = stripComment(lines[i]).trim()
            i++
            if (line.isEmpty()) continue

            val sectionMatch = SECTION.matchEntire(line)
            if (sectionMatch != null) {
                flushDep()
                section = sectionMatch.groupValues[1].trim()
                if (section == "mods") {
                    modCount++
                    if (modCount == 1) draft = Draft() else return build(draft, minecraftRange, depends)
                } else if (section.startsWith("dependencies.")) {
                    depOwner = section.removePrefix("dependencies.").trim()
                }
                continue
            }

            val eq = assignIndex(line) ?: continue
            val key = line.substring(0, eq).trim()
            var value = line.substring(eq + 1).trim()

            // 多行字符串：本行没闭合就继续吃后面的行，直到出现闭合的三引号
            if (value.startsWith("\"\"\"") || value.startsWith("'''")) {
                val quote = value.take(3)
                value = value.removePrefix(quote)
                if (!value.contains(quote)) {
                    val sb = StringBuilder(value.replace('\n', ' '))
                    var closed = false
                    while (i < lines.size) {
                        val next = lines[i]
                        i++
                        if (next.contains(quote)) {
                            sb.append('\n').append(next.substringBefore(quote))
                            closed = true
                            break
                        }
                        sb.append('\n').append(next)
                    }
                    if (!closed) {
                        // 没闭合：按已读到的内容用（不因此丢弃整个模组）
                        value = sb.toString()
                    } else {
                        value = sb.toString()
                    }
                } else {
                    value = value.substringBefore(quote).trim()
                }
            } else {
                value = unquote(value)
            }

            when {
                section == "mods" && draft != null -> draft.apply(key, value)
                depOwner != null -> when (key) {
                    "modId" -> depModId = value
                    "versionRange" -> depRange = value
                    "type" -> depType = value
                }
            }
        }
        flushDep()
        return build(draft, minecraftRange, depends)
    }

    private class Draft {
        var modId: String? = null
        var version: String? = null
        var displayName: String? = null
        var description: String? = null
        var authors: String? = null
        var logoFile: String? = null

        fun apply(key: String, value: String) {
            when (key.lowercase()) {
                "modid" -> modId = value
                "version" -> version = value
                "displayname" -> displayName = value
                "description" -> description = value
                "authors" -> authors = value
                "authorlist" -> authors = authors ?: value
                "logofile" -> logoFile = value
                else -> Unit
            }
        }
    }

    private fun build(draft: Draft?, minecraftRange: String?, depends: Set<String>): Mod? {
        val d = draft ?: return null
        val id = d.modId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val authors = d.authors?.split(',', ';')
            ?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        return Mod(
            modId = id,
            version = d.version?.trim(),
            displayName = d.displayName?.trim()?.takeIf { it.isNotEmpty() },
            description = d.description?.trim()?.takeIf { it.isNotEmpty() },
            authors = authors,
            logoFile = d.logoFile?.trim()?.takeIf { it.isNotEmpty() },
            minecraftRange = minecraftRange?.trim()?.takeIf { it.isNotEmpty() },
            depends = depends.filterNot { it == id }.toList(),
        )
    }

    // ── 词法小工具 ──

    /** 索引 0 是键名起点；找不到 `=` 返回 null */
    private fun assignIndex(line: String): Int? {
        var inBasic = false
        var inLiteral = false
        var idx = 0
        while (idx < line.length) {
            val c = line[idx]
            when {
                c == '\\' && inBasic -> idx++                     // 转义字符：跳过下一个
                c == '"' && !inLiteral -> inBasic = !inBasic
                c == '\'' && !inBasic -> inLiteral = !inLiteral
                c == '=' && !inBasic && !inLiteral -> return idx
            }
            idx++
        }
        return null
    }

    /** 去掉行内注释：`#` 在引号内时保留 */
    private fun stripComment(line: String): String {
        var inBasic = false
        var inLiteral = false
        var idx = 0
        while (idx < line.length) {
            val c = line[idx]
            when {
                c == '\\' && inBasic -> idx++
                c == '"' && !inLiteral -> inBasic = !inBasic
                c == '\'' && !inBasic -> inLiteral = !inLiteral
                c == '#' && !inBasic && !inLiteral -> return line.substring(0, idx)
            }
            idx++
        }
        return line
    }

    /** 单行值去引号；裸值（数字/布尔）原样返回 */
    private fun unquote(raw: String): String {
        val v = raw.trim()
        return when {
            v.length >= 2 && v.startsWith("\"") && v.endsWith("\"") ->
                v.substring(1, v.length - 1)
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                    .replace("\\n", "\n")
                    .replace("\\t", "\t")
            v.length >= 2 && v.startsWith("'") && v.endsWith("'") -> v.substring(1, v.length - 1)
            else -> v
        }
    }
}
