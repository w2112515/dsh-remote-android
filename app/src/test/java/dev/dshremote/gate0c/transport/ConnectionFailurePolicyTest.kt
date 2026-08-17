package dev.dshremote.gate0c.transport

import dev.dshremote.protocol.v1alpha.ErrorCode
import dev.dshremote.protocol.v1alpha.SecureErrorCode
import dev.dshremote.security.PairedHostLockedException
import dev.dshremote.security.PairedHostStorageException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionFailurePolicyTest {
    @Test
    fun incompatibleProtocolRemainsDistinctEvenWithCachedContent() {
        val error = SecureRemoteProtocolException(
            SecureErrorCode.SECURE_ERROR_CODE_INCOMPATIBLE_VERSION,
            "expected protocol 2",
        )

        assertEquals(ConnectionPhase.INCOMPATIBLE, connectionPhaseForCarrierFailure(error, false))
        assertEquals(ConnectionPhase.INCOMPATIBLE, connectionPhaseForCarrierFailure(error, true))
    }

    @Test
    fun ordinaryFailuresUseOfflineOnlyWhenContentCanBeRetained() {
        val error = IllegalStateException("Host unavailable")

        assertEquals(ConnectionPhase.FAILED, connectionPhaseForCarrierFailure(error, false))
        assertEquals(ConnectionPhase.OFFLINE, connectionPhaseForCarrierFailure(error, true))
    }

    @Test
    fun revokedOrUnknownAuthenticatedIdentityRequiresANewPairingCeremony() {
        val revoked = SecureRemoteProtocolException(
            SecureErrorCode.SECURE_ERROR_CODE_UNAUTHORIZED_DEVICE,
            "authenticated device is not authorized",
        )

        assertEquals(true, requiresNewPairing(revoked))
        assertEquals(false, requiresNewPairing(IllegalStateException("Host unavailable")))
        assertEquals(
            false,
            requiresNewPairing(
                SecureRemoteProtocolException(
                    SecureErrorCode.SECURE_ERROR_CODE_INCOMPATIBLE_VERSION,
                    "expected protocol 2",
                ),
            ),
        )
    }

    @Test
    fun authenticatedApplicationProtocolMismatchIsRecognized() {
        assertEquals(true, isIncompatibleProtocolError(ErrorCode.ERROR_CODE_INCOMPATIBLE_VERSION))
        assertEquals(false, isIncompatibleProtocolError(ErrorCode.ERROR_CODE_SNAPSHOT_REQUIRED))
        assertEquals(false, isIncompatibleProtocolError(ErrorCode.ERROR_CODE_INVALID_REQUEST))
    }

    @Test
    fun lockedStorageIsReportedAsTransientNeverAsRepair() {
        val error = PairedHostLockedException("Host storage is sealed until the device is unlocked")

        assertEquals(ConnectionPhase.FAILED, connectionPhaseForCarrierFailure(error, false))
        assertEquals(ConnectionPhase.OFFLINE, connectionPhaseForCarrierFailure(error, true))
        assertFalse(requiresNewPairing(error))

        val event = carrierFailureEventCopy(error, incompatible = false)
        val detail = carrierFailureDetail(error, incompatible = false)
        assertTrue(event.contains("unlock the device"))
        assertTrue(detail.contains("intact"))
        assertTrue(detail.contains("no repair", ignoreCase = true))
        assertFalse(detail.contains("explicit repair is required"))
    }

    @Test
    fun genuineStorageCorruptionKeepsExplicitRepairCopy() {
        val error = PairedHostStorageException(
            "Stored Host pin cannot be authenticated; explicit repair is required",
        )

        assertEquals(
            "Carrier failed: Stored Host pin cannot be authenticated; explicit repair is required",
            carrierFailureEventCopy(error, incompatible = false),
        )
        assertEquals(
            "Stored Host pin cannot be authenticated; explicit repair is required",
            carrierFailureDetail(error, incompatible = false),
        )
        assertFalse(requiresNewPairing(error))
    }

    @Test
    fun incompatibleCopyIsUnchangedByLockedClassifier() {
        val error = SecureRemoteProtocolException(
            SecureErrorCode.SECURE_ERROR_CODE_INCOMPATIBLE_VERSION,
            "expected protocol 2",
        )

        assertEquals(
            "The Host secure protocol is incompatible with this app.",
            carrierFailureEventCopy(error, incompatible = true),
        )
        assertEquals(
            "Update DSH Remote and the Host integration before reconnecting.",
            carrierFailureDetail(error, incompatible = true),
        )
    }
}
