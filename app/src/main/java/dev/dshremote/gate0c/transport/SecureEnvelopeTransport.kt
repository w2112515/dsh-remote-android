package dev.dshremote.gate0c.transport

import com.google.protobuf.ByteString
import dev.dshremote.protocol.v1alpha.SecureClientFrame
import dev.dshremote.protocol.v1alpha.SecureConnectionHello
import dev.dshremote.protocol.v1alpha.SecureServerFrame
import dev.dshremote.security.DeviceIdentityStore
import dev.dshremote.security.NoiseHandshake
import dev.dshremote.security.PairingProtocol
import dev.dshremote.security.SecureChannel
import io.grpc.ManagedChannel
import io.grpc.okhttp.OkHttpChannelBuilder
import io.grpc.stub.StreamObserver
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Client side of the Noise IK secure envelope (`SecureConnect`): hello → IK
 * handshake → AEAD ciphertext framing. Both v1alpha secure carriers — the
 * Remote projection channel and the supervisor management channel (ADR-007)
 * — speak the exact same envelope; keeping the state machine single-sourced
 * is a security property, mirroring the Host's `secure-channel.ts`.
 *
 * The channel-specific pieces come from the caller: which gRPC method opens
 * the bidi call ([openCall]), the application hello written as the first
 * record the instant the transport keys exist ([applicationHello], produced
 * and sent inside the Noise lock so no other record can slip ahead of it),
 * and the plaintext application-frame consumer ([onPlaintext], invoked
 * outside the lock; the buffer is zeroed right after it returns).
 */
