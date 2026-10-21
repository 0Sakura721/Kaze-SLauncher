package com.kaze.newage.core.ai

/**
 * 每个工具的权限策略（借鉴 Operit：**每个工具单独设 Allow automatically / Ask every time / Deny**）。
 *
 * ## 为什么不复用 [AiCommandPolicy]
 * 那个管的是"控制台命令"这一件事（模型给一条 MC 命令、要不要替你发出去），三档语义是
 * "建议 / 白名单自动 / 全部自动"。这里管的是"**工具要不要执行**"，是另一层：
 * 同一个 `read_file`，你可以让它自动读，也可以每次问，也可以彻底禁止。
 * 两者叠加：`execute_command` 工具本身由 [AiCommandPolicy] 决定建议还是自动执行，
 * 所以它**不在这张表里**（避免两套档位互相打架）。
 *
 * ## 与"敏感文件确认"的关系
 * 敏感文件（凭据类 / ops.json / 带 rcon 的 server.properties）的读取确认是**独立的安全底线**
 * （防的是"玩家聊天/网页诱导 AI 读配置发给服务商"），不随本策略放宽：
 * 即使把 `read_file` 设成自动，命中敏感判定时仍会弹确认。
 *
 * ## 存储格式
 * 一个字符串：`toolId=modeId;toolId=modeId`（如 `read_file=ask;write_file=auto`）。
 * 只存被改动过的项也行 —— [modeOf] 对缺失项回落到工具默认值，所以升级新增工具不会受影响。
 */
object AiToolPolicy {

    enum class Mode(val id: String, val label: String, val desc: String) {
        AUTO(
            "auto", "自动",
            "AI 需要时直接执行，不打扰你",
        ),
        ASK(
            "ask", "每次询问",
            "执行前弹一张确认卡，你点「允许」才执行",
        ),
        DENY(
            "deny", "禁止",
            "永不执行；会告诉模型该工具已被你禁用（避免它反复重试）",
        ),
    }

    /** 一个可配置的工具。`defaultMode` 是没配置过时的档位 */
    data class Tool(
        val id: String,
        val label: String,
        val desc: String,
        val defaultMode: Mode,
    )

    /**
     * 默认只读的自动、写盘的要问 —— 与改造前的行为一致（写文件本来就必须确认），
     * 所以升级到本策略不会悄悄放宽任何已有边界。
     */
    val TOOLS: List<Tool> = listOf(
        Tool(
            "read_file", "读取文件",
            "读取实例目录里的文本文件（内容会发给模型服务商）",
            Mode.AUTO,
        ),
        Tool(
            "list_dir", "列出目录",
            "列出实例目录下的文件清单",
            Mode.AUTO,
        ),
        Tool(
            "fetch_page", "抓取网页",
            "抓取一个 http(s) 页面并回喂模型",
            Mode.AUTO,
        ),
        Tool(
            "write_file", "写入文件",
            "写入/覆盖实例目录里的文本文件（会改你的文件）",
            Mode.ASK,
        ),
    )

    private val BY_ID = TOOLS.associateBy { it.id }

    fun modeById(id: String): Mode = Mode.entries.firstOrNull { it.id == id } ?: Mode.AUTO

    fun toolById(id: String): Tool? = BY_ID[id]

    /** 该工具没被配置过时的档位；未知工具返回 [Mode.AUTO]（不认识的工具不该被静默禁用） */
    fun defaultMode(toolId: String): Mode = BY_ID[toolId]?.defaultMode ?: Mode.AUTO

    /**
     * 解析存储串。**非法项一律忽略**（回落到默认）而不是抛异常：
     * 这串来自用户可编辑的 prefs，读坏了最多是回到默认档，不该让 AI 功能整个崩掉。
     */
    fun parse(raw: String?): Map<String, Mode> {
        if (raw.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<String, Mode>()
        for (part in raw.split(';')) {
            val trimmed = part.trim()
            if (trimmed.isEmpty()) continue
            val eq = trimmed.indexOf('=')
            if (eq <= 0) continue
            val id = trimmed.substring(0, eq).trim()
            val modeId = trimmed.substring(eq + 1).trim()
            if (id.isEmpty() || modeId.isEmpty()) continue
            val mode = Mode.entries.firstOrNull { it.id == modeId } ?: continue
            out[id] = mode
        }
        return out
    }

    /** 取某工具当前的档位（未配置 → 默认） */
    fun modeOf(raw: String?, toolId: String): Mode =
        parse(raw)[toolId] ?: defaultMode(toolId)

    /**
     * 改一个工具的档位并返回新的存储串。
     *
     * 用 [parse] 的**解析结果**重建而不是字符串拼接：这样非法项会被顺手清掉，
     * 也不会因为同一个 id 出现两次而产生歧义。
     * 只写入"与默认不同"的项 —— 默认值是代码里的，不该被固化进 prefs（否则以后改默认值对老用户不生效）。
     */
    fun encode(raw: String?, toolId: String, mode: Mode): String {
        val map = parse(raw).toMutableMap()
        if (mode == defaultMode(toolId)) map.remove(toolId) else map[toolId] = mode
        return map.entries.joinToString(";") { "${it.key}=${it.value.id}" }
    }

    /** 该工具是否被禁止执行 */
    fun isDenied(raw: String?, toolId: String): Boolean = modeOf(raw, toolId) == Mode.DENY

    /** 该工具是否每次都要确认 */
    fun needsAsk(raw: String?, toolId: String): Boolean = modeOf(raw, toolId) == Mode.ASK
}