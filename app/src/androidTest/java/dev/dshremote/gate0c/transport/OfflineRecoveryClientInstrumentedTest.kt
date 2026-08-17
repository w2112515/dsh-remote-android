package dev.dshremote.gate0c.transport

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.dshremote.security.DeviceIdentityStore
import dev.dshremote.security.PairedHostStore
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfflineRecoveryClientInstrumentedTest {
    private val context by lazy { ApplicationProvider.getApplicationContext<android.content.Context>() }
    private val identityStore by lazy { DeviceIdentityStore(context) }
    private val hostStore by lazy { PairedHostStore(context) }
    private val offlineStore by lazy { OfflineProjectionStore(context) }

    @Test
    fun seedAuthenticatedProjectionDraftAndAnchor() {
        val arguments = InstrumentationRegistry.getArguments()
        val invitation = arguments.getString("offlineSeedInvitation")
        val expectedSessionId = arguments.getString("offlineExpectedSessionId")
        assumeTrue(
            "Offline seed arguments not supplied",
            !invitation.isNullOrBlank() && !expectedSessionId.isNullOrBlank(),
        )
        clearAll()
        val client = Gate0CClient(context)
        try {
            client.pair(requireNotNull(invitation))
            val ready = waitForState(client, timeoutSeconds = 30) { it.phase == ConnectionPhase.READY }
            assertEquals(expectedSessionId, ready.sessionId)
            val anchor = ready.timeline.first { it.kind == TimelineKind.ASSISTANT }.id
            client.updateLocalDraft(OFFLINE_DRAFT)
            client.updateReadingPosition(anchor, READING_OFFSET, followTail = false)
        } finally {
            client.close()
        }
        assertTrue(offlineStore.encryptedFileForTest().exists())
    }

    @Test
    fun restoresStaleWorkspaceWithHostUnavailableThenClearsFixture() {
        val enabled = InstrumentationRegistry.getArguments().getString("offlineRestore")
        assumeTrue("Offline restore argument not supplied", enabled == "true")
        val client = Gate0CClient(context)
        try {
            client.connect()
            val restored = waitForState(client, timeoutSeconds = 15, allowFailure = true) {
                it.offlineSnapshot && it.localDraft == OFFLINE_DRAFT && it.timeline.isNotEmpty()
            }
            assertTrue(restored.phase != ConnectionPhase.READY && restored.phase != ConnectionPhase.RECONCILED)
            assertNotNull(restored.offlineCacheSavedAtMs)
            assertEquals(READING_OFFSET, restored.readingOffsetPx)
            assertTrue(!restored.followTail)
            assertTrue(restored.sessions.isNotEmpty())

            val failed = waitForState(client, timeoutSeconds = 15, allowFailure = true) {
                it.phase == ConnectionPhase.OFFLINE
            }
            assertTrue(failed.offlineSnapshot)
            assertEquals(OFFLINE_DRAFT, failed.localDraft)
            assertTrue(failed.timeline.isNotEmpty())

            val hostBeforeClear = requireNotNull(hostStore.loadSole())
            val devicePublicKeyBeforeClear = identityStore.loadOrCreate().use { it.publicKey }
            client.clearOfflineWorkspace()
            val cleared = client.state.value
            assertFalse(offlineStore.encryptedFileForTest().exists())
            assertFalse(cleared.offlineSnapshot)
            assertTrue(cleared.sessions.isEmpty() && cleared.timeline.isEmpty())
            assertTrue(cleared.localDraft.isEmpty() && cleared.readingAnchorId == null)
            val hostAfterClear = requireNotNull(hostStore.loadSole())
            val devicePublicKeyAfterClear = identityStore.loadOrCreate().use { it.publicKey }
            assertArrayEquals(hostBeforeClear.hostPublicKey, hostAfterClear.hostPublicKey)
            assertArrayEquals(devicePublicKeyBeforeClear, devicePublicKeyAfterClear)
            hostBeforeClear.hostPublicKey.fill(0)
            hostAfterClear.hostPublicKey.fill(0)
            devicePublicKeyBeforeClear.fill(0)
            devicePublicKeyAfterClear.fill(0)
        } finally {
            client.close()
            clearAll()
        }
    }

    private fun waitForState(
        client: Gate0CClient,
        timeoutSeconds: Long,
        allowFailure: Boolean = false,
        predicate: (Gate0CState) -> Boolean,
    ): Gate0CState {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (System.nanoTime() < deadline) {
            val state = client.state.value
            if (!allowFailure && state.phase == ConnectionPhase.FAILED) {
                throw AssertionError("Carrier failed: ${state.failure}")
            }
            if (predicate(state)) return state
            Thread.sleep(25)
        }
        throw AssertionError("Timed out waiting for offline recovery state; last=${client.state.value}")
    }

    private fun clearAll() {
        offlineStore.clear()
        hostStore.delete()
        identityStore.delete()
    }

    private companion object {
        const val OFFLINE_DRAFT = "Review this local draft after reconnect"
        const val READING_OFFSET = 7
    }
}
