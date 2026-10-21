package com.kaze.newage.core.ai

/**
 * 正文流里的 `<think>` / `<thinking>` 状态机。
 *
 * ## 为什么需要它
 * OpenAI 兼容服务商分两类：
 *  - 官方（DeepSeek 等）把推理放在独立的 `reasoning_content` 字段，正文本来就是干净的；
 *  - 另一批服务商把推理**混在正文 content 里**用 `<think>…</think>` 包起来流式输出。
 *
 * 旧实现只处理第一种：正文里的 `<think>` 会被逐 token 当成正文显示在气泡里（还带着标签），
 * 直到整轮结束才由 `AiSuggestion.splitThinking` 事后拆走 —— 用户看到的是
 * "思考先出现在正文、结束时又突然跳进折叠面板"。这个状态机让第二种协议也能**流式**分流：
 * 开标签之后的增量走 reasoning，闭标签之后回到正文。
 *
 * ## 跨 chunk 的半个标签
 * 增量是按 SSE chunk 到达的，标签完全可能被切成 `"<thi"` + `"nk>"`。
 * 缓冲 [pending] 保存"可能是标签前缀"的尾巴：只有确认它不是任何标签前缀时才吐出去，
 * 所以界面上永远不会出现 `<thi` 这种残片。
 *
 * 与 [AiSuggestion.splitThinking] 的语义保持一致：
 *  - 支持 `<think>` 与 `<thinking>`；
 *  - 开标签一直不闭包时，已收到的部分全部算思考（不会漏回正文）；
 *  - 流结束时仍未补全的半个标签直接丢弃（既不是正文也不是有意义的思考内容）。
 */
internal class AiThinkParser(
    /** 语义块回调：thinking=true 的 text 属于思考过程，false 属于正文 */
    private val emit: (thinking: Boolean, text: String) -> Unit,
) {

    private var thinking = false
    private val pending = StringBuilder()
    private val out = StringBuilder()

    /** true = 当前处于尚未闭合的思考段（流结束时用于诊断/测试） */
    val inThinking: Boolean get() = thinking

    /** 喂一个正文增量；只会在语义块边界回调，最长一次回调 = 这个 chunk 的一段连续文本 */
    fun accept(chunk: String) {
        if (chunk.isEmpty()) return
        for (ch in chunk) {
            if (pending.isEmpty()) {
                if (ch == '<') pending.append(ch) else out.append(ch)
                continue
            }
            pending.append(ch)
            val markers = if (thinking) CLOSE_TAGS else OPEN_TAGS
            when {
                // 完整标签：先把标签之前的正文按当前段吐出去，再切换状态
                pending.toString() in markers -> {
                    flush()
                    thinking = !thinking
                    pending.clear()
                }
                // 还是某个标签的前缀（如 "<thi"）：留着等下一个 chunk
                markers.any { it.startsWith(pending) } -> Unit
                // 不是标签（如数学比较 "a < b"）：把缓冲原样并回正文
                else -> {
                    out.append(pending)
                    pending.clear()
                }
            }
        }
        flush()
    }

    /**
     * 流结束。半截标签直接丢掉：
     *  - 正文里的 `"<thi"` 是截断的标签，不是用户该看到的回答；
     *  - 思考段里的 `"</thi"` 同理。
     * 未闭合思考段的**内容**在 accept 阶段就已经全部走 reasoning 了，不需要在这里补发。
     */
    fun finish() {
        pending.clear()
        flush()
    }

    private fun flush() {
        if (out.isEmpty()) return
        emit(thinking, out.toString())
        out.clear()
    }

    private companion object {
        val OPEN_TAGS = listOf("<think>", "<thinking>")
        val CLOSE_TAGS = listOf("</think>", "</thinking>")
    }
}

/**
 * 合并两条思考来源（原生 `reasoning_content` + 正文 `<think>` 段）。
 *
 * 旧实现是 `reasoning_content ?: inlineThink`：只要服务商回了原生推理，正文里的
 * inline 推理就被整段丢弃（`splitThinking` 已经从正文剥掉了它）—— 这是"丢失"。
 * 这里按顺序拼接并做包含去重（有的网关会把同一段推理同时塞进两个字段），
 * 保证既不会重复、也不会丢。
 */
internal fun mergeReasoning(vararg parts: String?): String? {
    val values = parts.mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
    if (values.isEmpty()) return null
    val merged = ArrayList<String>(values.size)
    for (value in values) {
        if (merged.any { it == value || it.contains(value) }) continue
        merged.removeAll { value.contains(it) }
        merged.add(value)
    }
    return merged.joinToString("\n\n").takeIf { it.isNotEmpty() }
}
