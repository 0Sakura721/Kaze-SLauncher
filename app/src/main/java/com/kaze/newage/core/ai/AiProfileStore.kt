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
     * 解码结果：[profiles] 可用档案；[corrupt] = 存的是非空 JSON 但解析不了。
     *
     * 为什么要单独报一个 corrupt 标志：解析失败时返回空列表本身没错（不能因为一段坏 JSON
     * 让设置页打不开），但**必须让上层知道"这不是没有配置，而是配置坏了"** ——
     * 否则用户看到的是"档案全没了、Key 也没了"，而真相是那份 JSON 还在（也许只是少了个括号）。
     * 上层据此保留原串并提示用户。
     */
    data class Decoded(val profiles: List<AiProfile>, val corrupt: Boolean)

    /** 解码失败（空串/损坏的 JSON）时 profiles 为空列表，绝不让设置页打不开 */
    fun decodeChecked(raw: String): Decoded {
        val text = raw.trim()
        if (text.isEmpty()) return Decoded(emptyList(), corrupt = false)
        return runCatching { json.decodeFromString<List<AiProfile>>(text) }
            .fold(onSuccess = { Decoded(it, corrupt = false) }, onFailure = { Decoded(emptyList(), corrupt = true) })
    }

    fun decode(raw: String): List<AiProfile> = decodeChecked(raw).profiles

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
