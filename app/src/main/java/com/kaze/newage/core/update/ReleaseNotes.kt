package com.kaze.newage.core.update

/**
 * 把 GitHub Release 的 Markdown 正文转成**对话框里能读的纯文本**。
 *
 * 起因（真机截图实锤）：更新弹窗原来直接把 `info.body` 丢进 Text，于是用户看到的是
 * 带 `##`、`>`、`**`、`| 表格 |` 的**原始 Markdown**，而且被 `take(400)` 截断，
 * 正文只露出开头几行、后半截看不到。
 *
 * 只处理我们自己的发布说明会用到的那几种语法（标题/引用/加粗/行内代码/列表/表格/
 * 分隔线/链接），不引入 Markdown 渲染库 —— 目标是"读得懂"，不是"渲染得漂亮"。
 *
 * ⚠️ 关键点：**必须先按段落把软换行合并，再做行内标记清理**。
 * 发布正文是硬换行的，`**加粗**` 很容易跨行：
 * ```
 *  —— **每次变化都会取消
 *   上一个 effect**，动画就停在半路
 * ```
 * 逐行跑正则时这对 `**` 谁也匹配不上，于是残留在界面上（真机实测就是这么漏的）。
 */
object ReleaseNotes {

    /**
     * @param markdown 发布正文
     * @param dropTitle 若首个标题里包含这个字符串（通常是版本号），把它去掉 ——
     *        弹窗标题已经写了「发现新版本 X」，正文里再来一行 `## vX.Y.Z` 是重复的
     */
    fun toPlainText(markdown: String, dropTitle: String? = null): String {
        val out = ArrayList<String>()
        var para: StringBuilder? = null
        var titleDropped = false

        fun flush() {
            val p = para ?: return
            val text = clean(p.toString().replace(Regex("\\s+"), " ").trim())
            if (text.isNotEmpty()) out.add(text)
            para = null
        }

        fun blank() {
            flush()
            if (out.isNotEmpty() && out.last().isNotEmpty()) out.add("")
        }

        for (raw in markdown.replace("\r\n", "\n").split("\n")) {
            val line = raw.trimEnd()
            val t = line.trim()

            when {
                // 分隔线
                t == "---" || t == "***" -> blank()

                // 引用：> xxx（多行引用会被合并）
                t.startsWith(">") -> {
                    val body = t.removePrefix(">").trim()
                    if (body.isEmpty()) blank()
                    else para = (para ?: StringBuilder()).append(if (para == null) "" else " ").append(body)
                }

                // 标题
                Regex("^#{1,6}\\s+").containsMatchIn(t) -> {
                    flush()
                    val text = clean(t.replace(Regex("^#{1,6}\\s+"), ""))
                    if (!titleDropped && dropTitle != null && text.contains(dropTitle, ignoreCase = true)) {
                        titleDropped = true
                    } else {
                        if (out.isNotEmpty() && out.last().isNotEmpty()) out.add("")
                        out.add("【$text】")
                    }
                }

                // 表格：|---|---| 分隔行丢掉，其余行按 · 连接
                t.startsWith("|") && t.endsWith("|") -> {
                    flush()
                    if (t.all { it == '|' || it == '-' || it == ':' || it == ' ' }) {
                        // 分隔行，忽略
                    } else {
                        val cells = t.trim('|').split("|").map { clean(it.trim()) }.filter { it.isNotEmpty() }
                        if (cells.isNotEmpty()) out.add(cells.joinToString(" · "))
                    }
                }

                // 列表项：新起一段
                Regex("^\\s*([-*+]|\\d+[.)])\\s+").containsMatchIn(line) -> {
                    flush()
                    para = StringBuilder("· ").append(
                        line.replace(Regex("^\\s*([-*+]|\\d+[.)])\\s+"), ""),
                    )
                }

                t.isEmpty() -> blank()

                // 普通文本 / 列表项的续行：并入当前段落
                else -> {
                    val p = para
                    para = if (p == null) StringBuilder(t) else p.append(" ").append(t)
                }
            }
        }
        flush()

        // 压掉连续空行与首尾空白
        val sb = StringBuilder()
        var blankSeen = false
        for (l in out) {
            if (l.isBlank()) {
                if (!blankSeen && sb.isNotEmpty()) sb.append('\n')
                blankSeen = true
            } else {
                sb.append(l).append('\n')
                blankSeen = false
            }
        }
        return sb.toString().trim()
    }

    /** 去掉行内标记：**粗**、__粗__、*斜*、`代码`、[文字](链接)；再兜底清掉跨行残留的孤立标记 */
    private fun clean(s: String): String = s
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
        .replace(Regex("__(.+?)__"), "$1")
        .replace(Regex("(?<![\\w*])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![\\w*])"), "$1")
        .replace(Regex("`([^`]+)`"), "$1")
        .replace(Regex("\\[([^\\]]+)]\\(([^)]+)\\)"), "$1")
        // 兜底：段落合并后仍可能剩下配不成对的标记（原文本身写坏了），直接去掉
        .replace("**", "")
        .replace("__", "")
        .trim()
}
