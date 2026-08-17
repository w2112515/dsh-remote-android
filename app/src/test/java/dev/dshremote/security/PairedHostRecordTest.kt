package dev.dshremote.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PairedHostRecordTest {
    private fun record(capabilities: Long): PairedHostRecord = PairedHostRecord(
        hostPublicKey = ByteArray(32) { 0x11 },
        endpointHost = "192.168.1.20",
        endpointPort = 50_051,
        capabilities = capabilities,
        pairedAtMs = 1_000,
    )

    @Test
    fun acceptsEveryClosedProfileIncludingHostSupervisor() {
        for (profile in PairedHostRecord.SUPPORTED_CAPABILITY_PROFILES) {
            assertEquals(profile, record(profile).capabilities)
        }
        assertEquals(6, PairedHostRecord.SUPPORTED_CAPABILITY_PROFILES.size)
        assertEquals(351L, PairedHostRecord.HOST_SUPERVISOR_CAPABILITIES)
    }

    @Test
    fun rejectsMasksOutsideTheClosedSet() {
        assertThrows(IllegalArgumentException::class.java) { record(0) }
        assertThrows(IllegalArgumentException::class.java) { record(7) }
        assertThrows(IllegalArgumentException::class.java) { record(351 or 512) }
    }
}
