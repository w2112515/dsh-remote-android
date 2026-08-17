package dev.dshremote.security

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.security.KeyStoreException
import android.security.keystore.UserNotAuthenticatedException
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.InvalidKeyException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Authenticated M1 record written only after Host-confirmed Noise pairing. */
data class PairedHostRecord(
    val hostPublicKey: ByteArray,
    val endpointHost: String,
    val endpointPort: Int,
    val capabilities: Long,
    val pairedAtMs: Long,
) {
    init {
        require(hostPublicKey.size == 32) { "Host public key must be 32 bytes" }
        require(isAllowedRemoteEndpoint(endpointHost)) { "Invalid Host endpoint" }
        require(endpointPort in 1..65_535) { "Invalid Host endpoint port" }
        require(capabilities in SUPPORTED_CAPABILITY_PROFILES) { "Unsupported Host capability profile" }
        require(pairedAtMs >= 0) { "Invalid pairing timestamp" }
    }

    fun copyForUse(): PairedHostRecord = copy(hostPublicKey = hostPublicKey.copyOf())

    companion object {
        const val READ_ONLY_CAPABILITIES = 3L
        const val APPROVAL_REVIEWER_CAPABILITIES = 19L
        const val SESSION_CONTROL_CAPABILITIES = 71L
        const val SESSION_OPERATOR_CAPABILITIES = 79L
        const val SESSION_SUPERVISOR_CAPABILITIES = 95L
        const val HOST_SUPERVISOR_CAPABILITIES = 351L
        val SUPPORTED_CAPABILITY_PROFILES = setOf(
            READ_ONLY_CAPABILITIES,
            APPROVAL_REVIEWER_CAPABILITIES,
            SESSION_CONTROL_CAPABILITIES,
            SESSION_OPERATOR_CAPABILITIES,
            SESSION_SUPERVISOR_CAPABILITIES,
            HOST_SUPERVISOR_CAPABILITIES,
        )
    }
}

/** Authenticated Host identity awaiting proof that the Host committed authorization. */
data class PendingHostRecoveryRecord(
    val hostPublicKey: ByteArray,
    val endpointHost: String,
    val endpointPort: Int,
    val capabilities: Long,
    val verificationCode: String,
    val startedAtMs: Long,
    val invitationExpiresAtMs: Long,
) {
    init {
        require(hostPublicKey.size == 32) { "Host public key must be 32 bytes" }
        require(isAllowedRemoteEndpoint(endpointHost)) { "Invalid Host endpoint" }
        require(endpointPort in 1..65_535) { "Invalid Host endpoint port" }
        require(capabilities in PairedHostRecord.SUPPORTED_CAPABILITY_PROFILES) {
            "Unsupported Host capability profile"
        }
        require(verificationCode.matches(Regex("\\d{8}"))) { "Invalid pairing comparison code" }
        require(startedAtMs >= 0) { "Invalid pairing start timestamp" }
        require(invitationExpiresAtMs > startedAtMs) { "Invalid pairing invitation expiry" }
    }

    fun copyForUse(): PendingHostRecoveryRecord = copy(hostPublicKey = hostPublicKey.copyOf())

    fun confirmedHost(confirmedAtMs: Long): PairedHostRecord = PairedHostRecord(
        hostPublicKey = hostPublicKey.copyOf(),
        endpointHost = endpointHost,
        endpointPort = endpointPort,
        capabilities = capabilities,
        pairedAtMs = confirmedAtMs,
    )
}

/** Keystore-authenticated, non-backed-up storage for confirmed and pending Host state. */
class PairedHostStore(context: Context) {
    private val keyguardManager = context.getSystemService(KeyguardManager::class.java)
    private val recordFile = AtomicFile(
        File(context.noBackupFilesDir, "security/paired-host.bin").also {
            it.parentFile?.mkdirs()
        },
    )
    private val hostsDir = File(context.noBackupFilesDir, "security/hosts")
    private val pendingFile = AtomicFile(
        File(context.noBackupFilesDir, "security/pending-host-recovery.bin"),
    )

    /**
     * Stable Host key (S-multi-host): lowercase SHA-256 hex of the Host public key —
     * the same digest as the display fingerprint, formatted file-safe.
     */
    fun hostIdOf(record: PairedHostRecord): String = hostIdOfKey(record.hostPublicKey)

