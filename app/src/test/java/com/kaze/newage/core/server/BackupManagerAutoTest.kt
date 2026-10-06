package com.kaze.newage.core.server

import com.kaze.newage.data.model.ServerInstance
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * 停服自动备份（BackupManager.autoBackup / pruneAutoBackups）的约定：
 *  - 文件名必须带 auto_ 前缀（与手动备份区分，清理只认这一系列）；
 *  - 超量只留最新的 [BackupManager.AUTO_KEEP] 份，且**绝不**触碰手动备份；
 *  - 失败静默成 null（自动路径没有用户盯着，不能把停止流程打断）。
 */
class BackupManagerAutoTest {

    private fun newInstance(root: File): ServerInstance {
        val dir = File(root, "survival").apply { mkdirs() }
        File(dir, "server.properties").writeText("server-port=25565\n")
        File(dir, "world").apply { mkdirs() }
        File(dir, "world/level.dat").writeText("data")
        return ServerInstance(id = "test-id", name = "survival", dir = dir)
    }

    @Test
    fun `自动备份带 auto_ 前缀且在备份列表里可见可恢复`() {
        val root = Files.createTempDirectory("kaze-auto-backup").toFile()
        val instance = newInstance(root)

        val f = BackupManager.autoBackup(instance)

        assertTrue("自动备份应成功", f != null && f.isFile)
        assertTrue("文件名应带 auto_ 前缀：${f!!.name}", f.name.startsWith(BackupManager.AUTO_PREFIX))
        assertEquals(listOf(f.name), BackupManager.autoBackups(instance).map { it.name })
        // 世界卡的备份列表（list）必须能看到它，否则备份了却没法恢复
        assertTrue(BackupManager.list(instance).any { it.name == f.name })
    }

    @Test
    fun `自动备份超量时只留最新的且手动备份永不清理`() {
        val root = Files.createTempDirectory("kaze-auto-prune").toFile()
        val instance = newInstance(root)
        val backupsDir = File(instance.dir.parentFile, "backups/${instance.dir.name}").apply { mkdirs() }

        val manual = BackupManager.backup(instance)
        for (i in 1..8) {
            val f = File(backupsDir, "${BackupManager.AUTO_PREFIX}survival_test-id_2026010$i-000000.zip")
            f.writeText("zip-$i")
            // 同一批新建文件的 lastModified 可能相同，排序就失去依据 —— 显式错开
            assertTrue(f.setLastModified(1_000_000L * i))
        }

        BackupManager.pruneAutoBackups(instance)

        val left = BackupManager.autoBackups(instance)
        assertEquals(BackupManager.AUTO_KEEP, left.size)
        // 留下的必须是最新 5 份（8..4），最旧的 1..3 删掉
        assertEquals(
            (8 downTo 4).map { i -> "${BackupManager.AUTO_PREFIX}survival_test-id_2026010$i-000000.zip" },
            left.map { it.name },
        )
        // 手动备份没有 auto_ 前缀，不在清理范围
        assertTrue("手动备份不能被清理", manual.isFile)
    }

    @Test
    fun `实例名本身以 auto_ 开头时手动备份不被误判为自动备份`() {
        val root = Files.createTempDirectory("kaze-auto-name").toFile()
        val dir = File(root, "auto_survival").apply { mkdirs() }
        File(dir, "server.properties").writeText("server-port=25565\n")
        val instance = ServerInstance(id = "test-id", name = "auto_survival", dir = dir)

        val manual = BackupManager.backup(instance)
        // 名字撞车：这份**手动**备份的文件名天生以 auto_ 开头（auto_ + 名字前缀）
        assertTrue("前提不成立：${manual.name}", manual.name.startsWith(BackupManager.AUTO_PREFIX))

        assertTrue("手动备份不能被认成自动备份", BackupManager.autoBackups(instance).isEmpty())
        BackupManager.pruneAutoBackups(instance)
        assertTrue("撞前缀的手动备份不能被清理", manual.isFile)
    }

    @Test
    fun `备份失败时自动备份返回 null 而不是抛出`() {
        val root = Files.createTempDirectory("kaze-auto-fail").toFile()
        val dir = File(root, "survival").apply { mkdirs() }
        File(dir, "world.dat").writeText("x")
        val instance = ServerInstance(id = "x", name = "survival", dir = dir)

        // 权限位对 root 不生效（CI 的 runner 是普通用户，但不赌环境）：
        // 设不进去就跳过 —— 这条只验证「异常被吞成 null」这层薄封装
        val locked = File(dir, "world.dat")
        Assume.assumeTrue(locked.setReadable(false) && !locked.canRead())

        assertEquals(null, BackupManager.autoBackup(instance))
    }
}
