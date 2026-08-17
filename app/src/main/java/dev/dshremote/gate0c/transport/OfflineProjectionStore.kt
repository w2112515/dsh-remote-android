package dev.dshremote.gate0c.transport

import android.content.Context
import android.util.AtomicFile
import dev.dshremote.security.SealedWrappingKeys
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class OfflineCacheLoad(
    val workspace: OfflineWorkspaceCache?,
    val warning: String? = null,
)

/** Authenticated, Host-bound and bounded offline Session projection cache. */
internal class OfflineProjectionStore(context: Context, hostScope: String? = null) {
    private val cacheFile = AtomicFile(
        // S-multi-host: each Host owns its cache file; the legacy flat path stays
        // the single-Host (null scope) location so existing installs keep their cache.
        if (hostScope == null) {
            File(context.noBackupFilesDir, "cache/offline-workspace.bin").also {
                it.parentFile?.mkdirs()
            }
        } else {
            require(hostScope.matches(HOST_SCOPE_PATTERN)) { "Invalid offline cache Host scope" }
            File(context.noBackupFilesDir, "cache/hosts/offline-workspace-$hostScope.bin").also {
                it.parentFile?.mkdirs()
            }
        },
    )

    @Synchronized
    fun load(
        expectedHostBinding: ByteArray,
        nowMs: Long = System.currentTimeMillis(),
    ): OfflineCacheLoad {
        require(expectedHostBinding.size == OfflineWorkspaceCache.HOST_BINDING_BYTES)
        if (!cacheFile.baseFile.exists()) return OfflineCacheLoad(null)
        var plaintext: ByteArray? = null
        return try {
            val (iv, ciphertext) = readEnvelope()
            try {
                val cipher = Cipher.getInstance(CIPHER)
                cipher.init(Cipher.DECRYPT_MODE, existingKey(), GCMParameterSpec(TAG_BITS, iv))
                cipher.updateAAD(AAD)
                plaintext = cipher.doFinal(ciphertext)
                val workspace = OfflineWorkspaceCodec.decode(plaintext)
                if (!MessageDigest.isEqual(workspace.hostBinding, expectedHostBinding)) {
                    cacheFile.delete()
                    OfflineCacheLoad(null, "Discarded offline data from a different Host or device identity.")
                } else if (workspace.savedAtMs > nowMs || nowMs - workspace.savedAtMs > CACHE_TTL_MS) {
                    cacheFile.delete()
                    OfflineCacheLoad(null, "Discarded an expired offline cache.")
                } else {
                    OfflineCacheLoad(workspace)
                }
            } finally {
                iv.fill(0)
                ciphertext.fill(0)
            }
        } catch (_: Exception) {
            cacheFile.delete()
            OfflineCacheLoad(null, "Discarded an unreadable or incompatible offline cache.")
        } finally {
            plaintext?.fill(0)
        }
    }

    @Synchronized
    fun save(workspace: OfflineWorkspaceCache) {
        val plaintext = OfflineWorkspaceCodec.encode(workspace)
        require(plaintext.size <= MAX_PLAINTEXT_BYTES) { "Offline cache exceeds its storage bound" }
        var ciphertext: ByteArray? = null
        try {
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            cipher.updateAAD(AAD)
            ciphertext = cipher.doFinal(plaintext)
            writeEnvelope(cipher.iv, ciphertext)
        } finally {
            plaintext.fill(0)
            ciphertext?.fill(0)
        }
    }

    @Synchronized
    fun clear() {
        cacheFile.delete()
    }

    internal fun encryptedFileForTest(): File = cacheFile.baseFile

    private fun writeEnvelope(iv: ByteArray, ciphertext: ByteArray) {
        val envelope = ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.write(MAGIC)
                data.writeInt(ENVELOPE_VERSION)
                data.writeInt(iv.size)
                data.write(iv)
                data.writeInt(ciphertext.size)
                data.write(ciphertext)
            }
            output.toByteArray()
        }
        var stream: java.io.FileOutputStream? = cacheFile.startWrite()
        try {
            stream!!.write(envelope)
            cacheFile.finishWrite(stream)
            stream = null
        } finally {
            stream?.let(cacheFile::failWrite)
            envelope.fill(0)
        }
    }

    private fun readEnvelope(): Pair<ByteArray, ByteArray> {
        val bytes = cacheFile.readFully()
        require(bytes.size <= MAX_ENVELOPE_BYTES) { "Offline cache envelope exceeds its bound" }
        try {
            DataInputStream(ByteArrayInputStream(bytes)).use { data ->
                val magic = ByteArray(MAGIC.size).also(data::readFully)
                require(magic.contentEquals(MAGIC) && data.readInt() == ENVELOPE_VERSION)
                val ivSize = data.readInt()
                require(ivSize in 12..16)
                val iv = ByteArray(ivSize).also(data::readFully)
                val ciphertextSize = data.readInt()
                require(ciphertextSize in 17..MAX_ENVELOPE_BYTES && ciphertextSize <= data.available())
                val ciphertext = ByteArray(ciphertextSize).also(data::readFully)
                require(data.available() == 0)
                return iv to ciphertext
            }
        } finally {
            bytes.fill(0)
        }
    }

    private fun existingKey(): SecretKey =
        SealedWrappingKeys.existing(KEY_ALIAS)
            ?: error("Offline cache wrapping key is missing")

    private fun getOrCreateKey(): SecretKey = SealedWrappingKeys.getOrCreate(KEY_ALIAS)

    companion object {
        private const val KEY_ALIAS = "dsh_remote_offline_projection_wrap_v1"
        private const val CIPHER = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val ENVELOPE_VERSION = 1
        private const val MAX_PLAINTEXT_BYTES = 3 * 1024 * 1024
        private const val MAX_ENVELOPE_BYTES = MAX_PLAINTEXT_BYTES + 1024
        private const val CACHE_TTL_MS = 30L * 24 * 60 * 60 * 1_000
        private val MAGIC = "DSHRCCH1".encodeToByteArray()
        private val AAD = "dsh-remote/offline-projection/v1".encodeToByteArray()
        private val HOST_SCOPE_PATTERN = Regex("[0-9a-f]{64}")

        fun hostBinding(
            hostPublicKey: ByteArray,
            devicePublicKey: ByteArray,
            capabilities: Long,
        ): ByteArray {
            require(hostPublicKey.size == 32 && devicePublicKey.size == 32)
            return MessageDigest.getInstance("SHA-256").run {
                update("dsh-remote/offline-authority/v1".encodeToByteArray())
                update(hostPublicKey)
                update(devicePublicKey)
                update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(capabilities).array())
                update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(1).array())
                digest()
            }
        }
    }
}
