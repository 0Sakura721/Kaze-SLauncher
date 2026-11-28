package com.kaze.newage.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 樱花映射（SakuraFrp）`/tunnels` 响应的**容错解析**测试。
 *
 * 这里的容错是刻意设计的，因为上游 API 真的会变：
 *  - `/tunnels` 会把 `status_reason` / `note` 返回 **null**，而模型字段是非空 String ——
 *    不开 `coerceInputValues` 时 kotlinx 会抛 `Unexpected 'null' value`，
 *    用户看到的像"密钥识别不了"，其实登录已经成功了。
 *  - 字段类型也可能漂移（比如某天 `online` 变成数字 1/0）。
 *
 * 设计意图（见源码注释）：**宁可少一两个字段，也不能让整个登录流程失败**。
 */
class SakuraFrpTest {

    private fun tunnel(
        id: Long = 1,
        name: String = "t1",
        node: Long = 2,
        type: String = "tcp",
        online: Boolean = true,
        status: Int = 1,
        reason: String = "",
        remote: String = "",
        localIp: String = "",
        localPort: Int = 0,
    ) = """
        {"id":$id,"name":"$name","node":$node,"type":"$type",
         "online":$online,"status":$status,"status_reason":"$reason",
         "remote":"$remote","local_ip":"$localIp","local_port":$localPort}
    """.trimIndent()

    // ══════════ 正常解析 ══════════

    @Test
    fun `正常响应解析出全部字段`() {
        val body = """[${tunnel(id = 42, name = "我的服", node = 7, type = "tcp",
            online = true, status = 2, reason = "ok", remote = "cn-bj.example.com:12345",
            localIp = "127.0.0.1", localPort = 25565)}]"""
        val list = SakuraFrpApi.parseTunnels(body)
        assertEquals(1, list.size)
        val t = list[0]
        assertEquals(42L, t.id)
        assertEquals("我的服", t.name)
        assertEquals(7L, t.node)
        assertEquals("tcp", t.type)
        assertTrue(t.online)
        assertEquals(2, t.status)
        assertEquals("ok", t.status_reason)
        assertEquals("cn-bj.example.com:12345", t.remote)
        assertEquals("127.0.0.1", t.local_ip)
        assertEquals(25565, t.local_port)
    }

    @Test
    fun `空数组得到空列表`() {
        assertTrue(SakuraFrpApi.parseTunnels("[]").isEmpty())
    }

    @Test
    fun `多条隧道都解析`() {
        val body = "[${tunnel(id = 1)},${tunnel(id = 2)}]"
        assertEquals(2, SakuraFrpApi.parseTunnels(body).size)
    }

    // ══════════ null 字段（真实会遇到的坑） ══════════

    @Test
    fun `status_reason 为 null 时降级成空串而不是失败`() {
        val body = """[{"id":1,"name":"t","node":1,"type":"tcp","online":true,
            "status":1,"status_reason":null,"remote":"","local_ip":"","local_port":0}]"""
        val list = SakuraFrpApi.parseTunnels(body)
        assertEquals("不应因为一个 null 字段就整条失败", 1, list.size)
        assertEquals("", list[0].status_reason)
    }

    @Test
    fun `多个字段为 null 仍能解析`() {
        val body = """[{"id":1,"name":null,"node":1,"type":null,"online":true,
            "status":1,"status_reason":null,"remote":null,"local_ip":null,"local_port":0}]"""
        val list = SakuraFrpApi.parseTunnels(body)
        assertEquals(1, list.size)
        assertEquals("", list[0].name)
        assertEquals("", list[0].type)
    }

    // ══════════ 类型漂移（退化成宽松解析） ══════════

    @Test
    fun `online 变成数字时仍能解析`() {
        // 假设上游某天把布尔改成 1/0
        val body = """[{"id":1,"name":"t","node":1,"type":"tcp","online":1,
            "status":1,"status_reason":"","remote":"","local_ip":"","local_port":0}]"""
        val list = SakuraFrpApi.parseTunnels(body)
        assertEquals("类型漂移不该让整个登录失败", 1, list.size)
        // 宽松解析读不到布尔就是 false，不崩
        assertEquals(false, list[0].online)
    }

    @Test
    fun `id 变成字符串时仍能解析`() {
        val body = """[{"id":"42","name":"t","node":"7","type":"tcp","online":true,
            "status":"1","status_reason":"","remote":"","local_ip":"","local_port":"25565"}]"""
        val list = SakuraFrpApi.parseTunnels(body)
        assertEquals(1, list.size)
        assertEquals(42L, list[0].id)
        assertEquals(7L, list[0].node)
        assertEquals(25565, list[0].local_port)
    }

    @Test
    fun `缺字段时用默认值`() {
        val body = """[{"id":1}]"""
        val list = SakuraFrpApi.parseTunnels(body)
        assertEquals(1, list.size)
        assertEquals("", list[0].name)
        assertEquals(0L, list[0].node)
        assertEquals(false, list[0].online)
    }

    // ══════════ 结构异常 ══════════

    @Test
    fun `不是数组时抛异常（调用方会捕获并提示）`() {
        try {
            SakuraFrpApi.parseTunnels("""{"error":"unauthorized"}""")
            assertTrue("应抛异常", false)
        } catch (e: Exception) {
            assertTrue(true)
        }
    }

    @Test
    fun `数组里混入非对象元素时跳过而不是失败`() {
        val body = """[{"id":1,"name":"ok","node":1,"type":"tcp","online":true,
            "status":1,"status_reason":"","remote":"","local_ip":"","local_port":0}, 42, null, "str"]"""
        val list = SakuraFrpApi.parseTunnels(body)
        assertEquals("只保留对象元素", 1, list.size)
        assertEquals("ok", list[0].name)
    }

    @Test
    fun `空字符串与非 JSON 抛异常`() {
        for (bad in listOf("", "not json", "<html>")) {
            try {
                SakuraFrpApi.parseTunnels(bad)
                assertTrue("对 [$bad] 应抛异常", false)
            } catch (e: Exception) {
                assertTrue(true)
            }
        }
    }

    // ══════════ summary 文本 ══════════

    @Test
    fun `summary 含类型与本地端口`() {
        val t = SakuraTunnel(id = 1, name = "a", type = "tcp", local_port = 25565,
            local_ip = "127.0.0.1", remote = "cn.example.com:1234")
        val s = t.summary
        assertTrue("应含类型: $s", s.contains("TCP"))
        assertTrue("应含远程地址: $s", s.contains("cn.example.com:1234"))
        assertTrue("应含本地端口: $s", s.contains("25565"))
    }

    @Test
    fun `summary 在 local_ip 为空时用 127_0_0_1 兜底`() {
        val t = SakuraTunnel(id = 1, type = "tcp", local_port = 25565, local_ip = "")
        assertTrue("应显示 127.0.0.1: ${t.summary}", t.summary.contains("127.0.0.1"))
    }

    @Test
    fun `summary 带上 status_reason`() {
        val t = SakuraTunnel(id = 1, type = "tcp", local_port = 1, status_reason = "节点维护中")
        assertTrue(t.summary.contains("节点维护中"))
    }
}