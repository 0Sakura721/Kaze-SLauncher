package com.kaze.newage.core.server

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 用户自定义 JVM 附加参数的切分（DefaultServerManager.extraJvmArgList）。
 *
 * 这是「实例高级设置」直接拼进 java 命令行的内容：切错（把空串切成 [""]）
 * 会给 java 传一个空参数，进程直接起不来，所以边界场景单独锁住。
 */
class DefaultServerManagerJvmArgsTest {

    @Test
    fun `按空白切分并去掉首尾空白`() {
        assertEquals(
            listOf("-XX:+UseG1GC", "-Dlog4j.skipJansi=true"),
            DefaultServerManager.extraJvmArgList("  -XX:+UseG1GC   -Dlog4j.skipJansi=true  "),
        )
    }

    @Test
    fun `空串与纯空白得到空列表`() {
        assertEquals(emptyList<String>(), DefaultServerManager.extraJvmArgList(""))
        assertEquals(emptyList<String>(), DefaultServerManager.extraJvmArgList("   \t\n  "))
    }

    @Test
    fun `制表符与换行同样算空白分隔`() {
        assertEquals(listOf("a", "b", "c"), DefaultServerManager.extraJvmArgList("a\tb\nc"))
    }
}
