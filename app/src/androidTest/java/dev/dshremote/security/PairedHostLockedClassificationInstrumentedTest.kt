package dev.dshremote.security

import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import androidx.test.ext.junit.runners.AndroidJUnit4
import javax.crypto.AEADBadTagException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The paired-Host wrapping key is gated by `setUnlockedDeviceRequired(true)`. While the
 * device is locked, Keystore surfaces [UserNotAuthenticatedException] (or, on some OEM
 * stacks, a [KeyStoreException] whose detail says the user is not authenticated). Those
 * cases must classify as the transient [PairedHostLockedException] path — never as the
 * corruption/repair path.
 */
@RunWith(AndroidJUnit4::class)
class PairedHostLockedClassificationInstrumentedTest {
    @Test
    fun userNotAuthenticatedAnywhereInTheChainClassifiesAsLocked() {
        assertTrue(isDeviceLockedStorageError(UserNotAuthenticatedException()))
        assertTrue(
            isDeviceLockedStorageError(
                IllegalStateException("cipher init failed", UserNotAuthenticatedException()),
            ),
        )
    }

    @Test
    fun corruptionAndInvalidationNeverClassifyAsLocked() {
        assertFalse(isDeviceLockedStorageError(AEADBadTagException("tag mismatch")))
        assertFalse(isDeviceLockedStorageError(KeyPermanentlyInvalidatedException()))
        assertFalse(isDeviceLockedStorageError(IllegalStateException("Trailing paired Host record data")))
    }

    @Test
    fun oemSealedKeySurfaceIsAKeyStoreCryptoFailure() {
        // Observed on vivo (MediaTek Keymaster): plain InvalidKeyException over
        // KeyStoreException instead of UserNotAuthenticatedException.
        // android.security.KeyStoreException's constructor is package-private, so the
        // Keystore-in-chain shape itself is covered by the vivo physical acceptance record;
        // the constructible surfaces are pinned here.
        assertTrue(isKeyStoreCryptoFailure(java.security.InvalidKeyException("sealed")))
        assertTrue(
            isKeyStoreCryptoFailure(
                java.security.InvalidKeyException("sealed", UserNotAuthenticatedException()),
            ),
        )
        assertTrue(isKeyStoreCryptoFailure(UserNotAuthenticatedException()))
    }

    @Test
    fun dataLayerCorruptionIsNotAKeyStoreCryptoFailure() {
        assertFalse(isKeyStoreCryptoFailure(AEADBadTagException("tag mismatch")))
        assertFalse(isKeyStoreCryptoFailure(IllegalStateException("Trailing paired Host envelope data")))
    }
}
