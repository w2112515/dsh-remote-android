package dev.dshremote.security

import java.io.Closeable

/** Fail-closed error emitted by the shared native security core. */
class SecurityCoreException(message: String) : IllegalStateException(message)

/** One Noise static identity held in process memory only while in use. */
class SecureIdentity private constructor(
    private var protectedBytes: ByteArray?,
) : Closeable {
    val publicKey: ByteArray
        @Synchronized get() = requireBytes().copyOfRange(PRIVATE_KEY_BYTES, ENCODED_IDENTITY_BYTES)

    @Synchronized
    internal fun <T> useProtectedBytes(block: (ByteArray) -> T): T = block(requireBytes())

    @Synchronized
    internal fun protectedStorageCopy(): ByteArray = requireBytes().copyOf()

    @Synchronized
    override fun close() {
        protectedBytes?.fill(0)
        protectedBytes = null
    }

    private fun requireBytes(): ByteArray =
        checkNotNull(protectedBytes) { "SecureIdentity is closed" }

    companion object {
        private const val PRIVATE_KEY_BYTES = 32
        private const val ENCODED_IDENTITY_BYTES = 64

        fun generate(): SecureIdentity = SecureIdentity(NativeSecurity.generateIdentity())

        internal fun takeProtectedStorageBytes(bytes: ByteArray): SecureIdentity {
            require(bytes.size == ENCODED_IDENTITY_BYTES) { "Invalid protected identity size" }
            return SecureIdentity(bytes)
        }
    }
}

/** One ordered Noise handshake; it becomes unusable after transport conversion. */
class NoiseHandshake private constructor(
    private var handle: Long,
) : Closeable {
    @Synchronized
    fun write(payload: ByteArray = byteArrayOf()): ByteArray =
        NativeSecurity.handshakeWrite(requireHandle(), payload)

    @Synchronized
    fun read(message: ByteArray): ByteArray =
        NativeSecurity.handshakeRead(requireHandle(), message)

    @get:Synchronized
    val isFinished: Boolean
        get() = NativeSecurity.handshakeFinished(requireHandle())

    @Synchronized
    fun peerPublicKey(): ByteArray = NativeSecurity.handshakePeerKey(requireHandle())

    @Synchronized
    fun verificationCode(): String =
        NativeSecurity.handshakeVerificationCode(requireHandle())

    @Synchronized
    fun intoTransport(): SecureChannel {
        val current = requireHandle()
        val transport = NativeSecurity.handshakeIntoTransport(current)
        NativeSecurity.handshakeDestroy(current)
        handle = 0
        return SecureChannel(transport)
    }

    @Synchronized
    override fun close() {
        if (handle != 0L) {
            NativeSecurity.handshakeDestroy(handle)
            handle = 0
        }
    }

    private fun requireHandle(): Long = check(handle != 0L) { "NoiseHandshake is closed" }.let { handle }

    companion object {
        fun pairingInitiator(
            identity: SecureIdentity,
            invitationPsk: ByteArray,
            canonicalPrologue: ByteArray,
        ): NoiseHandshake = identity.useProtectedBytes { encoded ->
            NoiseHandshake(
                NativeSecurity.pairingInitiator(encoded, invitationPsk, canonicalPrologue),
            )
        }

        fun pairingResponder(
            identity: SecureIdentity,
            invitationPsk: ByteArray,
            canonicalPrologue: ByteArray,
        ): NoiseHandshake = identity.useProtectedBytes { encoded ->
            NoiseHandshake(
                NativeSecurity.pairingResponder(encoded, invitationPsk, canonicalPrologue),
            )
        }

        fun connectionInitiator(
            identity: SecureIdentity,
            hostPublicKey: ByteArray,
            canonicalPrologue: ByteArray,
        ): NoiseHandshake = identity.useProtectedBytes { encoded ->
            NoiseHandshake(
                NativeSecurity.connectionInitiator(encoded, hostPublicKey, canonicalPrologue),
            )
        }

        fun connectionResponder(
            identity: SecureIdentity,
            canonicalPrologue: ByteArray,
        ): NoiseHandshake = identity.useProtectedBytes { encoded ->
            NoiseHandshake(
                NativeSecurity.connectionResponder(encoded, canonicalPrologue),
            )
        }
    }
}

