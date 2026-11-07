package com.kaze.newage.core.ai

import java.io.File

/**
 * 技能的**写入**能力（新建 / 导入 / 删除）。
 *
 * ## 为什么需要它
 * 之前用户只能自己把 md 文件拷进 `<实例目录>/skills/<名字>/SKILL.md` ——
 * 手机上要找一个能编辑 md 的文件管理器，还要记住目录结构与 frontmatter 格式。
 * 这里补上应用内创建，格式由应用保证。
 *
 * ## 安全边界（每条都对应一种真实的破坏）
 * 1. **目录名必须是安全的单段路径**：用户输入的名字可能带 `/`、`..`、控制字符，
 *    直接当目录名会写到 skills/ 外面去（例如 `../` 覆盖 server.properties）。
 * 2. **不覆盖已有技能**：[create] 遇到同名目录返回错误而不是覆盖 —— 技能是用户
 *    手写的文档，静默覆盖等于丢数据。要改就删了再建，或改名字。
 * 3. **大小上限**：技能会被塞进提示词，超大文件会挤掉真正有用的现场信息
 *    （见 [AiSkills.MAX_DESCRIPTION_CHARS] 的思路），这里限制正文长度。
 */
object SkillWriter {

    /** 正文上限：技能是"给 AI 看的说明书"，超过这个量级已经不是说明书了 */
    const val MAX_BODY_CHARS = 20_000

    /** 名字（目录名）上限，与常见文件系统的单段长度保持余量 */
    const val MAX_ID_LENGTH = 40

    /** 结果：成功带落地路径，失败带可展示的原因 */
    sealed interface Result {
        data class Ok(val id: String, val file: File) : Result
        data class Failed(val reason: String) : Result
    }

    /**
     * 把用户输入的名字规整成合法的目录名。
     *
     * 允许中文、字母、数字、`-`、`_`、空格；其余（`/`、`\`、`.`、`:` 等）一律替换成 `-`。
     * 结果为空或只剩符号时返回 null（由调用方提示"名字无效"）。
     */
    fun safeId(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val mapped = buildString {
            for (c in trimmed) {
                val ok = c.isLetterOrDigit() || c == '-' || c == '_' || c == ' '
                append(if (ok) c else '-')
            }
        }.trim().trim('.', '-', '_')
        if (mapped.isEmpty()) return null
        // 折叠连续的 -，避免 "a----b"
        val collapsed = mapped.replace(Regex("-{2,}"), "-").take(MAX_ID_LENGTH).trim()
        return collapsed.ifEmpty { null }
    }

    /**
     * 生成 SKILL.md 的完整内容。
     *
     * frontmatter 用 YAML：`name` 与 `description` 是 [AiSkills.scan] 解析的字段。
     * 正文原样保留 —— 用户可能写 markdown 表格、代码块，不该被改写。
     */
    fun buildContent(name: String, description: String, body: String): String {
        val safeName = name.trim().ifEmpty { "未命名技能" }
        val safeDesc = description.trim().replace("\n", " ")
        return buildString {
            append("---\n")
            append("name: ").append(oneLine(safeName)).append('\n')
            if (safeDesc.isNotEmpty()) {
                append("description: ").append(oneLine(safeDesc)).append('\n')
            }
            append("---\n\n")
            append(body.trimEnd()).append('\n')
        }
    }

    /** frontmatter 的值不能带换行（会把 YAML 结构破坏）；引号也去掉避免解析歧义 */
    private fun oneLine(s: String): String =
        s.replace("\n", " ").replace("\"", "'").trim()

    /**
     * 新建一个技能。
     *
     * @param instanceDir 实例目录（技能落在 `<实例目录>/skills/<id>/SKILL.md`）
     * @return Ok / Failed；**同名已存在时返回 Failed，不覆盖**
     */
    fun create(
        instanceDir: File,
        name: String,
        description: String,
        body: String,
    ): Result {
        val id = safeId(name) ?: return Result.Failed("名字无效：至少包含一个字母、数字或汉字")
        if (body.isBlank()) return Result.Failed("正文不能为空：写点这个技能要 AI 记住什么")
        if (body.length > MAX_BODY_CHARS) {
            return Result.Failed("正文太长（${body.length} 字符，上限 $MAX_BODY_CHARS）")
        }
        val root = File(instanceDir, "skills")
        val dir = File(root, id)
        if (dir.exists()) {
            return Result.Failed("已存在同名技能「$id」：换一个名字，或先删除再新建")
        }
        return runCatching {
            if (!dir.mkdirs()) return Result.Failed("无法创建目录（存储权限或空间不足）")
            val file = File(dir, AiSkills.SKILL_FILE)
            file.writeText(buildContent(name, description, body))
            Result.Ok(id, file)
        }.getOrElse { Result.Failed("写入失败：${it.message ?: it.javaClass.simpleName}") }
    }

    /**
     * 从已有文本导入（用户在文件选择器里挑了一个 .md）。
     *
     * 与 [create] 的区别：内容原样写入，**不重新生成 frontmatter** ——
     * 导入的文件可能已经有自己的 frontmatter，重写会丢字段。
     * 只有完全没有 frontmatter 时才补一个（否则 [AiSkills.scan] 拿不到描述）。
     */
    fun importText(instanceDir: File, fileName: String, text: String): Result {
        if (text.isBlank()) return Result.Failed("文件是空的")
        if (text.length > MAX_BODY_CHARS * 2) {
            return Result.Failed("文件太大（${text.length} 字符）")
        }
        val base = fileName.substringBeforeLast('.').ifEmpty { fileName }
        val id = safeId(base) ?: return Result.Failed("文件名无法用作技能名：$fileName")
        val root = File(instanceDir, "skills")
        val dir = File(root, id)
        if (dir.exists()) {
            return Result.Failed("已存在同名技能「$id」：改名后重试，或先删除旧的")
        }
        return runCatching {
            if (!dir.mkdirs()) return Result.Failed("无法创建目录（存储权限或空间不足）")
            val file = File(dir, AiSkills.SKILL_FILE)
            val normalized = text.replace("\r\n", "\n")
            val content = if (normalized.trimStart().startsWith("---")) {
                normalized
            } else {
                // 没有 frontmatter：补一个，用文件名当 name、正文首行当 description
                val firstLine = normalized.lineSequence()
                    .firstOrNull { it.isNotBlank() }?.trim()?.take(120).orEmpty()
                buildContent(base, firstLine, normalized)
            }
            file.writeText(content)
            Result.Ok(id, file)
        }.getOrElse { Result.Failed("写入失败：${it.message ?: it.javaClass.simpleName}") }
    }

    /** 删除技能（整个目录）；名字先过 [safeId] 防止越界删除 */
    fun delete(instanceDir: File, id: String): Result {
        val safe = safeId(id) ?: return Result.Failed("技能名无效")
        val dir = File(File(instanceDir, "skills"), safe)
        // 二次确认：解析后的父目录必须是 skills/ 本身，杜绝 ".." 之类的越界
        val root = runCatching { File(instanceDir, "skills").canonicalFile }.getOrNull()
        val target = runCatching { dir.canonicalFile }.getOrNull()
        if (root == null || target == null || target.parentFile != root) {
            return Result.Failed("路径越界")
        }
        if (!dir.exists()) return Result.Failed("技能不存在")
        return runCatching {
            if (!dir.deleteRecursively()) return Result.Failed("删除失败（文件被占用？）")
            Result.Ok(safe, dir)
        }.getOrElse { Result.Failed("删除失败：${it.message ?: it.javaClass.simpleName}") }
    }
}