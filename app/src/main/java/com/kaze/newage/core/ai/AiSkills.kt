package com.kaze.newage.core.ai

import java.io.File

/**
 * 技能（skills）：用户放在实例目录 `skills/` 下的 md 文档，AI 需要时自己去读。
 *
 * ## 为什么是"清单 + 按需读取"，而不是把全文塞进提示词
 * 提示词每轮都会发给模型。把每个技能的全文都塞进去，几个技能就能把上下文占满，
 * 而且大部分轮次根本用不上。所以：
 *  - 系统提示里只放**清单**（名字 + 一句话描述 + 文件路径）；
 *  - AI 判断某个技能相关时，用已有的 `read_file` 工具读 `skills/<名字>/SKILL.md` 全文。
 * 这样不新增任何工具、也不改动工具权限模型（读文件已经受既有策略约束）。
 *
 * ## 目录约定
 * ```
 * <实例目录>/skills/<技能名>/SKILL.md
 * ```
 * 与 Claude / DSH 的 `SKILL.md` 约定一致，用户可以直接把别处的技能目录拷进来。
 *
 * ## 为什么只有本地
 * 用户明确要求"本地目录导入 md"：不联网、不内置商店、不做网络下载。
 * 技能是用户自己的文件，应用只负责发现、展示和开关。
 */
object AiSkills {

    /** 技能文件名（大小写不敏感匹配） */
    const val SKILL_FILE = "SKILL.md"

    /** 单条技能的描述上限（塞进提示词里，太长会挤掉真正有用的现场信息） */
    private const val MAX_DESCRIPTION_CHARS = 200

    /** 清单里最多列多少个技能（防止用户塞 200 个把提示词撑爆） */
    const val MAX_SKILLS_IN_PROMPT = 40

    /** 一个已发现的技能 */
    data class Skill(
        /** 目录名（也是 AI 读文件时要用的路径段） */
        val id: String,
        /** 展示名：frontmatter 的 name，没有就用目录名 */
        val name: String,
        /** 一句话描述：frontmatter 的 description，没有就取正文首行 */
        val description: String,
        /** SKILL.md 相对实例目录的路径（给 read_file 用） */
        val relativePath: String,
        /** 文件大小，用于过滤明显放错的大文件 */
        val sizeBytes: Long,
    ) {

        /** 给提示词用的一行 */
        fun promptLine(): String = "- $name：$description（读取 `$relativePath`）"
    }

    /** 扫描结果 */
    data class Scan(
        val skills: List<Skill>,
        /** 扫描中遇到的问题（目录不存在不算问题） */
        val warnings: List<String> = emptyList(),
    )

    /**
     * 扫描实例目录下的 skills/。
     *
     * 刻意**宽容**：一个坏技能不该让整个清单消失 —— 解析失败只记 warning，
     * 其余技能照常可用（与 AiProfileStore 的"损坏不静默吞掉"同一套思路）。
     */
    fun scan(instanceDir: File?): Scan {
        if (instanceDir == null) return Scan(emptyList())
        val root = File(instanceDir, "skills")
        if (!root.isDirectory) return Scan(emptyList())

        val warnings = mutableListOf<String>()
        val out = mutableListOf<Skill>()

        val entries = root.listFiles()?.sortedBy { it.name.lowercase() }.orEmpty()
        for (entry in entries) {
            if (!entry.isDirectory) continue
            val md = findSkillFile(entry)
            if (md == null) {
                warnings += "${entry.name}：缺少 $SKILL_FILE"
                continue
            }
            // 明显是放错的东西（几百 KB 的 md 不该当技能）
            if (md.length() > 512 * 1024) {
                warnings += "${entry.name}：$SKILL_FILE 过大（${md.length() / 1024} KB），已跳过"
                continue
            }
            val text = runCatching { md.readText() }.getOrNull()
            if (text == null) {
                warnings += "${entry.name}：$SKILL_FILE 读不出来"
                continue
            }
            val (name, description) = parseHeader(entry.name, text)
            out += Skill(
                id = entry.name,
                name = name,
                description = description,
                relativePath = "skills/${entry.name}/${md.name}",
                sizeBytes = md.length(),
            )
        }
        return Scan(out, warnings)
    }

    /** 技能文件的大小写兼容查找（用户从 Windows 拷进来可能叫 skill.md） */
    private fun findSkillFile(dir: File): File? =
        dir.listFiles()?.firstOrNull { it.isFile && it.name.equals(SKILL_FILE, ignoreCase = true) }

    /**
     * 解析 frontmatter 的 `name` / `description`；没有 frontmatter 就退回
     * "目录名 + 正文第一段非空行"，这样随手写的 md 也能用。
     */
    internal fun parseHeader(dirName: String, text: String): Pair<String, String> {
        val normalized = text.replace("\r\n", "\n")
        var name = dirName
        var description = ""

        if (normalized.startsWith("---")) {
            val end = normalized.indexOf("\n---", startIndex = 3)
            if (end > 0) {
                val front = normalized.substring(3, end)
                for (raw in front.split('\n')) {
                    val line = raw.trim()
                    val colon = line.indexOf(':')
                    if (colon <= 0) continue
                    val key = line.substring(0, colon).trim().lowercase()
                    val value = line.substring(colon + 1).trim().removeSurrounding("\"").removeSurrounding("'")
                    when (key) {
                        "name" -> if (value.isNotEmpty()) name = value
                        "description" -> if (value.isNotEmpty()) description = value
                    }
                }
            }
        }

        if (description.isEmpty()) {
            // 取正文第一段非空、非标题、非代码围栏的行。
            // 只有**确实以 frontmatter 开头**时才跳过它的结束标记 —— 否则正文里的
            // markdown 分隔线（`---`）会被误当成 frontmatter 结束，把前半截正文切掉。
            val body = if (normalized.startsWith("---")) {
                val end = normalized.indexOf("\n---", startIndex = 3)
                if (end > 0) normalized.substring(end + 4) else normalized
            } else {
                normalized
            }
            description = body.split('\n')
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("```") }
                .orEmpty()
        }

        return name.trim() to description.trim().take(MAX_DESCRIPTION_CHARS)
    }

    /**
     * 生成注入系统提示词的技能清单段；没有可用技能时返回空串（调用方据此不插这段）。
     *
     * [disabledIds] 里的是用户关掉的技能 —— 不列出来，AI 就不会去读。
     */
    fun promptBlock(scan: Scan, disabledIds: Set<String>): String {
        val enabled = scan.skills.filterNot { it.id in disabledIds }
        if (enabled.isEmpty()) return ""
        val shown = enabled.take(MAX_SKILLS_IN_PROMPT)
        return buildString {
            append("\n\n【可用技能（用户放在实例 skills/ 目录的说明文档）】\n")
            append("这些是**用户自己写的操作指南**，与技能相关的任务请先读取对应文件再按其中步骤执行；")
            append("不相关就不要读（读文件会把内容发给模型服务商）。\n")
            shown.forEach { append(it.promptLine()).append('\n') }
            if (enabled.size > shown.size) {
                append("（另有 ${enabled.size - shown.size} 个技能未列出）\n")
            }
        }
    }
}
