package dev.dshremote.gate0c.ui.v2

import dev.dshremote.gate0c.transport.ConnectionPhase
import dev.dshremote.gate0c.transport.Gate0CState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V2CreateGateTest {
    @Test
    fun emptyFleetExplainsThereIsNoHost() {
        assertEquals("还没有配对的主机。", createBlockedReason(emptyList()))
    }

    @Test
    fun offlineFleetExplainsReconnect() {
        val reason = createBlockedReason(
            listOf(
                Gate0CState(
                    phase = ConnectionPhase.OFFLINE,
                    grantedCapabilities = 68uL,
                ),
            ),
        )
        assertTrue(reason.contains("离线"))
    }

    @Test
    fun readOnlyGrantExplainsCapability() {
        val reason = createBlockedReason(
            listOf(
                Gate0CState(
                    phase = ConnectionPhase.READY,
                    grantedCapabilities = 3uL,
                ),
            ),
        )
        assertTrue(reason.contains("只读"))
    }

    @Test
    fun authorizedHostIsNotBlocked() {
        assertTrue(
            createAuthorized(
                Gate0CState(
                    phase = ConnectionPhase.READY,
                    grantedCapabilities = 68uL,
                ),
            ),
        )
    }
}
