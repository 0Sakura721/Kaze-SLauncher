package com.kaze.newage.ui.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 测试台自检：假宿主 + 本地假 SSE 端点能真的跑通一次工具循环 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AiLoopHarnessProbeTest {

    @Test
    fun `JSON 夹带协议能跑完一轮读文件`() {
        val h = AiLoopHarness(
            responses = listOf(
                Sse.reply(jsonToolReply("我先看看配置", "read_file", "server.properties")),
                Sse.reply(jsonFinalReply("看完了：没问题")),
            ),
            model = JSON_MODEL,
        )
        try {
            h.instanceDir.resolve("server.properties").writeText("motd=hi\n", Charsets.UTF_8)
            h.ask()
            assertEquals("应有两轮请求", 2, h.requestCount())
            assertTrue(h.assistantText().contains("看完了"))
        } finally {
            h.close()
        }
    }

    @Test
    fun `原生协议能跑完一轮读文件`() {
        val h = AiLoopHarness(
            responses = listOf(
                Sse.toolCall("call_1", "read_file", """{"path":"server.properties"}"""),
                Sse.final("完成"),
            ),
        )
        try {
            h.instanceDir.resolve("server.properties").writeText("motd=hi\n", Charsets.UTF_8)
            h.ask()
            assertEquals("应有两轮请求", 2, h.requestCount())
            assertEquals(listOf("call_1"), h.toolResultMessages(1).map { it.first })
            assertTrue(h.toolResultMessages(1).first().second.contains("motd=hi"))
        } finally {
            h.close()
        }
    }
}