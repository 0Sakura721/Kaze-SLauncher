package com.kaze.newage.ui.ai

import com.kaze.newage.core.ai.AiCommandPolicy
import com.kaze.newage.core.server.ServerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 控制台命令的执行闸门（[AiCommandPolicy]：仅建议 / 白名单自动 / 全部自动）。
 *
 * 这是"AI 能不能自己往服务端 stdin 里写字"的唯一开关，此前没有测试：
 *  1. 仅建议档只出建议卡片，**一个字节都不能发给服务端**；
 *  2. 白名单档只放行查询类命令，`stop` 这类仍降级为建议卡片；
 *  3. 服务端没运行时必须拒绝并说明原因（stdin 不存在，发了也是静默丢失，还会谎报"已执行"）；
 *  4. 原生协议下连 `execute_command` 这个工具本身都不该出现在工具清单里（仅建议档）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AiCommandGateLoopTest {

    private fun commandLoop(command: String) = listOf(
        Sse.reply(jsonToolReply("建议执行这条", "execute_command", command)),
        Sse.reply(jsonFinalReply("已经处理")),
    )

    private fun AiLoopHarness.commandCards() = messages.filter { !it.isUser && it.command != null }

    @Test
    fun `仅建议档下命令只出建议卡片，不发给服务端`() {
        val h = AiLoopHarness(commandLoop("list"), model = JSON_MODEL)
        try {
            h.ask()

            assertTrue("仅建议档绝不能自动发送", h.manager.sent.isEmpty())
            assertEquals(2, h.requestCount())
            assertTrue(
                "要回喂模型'已展示建议卡片、等用户确认'",
                h.requestBodyText(1).contains("已向用户展示命令建议卡片"),
            )
            val card = h.commandCards().last()
            assertEquals("list", card.command)
            assertFalse("没有用户点击就不能算已执行", card.commandSent)
            assertTrue(card.text.contains("需要你确认"))
        } finally {
            h.close()
        }
    }

    @Test
    fun `白名单自动档下查询类命令直接执行并回喂执行摘要`() {
        val h = AiLoopHarness(commandLoop("list"), model = JSON_MODEL)
        try {
            h.prefs.setAiCommandMode(AiCommandPolicy.Mode.SAFE.id)
            h.ask()

            assertEquals(listOf("list"), h.manager.sent.toList())
            assertEquals(2, h.requestCount())
            assertTrue(h.requestBodyText(1).contains("已执行：list"))
            assertTrue(h.messages.any { it.toolNote == "执行了 list" })
        } finally {
            h.close()
        }
    }

    @Test
    fun `白名单自动档下非白名单命令仍降级为建议卡片`() {
        val h = AiLoopHarness(commandLoop("stop"), model = JSON_MODEL)
        try {
            h.prefs.setAiCommandMode(AiCommandPolicy.Mode.SAFE.id)
            h.ask()

            assertTrue("stop 不在白名单里，绝不能自动执行", h.manager.sent.isEmpty())
            assertEquals(2, h.requestCount())
            // 两处文案分工不同：
            //   回喂给模型的是"已向用户展示命令建议卡片"（模型据此知道要等用户）；
            //   "需要你确认"是给用户看的气泡文案 —— 别拿回喂正文去找气泡文案。
            assertTrue(
                "回喂里没有说明'已展示建议卡片、等用户确认'",
                h.requestBodyText(1).contains("已向用户展示命令建议卡片"),
            )
            val card = h.commandCards().last()
            assertEquals("stop", card.command)
            assertFalse(card.commandSent)
        } finally {
            h.close()
        }
    }

    @Test
    fun `服务端未运行时拒绝执行并把原因回喂模型`() {
        val h = AiLoopHarness(commandLoop("list"), model = JSON_MODEL)
        try {
            h.prefs.setAiCommandMode(AiCommandPolicy.Mode.SAFE.id)
            h.manager.setState(h.instanceId, ServerState.Stopped)
            h.ask()

            assertTrue("未运行时不能发命令", h.manager.sent.isEmpty())
            assertEquals(2, h.requestCount())
            assertTrue(
                "必须如实回喂'服务端未运行'，否则模型会以为是执行成功了",
                h.requestBodyText(1).contains("服务端未运行"),
            )
        } finally {
            h.close()
        }
    }

    @Test
    fun `仅建议档不会把 execute_command 交给模型`() {
        val suggest = AiLoopHarness(listOf(Sse.final("好的")), model = JSON_MODEL)
        try {
            suggest.ask()
            // 说明：原生工具清单只对**官方 DeepSeek 端点**下发
            // （AiClient.supportsNativeThinkingParam 判定）。这个 harness 用的是 JSON 夹带
            // 协议的本地假端点，请求体里本来就没有 tools 字段 —— 拿 toolNames(0) 断言
            // 必然失败。这里断言真正成立的事实：请求体里不出现 execute_command。
            // （原生协议下"工具清单随档位增减"由 AiToolPermissionLoopTest 的原生用例覆盖。）
            assertFalse(
                "仅建议档不该把 execute_command 交给模型",
                suggest.requestBodyText(0).contains("\"execute_command\""),
            )
        } finally {
            suggest.close()
        }
    }

    @Test
    fun `界面上的执行按钮在服务端未运行时拒绝发送，运行后才发送并标记`() {
        val h = AiLoopHarness(
            listOf(Sse.reply(jsonFinalReply("建议执行 list", command = "list"))),
            model = JSON_MODEL,
        )
        try {
            h.ask()
            val card = h.commandCards().last()
            assertEquals("list", card.command)

            h.manager.setState(h.instanceId, ServerState.Stopped)
            h.controller.executeAiSuggestion(card.id)
            assertTrue("未运行时点执行也必须拒绝", h.manager.sent.isEmpty())
            assertFalse(h.messages.first { it.id == card.id }.commandSent)

            h.manager.setState(h.instanceId, ServerState.Running)
            h.controller.executeAiSuggestion(card.id)
            // 第一次点击是空操作，第二次才真的发出去 —— 且只发一次
            assertEquals(listOf("list"), h.manager.sent.toList())
            assertTrue(h.messages.first { it.id == card.id }.commandSent)
        } finally {
            h.close()
        }
    }
}