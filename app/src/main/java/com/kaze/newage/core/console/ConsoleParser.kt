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
    // \S+ 而非 \w+：离线服玩家名常含中文等非 ASCII 字符，\w 会漏解析导致在线人数不准
    private val JOIN_PATTERN = Regex("""(\S+) joined the game""")
    private val LEAVE_PATTERN = Regex("""(\S+) left the game""")

    /**
     * 玩家聊天行：`[12:34:56] [Server thread/INFO]: <Steve> 内容`。
     *
     * 加入/离开事件必须排除聊天行，否则玩家在游戏里说一句 "Steve joined the game"
     * （或 "<Steve> I joined the game"）就会被当成真实的加入事件，在线名单里凭空多出一个人；
     * leave 同理会误删。
     */
    private val CHAT_LINE = Regex(""":\s*<[^>]{1,32}>\s""")

    /** 若该行是玩家列表响应，返回名单（可能为空列表）；否则返回 null */
    fun parseOnlinePlayers(line: String): List<String>? {
        val m = LIST_PATTERN.find(line) ?: return null
        val tail = m.groupValues[1].trim()
        if (tail.isEmpty()) return emptyList()
        return tail.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** 玩家加入事件 → 玩家名；否则 null（聊天内容不算事件） */
    fun parseJoin(line: String): String? =
        if (CHAT_LINE.containsMatchIn(line)) null
        else JOIN_PATTERN.find(line)?.groupValues?.get(1)

    /** 玩家离开事件 → 玩家名；否则 null（聊天内容不算事件） */
    fun parseLeave(line: String): String? =
        if (CHAT_LINE.containsMatchIn(line)) null
        else LEAVE_PATTERN.find(line)?.groupValues?.get(1)
}
