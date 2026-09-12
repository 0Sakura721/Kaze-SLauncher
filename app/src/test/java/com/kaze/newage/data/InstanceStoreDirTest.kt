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
}
