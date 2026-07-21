package com.mcserver.launcher.utils

/**
 * Shell 命令安全工具。
 *
 * proot 内部通过 /bin/sh -c 执行命令，因此所有用户输入在拼接到
 * shell 字符串前必须经过安全检查与转义，防止命令注入。
 */
object ShellUtils {

    /** 不安全的 shell 元字符（禁止出现在路径/参数中直接拼接的位置） */
    private val UNSAFE_SHELL_CHARS = setOf(';', '&', '|', '`', '$', '<', '>', '(', ')', '{', '}', '\"', '\\')

    /**
     * 检查字符串是否只包含安全的字符（字母、数字、下划线、连字符、点、斜杠、空格等）。
     *
     * 注意：此检查适用于路径和简单参数值，**不能**替代对命令本身的审查。
     * 如果返回 false，说明字符串中含有 shell 元字符，直接拼接到 `sh -c` 中是危险的。
     */
    fun validateShellSafe(s: String): Boolean {
        return s.none { it in UNSAFE_SHELL_CHARS || it.code < 32 || it == '\n' || it == '\r' }
    }

    /**
     * 转义单引号字符串，用于 `'...'` 形式的 shell 字符串字面量。
     *
     * 示例：
     * ```
     * val path = "/path/with 'quotes'"
     * val safe = "'${escapeSingleQuote(path)}'"   // => '/path/with '\''quotes'\'''
     * ```
     *
     * 此转义策略在 POSIX sh 中安全，通过将每个 `'` 替换为 `'\''`
     *（结束当前单引号字符串、插入转义的单引号、重新开启单引号字符串）。
     */
    fun escapeSingleQuote(s: String): String = s.replace("'", "'\\''")
}