internal class SecureEnvelopeTransport(
    private val host: SecureHostTarget,
    private val identityStore: DeviceIdentityStore,
    private val openCall: (ManagedChannel, StreamObserver<SecureServerFrame>) -> StreamObserver<SecureClientFrame>,
    private val applicationHello: () -> ByteArray,
    private val onPlaintext: (ByteArray) -> Unit,
    private val onError: (Throwable) -> Unit,
    private val onCompleted: () -> Unit,
) : Closeable {
    private val lock = Any()
    private var channel: ManagedChannel? = null
    private var request: StreamObserver<SecureClientFrame>? = null
    private var handshake: NoiseHandshake? = null
    private var secureChannel: SecureChannel? = null
    private var closed = false

    fun connect() {
        synchronized(lock) {
            check(!closed) { "Secure transport is closed" }
            check(channel == null) { "Secure transport is already connected" }
            val connectionId = UUID.randomUUID().toString()
            val nextChannel = OkHttpChannelBuilder.forAddress(host.endpointHost, host.endpointPort)
                .usePlaintext()
                .keepAliveTime(30, TimeUnit.SECONDS)
                .keepAliveTimeout(10, TimeUnit.SECONDS)
                .keepAliveWithoutCalls(true)
                .maxInboundMessageSize(MAX_SECURE_ENVELOPE_BYTES)
                .build()
            channel = nextChannel
            val response = object : StreamObserver<SecureServerFrame> {
                override fun onNext(frame: SecureServerFrame) = receive(frame)
                override fun onError(error: Throwable) = fail(error)
                override fun onCompleted() {
                    synchronized(lock) {
                        if (closed) return
                        closed = true
                    }
                    close()
                    this@SecureEnvelopeTransport.onCompleted.invoke()
                }
            }
            request = openCall(nextChannel, response)
            val prologue = PairingProtocol.connectionPrologue(host.hostPublicKey, connectionId)
            handshake = identityStore.loadOrCreate().use { identity ->
                NoiseHandshake.connectionInitiator(identity, host.hostPublicKey, prologue)
            }
            request!!.onNext(
                SecureClientFrame.newBuilder()
                    .setFrameId(frameId())
                    .setHello(
                        SecureConnectionHello.newBuilder()
                            .setProtocolVersion(PairingProtocol.PROTOCOL_VERSION)
                            .setConnectionId(connectionId)
                            .setHostPublicKey(ByteString.copyFrom(host.hostPublicKey)),
                    )
                    .build(),
            )
            val first = handshake!!.write()
            request!!.onNext(handshakeFrame(first))
            first.fill(0)
            prologue.fill(0)
        }
    }

    /**
     * Encrypt and frame one application record. Ownership of [plaintext]
     * transfers here: the buffer is zeroed on every path.
     * @return false when the transport is closed or not yet authenticated.
     */
    fun sendPlaintext(plaintext: ByteArray): Boolean = synchronized(lock) {
        if (closed || secureChannel == null) {
            plaintext.fill(0)
            return false
        }
        writeRecordLocked(plaintext)
    }

    private fun writeRecordLocked(plaintext: ByteArray): Boolean {
        val transport = secureChannel ?: run {
            plaintext.fill(0)
            return false
        }
        if (plaintext.size > MAX_NOISE_PLAINTEXT_BYTES) {
            plaintext.fill(0)
            fail(IllegalArgumentException("Remote frame exceeds the Noise plaintext bound"))
            return false
        }
        val ciphertext = try {
            transport.encrypt(plaintext)
        } finally {
            plaintext.fill(0)
        }
        return try {
            request?.onNext(
                SecureClientFrame.newBuilder()
                    .setFrameId(frameId())
                    .setCiphertext(ByteString.copyFrom(ciphertext))
                    .build(),
            )
            true
        } finally {
            ciphertext.fill(0)
        }
    }

    private fun receive(frame: SecureServerFrame) {
        var plaintext: ByteArray? = null
        synchronized(lock) {
            if (closed) return
            when (frame.payloadCase) {
                SecureServerFrame.PayloadCase.HANDSHAKE_MESSAGE -> {
                    val active = handshake ?: return fail(IllegalStateException("Unexpected Host handshake message"))
                    val message = frame.handshakeMessage.toByteArray()
                    try {
                        active.read(message)
                    } finally {
                        message.fill(0)
                    }
                    if (!active.isFinished) return fail(IllegalStateException("Noise IK handshake is incomplete"))
                    secureChannel = active.intoTransport()
                    handshake = null
                    // The channel's own hello goes out before the lock is
                    // released — nothing can interleave ahead of it.
                    writeRecordLocked(applicationHello())
                }

                SecureServerFrame.PayloadCase.CIPHERTEXT -> {
                    val transport = secureChannel
                        ?: return fail(IllegalStateException("Encrypted frame arrived before authentication"))
                    val ciphertext = frame.ciphertext.toByteArray()
                    plaintext = try {
                        transport.decrypt(ciphertext)
                    } finally {
                        ciphertext.fill(0)
                    }
                }

                SecureServerFrame.PayloadCase.ERROR -> return fail(
                    SecureRemoteProtocolException(frame.error.code, frame.error.detail),
                )

                SecureServerFrame.PayloadCase.PAYLOAD_NOT_SET,
                null,
                -> return fail(IllegalStateException("Empty secure Host frame"))
            }
        }
        // Application handling may persist snapshots and immediately ACK. Keep
        // it outside the Noise state lock so a UI/test thread can submit a
        // frame while the callback performs those independent tasks. gRPC
        // delivers stream callbacks serially, so decrypt order is kept.
        plaintext?.let { bytes ->
            try {
                onPlaintext(bytes)
            } finally {
                bytes.fill(0)
            }
        }
    }

    private fun fail(error: Throwable) {
        val notify = synchronized(lock) {
            if (closed) return
            closed = true
            true
        }
        if (notify) {
            close()
            onError(error)
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed && channel == null) return
            closed = true
            runCatching { request?.onCompleted() }
            request = null
            handshake?.close()
            handshake = null
            secureChannel?.close()
            secureChannel = null
            host.hostPublicKey.fill(0)
            channel?.shutdownNow()
            channel = null
        }
    }

    private fun handshakeFrame(message: ByteArray): SecureClientFrame =
        SecureClientFrame.newBuilder()
            .setFrameId(frameId())
            .setHandshakeMessage(ByteString.copyFrom(message))
            .build()

    private fun frameId(): String = "android-secure-${UUID.randomUUID()}"

    companion object {
        const val MAX_NOISE_MESSAGE_BYTES = 65_535
        const val MAX_NOISE_PLAINTEXT_BYTES = MAX_NOISE_MESSAGE_BYTES - 16
        const val MAX_SECURE_ENVELOPE_BYTES = 70_000
    }
}
