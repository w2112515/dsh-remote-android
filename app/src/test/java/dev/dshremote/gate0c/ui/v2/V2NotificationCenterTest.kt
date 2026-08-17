package dev.dshremote.gate0c.ui.v2

import dev.dshremote.gate0c.transport.ConnectionPhase
import dev.dshremote.gate0c.transport.Gate0CState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V2NotificationCenterTest {
    @Test
    fun markReadThenClearReadRemovesOnlyReadItems() {
        val center = V2NotificationCenter()
        center.reduce(
            Gate0CState(phase = ConnectionPhase.READY),
            Gate0CState(phase = ConnectionPhase.OFFLINE),
            hostId = "h1",
            hostLabel = "studio",
        )
        assertEquals(1, center.notifications.value.size)
        assertTrue(center.notifications.value.single().unread)

        val id = center.notifications.value.single().id
        center.markRead(id)
        assertFalse(center.notifications.value.single().unread)

        center.clearRead()
        assertTrue(center.notifications.value.isEmpty())
    }

    @Test
    fun dismissRemovesOneRowAndClearAllEmptiesTheInbox() {
        val center = V2NotificationCenter()
        center.reduce(
            Gate0CState(phase = ConnectionPhase.READY),
            Gate0CState(phase = ConnectionPhase.OFFLINE),
            hostId = "h1",
        )
        center.reduce(
            Gate0CState(phase = ConnectionPhase.OFFLINE),
            Gate0CState(phase = ConnectionPhase.READY, newPairingRequired = true),
            hostId = "h1",
        )
        assertEquals(2, center.notifications.value.size)

        val first = center.notifications.value.first().id
        center.dismiss(first)
        assertEquals(1, center.notifications.value.size)

        center.clearAll()
        assertTrue(center.notifications.value.isEmpty())
    }
}