/** Ordered authenticated record protection for one logical connection. */
class SecureChannel internal constructor(
    private var handle: Long,
) : Closeable {
    @Synchronized
    fun encrypt(plaintext: ByteArray): ByteArray =
        NativeSecurity.transportEncrypt(requireHandle(), plaintext)

    @Synchronized
    fun decrypt(ciphertext: ByteArray): ByteArray =
        NativeSecurity.transportDecrypt(requireHandle(), ciphertext)

    @Synchronized
    fun rekey() = NativeSecurity.transportRekey(requireHandle())

    @Synchronized
    override fun close() {
        if (handle != 0L) {
            NativeSecurity.transportDestroy(handle)
            handle = 0
        }
    }

    private fun requireHandle(): Long = check(handle != 0L) { "SecureChannel is closed" }.let { handle }
}

internal object NativeSecurity {
    init {
        System.loadLibrary("dsh_remote_security_core")
    }

    fun generateIdentity(): ByteArray = nativeGenerateIdentity()
    fun pairingInitiator(identity: ByteArray, psk: ByteArray, prologue: ByteArray): Long =
        nativePairingInitiator(identity, psk, prologue)
    fun pairingResponder(identity: ByteArray, psk: ByteArray, prologue: ByteArray): Long =
        nativePairingResponder(identity, psk, prologue)
    fun connectionInitiator(identity: ByteArray, hostKey: ByteArray, prologue: ByteArray): Long =
        nativeConnectionInitiator(identity, hostKey, prologue)
    fun connectionResponder(identity: ByteArray, prologue: ByteArray): Long =
        nativeConnectionResponder(identity, prologue)
    fun handshakeWrite(handle: Long, payload: ByteArray): ByteArray =
        nativeHandshakeWrite(handle, payload)
    fun handshakeRead(handle: Long, message: ByteArray): ByteArray =
        nativeHandshakeRead(handle, message)
    fun handshakeFinished(handle: Long): Boolean = nativeHandshakeFinished(handle)
    fun handshakePeerKey(handle: Long): ByteArray = nativeHandshakePeerKey(handle)
    fun handshakeVerificationCode(handle: Long): String =
        nativeHandshakeVerificationCode(handle)
    fun handshakeIntoTransport(handle: Long): Long = nativeHandshakeIntoTransport(handle)
    fun handshakeDestroy(handle: Long) = nativeHandshakeDestroy(handle)
    fun transportEncrypt(handle: Long, plaintext: ByteArray): ByteArray =
        nativeTransportEncrypt(handle, plaintext)
    fun transportDecrypt(handle: Long, ciphertext: ByteArray): ByteArray =
        nativeTransportDecrypt(handle, ciphertext)
    fun transportRekey(handle: Long) = nativeTransportRekey(handle)
    fun transportDestroy(handle: Long) = nativeTransportDestroy(handle)

    @JvmStatic private external fun nativeGenerateIdentity(): ByteArray
    @JvmStatic private external fun nativePairingInitiator(identity: ByteArray, psk: ByteArray, prologue: ByteArray): Long
    @JvmStatic private external fun nativePairingResponder(identity: ByteArray, psk: ByteArray, prologue: ByteArray): Long
    @JvmStatic private external fun nativeConnectionInitiator(identity: ByteArray, hostKey: ByteArray, prologue: ByteArray): Long
    @JvmStatic private external fun nativeConnectionResponder(identity: ByteArray, prologue: ByteArray): Long
    @JvmStatic private external fun nativeHandshakeWrite(handle: Long, payload: ByteArray): ByteArray
    @JvmStatic private external fun nativeHandshakeRead(handle: Long, message: ByteArray): ByteArray
    @JvmStatic private external fun nativeHandshakeFinished(handle: Long): Boolean
    @JvmStatic private external fun nativeHandshakePeerKey(handle: Long): ByteArray
    @JvmStatic private external fun nativeHandshakeVerificationCode(handle: Long): String
    @JvmStatic private external fun nativeHandshakeIntoTransport(handle: Long): Long
    @JvmStatic private external fun nativeHandshakeDestroy(handle: Long)
    @JvmStatic private external fun nativeTransportEncrypt(handle: Long, plaintext: ByteArray): ByteArray
    @JvmStatic private external fun nativeTransportDecrypt(handle: Long, ciphertext: ByteArray): ByteArray
    @JvmStatic private external fun nativeTransportRekey(handle: Long)
    @JvmStatic private external fun nativeTransportDestroy(handle: Long)
}
