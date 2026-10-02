package com.kaze.newage.core.ai

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 模型配置档案的存取与文本解析（纯逻辑，SharedPreferences 只做字符串存取）。
 */
object AiProfileStore {

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(profiles: List<AiProfile>): String = json.encodeToString(profiles)

    /** 解码失败（空串/损坏的 JSON）一律返回空列表，绝不让设置页打不开 */
    fun decode(raw: String): List<AiProfile> {
        val text = raw.trim()
        if (text.isEmpty()) return emptyList()
        return runCatching { json.decodeFromString<List<AiProfile>>(text) }.getOrElse { emptyList() }
    }

    /**
     * 模型名编辑框的解析：中英文逗号都接受（Operit 同款"逗号分隔多模型"），
     * 去重并去空段；全部为空返回空列表（调用方自行回退默认模型）。
     */
    fun parseModelList(raw: String): List<String> =
        raw.split(',', '，')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
}
