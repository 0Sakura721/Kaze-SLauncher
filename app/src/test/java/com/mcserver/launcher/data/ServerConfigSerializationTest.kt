package com.mcserver.launcher.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerConfigSerializationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `serialize and deserialize preserves all fields`() {
        val original = SerializableServerConfig(
            id = "test-001", name = "测试服务器", jarPath = "/data/test/server.jar",
            javaVersion = 21, allocatedMemoryMB = 4096, serverPort = 25565,
            additionalArgs = "-XX:+UseG1GC", autoRestart = true, nogui = true,
            startupMode = "DIRECT_JAR", motd = "欢迎", maxPlayers = 10,
            gamemode = "creative", difficulty = "peaceful", pvp = false,
            onlineMode = false, whiteList = true, maxRestarts = 5,
            rconEnabled = true, rconPassword = "test-pwd", rconPort = 25575
        )
        val s = SerializableServerConfig.serializeList(listOf(original))
        assertTrue(s.contains("test-001"))
        val d = SerializableServerConfig.deserializeList(s)
        assertEquals(1, d.size); assertEquals(original.id, d[0].id); assertEquals(original.jarPath, d[0].jarPath)
        assertEquals(original.allocatedMemoryMB, d[0].allocatedMemoryMB); assertEquals(original.rconPassword, d[0].rconPassword)
    }

    @Test fun `unknown keys ignored`() {
        val r = SerializableServerConfig.deserializeList("""[{"id":"t1","name":"S1","unknown":"ignored"}]""")
        assertEquals(1, r.size); assertEquals("t1", r[0].id); assertEquals("S1", r[0].name)
    }

    @Test fun `empty array`() = assertTrue(SerializableServerConfig.deserializeList("[]").isEmpty())
    @Test fun `invalid json`() = assertTrue(SerializableServerConfig.deserializeList("invalid").isEmpty())

    @Test fun `ServerConfig round-trip`() {
        val c = ServerConfig(name = "Test", allocatedMemoryMB = 2048, serverPort = 25565, onlineMode = true)
        val s = SerializableServerConfig.fromServerConfig(c); val b = s.toServerConfig()
        assertEquals(c.name, b.name); assertEquals(c.allocatedMemoryMB, b.allocatedMemoryMB)
        assertEquals(c.serverPort, b.serverPort); assertEquals(c.onlineMode, b.onlineMode)
    }
}
