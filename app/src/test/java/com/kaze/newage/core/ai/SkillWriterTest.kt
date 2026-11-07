package com.kaze.newage.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 技能写入测试。
 *
 * 重点测**破坏性场景**，因为这里写的是用户磁盘上的真实文件：
 *  - 目录名越界（`../` 写到 skills 外面）；
 *  - 同名覆盖（技能是手写文档，静默覆盖等于丢数据）；
 *  - frontmatter 生成是否合法（写错了 AI 就读不到描述）。
 */
class SkillWriterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ══════════ 目录名规整 ══════════

    @Test
    fun `正常名字原样保留`() {
        assertEquals("建筑规范", SkillWriter.safeId("建筑规范"))
        assertEquals("build-guide", SkillWriter.safeId("build-guide"))
        assertEquals("my skill", SkillWriter.safeId("my skill"))
    }

    @Test
    fun `路径分隔符被替换掉（防越界）`() {
        // "../evil" 必须变成一个普通名字，绝不能保留 ".."
        val id = SkillWriter.safeId("../evil")
        assertNotNull(id)
        assertFalse("不能含斜杠", id!!.contains('/') || id.contains('\\'))
        assertFalse("不能是 .. 或其变体", id == ".." || id.startsWith("."))
    }

    @Test
    fun `以点开头的名字被去掉点`() {
        val id = SkillWriter.safeId("...hidden")
        assertNotNull(id)
        assertFalse(id!!.startsWith("."))
    }

    @Test
    fun `空名字与纯符号返回 null`() {
        assertNull(SkillWriter.safeId(""))
        assertNull(SkillWriter.safeId("   "))
        assertNull(SkillWriter.safeId("///"))
        assertNull(SkillWriter.safeId("---"))
    }

    @Test
    fun `超长名字被截断`() {
        val id = SkillWriter.safeId("a".repeat(200))
        assertNotNull(id)
        assertTrue("不超过上限", id!!.length <= SkillWriter.MAX_ID_LENGTH)
    }

    @Test
    fun `连续短横被折叠`() {
        assertEquals("a-b", SkillWriter.safeId("a----b"))
    }

    // ══════════ frontmatter 生成 ══════════

    @Test
    fun `生成的内容能被 AiSkills 解析出名字与描述`() {
        val content = SkillWriter.buildContent("建筑规范", "服务器建筑风格约定", "正文内容")
        assertTrue(content.startsWith("---\n"))
        assertTrue(content.contains("name: 建筑规范"))
        assertTrue(content.contains("description: 服务器建筑风格约定"))
        assertTrue(content.endsWith("正文内容\n"))
    }

    @Test
    fun `描述里的换行被压成一行（否则 YAML 结构坏掉）`() {
        val content = SkillWriter.buildContent("n", "第一行\n第二行", "b")
        val descLine = content.lines().first { it.startsWith("description:") }
        assertEquals("description: 第一行 第二行", descLine)
    }

    @Test
    fun `描述里的引号被替换（避免解析歧义）`() {
        val content = SkillWriter.buildContent("n", "他说\"你好\"", "b")
        assertFalse("不能留英文双引号", content.contains("他说\"你好\""))
    }

    @Test
    fun `没有名字时给占位而不是空`() {
        val content = SkillWriter.buildContent("  ", "d", "b")
        assertTrue(content.contains("name: 未命名技能"))
    }

    // ══════════ 新建 ══════════

    @Test
    fun `新建成功并落盘到 skills-slash-id-slash-SKILL点md`() {
        val inst = tmp.newFolder("inst")
        val r = SkillWriter.create(inst, "建筑规范", "风格约定", "正文")
        assertTrue(r is SkillWriter.Result.Ok)
        val file = File(inst, "skills/建筑规范/${AiSkills.SKILL_FILE}")
        assertTrue("文件应存在", file.exists())
        assertTrue("内容应含正文", file.readText().contains("正文"))
    }

    @Test
    fun `同名技能不被覆盖`() {
        val inst = tmp.newFolder("inst")
        assertTrue(SkillWriter.create(inst, "dup", "甲", "第一版") is SkillWriter.Result.Ok)
        val r = SkillWriter.create(inst, "dup", "乙", "第二版")
        assertTrue("第二次必须失败", r is SkillWriter.Result.Failed)
        val file = File(inst, "skills/dup/${AiSkills.SKILL_FILE}")
        assertTrue("原内容不能被改写", file.readText().contains("第一版"))
    }

    @Test
    fun `空正文被拒绝`() {
        val inst = tmp.newFolder("inst")
        val r = SkillWriter.create(inst, "x", "d", "   ")
        assertTrue(r is SkillWriter.Result.Failed)
    }

    @Test
    fun `超长正文被拒绝`() {
        val inst = tmp.newFolder("inst")
        val r = SkillWriter.create(inst, "x", "d", "a".repeat(SkillWriter.MAX_BODY_CHARS + 1))
        assertTrue(r is SkillWriter.Result.Failed)
    }

    @Test
    fun `名字无效被拒绝`() {
        val inst = tmp.newFolder("inst")
        val r = SkillWriter.create(inst, "///", "d", "body")
        assertTrue(r is SkillWriter.Result.Failed)
        assertFalse("不该创建任何东西", File(inst, "skills").exists())
    }

    // ══════════ 导入 ══════════

    @Test
    fun `导入已有 frontmatter 的文件时原样保留`() {
        val inst = tmp.newFolder("inst")
        val original = "---\nname: 原名字\ndescription: 原描述\n---\n\n正文"
        val r = SkillWriter.importText(inst, "my-skill.md", original)
        assertTrue(r is SkillWriter.Result.Ok)
        val file = File(inst, "skills/my-skill/${AiSkills.SKILL_FILE}")
        assertEquals("内容应原样写入", original, file.readText())
    }

    @Test
    fun `导入没有 frontmatter 的文件时自动补一个`() {
        val inst = tmp.newFolder("inst")
        val r = SkillWriter.importText(inst, "notes.md", "# 标题\n\n内容")
        assertTrue(r is SkillWriter.Result.Ok)
        val text = File(inst, "skills/notes/${AiSkills.SKILL_FILE}").readText()
        assertTrue("应补 frontmatter", text.startsWith("---\n"))
        assertTrue("name 用文件名", text.contains("name: notes"))
    }

    @Test
    fun `导入空文件被拒绝`() {
        val inst = tmp.newFolder("inst")
        assertTrue(SkillWriter.importText(inst, "empty.md", "  \n ") is SkillWriter.Result.Failed)
    }

    @Test
    fun `导入同名技能被拒绝`() {
        val inst = tmp.newFolder("inst")
        assertTrue(SkillWriter.importText(inst, "same.md", "内容") is SkillWriter.Result.Ok)
        assertTrue(SkillWriter.importText(inst, "same.md", "别的内容") is SkillWriter.Result.Failed)
    }

    // ══════════ 删除 ══════════

    @Test
    fun `删除成功`() {
        val inst = tmp.newFolder("inst")
        assertTrue(SkillWriter.create(inst, "gone", "d", "b") is SkillWriter.Result.Ok)
        assertTrue(SkillWriter.delete(inst, "gone") is SkillWriter.Result.Ok)
        assertFalse(File(inst, "skills/gone").exists())
    }

    @Test
    fun `删除越界名字被拒绝`() {
        val inst = tmp.newFolder("inst")
        // 造一个 skills 外面的目录，确认删除不会碰到它
        val outside = File(inst, "important").apply { mkdirs() }
        val safe = SkillWriter.safeId("../important")
        val r = SkillWriter.delete(inst, "../important")
        assertTrue("必须拒绝", r is SkillWriter.Result.Failed)
        assertTrue("skills 外的目录不能被删", outside.exists())
        assertNotNull(safe)
    }

    @Test
    fun `删除不存在的技能返回失败`() {
        val inst = tmp.newFolder("inst")
        assertTrue(SkillWriter.delete(inst, "nope") is SkillWriter.Result.Failed)
    }

    // ══════════ 与扫描联动 ══════════

    @Test
    fun `新建后立刻能被 scan 发现`() {
        val inst = tmp.newFolder("inst")
        SkillWriter.create(inst, "联动测试", "描述文本", "正文")
        val scan = AiSkills.scan(inst)
        assertEquals(1, scan.skills.size)
        assertEquals("联动测试", scan.skills[0].name)
        assertEquals("描述文本", scan.skills[0].description)
    }
}