package com.mcserver.launcher.core.instance

import com.mcserver.launcher.data.ServerInstance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTypeTest {

    @Test
    fun fromKey_knownKeys() {
        assertEquals(CoreType.PAPER, CoreType.fromKey("paper"))
        assertEquals(CoreType.VANILLA, CoreType.fromKey("vanilla"))
        assertEquals(CoreType.FABRIC, CoreType.fromKey("fabric"))
        assertEquals(CoreType.SPIGOT, CoreType.fromKey("spigot"))
        assertEquals(CoreType.FORGE, CoreType.fromKey("forge"))
        assertEquals(CoreType.PURPUR, CoreType.fromKey("purpur"))
    }

    @Test
    fun fromKey_unknownFallsBackToPaper() {
        assertEquals(CoreType.PAPER, CoreType.fromKey(null))
        assertEquals(CoreType.PAPER, CoreType.fromKey("nope"))
    }

    @Test
    fun instance_defaults() {
        val inst = ServerInstance(name = "测试服")
        assertEquals("测试服", inst.name)
        assertEquals(CoreType.PAPER, inst.coreType)
        assertEquals("-Xmx2G", inst.jvmArgs)
        assertTrue(inst.id.isNotBlank())
        assertTrue(inst.agreeEula)
    }
}