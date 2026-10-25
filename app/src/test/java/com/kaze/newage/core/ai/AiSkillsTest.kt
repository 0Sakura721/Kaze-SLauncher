package com.kaze.newage.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 技能扫描与提示词生成测试。
 *
 * 重点：
 *  - 坏技能不能连带让整个清单消失（宽容解析）；
 *  - frontmatter 缺失时退回"目录名 + 正文首行"，随手写的 md 也能用；
 *  - 关掉的技能绝不能出现在提示词里（否则 AI 照读，开关就形同虚设）。
 */
class AiSkillsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun instance(): File = tmp.newFolder("inst")

    private fun skill(root: File, dir: String, content: String, fileName: String = "SKILL.md"): File {
        val d = File(root, "skills/$dir")
        d.mkdirs()
        val f = File(d, fileName)
        f.writeText(content)
        return d
    }

    @Test
    fun `没有 skills 目录时返回空且不报警告`() {
        val scan = AiSkills.scan(instance())
        assertTrue(scan.skills.isEmpty())
        assertTrue("目录不存在不该算问题", scan.warnings.isEmpty())
    }

    @Test
    fun `实例目录为 null 时安全返回空`() {
        val scan = AiSkills.scan(null)
        assertTrue(scan.skills.isEmpty())
    }

    @Test
    fun `解析 frontmatter 的 name 与 description`() {
        val root = instance()
        skill(
            root, "backup",
            """
            ---
            name: 备份与回滚
            description: 教你怎么在改配置前先做一份可回滚的备份
            ---

            正文从这里开始。
            """.trimIndent(),
        )
        val scan = AiSkills.scan(root)
        assertEquals(1, scan.skills.size)
        val s = scan.skills.single()
        assertEquals("备份与回滚", s.name)
        assertEquals("教你怎么在改配置前先做一份可回滚的备份", s.description)
        assertEquals("skills/backup/SKILL.md", s.relativePath)
    }

    @Test
    fun `没有 frontmatter 时退回目录名与正文首行`() {
        val root = instance()
        skill(root, "paper-perf", "# 标题\n\n调 Paper 的 view-distance 与 simulation-distance。\n更多内容…")
        val s = AiSkills.scan(root).skills.single()
        assertEquals("paper-perf", s.name)
        assertEquals("调 Paper 的 view-distance 与 simulation-distance。", s.description)
    }

    @Test
    fun `正文首行是标题或代码围栏时继续往下找`() {
        val root = instance()
        skill(root, "x", "# 大标题\n```\ncode\n```\n真正的说明在这里。")
        assertEquals("真正的说明在这里。", AiSkills.scan(root).skills.single().description)
    }

    @Test
    fun `文件名大小写不敏感`() {
        val root = instance()
        skill(root, "win", "说明文字", fileName = "skill.md")
        assertEquals(1, AiSkills.scan(root).skills.size)
    }

    @Test
    fun `缺 SKILL_md 的目录记一条警告但不影响其它技能`() {
        val root = instance()
        File(root, "skills/broken").mkdirs()                 // 空目录
        skill(root, "ok", "正常技能")
        val scan = AiSkills.scan(root)
        assertEquals(listOf("ok"), scan.skills.map { it.id })
        assertEquals(1, scan.warnings.size)
        assertTrue(scan.warnings.first().contains("broken"))
    }

    @Test
    fun `超大 SKILL_md 被跳过并给出警告`() {
        val root = instance()
        skill(root, "huge", "x".repeat(600 * 1024))
        val scan = AiSkills.scan(root)
        assertTrue(scan.skills.isEmpty())
        assertTrue(scan.warnings.single().contains("过大"))
    }

    @Test
    fun `技能按目录名排序（清单稳定可复现）`() {
        val root = instance()
        skill(root, "zeta", "z")
        skill(root, "alpha", "a")
        skill(root, "Mike", "m")
        assertEquals(listOf("alpha", "Mike", "zeta"), AiSkills.scan(root).skills.map { it.id })
    }

    @Test
    fun `没有技能时提示词段为空`() {
        assertEquals("", AiSkills.promptBlock(AiSkills.scan(instance()), emptySet()))
    }

    @Test
    fun `提示词段包含名字描述与可读路径`() {
        val root = instance()
        skill(
            root, "dynmap",
            """
            ---
            name: Dynmap
            description: 给服务器装网页地图
            ---
            """.trimIndent(),
        )
        val block = AiSkills.promptBlock(AiSkills.scan(root), emptySet())
        assertTrue(block.contains("可用技能"))
        assertTrue(block.contains("Dynmap"))
        assertTrue(block.contains("给服务器装网页地图"))
        assertTrue(block.contains("skills/dynmap/SKILL.md"))
        // 必须明确告诉模型"不相关就不要读"，否则它会无脑把技能全文读进上下文
        assertTrue(block.contains("不相关就不要读"))
    }

    @Test
    fun `被关掉的技能不出现在提示词里`() {
        val root = instance()
        skill(root, "a", "技能 A")
        skill(root, "b", "技能 B")
        val scan = AiSkills.scan(root)
        val block = AiSkills.promptBlock(scan, setOf("a"))
        assertFalse("关掉的技能不能出现在清单里", block.contains("skills/a/"))
        assertTrue(block.contains("skills/b/"))
    }

    @Test
    fun `全部关掉时提示词段为空`() {
        val root = instance()
        skill(root, "a", "A")
        val scan = AiSkills.scan(root)
        assertEquals("", AiSkills.promptBlock(scan, setOf("a")))
    }

    @Test
    fun `技能过多时截断并说明剩余数量`() {
        val root = instance()
        repeat(45) { i -> skill(root, "s%02d".format(i), "技能 $i") }
        val scan = AiSkills.scan(root)
        assertEquals(45, scan.skills.size)
        val block = AiSkills.promptBlock(scan, emptySet())
        assertTrue(block.contains("另有 5 个技能未列出"))
    }

    @Test
    fun `超长描述被截断`() {
        val root = instance()
        skill(root, "long", "x".repeat(500))
        val s = AiSkills.scan(root).skills.single()
        assertTrue("描述必须截断，否则会挤掉现场信息", s.description.length <= 200)
    }

    @Test
    fun `frontmatter 字段顺序任意、引号被去掉`() {
        val root = instance()
        skill(
            root, "q",
            """
            ---
            description: "带引号的描述"
            name: 'Quoted'
            ---
            """.trimIndent(),
        )
        val s = AiSkills.scan(root).skills.single()
        assertEquals("Quoted", s.name)
        assertEquals("带引号的描述", s.description)
    }

    @Test
    fun `CRLF 换行的 md 也能解析`() {
        val root = instance()
        skill(root, "crlf", "---\r\nname: Win\r\ndescription: 从 Windows 拷来的\r\n---\r\n\r\n正文")
        val s = AiSkills.scan(root).skills.single()
        assertEquals("Win", s.name)
        assertEquals("从 Windows 拷来的", s.description)
    }
}
