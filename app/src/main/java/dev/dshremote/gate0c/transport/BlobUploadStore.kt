package dev.dshremote.gate0c.transport

import android.content.Context
import android.util.AtomicFile
import dev.dshremote.security.SealedWrappingKeys
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * ADR-005 上传声明的 Android 落点：Keystore 包裹、按 Host 作用域隔离的
 * [BlobUploadJournal]。纪律与 PendingCommandStore 相同——独立密钥别名与
 * AAD、AES/GCM、AtomicFile 写入、noBackup 目录。解密或解析失败意味着声明
 * 已不可信（篡改或密钥轮换）：删除并如实返回"无可续传"，管线把残留暂存
 * 当孤儿清扫，Host 侧按 TTL 清扫——绝不凭不可信声明续传。
 */
internal class BlobUploadStore(context: Context, hostScope: String? = null) : BlobUploadJournal {
    private val journalFile = AtomicFile(
        // 与 PendingCommandStore 同一布局：每个 Host 一本日志；null 作用域
        // 为单 Host 路径。
        if (hostScope == null) {
            File(context.noBackupFilesDir, "security/blob-upload-journal.bin").also {
                it.parentFile?.mkdirs()
            }
        } else {
            require(hostScope.matches(HOST_SCOPE_PATTERN)) { "Invalid blob upload Host scope" }
            File(context.noBackupFilesDir, "security/hosts/blob-upload-journal-$hostScope.bin").also {
                it.parentFile?.mkdirs()
            }
        },
    )

    @Synchronized
    override fun load(): BlobUploadDeclaration? {
        if (!journalFile.baseFile.exists()) return null
        var plaintext: ByteArray? = null
        return try {
            val (iv, ciphertext) = readEnvelope()
            try {
                val cipher = Cipher.getInstance(CIPHER)
                cipher.init(Cipher.DECRYPT_MODE, existingKey(), GCMParameterSpec(TAG_BITS, iv))
                cipher.updateAAD(AAD)
                plaintext = cipher.doFinal(ciphertext)
                BlobUploadJournalCodec.decode(plaintext)
            } finally {
                iv.fill(0)
                ciphertext.fill(0)
            }
        } catch (_: Exception) {
            journalFile.delete()
            null
        } finally {
            plaintext?.fill(0)
        }
    }

    @Synchronized
    override fun save(declaration: BlobUploadDeclaration) {
        val plaintext = BlobUploadJournalCodec.encode(declaration)
        require(plaintext.size <= MAX_PLAINTEXT_BYTES)
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
    override fun clear() {
        journalFile.delete()
    }

    internal fun encryptedFileForTest(): File = journalFile.baseFile

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
        var stream: java.io.FileOutputStream? = journalFile.startWrite()
        try {
            stream!!.write(envelope)
            journalFile.finishWrite(stream)
            stream = null
        } finally {
            stream?.let(journalFile::failWrite)
            envelope.fill(0)
        }
    }

    private fun readEnvelope(): Pair<ByteArray, ByteArray> {
        val bytes = journalFile.readFully()
        require(bytes.size <= MAX_ENVELOPE_BYTES)
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
            ?: error("Blob upload wrapping key is missing")

    private fun getOrCreateKey(): SecretKey = SealedWrappingKeys.getOrCreate(KEY_ALIAS)

    private companion object {
        const val KEY_ALIAS = "dsh_remote_blob_upload_wrap_v1"
        const val CIPHER = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val ENVELOPE_VERSION = 1
        const val MAX_PLAINTEXT_BYTES = 4 * 1024
        const val MAX_ENVELOPE_BYTES = MAX_PLAINTEXT_BYTES + 1_024
        val MAGIC = "DSHRBLB1".encodeToByteArray()
        val AAD = "dsh-remote/blob-upload-journal/v1".encodeToByteArray()
        val HOST_SCOPE_PATTERN = Regex("[0-9a-f]{64}")
    }
}

/** 声明的有界二进制编码：解码经 [BlobUploadDeclaration] init 复验，篡改在此再次失败。 */
internal object BlobUploadJournalCodec {
    private const val FORMAT_VERSION = 1
    private const val MAX_NAME_BYTES = 512
    private val MEDIA_TYPE = Regex("[\\x21-\\x7e]{1,100}")

    fun encode(declaration: BlobUploadDeclaration): ByteArray = ByteArrayOutputStream().use { output ->
        DataOutputStream(output).use { data ->
            data.writeInt(FORMAT_VERSION)
            data.writeBoundedString(declaration.transferId)
            data.writeBoundedString(declaration.sha256Hex)
            data.writeLong(declaration.totalBytes)
            data.writeBoolean(declaration.mediaType != null)
            declaration.mediaType?.let { mediaType ->
                require(mediaType.matches(MEDIA_TYPE)) { "Invalid blob media type" }
                data.writeBoundedString(mediaType)
            }
            data.writeBoolean(declaration.displayName != null)
            declaration.displayName?.let { displayName ->
                require(displayName.isNotEmpty() && displayName.encodeToByteArray().size <= MAX_NAME_BYTES) {
                    "Invalid blob display name"
                }
                data.writeBoundedString(displayName)
            }
            data.writeLong(declaration.createdAtMs)
        }
        output.toByteArray()
    }

    fun decode(bytes: ByteArray): BlobUploadDeclaration = DataInputStream(ByteArrayInputStream(bytes)).use { data ->
        val version = data.readInt()
        require(version == FORMAT_VERSION) { "Unsupported blob upload journal version" }
        val declaration = BlobUploadDeclaration(
            transferId = data.readBoundedString(),
            sha256Hex = data.readBoundedString(),
            totalBytes = data.readLong(),
            mediaType = if (data.readBoolean()) data.readBoundedString() else null,
            displayName = if (data.readBoolean()) data.readBoundedString() else null,
            createdAtMs = data.readLong(),
        )
        require(declaration.createdAtMs >= 0) { "Invalid blob declaration timestamp" }
        require(data.available() == 0) { "Trailing blob upload journal data" }
        declaration
    }
}

private fun DataOutputStream.writeBoundedString(value: String) {
    val bytes = value.encodeToByteArray()
    require(bytes.size <= 16 * 1024)
    writeInt(bytes.size)
    write(bytes)
    bytes.fill(0)
}

private fun DataInputStream.readBoundedString(maxChars: Int = 16 * 1024): String {
    val size = readInt()
    require(size in 0..(16 * 1024) && size <= available())
    val bytes = ByteArray(size).also(::readFully)
    return try {
        bytes.decodeToString(throwOnInvalidSequence = true).also { require(it.length <= maxChars) }
    } finally {
        bytes.fill(0)
    }
}
