package com.kaze.newage.core.server

import com.kaze.newage.data.model.ServerInstance
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 实例显示名带 `/`（或 `\` `:` 等）时的备份回归测试。
 *
 * 实例名是用户自己填的，与实例**目录名**（InstanceStore 会净化）是两回事。
 * `backup()` 原来直接拿 `instance.name` 拼文件名：
 *  - `生存/正式` → 路径落到 `backups/生存/正式_<时间戳>.zip`，而 `生存` 这一级从没被创建过，
 *    `FileOutputStream` 直接 FileNotFoundException —— 备份永远失败；
 *  - 而 `list()` 又用 `startsWith("${instance.name}_")` 匹配，两边口径还不一致：
 *    即使文件名里的非法字符被换成 `_`，列出来也匹配不上（备份建出来了却看不见）。
 */
class BackupManagerNameTest {

    private fun instanceWith(name: String, dir: File, id: String = java.util.UUID.randomUUID().toString()): ServerInstance =
        ServerInstance(id = id, name = name, dir = dir)

    @Test
    fun `名字带斜杠时备份仍然建得出来并列得出来`() {
        val root = Files.createTempDirectory("kaze-backup-name").toFile()
        val dir = File(root, "生存_正式").apply { mkdirs() }
        File(dir, "server.properties").writeText("server-port=25565\n")
        val instance = instanceWith("生存/正式", dir)

        val backup = BackupManager.backup(instance)

        assertTrue("备份文件不存在：${backup.absolutePath}", backup.isFile)
        assertEquals(
            "备份不该落到别的目录里",
            File(root, "backups/${dir.name}").absolutePath,
            backup.parentFile?.absolutePath,
        )
        assertTrue(
            "备份文件名里还留着斜杠：${backup.name}",
            !backup.name.contains('/') && !backup.name.contains('\\'),
        )
        assertTrue(
            "建出来的备份没被 list() 认出来：${backup.name}",
            BackupManager.list(instance).any { it.absolutePath == backup.absolutePath },
        )
    }

    @Test
    fun `名字带冒号与空格时备份文件名可用且可列出`() {
        val root = Files.createTempDirectory("kaze-backup-name2").toFile()
        val dir = File(root, "我的服").apply { mkdirs() }
        File(dir, "eula.txt").writeText("eula=true\n")
        val instance = instanceWith("我的服: 正式", dir)

        val backup = BackupManager.backup(instance)

        assertTrue(backup.isFile)
        assertTrue(!backup.name.contains(':') && !backup.name.contains(' '))
        assertTrue(BackupManager.list(instance).any { it.absolutePath == backup.absolutePath })
    }

    /**
     * 重命名实例后老备份必须还认得出。
     *
     * 备份文件名里原来只写显示名，`list()` 也只比显示名前缀 —— 用户在实例详情里改个名字，
     * 之前所有的备份就在界面里**凭空消失**了（文件其实还在磁盘上）。
     */
    @Test
    fun `重命名实例后备份仍然列得出来`() {
        val root = Files.createTempDirectory("kaze-backup-rename").toFile()
        val dir = File(root, "生存服").apply { mkdirs() }
        File(dir, "server.properties").writeText("server-port=25565\n")

        val before = instanceWith("生存服", dir)
        val backup = BackupManager.backup(before)

        val renamed = before.copy(name = "我的生存服（第二季）")
        val listed = BackupManager.list(renamed)
        assertTrue(
            "改名后老备份从列表里消失了：${listed.map { it.name }}",
            listed.any { it.absolutePath == backup.absolutePath },
        )
    }

    /**
     * 两个同名实例的备份不能互相串。
     *
     * 显示名可以重复（实例详情里改成一样），而文件名前缀 + 秒级时间戳完全可能撞上。
     * 恢复时串台就是把别人的世界存档盖到自己实例上。新备份带实例 id，按 id 认。
     */
    @Test
    fun `同名实例的备份互不串台`() {
        val root = Files.createTempDirectory("kaze-backup-dup").toFile()
        val dirA = File(root, "生存A").apply { mkdirs() }
        val dirB = File(root, "生存B").apply { mkdirs() }
        File(dirA, "world.marker").writeText("A")
        File(dirB, "world.marker").writeText("B")

        val a = instanceWith("同名服", dirA)
        val b = instanceWith("同名服", dirB)

        // 把 A 的备份挪到共享 backups/ 根下（模拟旧布局平铺），B 不该把它当成自己的
        val backupA = BackupManager.backup(a)
        val flat = File(root, "backups/" + backupA.name)
        assertTrue("用例前提：挪动必须成功", backupA.renameTo(flat))

        assertTrue(
            "B 认领了 A 的备份（恢复会把 A 的存档盖到 B 上）",
            BackupManager.list(b).none { it.absolutePath == flat.absolutePath },
        )
    }

    /**
     * 同名实例之间，**没有 id 的旧版平铺备份**谁都不要认。
     *
     * 旧版备份文件名只有「显示名 + 时间戳」，两个显示名相同的实例根本无法区分它属于谁。
     * 这条守卫此前是**失效**的（实现数的是备份目录、比的却是实例目录名，恒为 1 → 恒放行），
     * 于是两个同名实例会同时列出同一条旧备份，恢复时可能把对方的存档盖到自己头上。
     */
    @Test
    fun `同名实例下无法区分的旧版平铺备份谁都不要认`() {
        val root = Files.createTempDirectory("kaze-backup-legacy-dup").toFile()
        val dirA = File(root, "生存A").apply { mkdirs() }
        val dirB = File(root, "生存B").apply { mkdirs() }
        // id 故意不含在文件名里，模拟"没有 id 标记"的旧备份
        val a = instanceWith("同名服", dirA, id = "idA")
        val b = instanceWith("同名服", dirB, id = "idB")

        val flat = File(root, "backups/同名服_20260101-120000.zip").apply {
            parentFile?.mkdirs()
            writeText("legacy")
        }

        val all = listOf(a, b)
        assertTrue(
            "存在同名实例时不该认领无法区分的旧备份",
            BackupManager.list(a, all).none { it.absolutePath == flat.absolutePath },
        )
        assertTrue(
            "存在同名实例时不该认领无法区分的旧备份",
            BackupManager.list(b, all).none { it.absolutePath == flat.absolutePath },
        )
        // 没有同名兄弟时仍要认领 —— 否则正常用户的旧备份会平白在界面里消失
        assertTrue(
            "没有同名实例时应认得自己的旧备份",
            BackupManager.list(a, listOf(a)).any { it.absolutePath == flat.absolutePath },
        )
    }
}
