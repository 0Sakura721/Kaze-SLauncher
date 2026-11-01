package com.kaze.newage.core.i18n

/**
 * 运行时文案查表。
 *
 * ## 用法
 * ```kotlin
 * Text(t("取消"))                       // 静态文案
 * Text(t("已安装（%d）", count))          // 带参数
 * Text(t("下载 %s…", fileName))
 * ```
 * 中文是**原文即 key**：查不到英文时原样返回中文（绝不返回 key 本身、也不返回空串），
 * 所以漏翻的地方只会显示中文，不会让界面变成空白或 `missing_string`。
 *
 * ## 参数占位
 * 用 `%s` / `%d` 而不是 Kotlin 的 `$var` —— 字符串模板在编译期就把值嵌进去了，
 * 查表时已经拿不到模板本身。所以带插值的文案要改成「模板 + 参数」两段式。
 */
object Strings {

    /** 英文表：中文原文 → 英文。查不到就回退中文。 */
    private val en: Map<String, String> = EnglishStrings.TABLE

    /**
     * 取当前语言下的文案。
     *
     * @param zh 中文原文（同时是查表的 key）
     * @return 当前语言对应的文案；英文表里没有则返回中文原文
     */
    fun t(zh: String): String = if (LanguageState.isChinese) zh else (en[zh] ?: zh)

    /**
     * 带参数的文案。
     *
     * 参数按顺序替换 `%s` / `%d`。**数量不匹配时不抛异常** —— 少给的占位保留原样，
     * 多给的忽略：文案出错不该让界面崩掉（这条在真机上比"严格校验"重要得多）。
     */
    fun t(zh: String, vararg args: Any?): String {
        val template = t(zh)
        if (args.isEmpty()) return template
        val sb = StringBuilder(template.length + 16)
        var argIdx = 0
        var i = 0
        while (i < template.length) {
            val c = template[i]
            if (c == '%' && i + 1 < template.length && argIdx < args.size) {
                val spec = template[i + 1]
                if (spec == 's' || spec == 'd') {
                    sb.append(args[argIdx]?.toString() ?: "")
                    argIdx++
                    i += 2
                    continue
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }
}

/** 顶层别名，少打几个字（调用点非常多） */
fun t(zh: String): String = Strings.t(zh)
fun t(zh: String, vararg args: Any?): String = Strings.t(zh, *args)