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

    private fun instanceWith(name: String, dir: File): ServerInstance =
        ServerInstance(name = name, dir = dir)

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
}
