package dev.dshremote.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanDiscoveryTest {
    @Test
    fun parsesOnlyTheBoundedPrivacyMinimizedTxtContract() {
        val hostId = "AB".repeat(32)
        val host = DshDiscoveryRecord.parse(
            serviceName = "Studio workstation",
            attributes = mapOf(
                "v" to "1".encodeToByteArray(),
                "id" to hostId.encodeToByteArray(),
                "platform" to "win32".encodeToByteArray(),
                "pairing" to "required".encodeToByteArray(),
            ),
            pairedHostIds = setOf(hostId),
        )
        requireNotNull(host)
        assertEquals("Studio workstation", host.displayName)
        assertEquals("win32", host.platform)
        assertEquals(NearbyHostPairingState.PAIRED, host.pairingState)
    }

    @Test
    fun rejectsUnknownVersionsAndMalformedIdentityWithoutGuessing() {
        fun record(version: String, hostId: String) = DshDiscoveryRecord.parse(
            serviceName = "Host",
            attributes = mapOf(
                "v" to version.encodeToByteArray(),
                "id" to hostId.encodeToByteArray(),
                "platform" to "linux".encodeToByteArray(),
                "pairing" to "required".encodeToByteArray(),
            ),
            pairedHostIds = emptySet(),
        )
        assertNull(record("2", "AA".repeat(32)))
        assertNull(record("1", "host-name-not-an-identity"))
    }
}

