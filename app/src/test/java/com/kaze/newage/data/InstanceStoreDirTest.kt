package com.kaze.newage.data

import androidx.test.core.app.ApplicationProvider
import com.kaze.newage.NewAgeApp
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 实例目录创建的回归测试。
 *
 * 这两条都必须锁死——它们各自对应一个会造成**不可逆数据丢失**的真实缺陷：
 *
 *  1. 实例名 `..` / `.` 会让 `File(instancesRoot(), name)` 解析到实例根之外
 *     （`..` = 实例根的父目录，默认即应用外部私有目录，装着全部实例、世界存档与 instances.json；
 *      `.` = 实例根本身）。删除这种实例会 `deleteRecursively()` 把全部数据一起删掉。
 *  2. 同名目录被复用：向导默认名是「核心-版本」，不改名连续建两次会落到同一目录，
 *     第二条实例的 jar 覆盖第一条、新建时的属性覆盖项还会改写第一条的 server.properties。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class InstanceStoreDirTest {

    private fun store(): InstanceStore =
        ApplicationProvider.getApplicationContext<NewAgeApp>().container.instanceStore

    @Test
    fun `实例名里的点号不会逃出实例根目录`() {
        val s = store()
        val root = s.instancesRoot().canonicalPath
        // "." / ".." / "..." 以及首尾带点的名字都要落回根目录之内
        listOf("..", ".", "...", " .. ", "..隐藏", "尾点..", ".", "..").forEach { name ->
            val dir = s.createInstanceDir(name)
            val p = dir.canonicalPath
            assertTrue(
                "实例名 '$name' 建到了实例根之外：$p（根 = $root）",
                p == root || p.startsWith(root + File.separator),
            )
        }
    }

    @Test
    fun `空名与全非法字符名也不会逃出根目录`() {
        val s = store()
        val root = s.instancesRoot().canonicalPath
        listOf("", "   ", "/", "\\", "?", "...", " . . ").forEach { name ->
            val p = s.createInstanceDir(name).canonicalPath
            assertTrue("实例名 '$name' 越界：$p", p == root || p.startsWith(root + File.separator))
        }
    }

    @Test
    fun `同名不会复用已有目录`() {
        val s = store()
        val first = s.createInstanceDir("重名测试")
        // 让目录非空，模拟"已经建好的实例"
        File(first, "server.jar").writeText("dummy")
        val second = s.createInstanceDir("重名测试")
        assertNotEquals(
            "同名实例复用了同一目录，会互相覆盖 jar 与 server.properties",
            first.canonicalPath,
            second.canonicalPath,
        )
    }

    @Test
    fun `空目录可以复用（下载失败重试的场景）`() {
        val s = store()
        val first = s.createInstanceDir("空目录测试")
        // 目录为空时不加序号，避免重试一次就多出一个 "(2)" 目录
        assertEquals(first.canonicalPath, s.createInstanceDir("空目录测试").canonicalPath)
    }

    /**
     * 目录扫描恢复出来的记录，id 不能撞上实例库里已有的 id。
     *
     * 恢复项的 id 原来直接取目录名（正常创建的实例 id 是随机 UUID，手改过库、
     * 或由旧版本写下的库就可能撞上），而按 id 去重时 **JSON 里的记录优先** ——
     * 一旦撞上，被丢掉的正是用户那条真实记录（核心类型 / 内存 / MC 版本都在里面），
     * 留下的是一条从 jar 文件名猜出来的恢复项。
     */
    @Test
    fun `恢复出来的实例 id 不与已有记录相撞`() {
        val app = ApplicationProvider.getApplicationContext<NewAgeApp>()
        val s = app.container.instanceStore
        val dup = "撞ID测试"

        // 真实实例：目录名与 JSON 里的记录名不同，避免被"同目录"规则合并掉
        val realDir = s.createInstanceDir("真实实例")
        File(realDir, "server.jar").writeText("dummy")

        // 另建一个目录，目录名恰好等于上面那条记录的 id
        val strayDir = File(s.instancesRoot(), dup).apply { mkdirs() }
        File(strayDir, "server.jar").writeText("dummy")

        val json = File(app.filesDir, "instances.json")
        json.writeText(
            "[" +
                "{\"id\":\"$dup\",\"name\":\"用户自己起的名字\",\"coreType\":\"PAPER\"," +
                "\"mcVersion\":\"1.20.4\",\"javaMajor\":17,\"memoryMb\":4096,\"nogui\":true," +
                "\"autoRestart\":false,\"maxRestarts\":3," +
                "\"dirPath\":\"${realDir.absolutePath.replace("\\", "\\\\")}\"}" +
                "]"
        )
        s.rescan()
        val after = s.instances.value

        assertEquals("两条实例记录被 id 去重并成了一条", 2, after.size)
        val real = after.firstOrNull { it.dir == realDir }
        assertEquals("真实记录被恢复项顶掉了", "用户自己起的名字", real?.name)
        assertEquals("真实记录的内存应该保留", 4096, real?.memoryMb)
        assertEquals("恢复项目录不对", strayDir, after.firstOrNull { it.dir == strayDir }?.dir)
        assertTrue(
            "恢复项复用了已有记录的 id —— JSON 里的真实记录会被按 id 去重丢掉",
            after.first { it.dir == strayDir }.id != dup,
        )

        // id 必须稳定：再扫一次不能累积出更多记录
        s.rescan()
        assertEquals("重复扫描累积了记录", 2, s.instances.value.size)
    }
}
