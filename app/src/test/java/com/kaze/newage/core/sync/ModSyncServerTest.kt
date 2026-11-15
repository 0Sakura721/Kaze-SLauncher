package com.kaze.newage.core.sync

import com.kaze.newage.testutil.TestJars
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class ModSyncServerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var instance: com.kaze.newage.data.model.ServerInstance
    private lateinit var server: ModSyncServer
    private var port = 0

    @Before
    fun setUp() {
        instance = TestJars.instance(tmp.newFolder("srv"))
        File(instance.dir, "mods").mkdirs()
        TestJars.fabricMod(File(instance.dir, "mods/lithium.jar"))
        ClientPack.invalidate(instance.id)
        server = ModSyncServer(instance, tmp.newFolder("cache"))
        assertTrue(server.start(preferredPort = 0, token = "secret", bindAddress = "127.0.0.1"))
        port = server.state.value.port
        assertTrue("端口必须可连接", port > 0)
    }

    @After
    fun tearDown() {
        server.stop()
    }

    private data class Response(val code: Int, val body: ByteArray, val contentType: String) {
        val text: String get() = body.toString(Charsets.UTF_8)
    }

    private fun get(path: String, token: String? = null): Response {
        var lastError: Exception? = null
        repeat(3) { attempt ->
            try {
                val conn = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 15000
                conn.requestMethod = "GET"
                if (token != null) conn.setRequestProperty("X-Kaze-Token", token)
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.use { it.readBytes() } ?: ByteArray(0)
                val type = conn.contentType.orEmpty()
                conn.disconnect()
                return Response(code, body, type)
            } catch (e: Exception) {
                lastError = e
                Thread.sleep(50L * (attempt + 1))
            }
        }
        throw AssertionError("请求失败：$path", lastError)
    }

    @Test
    fun healthIsPublic() {
        val r = get("/health")
        assertEquals(200, r.code)
        assertEquals("ok", r.text)
    }

    @Test
    fun filesRequireToken() {
        assertEquals(401, get("/mods.txt").code)
        assertEquals(401, get("/mods.txt?token=wrong").code)
        assertEquals(401, get("/manifest.json").code)
        assertEquals(200, get("/mods.txt?token=secret").code)
        assertEquals(200, get("/mods.txt", token = "secret").code)
    }

    @Test
    fun servesModListAndManifest() {
        val tsv = get("/mods.txt?token=secret")
        assertEquals(200, tsv.code)
        assertTrue(tsv.text.contains("lithium.jar"))
        assertTrue(tsv.contentType.contains("text/plain"))

        val manifest = get("/manifest.json?token=secret")
        assertEquals(200, manifest.code)
        val root = Json.parseToJsonElement(manifest.text).jsonObject
        assertEquals("1.21.1", root["mcVersion"].toString().trim('"'))
    }

    @Test
    fun downloadsModBytes() {
        val expected = File(instance.dir, "mods/lithium.jar").readBytes()
        val byQuery = get("/mod?f=lithium.jar&token=secret")
        assertEquals(200, byQuery.code)
        assertTrue(expected.contentEquals(byQuery.body))

        val byPath = get("/mods/lithium.jar?token=secret")
        assertEquals(200, byPath.code)
        assertTrue(expected.contentEquals(byPath.body))
    }

    @Test
    fun rejectsUnknownAndTraversalNames() {
        assertEquals(404, get("/mod?f=missing.jar&token=secret").code)
        // 路径穿越：解码后仍不在清单里 → 404，绝不会读到 mods/ 之外
        assertEquals(404, get("/mods/..%2F..%2Fsecret.txt?token=secret").code)
        assertEquals(404, get("/nope?token=secret").code)
    }

    @Test
    fun servesClientPackZip() {
        val r = get("/client-pack.zip?token=secret")
        assertEquals(200, r.code)
        assertTrue(r.body.size > 100)
        // ZIP 魔数
        assertEquals(0x50, r.body[0].toInt() and 0xFF)
        assertEquals(0x4B, r.body[1].toInt() and 0xFF)
        assertTrue(r.contentType.contains("zip"))
    }

    @Test
    fun remoteApiWorksWhenBridgeProvided() {
        val bridge = object : ModSyncServer.RemoteBridge {
            override fun statusJson(): String = """{"state":"Running","mods":3}"""
            override fun logs(maxLines: Int): String = "line1\nline2"
            override fun command(cmd: String): String = "ok:$cmd"
            override fun start(): String = "starting"
            override fun stop(): String = "stopping"
        }
        val remote = ModSyncServer(instance, tmp.newFolder("cache-remote"), remote = bridge)
        assertTrue(remote.start(preferredPort = 0, token = "secret", bindAddress = "127.0.0.1"))
        val remotePort = remote.state.value.port
        try {
            val status = request(remotePort, "/api/status?token=secret")
            assertEquals(200, status.code)
            assertTrue(status.text.contains("Running"))

            assertEquals(401, request(remotePort, "/api/status").code)
            assertEquals("line1\nline2", request(remotePort, "/api/logs?n=10&token=secret").text)
            assertEquals("ok:list", request(remotePort, "/api/command?cmd=list&token=secret").text)
            assertEquals("starting", request(remotePort, "/api/start?token=secret").text)
            assertEquals(404, request(remotePort, "/api/unknown?token=secret").code)
        } finally {
            remote.stop()
        }
    }

    /** 指定端口的 GET（远程接口测试用，避免动 setUp 里的 [port]） */
    private fun request(onPort: Int, path: String): Response {
        val conn = URL("http://127.0.0.1:$onPort$path").openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 10000
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.use { it.readBytes() } ?: ByteArray(0)
        conn.disconnect()
        return Response(code, body, "")
    }

    @Test
    fun servesMrpack() {
        val r = get("/client-pack.mrpack?token=secret")
        assertEquals(200, r.code)
        assertTrue(r.body.size > 100)
        assertEquals(0x50, r.body[0].toInt() and 0xFF)
        assertEquals(0x4B, r.body[1].toInt() and 0xFF)
    }

    @Test
    fun servesSyncScripts() {
        val sh = get("/sync.sh?token=secret")
        assertEquals(200, sh.code)
        assertTrue(sh.text.startsWith("#!/bin/sh"))

        val ps1 = get("/sync.ps1?token=secret")
        assertEquals(200, ps1.code)
        assertTrue(ps1.text.contains("Get-FileHash"))
    }

    @Test
    fun countsRequests() {
        get("/health")
        get("/mods.txt?token=secret")
        assertTrue(server.state.value.requests >= 2)
    }

    @Test
    fun indexPageIsPublicAndNeverLeaksToken() {
        val r = get("/")
        assertEquals(200, r.code)
        assertTrue(r.text.contains("这个地址是通的"))
        assertTrue(!r.text.contains("secret"))
    }

    @Test
    fun stopClosesPort() {
        server.stop()
        val failed = runCatching {
            val conn = URL("http://127.0.0.1:$port/health").openConnection() as HttpURLConnection
            conn.connectTimeout = 1000
            conn.responseCode
        }.isFailure
        assertTrue(failed)
    }
}
