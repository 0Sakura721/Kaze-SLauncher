package com.kaze.newage.core.console

import java.util.Locale

/**
 * 大数字的中文短记法（1 位小数），以及日志体积的短记法。
 *
 * 真机需求：控制台行数到 1 万以后不要再显示 "12345678 行" 这种一串数字，
 * 缩成 `1.2万` / `1.2百万` / `1.2千万` / `1.2亿`，统统一位小数；点一下再看精确值。
 *
 * ## 两条容易踩的规则
 *
 * 1. **档位是混合十进制**（万 10^4 / 百万 10^6 / 千万 10^7 / 亿 10^8），不是"万 → 亿"直接跳：
 *    否则 1,234,567 会显示成 `123.5万`，比 `1.2百万` 难读。
 *
 * 2. **选档要按"四舍五入之后"的值判**。只按原始值比大小的话，
 *    999,999 会落进「万」档算出 99.9999 → 显示 `100.0万` —— 一个四位数级的怪值。
 *    这里从大到小试，谁**舍入到一位小数后 ≥ 1.0**（即 n ≥ 0.95 × 档位）就用谁，
 *    于是 999,999 → `1.0百万`、9,999,999 → `1.0千万`、99,999,999 → `1.0亿`。
 */
object CountFormat {

    /** (档位大小, 单位名)，从大到小 */
    private val UNITS = listOf(
        100_000_000L to "亿",
        10_000_000L to "千万",
        1_000_000L to "百万",
        10_000L to "万",
    )

    /** 12345 → "1.2万"；999 → "999" */
    fun short(n: Long): String {
        for ((unit, name) in UNITS) {
            // 0.95：一位小数下 0.95 会进位成 1.0，所以到了这个门槛就该换这一档
            if (n >= unit * 95 / 100) return one(n / unit.toDouble()) + name
        }
        return n.toString()
    }

    /**
     * 日志体积：1.2 GB / 12.3 MB / 456 KB / 789 B
     *
     * 档位门槛要按**这一次舍入会不会印出 1024.0** 来定，不能只比原始字节数：
     * MB 档印的是 `n/1024` 保留一位小数，它在 n ≥ 1048525（1023.95 KB）时会进位成
     * `1024.0` —— 于是 1048575 B 显示成 `1024.0 KB`，一个本不该出现的档位。
     * 所以 KB 档止于 0.05 KB 之前，MB 档同理止于 0.05 MB 之前。
     *
     * 注意门槛比 [short] 的 0.95 紧得多，这是有意的：那边不足档位时显示的是精确数字，
     * 而这里不足 KB 档要显示 `B` —— 放宽到 0.95 会让 972 B 变成难读的 `0.9 KB`。
     */
    fun bytes(n: Long): String = when {
        n >= GB - MB * 5 / 100 -> one(n / GB.toDouble()) + " GB"
        n >= MB - KB * 5 / 100 -> one(n / MB.toDouble()) + " MB"
        n >= KB -> one(n / KB.toDouble()) + " KB"
        else -> "$n B"
    }

    private const val KB = 1024L
    private const val MB = 1024L * 1024
    private const val GB = 1024L * 1024 * 1024

    private fun one(v: Double): String = String.format(Locale.US, "%.1f", v)
}