    /**
     * Every confirmed Host, migrating the legacy single-Host record on first read.
     * A file that does not authenticate against its own name fails closed like the
     * legacy record did — a silently dropped Host would read as "never paired".
     */
    @Synchronized
    fun list(): List<PairedHostRecord> {
        migrateLegacyRecord()
        val files = hostsDir.listFiles { file -> file.extension == "bin" } ?: return emptyList()
        return files.sortedBy { it.name }.map { file ->
            val hostId = file.nameWithoutExtension
            require(hostId.matches(HOST_ID_PATTERN)) { "Invalid paired Host file name" }
            loadProtected(
                file = AtomicFile(file),
                aad = hostAad(hostId),
                failure = "Stored Host pin cannot be authenticated; explicit repair is required",
                decode = ::decodeRecord,
            ) ?: throw PairedHostStorageException("Stored Host pin is empty; explicit repair is required")
        }
    }

    /** The only confirmed Host, or null when none or several exist (fleet mode owns the rest). */
    @Synchronized
    fun loadSole(): PairedHostRecord? = list().singleOrNull()

    @Synchronized
    fun load(hostId: String): PairedHostRecord? {
        require(hostId.matches(HOST_ID_PATTERN)) { "Invalid paired Host id" }
        migrateLegacyRecord()
        val file = AtomicFile(hostFile(hostId))
        if (!file.baseFile.exists()) return null
        return loadProtected(
            file = file,
            aad = hostAad(hostId),
            failure = "Stored Host pin cannot be authenticated; explicit repair is required",
            decode = ::decodeRecord,
        )
    }

    @Synchronized
    fun save(record: PairedHostRecord) {
        val hostId = hostIdOf(record)
        hostsDir.mkdirs()
        saveProtected(
            AtomicFile(hostFile(hostId)),
            hostAad(hostId),
            encodeRecord(record),
            "Unable to protect paired Host record",
        )
    }

    @Synchronized
    fun delete(hostId: String) {
        require(hostId.matches(HOST_ID_PATTERN)) { "Invalid paired Host id" }
        hostFile(hostId).delete()
    }

    @Synchronized
    fun loadPendingRecovery(): PendingHostRecoveryRecord? = loadProtected(
        file = pendingFile,
        aad = PENDING_AAD,
        failure = "Pending Host recovery cannot be authenticated; explicit repair is required",
        decode = ::decodePendingRecord,
    )

    @Synchronized
    fun savePendingRecovery(record: PendingHostRecoveryRecord) {
        saveProtected(
            pendingFile,
            PENDING_AAD,
            encodePendingRecord(record),
            "Unable to protect pending Host recovery",
        )
    }

    @Synchronized
    fun clearPendingRecovery() {
        pendingFile.delete()
    }

    @Synchronized
    fun delete() {
        recordFile.delete()
        hostsDir.listFiles()?.forEach { it.delete() }
        pendingFile.delete()
        SealedWrappingKeys.delete(KEY_ALIAS)
    }

    /** Import the pre-fleet single-Host record into per-Host storage exactly once. */
    private fun migrateLegacyRecord() {
        if (!recordFile.baseFile.exists()) return
        val legacy = loadProtected(
            file = recordFile,
            aad = CONFIRMED_AAD,
            failure = "Stored Host pin cannot be authenticated; explicit repair is required",
            decode = ::decodeRecord,
        )
        if (legacy != null) save(legacy)
        recordFile.delete()
    }

    private fun hostFile(hostId: String): File = File(hostsDir, "$hostId.bin")

    private fun hostAad(hostId: String): ByteArray = CONFIRMED_AAD + hostId.encodeToByteArray()

    internal fun encryptedFileForTest(hostId: String): File = hostFile(hostId)
    internal fun encryptedLegacyFileForTest(): File = recordFile.baseFile
    internal fun encryptedPendingFileForTest(): File = pendingFile.baseFile

    /** Instrumented-test legacy writer: exercises the one-time pre-fleet migration. */
    internal fun saveLegacyForTest(record: PairedHostRecord) {
        saveProtected(recordFile, CONFIRMED_AAD, encodeRecord(record), "Unable to protect paired Host record")
    }

    private fun encodeRecord(record: PairedHostRecord): ByteArray = ByteArrayOutputStream().use { output ->
        DataOutputStream(output).use { data ->
            data.writeInt(RECORD_VERSION)
            data.write(record.hostPublicKey)
            data.writeUTF(record.endpointHost)
            data.writeInt(record.endpointPort)
            data.writeLong(record.capabilities)
            data.writeLong(record.pairedAtMs)
        }
        output.toByteArray()
    }

