package com.kaze.newage.core.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.RuntimeException as KRuntimeException

/**
 * 「闪退也要留下现场」这条链路的测试。
 *
 * 背景：日志采集器跑在应用进程里，进程一闪退它跟着死，系统最后打的
 * `FATAL EXCEPTION` 根本来不及落盘。所以必须靠崩溃处理器**同步**写盘，
 * 这里锁住它的输出内容 —— 少了堆栈或 cause 链，事后就查不出原因。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppLogStoreCrashTest {

    private val store = AppLogStore(
        androidx.test.core.app.ApplicationProvider.getApplicationContext(),
    )

    @Test
    fun `崩溃文本包含线程名 时间 与完整堆栈`() {
        val boom = IllegalStateException("boom-原因")
        val text = store.formatCrash("main", boom)

        assertTrue("应标明线程", text.contains("main"))
        assertTrue("应有分隔标记", text.contains("崩溃"))
        assertTrue("应含异常类型", text.contains("IllegalStateException"))
        assertTrue("应含异常消息", text.contains("boom-原因"))
        assertTrue("应含栈帧", text.contains("AppLogStoreCrashTest"))
        assertTrue("应含时间戳（形如 2026-09-26 10:00:00）", Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}""").containsMatchIn(text))
    }

    @Test
    fun `cause 链不会丢`() {
        val root = KRuntimeException("根因")
        val outer = IllegalStateException("外层", root)
        val text = store.formatCrash("worker-1", outer)

        assertTrue("外层", text.contains("外层"))
        assertTrue("根因必须保留（只看最外层常常看不出问题）", text.contains("根因"))
    }

    @Test
    fun `日志目录在 files-log 下`() {
        assertEquals("logs", store.logDir.name)
        assertTrue(store.logDir.path.contains("files"))
    }
}
