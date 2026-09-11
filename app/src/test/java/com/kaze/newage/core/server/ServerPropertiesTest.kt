package com.kaze.newage.core.server

import com.kaze.newage.data.model.ServerInstance
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * server.properties 读写测试。
 * 重点锁住「保存时不得丢失未管理的键」与端口分配不撞车 ——
 * 这两点直接决定用户的端口/自定义配置会不会在编辑一次后被抹掉。
 */
class ServerPropertiesTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("kaze-props-test").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun inst(name: String): ServerInstance =
        ServerInstance(name = name, dir = File(root, name).apply { mkdirs() })

    @Test
    fun `defaults 带端口_motd 且关闭空服暂停`() {
        val d = ServerProperties.defaults(25566, "我的服")
        assertEquals("25566", d["server-port"])
        assertEquals("我的服", d["motd"])
        // proot 环境下暂停唤醒会卡死 → 必须默认禁用
        assertEquals("-1", d["pause-when-empty-seconds"])
    }

    @Test
    fun `写入后可读回`() {
        val dir = inst("a").dir
        ServerProperties.save(dir, linkedMapOf("server-port" to "25570", "motd" to "hi"))
        val loaded = ServerProperties.load(dir)
        assertEquals("25570", loaded["server-port"])
        assertEquals("hi", loaded["motd"])
    }

    @Test
    fun `保存时保留未管理的键`() {
        val dir = inst("a").dir
        File(dir, "server.properties").writeText("server-port=25565\nfoo=bar\n")
        // 只更新端口，foo 不在 props 里
        ServerProperties.save(dir, linkedMapOf("server-port" to "25580"))
        val loaded = ServerProperties.load(dir)
        assertEquals("25580", loaded["server-port"])
        assertEquals("bar", loaded["foo"])
    }

    @Test
    fun `值里含等号不会被截断`() {
        val dir = inst("a").dir
        ServerProperties.save(dir, linkedMapOf("motd" to "a=b=c"))
        assertEquals("a=b=c", ServerProperties.load(dir)["motd"])
    }

    @Test
    fun `跳过注释与空行`() {
        val dir = inst("a").dir
        File(dir, "server.properties").writeText("# comment\n\n!bang\nk=v\n")
        assertEquals(mapOf("k" to "v"), ServerProperties.load(dir))
    }

    @Test
    fun `无文件时返回空表且不崩`() {
        assertTrue(ServerProperties.load(inst("empty").dir).isEmpty())
    }

    @Test
    fun `端口分配跳过已占用的端口`() {
        val a = inst("a")
        val b = inst("b")
        ServerProperties.save(a.dir, linkedMapOf("server-port" to "25565"))
        ServerProperties.save(b.dir, linkedMapOf("server-port" to "25566"))
        assertEquals(25567, ServerProperties.findFreePort(listOf(a, b)))
    }

    @Test
    fun `全部空闲时从 25565 开始`() {
        assertEquals(25565, ServerProperties.findFreePort(listOf(inst("a"), inst("b"))))
    }

    @Test
    fun `ensureInitial 不覆盖已有配置`() {
        val a = inst("a")
        ServerProperties.save(a.dir, linkedMapOf("server-port" to "25600", "motd" to "keep me"))
        ServerProperties.ensureInitial(a, listOf(a))
        val loaded = ServerProperties.load(a.dir)
        assertEquals("25600", loaded["server-port"])
        assertEquals("keep me", loaded["motd"])
    }

    @Test
    fun `ensureInitial 为旧实例补写 pause-when-empty-seconds`() {
        val a = inst("a")
        // 模拟旧版本写下的文件：没有 pause-when-empty-seconds
        File(a.dir, "server.properties").writeText("server-port=25565\nmotd=old\n")
        ServerProperties.ensureInitial(a, listOf(a))
        assertEquals("-1", ServerProperties.load(a.dir)["pause-when-empty-seconds"])
    }

    @Test
    fun `ensureInitial 不覆盖用户主动设置的暂停值`() {
        val a = inst("a")
        File(a.dir, "server.properties").writeText("server-port=25565\npause-when-empty-seconds=60\n")
        ServerProperties.ensureInitial(a, listOf(a))
        assertEquals("60", ServerProperties.load(a.dir)["pause-when-empty-seconds"])
    }
}
