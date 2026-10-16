package com.kaze.newage.ui

import com.kaze.newage.core.console.ConsoleLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回读更早日志的**前插规则**：列表满时让实时尾部让位，保证刚读回来的历史一定可见。
 *
 * 旧实现是 `(older + current).takeLast(max)`，满列表下丢掉的正是排在最前面的、
 * 刚前插的那段历史 —— 用户点「加载更早日志」却什么都没出现。
 */
class ConsoleOlderPrependTest {

    private fun lines(prefix: String, range: IntRange) =
        range.map { ConsoleLine("$prefix$it") }

    @Test
    fun `列表未满时前插历史且保留全部实时行`() {
        val older = lines("旧", 1..10)
        val current = lines("新", 1..20)

        val out = prependOlderLines(older, current, max = 100)

        assertEquals(30, out.size)
        assertEquals("旧1", out.first().text)
        assertEquals("新20", out.last().text)
    }

    @Test
    fun `列表已满时前插的历史不被丢弃（实时尾部让位）`() {
        val older = lines("旧", 1..10)
        val current = lines("新", 1..100)

        val out = prependOlderLines(older, current, max = 50)

        assertEquals(50, out.size)
        // 关键断言：10 条历史全部留在最前面（旧实现在这里会一条都不剩）
        assertEquals("旧1", out[0].text)
        assertEquals("旧10", out[9].text)
        // 尾部是最新的实时行
        assertEquals("新100", out.last().text)
        assertTrue("历史必须完整保留", out.take(10).all { it.text.startsWith("旧") })
    }

    @Test
    fun `单个历史块超过上限时只保留其最新部分`() {
        val older = lines("旧", 1..100)

        val out = prependOlderLines(older, lines("新", 1..5), max = 30)

        assertEquals(30, out.size)
        assertEquals("旧71", out.first().text)
        assertEquals("旧100", out.last().text)
    }

    @Test
    fun `上限非正时返回空列表`() {
        assertEquals(emptyList<ConsoleLine>(), prependOlderLines(lines("旧", 1..3), lines("新", 1..3), max = 0))
    }
}
