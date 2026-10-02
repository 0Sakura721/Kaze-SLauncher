package com.kaze.newage.core.ai

/**
 * 上屏前的文本净化：待确认的路径、文件内容、工具提示里都可能带着控制符 ——
 * 来源既有模型（不可信输出），也有第三方网页（搜索结果）。
 *
 * 三类字符各有它必须被抹掉的理由，每一条都会让"用户看到的"与"实际发生的"不一致：
 *  - **C0/C1 控制符**（`\u0000`-`\u001F`、`\u007F`-`\u009F`）：终端转义序列（ESC[…）
 *    能在确认卡里改写光标位置，把"显示的文件名"与"真实的文件名"错开；
 *  - **bidi 控制符**（U+202A..U+202E、U+2066..U+2069、U+200E/U+200F/U+061C）：
 *    RLO 之类的字符能把 `gnp.exe` 显示成 `exe.png`，文件名看着对、实际完全不是它；
 *  - **零宽字符**（U+200B..U+200D、U+FEFF）：肉眼不可见，却让两个"看起来一样"的名字不相等。
 *
 * 只做**上屏**净化，绝不改真正落盘/外发的内容：写盘字节必须与模型给的完全一致，
 * 否则备份、diff 与用户预期全对不上（要改内容就该由模型自己改）。
 */
object AiSanitize {

    /** 判断单个字符是否属于"上屏必须抹掉"的不可见控制符 */
    private fun isInvisible(c: Char, keepLineBreaks: Boolean): Boolean = when {
        // 换行与制表在多行预览里是正常排版；单行展示（路径等）里则是伪造多行的手段
        c == '\n' || c == '\r' || c == '\t' -> !keepLineBreaks
        c < ' ' || c == '\u007F' -> true                      // C0 与 DEL
        c in '\u0080'..'\u009F' -> true                       // C1
        c == '\u061C' -> true                                 // ALM
        c == '\u200B' || c == '\u200C' || c == '\u200D' -> true // 零宽空格/连接符
        c == '\u200E' || c == '\u200F' -> true                // LRM / RLM
        c in '\u202A'..'\u202E' -> true                       // LRE/RLE/PDF/LRO/RLO
        c in '\u2066'..'\u2069' -> true                       // LRI/RLI/FSI/PDI
        c == '\uFEFF' -> true                                 // BOM / 零宽不换行空格
        else -> false
    }

    /** 多行文本净化（保留换行与制表，其余控制符/双向控制符/零宽字符抹掉） */
    fun display(raw: String): String = filter(raw, keepLineBreaks = true)

    /** 单行文本净化（路径、文件名、提示行）：换行与制表也一并换成空格，防止伪造出一张"多行卡片" */
    fun displayOneLine(raw: String): String = filter(raw, keepLineBreaks = false)

    private fun filter(raw: String, keepLineBreaks: Boolean): String {
        var dirty = false
        for (c in raw) {
            if (isInvisible(c, keepLineBreaks)) {
                dirty = true
                break
            }
        }
        if (!dirty) return raw
        val sb = StringBuilder(raw.length)
        for ((i, c) in raw.withIndex()) {
            when {
                !isInvisible(c, keepLineBreaks) -> sb.append(c)
                // 单行场景把换行/制表折成空格：直接删掉会把前后两个词粘成一个
                !keepLineBreaks && c == '\r' && raw.getOrNull(i + 1) == '\n' -> Unit // CRLF 只折一个空格
                !keepLineBreaks && (c == '\r' || c == '\n' || c == '\t') -> sb.append(' ')
            }
        }
        return sb.toString()
    }
}
