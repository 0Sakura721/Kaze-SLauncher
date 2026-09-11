package com.kaze.newage.core.console

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 控制台行解析测试。
 * 重点锁住「聊天内容不得被当作加入/离开事件」——玩家说一句
 * "Alex joined the game" 就凭空多出一个在线玩家，是很容易回归的坑。
 */
class ConsoleParserTest {

    @Test
    fun `解析 list 响应为玩家名单`() {
        val line = "[12:00:00] [Server thread/INFO]: There are 2 of a max of 20 players online: Steve, Alex"
        assertEquals(listOf("Steve", "Alex"), ConsoleParser.parseOnlinePlayers(line))
    }

    @Test
    fun `零人在线返回空名单而不是 null`() {
        val line = "[12:00:00] [Server thread/INFO]: There are 0 of a max of 20 players online:"
        assertEquals(emptyList<String>(), ConsoleParser.parseOnlinePlayers(line))
    }

    @Test
    fun `普通日志行不误判为名单`() {
        assertNull(
            ConsoleParser.parseOnlinePlayers(
                "[12:00:00] [Server thread/INFO]: Done (3.214s)! For help, type \"help\""
            )
        )
    }

    @Test
    fun `解析加入与离开事件`() {
        assertEquals("Steve", ConsoleParser.parseJoin("[12:00:00] [Server thread/INFO]: Steve joined the game"))
        assertEquals("Steve", ConsoleParser.parseLeave("[12:00:00] [Server thread/INFO]: Steve left the game"))
    }

    @Test
    fun `支持中文等非 ASCII 玩家名`() {
        assertEquals("小明", ConsoleParser.parseJoin("[12:00:00] [Server thread/INFO]: 小明 joined the game"))
    }

    @Test
    fun `聊天内容不算事件`() {
        // 玩家在游戏里打出这句话时，日志里同样含 "joined the game"，但它是聊天不是事件
        assertNull(ConsoleParser.parseJoin("[12:00:00] [Server thread/INFO]: <Steve> Alex joined the game"))
        assertNull(ConsoleParser.parseLeave("[12:00:00] [Server thread/INFO]: <Steve> Alex left the game"))
    }

    @Test
    fun `与事件无关的行返回 null`() {
        assertNull(ConsoleParser.parseJoin("[12:00:00] [Server thread/INFO]: Preparing spawn area: 50%"))
        assertNull(ConsoleParser.parseLeave("[12:00:00] [Server thread/INFO]: Saving chunks"))
    }
}