    private fun decodeRecord(bytes: ByteArray): PairedHostRecord =
        DataInputStream(ByteArrayInputStream(bytes)).use { data ->
            if (data.readInt() != RECORD_VERSION) throw PairedHostStorageException("Unsupported paired Host record")
            val publicKey = ByteArray(32).also(data::readFully)
            try {
                val host = data.readUTF()
                val port = data.readInt()
                val capabilities = data.readLong()
                val pairedAt = data.readLong()
                if (data.available() != 0) {
                    throw PairedHostStorageException("Trailing paired Host record data")
                }
                PairedHostRecord(publicKey, host, port, capabilities, pairedAt)
            } catch (error: Exception) {
                publicKey.fill(0)
                throw error
            }
        }

    private fun encodePendingRecord(record: PendingHostRecoveryRecord): ByteArray =
        ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.writeInt(PENDING_RECORD_VERSION)
                data.write(record.hostPublicKey)
                data.writeUTF(record.endpointHost)
                data.writeInt(record.endpointPort)
                data.writeLong(record.capabilities)
                data.writeUTF(record.verificationCode)
                data.writeLong(record.startedAtMs)
                data.writeLong(record.invitationExpiresAtMs)
            }
            output.toByteArray()
        }

    private fun decodePendingRecord(bytes: ByteArray): PendingHostRecoveryRecord =
        DataInputStream(ByteArrayInputStream(bytes)).use { data ->
            if (data.readInt() != PENDING_RECORD_VERSION) {
                throw PairedHostStorageException("Unsupported pending Host recovery record")
            }
            val publicKey = ByteArray(32).also(data::readFully)
            try {
                val host = data.readUTF()
                val port = data.readInt()
                val capabilities = data.readLong()
                val verificationCode = data.readUTF()
                val startedAt = data.readLong()
                val expiresAt = data.readLong()
                if (data.available() != 0) {
                    throw PairedHostStorageException("Trailing pending Host recovery data")
                }
                PendingHostRecoveryRecord(
                    publicKey,
                    host,
                    port,
                    capabilities,
                    verificationCode,
                    startedAt,
                    expiresAt,
                )
            } catch (error: Exception) {
                publicKey.fill(0)
                throw error
            }
        }

    private fun <T> loadProtected(
        file: AtomicFile,
        aad: ByteArray,
        failure: String,
        decode: (ByteArray) -> T,
    ): T? {
        if (!file.baseFile.exists()) return null
        val (iv, ciphertext) = readEnvelope(file)
        var plaintext: ByteArray? = null
        return try {
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(Cipher.DECRYPT_MODE, existingKey(), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(aad)
            plaintext = cipher.doFinal(ciphertext)
            decode(plaintext)
        } catch (error: Exception) {
            throw when (error) {
                is PairedHostLockedException -> error
                else -> wrapStorageFailure(error, failure)
            }
        } finally {
            iv.fill(0)
            ciphertext.fill(0)
            plaintext?.fill(0)
        }
    }

    private fun saveProtected(
        file: AtomicFile,
        aad: ByteArray,
        plaintext: ByteArray,
        failure: String,
    ) {
        var ciphertext: ByteArray? = null
        try {
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            cipher.updateAAD(aad)
            ciphertext = cipher.doFinal(plaintext)
            writeEnvelope(file, cipher.iv, ciphertext)
        } catch (error: Exception) {
            throw when (error) {
                is PairedHostLockedException -> error
                else -> wrapStorageFailure(error, failure)
            }
        } finally {
            plaintext.fill(0)
            ciphertext?.fill(0)
        }
    }

    private fun writeEnvelope(file: AtomicFile, iv: ByteArray, ciphertext: ByteArray) {
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
        var stream: java.io.FileOutputStream? = file.startWrite()
        try {
            stream!!.write(envelope)
            file.finishWrite(stream)
            stream = null
        } finally {
            stream?.let(file::failWrite)
            envelope.fill(0)
        }
    }

    private fun readEnvelope(file: AtomicFile): Pair<ByteArray, ByteArray> {
        val bytes = file.readFully()
        if (bytes.size > MAX_ENVELOPE_BYTES) {
            bytes.fill(0)
            throw PairedHostStorageException("Paired Host envelope exceeds its bound")
        }
        try {
            DataInputStream(ByteArrayInputStream(bytes)).use { data ->
                val magic = ByteArray(MAGIC.size).also(data::readFully)
                if (!magic.contentEquals(MAGIC) || data.readInt() != ENVELOPE_VERSION) {
                    throw PairedHostStorageException("Unsupported paired Host envelope")
                }
                val ivSize = data.readInt()
                if (ivSize !in 12..16) throw PairedHostStorageException("Invalid paired Host IV")
                val iv = ByteArray(ivSize).also(data::readFully)
                val ciphertextSize = data.readInt()
                if (ciphertextSize !in 17..MAX_ENVELOPE_BYTES || ciphertextSize > data.available()) {
                    iv.fill(0)
                    throw PairedHostStorageException("Invalid paired Host ciphertext")
                }
                val ciphertext = ByteArray(ciphertextSize).also(data::readFully)
                if (data.available() != 0) {
                    iv.fill(0)
                    ciphertext.fill(0)
                    throw PairedHostStorageException("Trailing paired Host envelope data")
                }
                return iv to ciphertext
            }
        } finally {
            bytes.fill(0)
        }
    }

    private fun existingKey(): SecretKey =
        SealedWrappingKeys.existing(KEY_ALIAS)
            ?: throw if (keyguardManager?.isKeyguardLocked == true) {
                PairedHostLockedException("Host storage is sealed until the device is unlocked")
            } else {
                PairedHostStorageException("Paired Host wrapping key is missing")
            }

    /**
     * The wrapping key is gated by `setUnlockedDeviceRequired(true)`. A Keystore crypto
     * failure while the keyguard is locked is the documented seal — the record is intact
     * and retry after unlock is the honest answer; genuine corruption is indistinguishable
     * until the device is unlocked, so corruption is only ever reported when unlocked.
     */
    private fun wrapStorageFailure(error: Exception, failure: String): IllegalStateException =
        if (
            isDeviceLockedStorageError(error) ||
            (keyguardManager?.isKeyguardLocked == true && isKeyStoreCryptoFailure(error))
        ) {
            PairedHostLockedException("Host storage is sealed until the device is unlocked", error)
        } else {
            PairedHostStorageException(failure, error)
        }

    private fun getOrCreateKey(): SecretKey = SealedWrappingKeys.getOrCreate(KEY_ALIAS)

    companion object {
        private const val KEY_ALIAS = "dsh_remote_paired_host_wrap_v1"
        private const val CIPHER = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val ENVELOPE_VERSION = 1
        private const val RECORD_VERSION = 1
        private const val PENDING_RECORD_VERSION = 1
        private const val MAX_ENVELOPE_BYTES = 8 * 1024
        private val MAGIC = "DSHRHOST1".encodeToByteArray()
        private val CONFIRMED_AAD = "dsh-remote/paired-host/v1".encodeToByteArray()
        private val PENDING_AAD = "dsh-remote/pending-host-recovery/v1".encodeToByteArray()
        private val HOST_ID_PATTERN = Regex("[0-9a-f]{64}")

        /** Lowercase SHA-256 hex of one Host public key — the file-safe Host key. */
        fun hostIdOfKey(hostPublicKey: ByteArray): String {
            require(hostPublicKey.size == 32) { "Host public key must be 32 bytes" }
            return java.security.MessageDigest.getInstance("SHA-256")
                .digest(hostPublicKey)
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
        }
    }
}

