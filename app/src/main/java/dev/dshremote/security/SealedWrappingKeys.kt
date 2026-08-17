package dev.dshremote.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The one Keystore wrapping-key policy every sealed store on this device
 * shares (identity, paired hosts, offline cache, pending commands, blob
 * staging): AES-256-GCM, unlocked-device required, StrongBox when the part
 * exists. Single-sourced so a hardening change cannot reach four stores and
 * miss the fifth — each store keeps only its own alias and envelope format.
 */
internal object SealedWrappingKeys {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    /** The alias's key, or null when this device never created it. */
    fun existing(alias: String): SecretKey? =
        keyStore().getKey(alias, null) as? SecretKey

    /** Destroy the alias's key (a store wipe); missing entries are a no-op. */
    fun delete(alias: String) {
        keyStore().deleteEntry(alias)
    }

    /** The alias's key, generated under the shared policy on first use. */
    fun getOrCreate(alias: String): SecretKey {
        existing(alias)?.let { return it }
        return try {
            generate(alias, useStrongBox = true)
        } catch (_: StrongBoxUnavailableException) {
            generate(alias, useStrongBox = false)
        }
    }

    private fun generate(alias: String, useStrongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .setUnlockedDeviceRequired(true)
            .setIsStrongBoxBacked(useStrongBox)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(spec)
            generateKey()
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
}
