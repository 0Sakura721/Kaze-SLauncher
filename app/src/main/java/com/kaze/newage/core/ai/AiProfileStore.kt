package com.kaze.newage.core.ai

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 模型配置档案的存取与文本解析（纯逻辑，SharedPreferences 只做字符串存取）。
 */
object AiProfileStore {

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(profiles: List<AiProfile>): String = json.encodeToString(profiles)

    /**
     * 解码结果。
     *
     * [corrupt] 是这里最关键的一位：原串**存在非空白内容**但解析不出来（被截断 / 手工改坏 /
     * 写入中断）。它绝不能和"从没配置过"混为一谈 —— 以前两者都返回 `emptyList()`，
     * 于是界面显示"还没有配置"，用户顺着引导新建一份配置就把损坏的原串覆盖掉了，
     * 里面所有 Key 静默消失（用户甚至不知道曾经有过）。上层据此保留原串并明确提示。
     */
    data class Decoded(val profiles: List<AiProfile>, val corrupt: Boolean)

    fun decodeResult(raw: String): Decoded {
        val text = raw.trim()
        if (text.isEmpty()) return Decoded(emptyList(), corrupt = false)
        return runCatching { json.decodeFromString<List<AiProfile>>(text) }
            .fold({ Decoded(it, corrupt = false) }, { Decoded(emptyList(), corrupt = true) })
    }

    /**
     * 解码失败（空串/损坏的 JSON）返回空列表，绝不让设置页打不开。
     * 需要区分"损坏"的场景请用 [decodeResult]（损坏时不能让上层顺手覆盖原串）。
     */
    fun decode(raw: String): List<AiProfile> = decodeResult(raw).profiles

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
