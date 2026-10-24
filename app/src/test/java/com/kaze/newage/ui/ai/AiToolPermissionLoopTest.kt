package com.kaze.newage.ui.ai

import com.kaze.newage.core.ai.AiToolPolicy
import com.kaze.newage.core.ai.AiToolStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 工具权限三档（[AiToolPolicy]：自动 / 每次询问 / 禁止）在**两种协议**下的端到端行为。
 *
 * 以前这一层完全没有测试：判定散在 [AiChatController] 的两条协议分支里，
 * 任何一处漏判都意味着"用户设了禁止、文件还是被读了并发给了第三方端点"。
 * 这里用假宿主 + 本地假 SSE 端点把两条分支各跑一遍，断言：
 *  1. DENY 不执行、内容不外发、且**明确告诉模型"被禁用了、不要重试"**（否则模型会反复重试）；
 *  2. ASK 在批准之前不执行、不发起下一轮请求，批准后才读，拒绝后不读；
 *  3. AUTO 直接执行，不产生确认卡；
 *  4. 敏感文件（ops.json）即使在 AUTO 档也要确认（独立的安全底线）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AiToolPermissionLoopTest {

    /** 只在真读到时才会出现在请求体里的标记串 */
    private val secret = "TOP-SECRET-CONTENT-42"

    private fun jsonReadLoop() = listOf(
        Sse.reply(jsonToolReply("我先看看配置", "read_file", "server.properties")),
        Sse.reply(jsonFinalReply("看完了")),
    )

    private fun nativeReadLoop() = listOf(
        Sse.toolCall("call_1", "read_file", """{"path":"server.properties"}"""),
        Sse.final("看完了"),
    )

    private fun AiLoopHarness.seedProperties() {
        instanceDir.resolve("server.properties").writeText("motd=$secret\n", Charsets.UTF_8)
    }

    private fun AiLoopHarness.toolCards() = messages.filter { !it.isUser && it.steps.isNotEmpty() }

    // ── DENY ──

    @Test
    fun `JSON 协议 DENY 档不执行，并把被禁用明确告诉模型`() {
        val h = AiLoopHarness(jsonReadLoop(), model = JSON_MODEL)
        try {
            h.prefs.setAiToolPolicy("read_file", AiToolPolicy.Mode.DENY)
            h.seedProperties()
            h.ask()

            // 不是静默丢弃：仍然回喂了一条"拒绝"结果，否则模型会以为工具坏了
            assertEquals(2, h.requestCount())
            val body = h.requestBodyText(1)
            assertFalse("被禁止的读取绝不能把文件内容发出去", body.contains(secret))
            assertTrue("必须说明该工具已被禁用", body.contains("该工具已被禁用"))
            assertTrue("必须明确要求不要重试", body.contains("不要重试"))

            val card = h.toolCards().last()
            assertTrue(card.isError)
            assertTrue(card.text.contains("已按你的设置跳过"))
            assertEquals(AiToolStep.Status.DENIED, card.steps.single().status)
        } finally {
            h.close()
        }
    }

    @Test
    fun `原生协议 DENY 档回喂 role=tool 且带同一 tool_call_id`() {
        val h = AiLoopHarness(nativeReadLoop())
        try {
            h.prefs.setAiToolPolicy("read_file", AiToolPolicy.Mode.DENY)
            h.seedProperties()
            h.ask()

            val results = h.toolResultMessages(1)
            assertEquals("被拒绝的调用也必须有一条结果占位", 1, results.size)
            assertEquals("call_1", results.single().first)
            assertTrue(results.single().second.contains("已被禁用"))
            assertTrue(results.single().second.contains("不要重试"))
            assertFalse(h.requestBodyText(1).contains(secret))
            assertEquals(setOf("call_1"), h.declaredToolCallIds(1))
        } finally {
            h.close()
        }
    }

    @Test
    fun `原生协议 DENY 档抓网页用 url 参数判定，不会去联网`() {
        val h = AiLoopHarness(
            listOf(
                Sse.toolCall("call_9", "fetch_page", """{"url":"http://example.invalid/leak"}"""),
                Sse.final("好吧"),
            ),
        )
        try {
            h.prefs.setAiToolPolicy("fetch_page", AiToolPolicy.Mode.DENY)
            h.ask()

            val result = h.toolResultMessages(1).single().second
            assertTrue("抓取网页被禁止时要说明", result.contains("抓取网页") && result.contains("已被禁用"))
            assertFalse("被禁止时绝不能真的发出网络请求", h.requestBodyText(1).contains("example.invalid/leak") && result.contains("HTTP"))
        } finally {
            h.close()
        }
    }

    // ── ASK ──

    @Test
    fun `JSON 协议 ASK 档先挂起，批准后才真的读`() {
        val h = AiLoopHarness(jsonReadLoop(), model = JSON_MODEL)
        try {
            h.prefs.setAiToolPolicy("read_file", AiToolPolicy.Mode.ASK)
            h.seedProperties()
            h.ask()

            assertEquals("挂起期间不得发起下一轮请求", 1, h.requestCount())
            assertEquals("一次只挂起一个待确认", 1, h.messages.count { it.readRequest != null })
            val card = h.lastWithReadRequest()!!
            assertEquals(0, card.readState)
            assertTrue("理由要写清是用户设了每次询问", card.readRequest!!.reason.contains("每次询问"))
            assertFalse("批准前文件内容不得外发", h.requestBodyText(0).contains(secret))

            h.approveRead(card.id)

            assertEquals(2, h.requestCount())
            assertTrue("批准后才回喂文件内容", h.requestBodyText(1).contains(secret))
            assertTrue(h.requestBodyText(1).contains("用户已确认"))
            assertEquals(1, h.messages.first { it.id == card.id }.readState)
        } finally {
            h.close()
        }
    }

    @Test
    fun `JSON 协议 ASK 档拒绝后不读，并回喂拒绝结果`() {
        val h = AiLoopHarness(jsonReadLoop(), model = JSON_MODEL)
        try {
            h.prefs.setAiToolPolicy("read_file", AiToolPolicy.Mode.ASK)
            h.seedProperties()
            h.ask()
            val card = h.lastWithReadRequest()!!

            h.denyRead(card.id)

            assertEquals(2, h.requestCount())
            assertFalse("拒绝后文件内容绝不能出现在请求里", h.requestBodyText(1).contains(secret))
            assertTrue(h.requestBodyText(1).contains("用户拒绝了"))
            assertEquals(2, h.messages.first { it.id == card.id }.readState)
        } finally {
            h.close()
        }
    }

    @Test
    fun `原生协议 ASK 档挂起与恢复都用同一条 tool_call_id 回喂`() {
        val h = AiLoopHarness(nativeReadLoop())
        try {
            h.prefs.setAiToolPolicy("read_file", AiToolPolicy.Mode.ASK)
            h.seedProperties()
            h.ask()

            assertEquals(1, h.requestCount())
            val card = h.lastWithReadRequest()!!
            assertEquals(0, card.readState)

            h.approveRead(card.id)

            val results = h.toolResultMessages(1)
            assertEquals(1, results.size)
            assertEquals("恢复时必须用挂起那次调用的 id 配对", "call_1", results.single().first)
            assertTrue(results.single().second.contains(secret))
            assertEquals(setOf("call_1"), h.declaredToolCallIds(1))
        } finally {
            h.close()
        }
    }

    @Test
    fun `原生协议 ASK 档拒绝后不读且按 role=tool 回喂`() {
        val h = AiLoopHarness(nativeReadLoop())
        try {
            h.prefs.setAiToolPolicy("read_file", AiToolPolicy.Mode.ASK)
            h.seedProperties()
            h.ask()
            val card = h.lastWithReadRequest()!!

            h.denyRead(card.id)

            val results = h.toolResultMessages(1)
            assertEquals("call_1", results.single().first)
            assertTrue(results.single().second.contains("用户拒绝了"))
            assertFalse(h.requestBodyText(1).contains(secret))
            assertEquals(setOf("call_1"), h.declaredToolCallIds(1))
        } finally {
            h.close()
        }
    }

    // ── AUTO ──

    @Test
    fun `JSON 协议默认 AUTO 直接执行，不产生确认卡`() {
        val h = AiLoopHarness(jsonReadLoop(), model = JSON_MODEL)
        try {
            h.seedProperties()
            h.ask()

            assertEquals(2, h.requestCount())
            assertTrue("AUTO 档要真的读到内容", h.requestBodyText(1).contains(secret))
            assertNull(h.lastWithReadRequest())
        } finally {
            h.close()
        }
    }

    @Test
    fun `原生协议默认 AUTO 直接执行并回喂 role=tool`() {
        val h = AiLoopHarness(nativeReadLoop())
        try {
            h.seedProperties()
            h.ask()

            assertEquals(2, h.requestCount())
            assertNull(h.lastWithReadRequest())
            val result = h.toolResultMessages(1).single()
            assertEquals("call_1", result.first)
            assertTrue(result.second.contains(secret))
        } finally {
            h.close()
        }
    }

    @Test
    fun `敏感文件即使在 AUTO 档也要先确认`() {
        val h = AiLoopHarness(
            listOf(
                Sse.toolCall("call_ops", "read_file", """{"path":"ops.json"}"""),
                Sse.final("好的"),
            ),
        )
        try {
            // ops.json 不在 AiToolPolicy 的清单里，AUTO 也不该放行它
            h.instanceDir.resolve("ops.json").writeText("""["$secret"]""", Charsets.UTF_8)
            h.ask()

            assertEquals("敏感读取必须挂起", 1, h.requestCount())
            val card = h.lastWithReadRequest()!!
            assertTrue("理由要说清是管理员名单", card.readRequest!!.reason.contains("ops.json"))
            assertFalse(h.requestBodyText(0).contains(secret))

            h.approveRead(card.id)
            assertTrue(h.requestBodyText(1).contains(secret))
        } finally {
            h.close()
        }
    }

    // ── 失败可见 ──

    @Test
    fun `读取失败时界面必须可见而不是只显示动作`() {
        val h = AiLoopHarness(
            listOf(
                Sse.reply(jsonToolReply("读一下", "read_file", "not-there.properties")),
                Sse.reply(jsonFinalReply("没读到")),
            ),
            model = JSON_MODEL,
        )
        try {
            h.ask()

            val card = h.toolCards().last()
            assertTrue("没读到要让用户看见", card.isError)
            assertEquals(AiToolStep.Status.FAILED, card.steps.single().status)
            assertTrue(card.text.contains("读取 not-there.properties"))
            assertTrue(h.requestBodyText(1).contains("文件不存在"))
        } finally {
            h.close()
        }
    }
}