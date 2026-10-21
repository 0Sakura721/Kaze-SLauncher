package com.kaze.newage.core.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 长对话裁剪的规则边界：system 永不丢、最近 N 轮保底、字符预算从旧到新、
 * 原生工具协议完整（tool_calls 与 tool 结果同生共死）、裁剪说明只插一条。
 */
class AiHistoryTrimTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun call(id: String) =
        """{"id":"$id","type":"function","function":{"name":"read_file","arguments":"{}"}}"""

    private fun toolCalls(vararg ids: String): String =
        ids.joinToString(",", "[", "]") { call(it) }

    private fun ids(raw: String): Set<String> {
        val arr = runCatching { json.parseToJsonElement(raw) as JsonArray }.getOrNull() ?: return emptySet()
        return arr.mapNotNull { ((it as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull }.toSet()
    }

    private fun system() = AiMessage(AiMessage.ROLE_SYSTEM, "规则")
    private fun user(text: String) = AiMessage(AiMessage.ROLE_USER, text)
    private fun assistant(text: String) = AiMessage(AiMessage.ROLE_ASSISTANT, text)
    private fun assistantCalls(vararg ids: String) =
        AiMessage(AiMessage.ROLE_ASSISTANT, "", toolCallsRaw = toolCalls(*ids))
    private fun tool(id: String, text: String = "结果") =
        AiMessage(AiMessage.ROLE_TOOL, text, toolCallId = id)

    private fun round(i: Int, chars: Int = 100): List<AiMessage> = listOf(
        user("问题$i-" + "u".repeat(chars)),
        assistant("回答$i-" + "a".repeat(chars)),
    )

    private fun isNote(m: AiMessage) = m.content.startsWith(AiHistoryTrim.NOTE_PREFIX)

    @Test
    fun `system 永不丢且最近 N 轮保底`() {
        val messages = buildList {
            add(system())
            repeat(20) { addAll(round(it)) }
        }
        // 预算极小：只能靠"最近 N 轮保底"
        val trimmed = AiHistoryTrim.trim(messages, maxRounds = 2, charBudget = 1)

        assertEquals(AiMessage.ROLE_SYSTEM, trimmed.first().role)
        assertEquals("规则", trimmed.first().content)
        assertTrue(trimmed.any { it.content.startsWith("问题18-") })
        assertTrue(trimmed.any { it.content.startsWith("问题19-") })
        assertFalse("更早的轮次应被丢掉", trimmed.any { it.content.startsWith("问题17-") })
        assertEquals(1, trimmed.count(::isNote))
    }

    @Test
    fun `字符预算从旧到新丢`() {
        val messages = buildList {
            add(system())
            repeat(20) { addAll(round(it, chars = 100)) }
        }
        val trimmed = AiHistoryTrim.trim(messages, maxRounds = 1, charBudget = 1200)
        val bodyChars = trimmed
            .filter { it.role != AiMessage.ROLE_SYSTEM && !isNote(it) }
            .sumOf { it.content.length }
        assertTrue("正文应被裁到预算内，实际 $bodyChars", bodyChars <= 1200)
        assertTrue("最新一轮必须留下", trimmed.any { it.content.startsWith("问题19-") })
        assertFalse("最旧的轮次应先被丢掉", trimmed.any { it.content.startsWith("问题0-") })
    }

    @Test
    fun `裁剪后工具协议完整`() {
        val messages = buildList {
            add(system())
            repeat(10) { i ->
                add(user("问题$i"))
                add(assistantCalls("c${i}a", "c${i}b"))
                add(tool("c${i}a"))
                add(tool("c${i}b"))
                add(assistant("回答$i"))
            }
        }
        val trimmed = AiHistoryTrim.trim(messages, maxRounds = 1, charBudget = 1)

        // 1) 每条 role=tool 都能找到承载它的 assistant.tool_calls
        trimmed.forEachIndexed { index, msg ->
            if (msg.role == AiMessage.ROLE_TOOL) {
                val owner = trimmed.take(index).lastOrNull {
                    it.role == AiMessage.ROLE_ASSISTANT && !it.toolCallsRaw.isNullOrBlank()
                }
                assertTrue("孤立 tool 结果：${msg.toolCallId}", owner != null)
                assertTrue(
                    "tool_call_id 不属于上一条 assistant：${msg.toolCallId}",
                    msg.toolCallId in ids(owner!!.toolCallsRaw!!),
                )
            }
        }
        // 2) 每个 assistant.tool_calls 的所有 id 都有紧跟其后的 tool 结果
        trimmed.forEachIndexed { index, msg ->
            val raw = msg.toolCallsRaw ?: return@forEachIndexed
            val need = ids(raw)
            val got = trimmed.drop(index + 1)
                .takeWhile { it.role == AiMessage.ROLE_TOOL }
                .mapNotNull { it.toolCallId }
                .toSet()
            assertTrue("tool_calls $need 缺少结果：$got", got.containsAll(need))
        }
    }

    @Test
    fun `缺少 tool 结果的 assistant 调用整组丢弃`() {
        val messages = listOf(system(), user("q"), assistantCalls("c1", "c2"), tool("c1"))
        val trimmed = AiHistoryTrim.trim(messages, maxRounds = 1, charBudget = 1)
        assertTrue(
            "残缺的 tool_calls 必须整组丢弃",
            trimmed.none { it.role == AiMessage.ROLE_ASSISTANT && it.toolCallsRaw != null },
        )
        assertTrue("对应的 tool 结果也不能留下", trimmed.none { it.role == AiMessage.ROLE_TOOL })
        assertTrue("最近一轮的用户问题要留着", trimmed.any { it.content == "q" })
    }

    @Test
    fun `裁剪说明只插一条且条数累计`() {
        val messages = buildList {
            add(system())
            repeat(10) { addAll(round(it)) }
        }
        val once = AiHistoryTrim.trim(messages, maxRounds = 2, charBudget = 1)
        val notes1 = once.filter(::isNote)
        assertEquals(1, notes1.size)
        val count1 = notes1.first().content

        // 对已经裁剪过的列表再裁一次：句数不能丢，也不能变成两条说明
        val twice = AiHistoryTrim.trim(once, maxRounds = 2, charBudget = 1)
        val notes2 = twice.filter(::isNote)
        assertEquals(1, notes2.size)
        assertEquals("没有新丢弃时说明不应消失或改写", count1, notes2.first().content)
    }

    @Test
    fun `不需要裁剪时原样保留`() {
        val messages = listOf(system(), user("q"), assistant("a"))
        assertEquals(
            messages,
            AiHistoryTrim.trim(messages, maxRounds = 3, charBudget = 100_000),
        )
    }

    @Test
    fun `JSON 夹带协议的工具结果不算一轮`() {
        // 一轮提问里可能塞进几十条 role=user 的工具结果：它们不是用户提问，
        // 数轮次时必须排除，否则最近 N 轮会被工具结果挤掉。
        val messages = buildList {
            add(system())
            add(user("早期问题"))
            add(assistant("早期回答"))
            repeat(30) { i -> add(AiMessage(AiMessage.ROLE_USER, "【工具结果】read_file → $i")) }
            add(user("真正的问题"))
            add(assistant("真正的回答"))
        }
        val trimmed = AiHistoryTrim.trim(messages, maxRounds = 1, charBudget = 1)
        assertTrue(trimmed.any { it.content == "真正的问题" })
        assertFalse("早期轮次应被丢掉", trimmed.any { it.content == "早期问题" })
    }
}
