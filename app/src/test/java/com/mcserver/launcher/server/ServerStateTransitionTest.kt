package com.mcserver.launcher.server

import com.mcserver.launcher.data.ServerState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerStateTransitionTest {

    private fun can(from: ServerState, to: ServerState) = when (to) {
        ServerState.STARTING -> from == ServerState.STOPPED || from == ServerState.ERROR
        ServerState.RUNNING -> from == ServerState.STARTING
        ServerState.STOPPING -> from == ServerState.RUNNING
        ServerState.STOPPED -> from in listOf(ServerState.STARTING, ServerState.RUNNING, ServerState.STOPPING, ServerState.ERROR)
        ServerState.ERROR -> from in listOf(ServerState.STARTING, ServerState.RUNNING)
    }

    @Test fun `stopped to starting`() = assertTrue(can(ServerState.STOPPED, ServerState.STARTING))
    @Test fun `starting to running`() = assertTrue(can(ServerState.STARTING, ServerState.RUNNING))
    @Test fun `starting to error`() = assertTrue(can(ServerState.STARTING, ServerState.ERROR))
    @Test fun `running to stopping`() = assertTrue(can(ServerState.RUNNING, ServerState.STOPPING))
    @Test fun `running to error`() = assertTrue(can(ServerState.RUNNING, ServerState.ERROR))
    @Test fun `stopping to stopped`() = assertTrue(can(ServerState.STOPPING, ServerState.STOPPED))
    @Test fun `error to starting`() = assertTrue(can(ServerState.ERROR, ServerState.STARTING))
    @Test fun `error to stopped`() = assertTrue(can(ServerState.ERROR, ServerState.STOPPED))
    @Test fun `no backwards`() { assertFalse(can(ServerState.RUNNING, ServerState.STARTING)); assertFalse(can(ServerState.STOPPED, ServerState.RUNNING)) }
    @Test fun `stopped cannot go to error directly`() = assertFalse(can(ServerState.STOPPED, ServerState.ERROR))
}
