package dev.dshremote.gate0c.transport

import androidx.test.core.app.ApplicationProvider
import dev.dshremote.security.PairedHostRecord
import dev.dshremote.security.PairedHostStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * S-multi-host: the fleet reconciles live clients with the paired-Host registry —
 * one client per record, removed records drop their client, per-Host slices stay
 * addressable by id. Loopback endpoints are never reachable here; reconciliation
 * is about registry membership, not carrier success.
 */
class HostFleetInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val pairedHostStore = PairedHostStore(context)

    private val hostA = PairedHostRecord(
        hostPublicKey = ByteArray(32) { index -> (index + 1).toByte() },
        endpointHost = "127.0.0.1",
        endpointPort = 50_061,
        capabilities = PairedHostRecord.READ_ONLY_CAPABILITIES,
        pairedAtMs = 1_000,
    )
    private val hostB = PairedHostRecord(
        hostPublicKey = ByteArray(32) { index -> (0x40 + index).toByte() },
        endpointHost = "127.0.0.1",
        endpointPort = 50_062,
        capabilities = PairedHostRecord.APPROVAL_REVIEWER_CAPABILITIES,
        pairedAtMs = 2_000,
    )
    private val hostAId get() = pairedHostStore.hostIdOf(hostA)
    private val hostBId get() = pairedHostStore.hostIdOf(hostB)

    @Before
    fun clearRegistry() {
        pairedHostStore.list().forEach { pairedHostStore.delete(pairedHostStore.hostIdOf(it)) }
    }

    @After
    fun cleanUp() {
        pairedHostStore.list().forEach { pairedHostStore.delete(pairedHostStore.hostIdOf(it)) }
    }

    @Test
    fun fleetReconcilesClientsWithThePairedHostRegistry() {
        pairedHostStore.save(hostA)
        pairedHostStore.save(hostB)
        val fleet = HostFleet(context)
        try {
            val ids = fleet.syncHosts().toSet()
            assertEquals(setOf(hostAId, hostBId), ids)
            assertEquals(setOf(hostAId, hostBId), fleet.slices.value.map { it.hostId }.toSet())
            assertNotNull(fleet.clientFor(hostAId))
            assertNotNull(fleet.clientFor(hostBId))

            // Each slice carries its own client's state; nothing is shared.
            val sliceA = fleet.slices.value.first { it.hostId == hostAId }
            val sliceB = fleet.slices.value.first { it.hostId == hostBId }
            assertTrue(sliceA.client !== sliceB.client)

            // Unknown session/approval ids resolve to no client.
            assertNull(fleet.clientForSession("session-not-projected"))
            assertNull(fleet.clientForApproval("approval-not-projected"))

            // Removing the record drops and closes exactly that client.
            pairedHostStore.delete(hostAId)
            fleet.syncHosts()
            assertEquals(listOf(hostBId), fleet.slices.value.map { it.hostId })
            assertNull(fleet.clientFor(hostAId))
            assertNotNull(fleet.clientFor(hostBId))
        } finally {
            fleet.close()
        }
    }
}
