package com.kaze.newage.core.ai

import kotlinx.serialization.Serializable

/**
 * AI 助手接口配置：OpenAI 兼容的 `/chat/completions` 端点。
 *
 * 默认预填 DeepSeek 官方地址与现行模型 [DEFAULT_MODEL]（deepseek-flash，V4.1-Flash）：
 * V4 起「思考模式」由请求参数 `thinking:{type:enabled/disabled}` 控制，**不再是两个模型名**
 * （旧名 deepseek-chat / deepseek-reasoner 已于 2026-07-24 停用，见官方 Change Log；
 * 收到旧名会归一到 [DEFAULT_MODEL]，见 [normalizeLegacyModel]）。
 * 换任何 OpenAI 兼容服务（硅基流动 / OpenRouter / 本地 llama.cpp 等）
 * 只需编辑服务地址与模型名，不用改代码。API Key 只存应用私有的 SharedPreferences
 * （其他应用读不到），绝不写日志、不进版本库。
 */
data class AiConfig(
    val baseUrl: String = DEFAULT_BASE_URL,
    /** 标准模式模型名 */
    val model: String = DEFAULT_MODEL,
    /**
     * 深度思考模式模型名。DeepSeek V4 起思考由参数控制，这里通常留空即可；
     * 仅作**其他服务商**（思考 = 换一个模型名的那种）的适配位。
     */
    val thinkingModel: String = "",
    val apiKey: String = "",
    /** 思考强度：false = 标准（快），true = 深度思考（更聪明也更慢） */
    val thinking: Boolean = false,
    /**
     * 附加请求参数（JSON 对象字符串，随请求体合入，可覆盖 temperature 等采样参数；
     * model / messages 不允许覆盖，非法 JSON 整体忽略）。
     * 部分服务商需要显式参数才返回思考过程（如 Qwen 的 {"enable_thinking":true}）。
     */
    val extraBody: String = "",
) {
    /** 三项齐全才算配置完成（密钥是硬前提；思考模型名缺失时回退标准模型） */
    val isConfigured: Boolean
        get() = apiKey.isNotBlank() && model.isNotBlank() && normalizeBaseUrl(baseUrl).isNotEmpty()

    /**
     * 本轮请求实际使用的模型。
     *
     * DeepSeek 新机制（V4 起）：思考模式由 `thinking` 参数控制，不再是两个模型名；
     * 旧名 deepseek-chat / deepseek-reasoner 已于 2026-07-24 停用（官方 Change Log），
     * 请求时一律归一到现行模型 deepseek-flash（V4.1-Flash）。
     */
    val requestModel: String
        get() {
            val chosen = when {
                !thinking -> model
                thinkingModel.isNotBlank() -> thinkingModel
                else -> model
            }
            return normalizeLegacyModel(chosen, baseUrl)
        }

    /** 完整请求端点：base 规范化后拼 /chat/completions（用户已填全路径时不重复拼） */
    val endpoint: String
        get() {
            val base = normalizeBaseUrl(baseUrl)
            if (base.isEmpty()) return ""
            return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
        }

    companion object {
        /** 官方现行 base（OpenAI 格式；/v1 兼容路径曾长期可用，但默认取文档主推地址） */
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"

        /**
         * 现行模型（V4.1-Flash，2026-09-10 发布）。1M 上下文、最大输出 384K，
         * 思考模式由 thinking 参数控制（enabled/disabled，默认 enabled），
         * 回复里 reasoning_content 携带思考过程。
         */
        const val DEFAULT_MODEL = "deepseek-flash"

        /**
         * 旧模型名归一：deepseek-chat / deepseek-reasoner 在**官方端点**已停用
         * （官方 Change Log 2026-04-24 公告、2026-07-24 生效），映射到 deepseek-flash。
         *
         * 只在端点属于 deepseek.com 时改写：第三方网关（硅基流动 / OpenRouter 等）
         * 可能真有一个叫 deepseek-chat 的可用模型，改写会把它们弄坏；
         * deepseek-v4-flash 等官方临时路由名原样保留。
         */
        fun normalizeLegacyModel(name: String, baseUrl: String): String {
            val n = name.trim()
            val official = baseUrl.contains("deepseek.com", ignoreCase = true)
            return if (official && n.lowercase() in setOf("deepseek-chat", "deepseek-reasoner")) {
                DEFAULT_MODEL
            } else {
                n
            }
        }

        /**
         * 规范化 base URL：去首尾空白与结尾斜杠；必须 http(s) 开头，否则返回空串（视为未配置）。
         * 允许 http 是刻意的：本地推理端点（127.0.0.1 的 llama.cpp 等）只能是 http，
         * 风险由用户自己权衡（密钥与日志只会发给他自己填的地址）。
         */
        fun normalizeBaseUrl(raw: String): String {
            val s = raw.trim().trimEnd('/')
            if (!s.startsWith("http://") && !s.startsWith("https://")) return ""
            val afterScheme = s.removePrefix("https://").removePrefix("http://")
            if (afterScheme.isBlank()) return ""
            return s
        }
    }
}

/** 对话消息（OpenAI 兼容格式：system / user / assistant） */
data class AiMessage(val role: String, val content: String) {
    companion object {
        const val ROLE_SYSTEM = "system"
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
    }
}

/**
 * 模型配置档案（参照 Operit 的「模型自定义选择分配系统」做的轻量版）。
 *
 * Operit 的做法：多个命名档案（端点 + Key + **逗号分隔的模型列表**），
 * 每个功能（FunctionType）分配一个档案与模型序号。我们只有一个功能「对话」，
 * 分配 = 选中哪个档案。DeepSeek V4 起「思考强度」由请求参数 thinking 控制，
 * 模型列表通常只需一个现行模型名；其它服务商若思考=换模型名，可填第二个名字作为
 * 深度思考模型（缺第二个时思考模式回退第一个）。档案保存在应用私有 SharedPreferences
 * （JSON），Key 不出应用。
 */
@Serializable
data class AiProfile(
    val id: String = java.util.UUID.randomUUID().toString(),
    /** 显示名，如「DeepSeek 官方」「硅基流动」 */
    val name: String = "",
    val baseUrl: String = AiConfig.DEFAULT_BASE_URL,
    val apiKey: String = "",
    /** 模型名列表（UI 里逗号分隔编辑）：DeepSeek 新机制下一个名字即可，思考由参数控制 */
    val models: List<String> = listOf(AiConfig.DEFAULT_MODEL),
    /** 附加请求参数 JSON（透传给 AiConfig.extraBody，供需要显式开启思考的服务商使用） */
    val extraBody: String = "",
) {
    /** 列表行的摘要：端点主机 + 模型名 */
    val summary: String
        get() = buildString {
            append(baseUrl.trim().trimEnd('/').substringAfter("//").ifBlank { "未填服务地址" })
            if (models.isNotEmpty()) {
                append(" · ")
                append(models.joinToString(" / "))
            }
        }
}