class PairedHostStorageException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * Transient counterpart of [PairedHostStorageException]: the wrapping key is gated by
 * `setUnlockedDeviceRequired(true)` and the device is currently locked. The stored record
 * is intact; no repair or re-pairing is required — retry after the user unlocks.
 */
class PairedHostLockedException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * True when a Keystore failure means "key unavailable while the device is locked" rather
 * than record corruption or key invalidation. Conservative: only the documented
 * [UserNotAuthenticatedException] and its OEM [KeyStoreException] surface qualify.
 * The public [KeyStoreException] class exists only from API 33; below that the
 * platform cannot produce it, so the guarded check is exact, not a downgrade.
 */
internal fun isDeviceLockedStorageError(error: Throwable): Boolean {
    var cursor: Throwable? = error
    while (cursor != null) {
        if (cursor is UserNotAuthenticatedException) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            cursor is KeyStoreException &&
            cursor.message?.contains("user not authenticated", ignoreCase = true) == true
        ) {
            return true
        }
        cursor = cursor.cause
    }
    return false
}

/**
 * True when the chain is a Keystore-layer crypto failure (key unusable), as opposed to a
 * data-layer failure (corrupt envelope/record). Some OEM Keymaster stacks surface the
 * locked-key seal as plain [InvalidKeyException] over [KeyStoreException] instead of
 * [UserNotAuthenticatedException].
 */
internal fun isKeyStoreCryptoFailure(error: Throwable): Boolean {
    var cursor: Throwable? = error
    while (cursor != null) {
        if (cursor is InvalidKeyException || cursor is UserNotAuthenticatedException) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && cursor is KeyStoreException) return true
        cursor = cursor.cause
    }
    return false
}
