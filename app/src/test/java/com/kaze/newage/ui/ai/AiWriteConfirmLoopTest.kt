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
 * 文件写入的挂起与恢复（`write_file` 是本工具循环里唯一"必须人来点一下"的动作）。
 *
 * 盯四件事：
 *  1. 挂起期间**绝不落盘**，批准才写；拒绝不写（写坏文件是不可逆的）；
 *  2. 一批里有两个写入时只挂起第一个，其余的按"已跳过"回喂占位 —— 否则
 *     assistant 里的 tool_call 没有配对结果，严格的服务端下一轮直接 400；
 *  3. 结构性禁止的文件（ops.json / eula.txt / *.mcfunction…）连"允许写入"按钮都不给；
 *  4. 批准/拒绝是终态，重复点击不重复执行。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AiWriteConfirmLoopTest {

    private fun jsonWriteLoop(content: String = "motd=新的\n") = listOf(
        Sse.reply(jsonToolReply("我准备改配置", "write_file", "config.yml", content)),
        Sse.reply(jsonFinalReply("改好了")),
    )

    private fun AiLoopHarness.writeCards() = messages.filter { it.writeRequest != null }

    // ── JSON 夹带协议：挂起 / 批准 / 拒绝 ──

    @Test
    fun `JSON 协议写入挂起期间不落盘，批准后才写`() {
        val h = AiLoopHarness(jsonWriteLoop())
        try {
            h.ask()

            val target = h.instanceDir.resolve("config.yml")
            assertFalse("挂起期间绝不能落盘", target.exists())
            assertEquals("一次只挂起一个待确认写入", 1, h.writeCards().size)
            val card = h.writeCards().single()
            assertEquals(0, card.writeState)
            assertEquals("config.yml", card.writeRequest!!.path)
            assertEquals("motd=新的\n", card.writeRequest!!.content)
            assertEquals("挂起期间不得发起下一轮请求", 1, h.requestCount())

            h.approveWrite(card.id)

            assertTrue(target.exists())
            assertEquals("motd=新的\n", target.readText(Charsets.UTF_8))
            assertEquals(1, h.messages.first { it.id == card.id }.writeState)
            assertEquals(2, h.requestCount())
            assertTrue("写入结果要回喂给模型", h.requestBodyText(1).contains("已写入"))
        } finally {
            h.close()
        }
    }

    @Test
    fun `JSON 协议拒绝写入不落盘并把拒绝结果回喂`() {
        val h = AiLoopHarness(jsonWriteLoop())
        try {
            h.ask()
            val card = h.writeCards().single()

            h.denyWrite(card.id)

            assertFalse("拒绝后绝不能落盘", h.instanceDir.resolve("config.yml").exists())
            assertEquals(2, h.messages.first { it.id == card.id }.writeState)
            assertEquals(2, h.requestCount())
            assertTrue(h.requestBodyText(1).contains("用户拒绝了本次写入"))
            assertTrue("要明确要求不重试", h.requestBodyText(1).contains("不要重试"))
        } finally {
            h.close()
        }
    }

    @Test
    fun `写入批准后重复点击不会重复执行`() {
        val h = AiLoopHarness(jsonWriteLoop())
        try {
            h.ask()
            val card = h.writeCards().single()
            h.approveWrite(card.id)
            val afterFirst = h.requestCount()

            // 卡片已经处于"已允许"终态：再点一次必须是空操作
            h.approveWrite(card.id)

            assertEquals(afterFirst, h.requestCount())
            assertEquals(1, h.messages.first { it.id == card.id }.writeState)
        } finally {
            h.close()
        }
    }

    @Test
    fun `JSON 协议 DENY 档不挂起不落盘，并明确告诉模型被禁用`() {
        val h = AiLoopHarness(jsonWriteLoop())
        try {
            h.prefs.setAiToolPolicy("write_file", AiToolPolicy.Mode.DENY)
            h.ask()

            assertNull("禁止档不该给出确认卡", h.writeCards().firstOrNull())
            assertFalse(h.instanceDir.resolve("config.yml").exists())
            assertEquals(2, h.requestCount())
            val body = h.requestBodyText(1)
            assertTrue(body.contains("写入文件工具已被禁用"))
            assertTrue(body.contains("不要重试"))

            val card = h.messages.last { !it.isUser && it.steps.isNotEmpty() }
            assertEquals(AiToolStep.Status.DENIED, card.steps.single().status)
        } finally {
            h.close()
        }
    }

    @Test
    fun `原生协议 DENY 档按 role=tool 回喂禁用说明`() {
        val h = AiLoopHarness(
            listOf(
                Sse.toolCall("call_w", "write_file", """{"path":"config.yml","content":"motd=x"}"""),
                Sse.final("好吧"),
            ),
        )
        try {
            h.prefs.setAiToolPolicy("write_file", AiToolPolicy.Mode.DENY)
            h.ask()

            assertNull(h.writeCards().firstOrNull())
            assertFalse(h.instanceDir.resolve("config.yml").exists())
            val result = h.toolResultMessages(1).single()
            assertEquals("call_w", result.first)
            assertTrue(result.second.contains("已被禁用"))
            assertTrue(result.second.contains("不要重试"))
        } finally {
            h.close()
        }
    }

    @Test
    fun `写入内容为空时回喂失败，不挂起确认`() {
        val h = AiLoopHarness(
            listOf(
                Sse.toolCall("call_e", "write_file", """{"path":"config.yml"}"""),
                Sse.final("那我补全"),
            ),
        )
        try {
            h.ask()

            assertNull(h.writeCards().firstOrNull())
            assertFalse(h.instanceDir.resolve("config.yml").exists())
            val result = h.toolResultMessages(1).single()
            assertTrue(result.second.contains("content 为空"))
        } finally {
            h.close()
        }
    }

    // ── 一批两个写入：只挂起第一个 ──

    @Test
    fun `原生协议一批两个写入只挂起第一个，第二个回喂已跳过`() {
        val h = AiLoopHarness(
            listOf(
                Sse.toolCalls(
                    Triple("call_a", "write_file", """{"path":"a.yml","content":"A=1"}"""),
                    Triple("call_b", "write_file", """{"path":"b.yml","content":"B=2"}"""),
                ),
                Sse.final("写完了"),
            ),
        )
        try {
            h.ask()

            assertEquals("绝不能同时挂起两个待确认", 1, h.writeCards().size)
            assertEquals("a.yml", h.writeCards().single().writeRequest!!.path)
            assertFalse(h.instanceDir.resolve("a.yml").exists())
            assertFalse("被跳过的那个更不能落盘", h.instanceDir.resolve("b.yml").exists())
            assertEquals("挂起时不发起下一轮", 1, h.requestCount())

            h.approveWrite(h.writeCards().single().id)

            assertTrue(h.instanceDir.resolve("a.yml").exists())
            assertFalse("被跳过的写入要等模型重新发起", h.instanceDir.resolve("b.yml").exists())
            assertEquals(2, h.requestCount())
            // 协议完整性：两个 tool_call 在下一轮请求里都必须有结果
            val results = h.toolResultMessages(1).toMap()
            assertEquals(setOf("call_a", "call_b"), results.keys)
            assertTrue(results.getValue("call_a").contains("已写入"))
            assertTrue("第二个要回喂'已跳过'而不是静默丢弃", results.getValue("call_b").contains("已跳过"))
            assertEquals(setOf("call_a", "call_b"), h.declaredToolCallIds(1))
        } finally {
            h.close()
        }
    }

    // ── 结构性禁止 ──

    @Test
    fun `JSON 协议对结构性禁止的文件不给确认卡`() {
        val h = AiLoopHarness(
            listOf(
                Sse.reply(jsonToolReply("我帮你加个管理员", "write_file", "ops.json", """["steve"]""")),
                Sse.reply(jsonFinalReply("好")),
            ),
            model = JSON_MODEL,
        )
        try {
            h.ask()

            assertNull("这类文件不该出现'允许写入'按钮", h.writeCards().firstOrNull())
            assertFalse(h.instanceDir.resolve("ops.json").exists())
            assertTrue(h.requestBodyText(1).contains("不允许通过 AI 修改"))
        } finally {
            h.close()
        }
    }

    @Test
    fun `原生协议对结构性禁止的文件按 role=tool 回喂策略拒绝`() {
        val h = AiLoopHarness(
            listOf(
                Sse.toolCall("call_forbid", "write_file", """{"path":"ops.json","content":"[]"}"""),
                Sse.final("好"),
            ),
        )
        try {
            h.ask()

            assertNull(h.writeCards().firstOrNull())
            assertFalse(h.instanceDir.resolve("ops.json").exists())
            val result = h.toolResultMessages(1).single()
            assertEquals("call_forbid", result.first)
            assertTrue(result.second.contains("被启动器策略拒绝"))
        } finally {
            h.close()
        }
    }

    // ── 权限档位里的「自动」必须真的生效 ──

    /**
     * 这条曾经是"记录当前行为"：旧实现两条协议分支都无条件挂起确认，AUTO 档形同虚设，
     * 与档位里"自动 = 不打扰你"的描述不一致。**该缺陷已修** —— `write_file` 设「自动」时
     * 直接写入、不弹确认卡；结构性禁止的文件与「禁止」档仍在其前拦下，AUTO 不是后门。
     */
    @Test
    fun `write_file 设成自动档时直接写入且不挂起确认`() {
        val h = AiLoopHarness(jsonWriteLoop())
        try {
            h.prefs.setAiToolPolicy("write_file", AiToolPolicy.Mode.AUTO)
            h.ask()

            // 1) 真的落盘了（没有点任何按钮）
            assertTrue(
                "AUTO 档应当直接写入，但文件没生成",
                h.instanceDir.resolve("config.yml").exists(),
            )
            // 2) 不该再产生待确认卡片
            assertEquals("AUTO 档不该弹确认卡", 0, h.writeCards().size)
            // 3) 模型要被告知"已按设置自动写入"，否则它会以为还要等用户
            assertTrue(
                "回喂里没有说明已自动写入",
                h.requestBodyText(1).contains("自动"),
            )
        } finally {
            h.close()
        }
    }
}