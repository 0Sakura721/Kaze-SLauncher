package com.kaze.newage.core.ai

/**
 * AI 助手接口配置：OpenAI 兼容的 `/chat/completions` 端点。
 *
 * 默认预填 DeepSeek 官方地址与两个模型 ——「思考强度」在 DeepSeek 上的实现就是
 * 模型切换：标准 = [DEFAULT_MODEL]（deepseek-chat），深度思考 = [DEFAULT_THINKING_MODEL]
 * （deepseek-reasoner）。换任何 OpenAI 兼容服务（硅基流动 / OpenRouter / 本地 llama.cpp 等）
 * 只需编辑这几个文本项，不用改代码。API Key 只存应用私有的 SharedPreferences
 * （其他应用读不到），绝不写日志、不进版本库。
 */
data class AiConfig(
    val baseUrl: String = DEFAULT_BASE_URL,
    /** 标准模式模型名 */
    val model: String = DEFAULT_MODEL,
    /** 深度思考模式模型名（玩家可自行编辑以适配其他服务商） */
    val thinkingModel: String = DEFAULT_THINKING_MODEL,
    val apiKey: String = "",
    /** 思考强度：false = 标准（快），true = 深度思考（更聪明也更慢） */
    val thinking: Boolean = false,
) {
    /** 三项齐全才算配置完成（密钥是硬前提；思考模型名缺失时回退标准模型） */
    val isConfigured: Boolean
        get() = apiKey.isNotBlank() && model.isNotBlank() && normalizeBaseUrl(baseUrl).isNotEmpty()

    /** 本轮请求实际使用的模型：深度思考 → [thinkingModel]（为空则回退 [model]，绝不发空模型名） */
    val requestModel: String
        get() = if (thinking) thinkingModel.ifBlank { model } else model

    /** 完整请求端点：base 规范化后拼 /chat/completions（用户已填全路径时不重复拼） */
    val endpoint: String
        get() {
            val base = normalizeBaseUrl(baseUrl)
            if (base.isEmpty()) return ""
            return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
        }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com/v1"
        const val DEFAULT_MODEL = "deepseek-chat"
        const val DEFAULT_THINKING_MODEL = "deepseek-reasoner"

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
