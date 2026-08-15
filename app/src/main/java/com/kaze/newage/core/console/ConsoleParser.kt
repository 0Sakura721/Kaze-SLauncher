package com.kaze.newage.core.console

/**
 * 控制台行解析：玩家列表响应与加入/离开事件。
 * 服务端 `list` 命令输出形如：
 * "There are 2 of a max of 20 players online: Steve, Alex"
 * 加入/离开： "Steve joined the game" / "Steve left the game"
 */
object ConsoleParser {

    private val LIST_PATTERN =
        Regex("""There are \d+ of a max of \d+ players online:\s*(.*)""")
    private val JOIN_PATTERN = Regex("""(\w+) joined the game""")
    private val LEAVE_PATTERN = Regex("""(\w+) left the game""")

    /** 若该行是玩家列表响应，返回名单（可能为空列表）；否则返回 null */
    fun parseOnlinePlayers(line: String): List<String>? {
        val m = LIST_PATTERN.find(line) ?: return null
        val tail = m.groupValues[1].trim()
        if (tail.isEmpty()) return emptyList()
        return tail.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** 玩家加入事件 → 玩家名；否则 null */
    fun parseJoin(line: String): String? = JOIN_PATTERN.find(line)?.groupValues?.get(1)

    /** 玩家离开事件 → 玩家名；否则 null */
    fun parseLeave(line: String): String? = LEAVE_PATTERN.find(line)?.groupValues?.get(1)
}
