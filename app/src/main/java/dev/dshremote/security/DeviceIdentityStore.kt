package dev.dshremote.security

import android.content.Context
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Keystore-wrapped, non-backed-up storage for the Android device identity. */
class DeviceIdentityStore(context: Context) {
    private val identityFile = AtomicFile(
        File(context.noBackupFilesDir, "security/device-identity.bin").also {
            it.parentFile?.mkdirs()
        },
    )

    @Synchronized
    fun loadOrCreate(): SecureIdentity {
        if (identityFile.baseFile.exists()) return load()

        val identity = SecureIdentity.generate()
        val protectedBytes = identity.protectedStorageCopy()
        try {
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateWrappingKey())
            cipher.updateAAD(AAD)
            val ciphertext = cipher.doFinal(protectedBytes)
            writeEnvelope(cipher.iv, ciphertext)
            ciphertext.fill(0)
            return identity
        } catch (error: Exception) {
            identity.close()
            throw DeviceIdentityStorageException("Unable to protect device identity", error)
        } finally {
            protectedBytes.fill(0)
        }
    }

    @Synchronized
    fun delete() {
        identityFile.delete()
        SealedWrappingKeys.delete(KEY_ALIAS)
    }

    internal fun encryptedFileForTest(): File = identityFile.baseFile

    private fun load(): SecureIdentity {
        val (iv, ciphertext) = readEnvelope()
        return try {
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(Cipher.DECRYPT_MODE, getExistingWrappingKey(), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(AAD)
            SecureIdentity.takeProtectedStorageBytes(cipher.doFinal(ciphertext))
        } catch (error: Exception) {
            throw DeviceIdentityStorageException(
                "Stored device identity cannot be authenticated; explicit repair is required",
                error,
            )
        } finally {
            iv.fill(0)
            ciphertext.fill(0)
        }
    }

    private fun getExistingWrappingKey(): SecretKey =
        SealedWrappingKeys.existing(KEY_ALIAS)
            ?: throw DeviceIdentityStorageException("Device identity wrapping key is missing")

    private fun getOrCreateWrappingKey(): SecretKey = SealedWrappingKeys.getOrCreate(KEY_ALIAS)

    private fun writeEnvelope(iv: ByteArray, ciphertext: ByteArray) {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.write(MAGIC)
            data.writeInt(FORMAT_VERSION)
            data.writeInt(iv.size)
            data.write(iv)
            data.writeInt(ciphertext.size)
            data.write(ciphertext)
        }
        var stream: java.io.FileOutputStream? = identityFile.startWrite()
        try {
            stream!!.write(output.toByteArray())
            identityFile.finishWrite(stream)
            stream = null
        } finally {
            stream?.let(identityFile::failWrite)
        }
    }

    private fun readEnvelope(): Pair<ByteArray, ByteArray> {
        val bytes = identityFile.readFully()
        if (bytes.size > MAX_ENVELOPE_BYTES) {
            throw DeviceIdentityStorageException("Device identity envelope exceeds its bound")
        }
        try {
            DataInputStream(ByteArrayInputStream(bytes)).use { data ->
                val magic = ByteArray(MAGIC.size).also(data::readFully)
                if (!magic.contentEquals(MAGIC) || data.readInt() != FORMAT_VERSION) {
                    throw DeviceIdentityStorageException("Unsupported device identity envelope")
                }
                val ivSize = data.readInt()
                if (ivSize !in 12..16) throw DeviceIdentityStorageException("Invalid identity IV")
                val iv = ByteArray(ivSize).also(data::readFully)
                val ciphertextSize = data.readInt()
                if (ciphertextSize !in 17..MAX_ENVELOPE_BYTES || ciphertextSize > data.available()) {
                    iv.fill(0)
                    throw DeviceIdentityStorageException("Invalid identity ciphertext")
                }
                val ciphertext = ByteArray(ciphertextSize).also(data::readFully)
                if (data.available() != 0) {
                    iv.fill(0)
                    ciphertext.fill(0)
                    throw DeviceIdentityStorageException("Trailing identity envelope data")
                }
                return iv to ciphertext
            }
        } finally {
            bytes.fill(0)
        }
    }

    companion object {
        private const val KEY_ALIAS = "dsh_remote_device_identity_wrap_v1"
        private const val CIPHER = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val FORMAT_VERSION = 1
        private const val MAX_ENVELOPE_BYTES = 4 * 1024
        private val MAGIC = "DSHRKEY1".encodeToByteArray()
        private val AAD = "dsh-remote/device-identity/v1".encodeToByteArray()
    }
}

class DeviceIdentityStorageException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
