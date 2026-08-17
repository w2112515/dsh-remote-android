package dev.dshremote.gate0c.transport

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.dshremote.security.DeviceIdentityStore
import dev.dshremote.security.PairedHostRecord
import dev.dshremote.security.PairedHostStore
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostFleetResetInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val identityStore = DeviceIdentityStore(context)
    private val pairedHostStore = PairedHostStore(context)
    private val offlineStore = OfflineProjectionStore(context)
    private val pendingCommandStore = PendingCommandStore(context)
    private val blobUploadStore = BlobUploadStore(context)

    @Before
    @After
    fun clearStores() {
        offlineStore.clear()
        pendingCommandStore.clear()
        blobUploadStore.clear()
        pairedHostStore.delete()
        identityStore.delete()
    }

    @Test
    fun fleetResetWipesLocalAuthorityWithoutTalkingToTheHost() {
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
        blobUploadStore.encryptedFileForTest().writeBytes(byteArrayOf(7, 8, 9))

        val fleet = HostFleet(context)
        fleet.resetLocalAuthority()

        assertTrue(fleet.slices.value.isEmpty())
        assertNull(pairedHostStore.loadSole())
        assertFalse(offlineStore.encryptedFileForTest().exists())
        assertFalse(pendingCommandStore.encryptedFileForTest().exists())
        assertFalse(blobUploadStore.encryptedFileForTest().exists())
        val replacementPublicKey = identityStore.loadOrCreate().use { it.publicKey }
        assertNotEquals(oldPublicKey.toList(), replacementPublicKey.toList())
        fleet.close()
    }
}
