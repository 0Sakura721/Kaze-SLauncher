package com.kaze.newage.core.server

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * eula.txt 判定与改写。
 *
 * 重点是 `isAccepted` 必须**逐行锚定**：服务端自己生成的 eula.txt 头部注释里就带着
 * "…you are indicating your agreement to our EULA…"，用户/教程也常留下 `# eula=true`
 * 这类被注释掉的备用行。无锚定匹配会把它们当成"已接受"，于是服务端起来就因
 * `You need to agree to the EULA` 立刻退出，而界面只报一句笼统的早退错误。
 */
class EulaHandlerTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("kaze-eula-test").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun write(text: String) = File(dir, "eula.txt").writeText(text)

    @Test
    fun `没有文件时不算已接受`() {
        assertFalse(EulaHandler.isAccepted(dir))
    }

    @Test
    fun `真正的 eula=true 才算已接受`() {
        write("#By changing the setting below to TRUE you are indicating your agreement to our EULA\n")
        assertFalse("只有注释、没有键时不算已接受", EulaHandler.isAccepted(dir))

        write("#comment\neula=true\n")
        assertTrue(EulaHandler.isAccepted(dir))

        // 大小写与空格：Properties 允许 `eula = TRUE`
        write("eula = TRUE\n")
        assertTrue(EulaHandler.isAccepted(dir))
    }

    @Test
    fun `被注释掉的 eula=true 不算已接受`() {
        write("# eula=true\n")
        assertFalse("注释行不是有效配置", EulaHandler.isAccepted(dir))

        write("!eula=true\n")
        assertFalse("感叹号也是 Properties 的注释前缀", EulaHandler.isAccepted(dir))
    }

    @Test
    fun `eula=false 行里的 eula=true 子串不算已接受`() {
        write("#previous value: eula=true\neula=false\n")
        assertFalse(EulaHandler.isAccepted(dir))
    }

    @Test
    fun `accept 写出可被判定的文件`() {
        EulaHandler.accept(dir)
        assertTrue(EulaHandler.isAccepted(dir))
    }

    @Test
    fun `flipToTrue 保留其余内容并改掉 false`() {
        write("# 我的批注\neula=false\nmotd=hi\n")
        val r = EulaHandler.flipToTrue(dir)
        assertTrue(r.accepted)
        assertTrue(r.changed)
        val text = File(dir, "eula.txt").readText()
        assertTrue("注释必须保留", text.contains("# 我的批注"))
        assertTrue("其余内容必须保留", text.contains("motd=hi"))
        assertTrue(EulaHandler.isAccepted(dir))
    }
}
