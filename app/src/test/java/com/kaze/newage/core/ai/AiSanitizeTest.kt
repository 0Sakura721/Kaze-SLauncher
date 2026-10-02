package com.kaze.newage.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 上屏净化测试：C0/C1 控制符、bidi 控制符、零宽字符都必须被抹掉。
 *
 * 这些用例锁的是"用户看到的 = 实际发生的"：确认卡上显示的文件名/内容一旦能被
 * 控制符改写，人工确认这道闸门就形同虚设。
 */
class AiSanitizeTest {

    @Test
    fun `C0 与 C1 控制符被抹掉`() {
        assertEquals("ab", AiSanitize.display("a\u0000b"))
        assertEquals("[31mred", AiSanitize.display("\u001B[31mred"))   // 终端转义序列
        assertEquals("ab", AiSanitize.display("a\u007Fb"))
        assertEquals("ab", AiSanitize.display("a\u0085b"))            // NEL（C1）
        assertEquals("ab", AiSanitize.display("a\u009Bb"))            // CSI（C1）
    }

    @Test
    fun `bidi 控制符被抹掉`() {
        // RLO 之类的字符能让渲染顺序与真实字符串不一致（文件名看着是 A、实际是 B）
        assertEquals("gnpexe", AiSanitize.display("gnp\u202Eexe"))
        assertEquals("ops.json", AiSanitize.display("ops\u202E.json"))
        assertEquals("abc", AiSanitize.display("\u202Aabc\u202C"))
        assertEquals("abc", AiSanitize.display("\u2066abc\u2069"))
        assertEquals("abc", AiSanitize.display("a\u200Eb\u200Fc"))
        assertEquals("abc", AiSanitize.display("a\u061Cbc"))
    }

    @Test
    fun `零宽字符被抹掉`() {
        assertEquals("ops.json", AiSanitize.display("ops\u200B.json"))
        assertEquals("abc", AiSanitize.display("a\u200Db\uFEFFc"))
    }

    @Test
    fun `多行预览保留换行与制表`() {
        assertEquals("a\nb", AiSanitize.display("a\nb"))
        assertEquals("a\tb", AiSanitize.display("a\tb"))
        assertEquals("a\r\nb", AiSanitize.display("a\r\nb"))
    }

    @Test
    fun `单行展示把换行折成空格`() {
        // 路径里混进换行 = 能在卡片上伪造出"第二个文件"
        assertEquals("server.properties ops.json", AiSanitize.displayOneLine("server.properties\nops.json"))
        assertEquals("a b", AiSanitize.displayOneLine("a\r\nb"))
        assertEquals("a b", AiSanitize.displayOneLine("a\tb"))
        assertEquals("ops.json", AiSanitize.displayOneLine("ops\u202E.json"))
    }

    @Test
    fun `干净文本原样返回同一个对象`() {
        // 热路径优化：确认卡每次重组都调它，没脏字符就不该复制字符串
        val clean = "server.properties"
        assertSame(clean, AiSanitize.display(clean))
        assertSame(clean, AiSanitize.displayOneLine(clean))
    }
}
