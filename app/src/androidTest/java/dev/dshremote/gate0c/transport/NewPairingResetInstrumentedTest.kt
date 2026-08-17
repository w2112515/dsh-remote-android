package dev.dshremote.gate0c.transport

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.dshremote.security.DeviceIdentityStore
import dev.dshremote.security.PairedHostRecord
import dev.dshremote.security.PairedHostStore
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NewPairingResetInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val identityStore = DeviceIdentityStore(context)
    private val pairedHostStore = PairedHostStore(context)
    private val offlineStore = OfflineProjectionStore(context)
    private val pendingCommandStore = PendingCommandStore(context)

    @Before
    @After
    fun clearStores() {
        offlineStore.clear()
        pendingCommandStore.clear()
        pairedHostStore.delete()
        identityStore.delete()
    }

    @Test
    fun explicitResetRemovesTheOldAuthorityDomainBeforeAnotherPairingCanStart() {
        val oldPublicKey = identityStore.loadOrCreate().use { it.publicKey }
        pairedHostStore.save(
            PairedHostRecord(
                hostPublicKey = ByteArray(32) { index -> (index + 1).toByte() },
                endpointHost = "127.0.0.1",
                endpointPort = 50_051,
                capabilities = PairedHostRecord.APPROVAL_REVIEWER_CAPABILITIES,
                pairedAtMs = 1_000,
            ),
        )
        offlineStore.encryptedFileForTest().writeBytes(byteArrayOf(1, 2, 3))
        pendingCommandStore.encryptedFileForTest().writeBytes(byteArrayOf(4, 5, 6))

        val client = Gate0CClient(context)
        client.startNewPairingCeremony()

        assertEquals(ConnectionPhase.UNPAIRED, client.state.value.phase)
        assertEquals(false, client.state.value.newPairingRequired)
        assertNull(pairedHostStore.loadSole())
        assertFalse(offlineStore.encryptedFileForTest().exists())
        assertFalse(pendingCommandStore.encryptedFileForTest().exists())
        val replacementPublicKey = identityStore.loadOrCreate().use { it.publicKey }
        assertNotEquals(oldPublicKey.toList(), replacementPublicKey.toList())
        client.close()
    }

    @Test
    fun scopedResetRemovesOnlyItsOwnHostAndKeepsTheDeviceIdentity() {
        // S-multi-host: a fleet member's re-pairing reset must not destroy the
        // other Host's pin or the shared device identity.
        val oldPublicKey = identityStore.loadOrCreate().use { it.publicKey }
        val hostA = PairedHostRecord(
            hostPublicKey = ByteArray(32) { index -> (index + 1).toByte() },
            endpointHost = "127.0.0.1",
            endpointPort = 50_051,
            capabilities = PairedHostRecord.APPROVAL_REVIEWER_CAPABILITIES,
            pairedAtMs = 1_000,
        )
        val hostB = PairedHostRecord(
            hostPublicKey = ByteArray(32) { index -> (0x40 + index).toByte() },
            endpointHost = "127.0.0.1",
            endpointPort = 50_052,
            capabilities = PairedHostRecord.READ_ONLY_CAPABILITIES,
            pairedAtMs = 2_000,
        )
        pairedHostStore.save(hostA)
        pairedHostStore.save(hostB)
        val hostAId = pairedHostStore.hostIdOf(hostA)
        val scopedOffline = OfflineProjectionStore(context, hostAId)
        val scopedCommands = PendingCommandStore(context, hostAId)
        scopedOffline.encryptedFileForTest().writeBytes(byteArrayOf(1, 2, 3))
        scopedCommands.encryptedFileForTest().writeBytes(byteArrayOf(4, 5, 6))

        val client = Gate0CClient(context, hostAId)
        client.startNewPairingCeremony()

        assertEquals(ConnectionPhase.UNPAIRED, client.state.value.phase)
        assertNull(pairedHostStore.load(hostAId))
        assertFalse(scopedOffline.encryptedFileForTest().exists())
        assertFalse(scopedCommands.encryptedFileForTest().exists())
        // The other Host and the shared device identity survive.
        assertEquals(
            hostB.pairedAtMs,
            pairedHostStore.load(pairedHostStore.hostIdOf(hostB))!!.pairedAtMs,
        )
        assertArrayEquals(oldPublicKey, identityStore.loadOrCreate().use { it.publicKey })
        client.close()
        scopedOffline.clear()
        scopedCommands.clear()
    }
}
