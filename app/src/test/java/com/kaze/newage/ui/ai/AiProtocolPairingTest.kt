package com.kaze.newage.ui.ai

import com.kaze.newage.core.ai.AiHistoryTrim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 回喂格式的**协议完整性**与上下文裁剪。
 *
 * 这是最容易悄悄写错、又最贵的一层：assistant 消息里的每一个 `tool_calls` 都必须在
 * 下一次请求里带**恰好一条** `role=tool` + 同 id 的结果；JSON 夹带协议则必须是普通
 * user 消息（多带一个 `tool_call_id` 或少回一条结果，严格的服务端都会直接 400）。
 * 断言全部落在**实际发出的请求体**上（[FakeSseEndpoint] 逐轮存下来），而不是内部状态。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AiProtocolPairingTest {

    @Test
    fun `原生协议一批两个只读调用，每个 id 恰好一条结果且内容各归各位`() {
        val h = AiLoopHarness(
            listOf(
                Sse.toolCalls(
                    Triple("call_1", "read_file", """{"path":"a.txt"}"""),
                    Triple("call_2", "read_file", """{"path":"b.txt"}"""),
                ),
                Sse.final("都读完了"),
            ),
        )
        try {
            h.instanceDir.resolve("a.txt").writeText("AAA-CONTENT\n", Charsets.UTF_8)
            h.instanceDir.resolve("b.txt").writeText("BBB-CONTENT\n", Charsets.UTF_8)
            h.ask()

            assertEquals(2, h.requestCount())
            assertEquals(setOf("call_1", "call_2"), h.declaredToolCallIds(1))
            val results = h.toolResultMessages(1)
            assertEquals("每个 tool_call 必须恰好一条结果", 2, results.size)
            val byId = results.toMap()
            assertEquals(setOf("call_1", "call_2"), byId.keys)
            assertTrue("结果不能串到另一个调用上", byId.getValue("call_1").contains("AAA-CONTENT"))
            assertFalse(byId.getValue("call_1").contains("BBB-CONTENT"))
            assertTrue(byId.getValue("call_2").contains("BBB-CONTENT"))
        } finally {
            h.close()
        }
    }

    @Test
    fun `JSON 夹带协议的工具结果回喂成 user 消息且不带 tool_call_id`() {
        val h = AiLoopHarness(
            listOf(
                Sse.reply(jsonToolReply("看一下", "read_file", "a.txt")),
                Sse.reply(jsonFinalReply("好了")),
            ),
            model = JSON_MODEL,
        )
        try {
            h.instanceDir.resolve("a.txt").writeText("AAA-CONTENT\n", Charsets.UTF_8)
            h.ask()

            assertEquals(2, h.requestCount())
            assertTrue("JSON 协议里不该出现 role=tool", h.toolResultMessages(1).isEmpty())
            assertFalse("JSON 协议没有 tool_call_id 这套字段", h.requestMessageRoles(1).contains("tool"))
            assertFalse(h.requestBodyText(1).contains("tool_call_id"))
            assertTrue(
                "工具结果必须是 user 消息",
                h.requestMessageContents(1).any { it.contains("【工具结果】read_file") },
            )
        } finally {
            h.close()
        }
    }

    @Test
    fun `多轮工具循环里每一轮请求的 tool_calls 与 role=tool 结果都保持成对`() {
        val rounds = listOf("r1.log", "r2.log", "r3.log")
        val responses = rounds.mapIndexed { i, name ->
            Sse.toolCall("call_$i", "read_file", """{"path":"$name"}""")
        } + Sse.final("总结完毕")
        val h = AiLoopHarness(responses)
        try {
            rounds.forEach { h.instanceDir.resolve(it).writeText("MARK-$it\n", Charsets.UTF_8) }
            h.ask()

            assertEquals("3 轮工具 + 1 轮收尾", 4, h.requestCount())
            for (i in 0 until h.requestCount()) {
                val declared = h.declaredToolCallIds(i)
                val answered = h.toolResultMessages(i).map { it.first }.toSet()
                if (declared.isEmpty()) continue
                assertEquals("第 $i 轮请求里 tool_calls 与 role=tool 必须一一对应", declared, answered)
            }
        } finally {
            h.close()
        }
    }

    @Test
    fun `未知工具名按 role=tool 回喂错误而不是静默丢弃`() {
        val h = AiLoopHarness(
            listOf(
                Sse.toolCall("call_x", "rm_rf_everything", """{"path":"/"}"""),
                Sse.final("换个办法"),
            ),
        )
        try {
            h.ask()

            val result = h.toolResultMessages(1).single()
            assertEquals("call_x", result.first)
            assertTrue(result.second.contains("未知工具"))
            assertEquals(setOf("call_x"), h.declaredToolCallIds(1))
        } finally {
            h.close()
        }
    }

    /**
     * **当前裁剪规则的实际边界（记录行为，不是期望行为）**：
     * [AiHistoryTrim] 的字符预算分支要求"整段都在最近 [AiHistoryTrim.DEFAULT_MAX_ROUNDS] 轮之前"，
     * 而一轮提问只会产生 **1** 个"真实用户消息"（工具结果消息被 `isToolResultUser` 排除、
     * 原生协议用 role=tool），所以单轮工具循环里 `keepFrom` 恒为 0、预算永远不放行任何丢弃 ——
     * 哪怕上下文已经被 64KB 的文件内容撑到远超预算。
     *
     * 见报告"真问题"一节：这削弱了"工具循环不裁必然撑爆上下文"这条设计意图。
     */
    @Test
    fun `单轮工具循环即使远超字符预算也不会插入裁剪说明`() {
        val h = AiLoopHarness(
            listOf(
                Sse.reply(jsonToolReply("读个大文件", "read_file", "huge.log")),
                Sse.reply(jsonFinalReply("读完了")),
            ),
            model = JSON_MODEL,
        )
        try {
            h.instanceDir.resolve("huge.log").writeText("HEAD-MARKER" + "x".repeat(70_000), Charsets.UTF_8)
            h.ask()

            assertEquals(2, h.requestCount())
            val body = h.requestBodyText(1)
            assertTrue(
                "前提：这一轮请求确实远超字符预算 ${AiHistoryTrim.DEFAULT_CHAR_BUDGET}",
                body.length > AiHistoryTrim.DEFAULT_CHAR_BUDGET,
            )
            assertFalse(
                "预算分支在单轮里不可达：既没丢消息，也就不会有裁剪说明",
                body.contains(AiHistoryTrim.NOTE_PREFIX),
            )
            assertTrue("大文件内容确实被回喂了（没有发生任何裁剪）", body.contains("HEAD-MARKER"))
        } finally {
            h.close()
        }
    }
}