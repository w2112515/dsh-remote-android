package dev.dshremote.gate0c.transport

import com.google.protobuf.ByteString
import dev.dshremote.protocol.v1alpha.PairingClientFrame
import dev.dshremote.protocol.v1alpha.PairingHello
import dev.dshremote.protocol.v1alpha.PairingServerFrame
import dev.dshremote.protocol.v1alpha.PairingStatus
import dev.dshremote.protocol.v1alpha.RemoteTransportGrpc
import dev.dshremote.security.DeviceIdentityStore
import dev.dshremote.security.NoiseHandshake
import dev.dshremote.security.PairedHostRecord
import dev.dshremote.security.PendingHostRecoveryRecord
import dev.dshremote.security.PairingProtocol
import dev.dshremote.security.ParsedPairingInvitation
import dev.dshremote.security.SecureChannel
import io.grpc.ManagedChannel
import io.grpc.okhttp.OkHttpChannelBuilder
import io.grpc.stub.StreamObserver
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.TimeUnit

/** First-pairing XXpsk3 client. It never persists a Host before confirmation. */
internal class HostPairingClient(
    invitationUri: String,
    private val deviceName: String,
    private val identityStore: DeviceIdentityStore,
    private val onAwaitingConfirmation: (PendingHostRecoveryRecord) -> Unit,
    private val onConfirmed: (PairedHostRecord) -> Unit,
    private val onRejected: () -> Unit,
    private val onSettlementUnknown: (PendingHostRecoveryRecord) -> Unit,
    private val onError: (Throwable) -> Unit,
) : Closeable {
    private val invitation: ParsedPairingInvitation = PairingProtocol.parseInvitationUri(invitationUri)
    private val lock = Any()
    private var channel: ManagedChannel? = null
    private var request: StreamObserver<PairingClientFrame>? = null
    private var handshake: NoiseHandshake? = null
    private var secureChannel: SecureChannel? = null
    private var verificationCode: String? = null
    private var pendingRecovery: PendingHostRecoveryRecord? = null
    private var closed = false
    private val pairingStartedAtMs = minOf(System.currentTimeMillis(), invitation.expiresAtMs - 1)

    fun connect() {
        synchronized(lock) {
            check(!closed) { "Pairing client is closed" }
            require(deviceName.isNotBlank() && deviceName.length <= 80) { "Invalid device name" }
            val nextChannel = OkHttpChannelBuilder.forAddress(invitation.endpointHost, invitation.endpointPort)
                .usePlaintext()
                .keepAliveTime(30, TimeUnit.SECONDS)
                .keepAliveTimeout(10, TimeUnit.SECONDS)
                .maxInboundMessageSize(MAX_SECURE_ENVELOPE_BYTES)
                .build()
            channel = nextChannel
            request = RemoteTransportGrpc.newStub(nextChannel).pair(object : StreamObserver<PairingServerFrame> {
                override fun onNext(frame: PairingServerFrame) = receive(frame)
                override fun onError(error: Throwable) = fail(error)
                override fun onCompleted() = fail(
                    IllegalStateException("Pairing stream closed before a final Host decision was received"),
                )
            })
            val prologue = PairingProtocol.pairingPrologue(invitation)
            val psk = invitation.invitationPsk
            handshake = try {
                identityStore.loadOrCreate().use { identity ->
                    NoiseHandshake.pairingInitiator(identity, psk, prologue)
                }
            } finally {
                psk.fill(0)
                prologue.fill(0)
            }
            request!!.onNext(
                PairingClientFrame.newBuilder()
                    .setFrameId(frameId())
                    .setHello(
                        PairingHello.newBuilder()
                            .setProtocolVersion(PairingProtocol.PROTOCOL_VERSION)
                            .setInvitationId(ByteString.copyFrom(invitation.invitationId))
                            .setHostPublicKey(ByteString.copyFrom(invitation.hostPublicKey))
                            .setDeviceName(deviceName.trim()),
                    )
                    .build(),
            )
            val first = handshake!!.write()
            request!!.onNext(handshakeFrame(first))
            first.fill(0)
        }
    }

    private fun receive(frame: PairingServerFrame) {
        synchronized(lock) {
            if (closed) return
            when (frame.payloadCase) {
                PairingServerFrame.PayloadCase.HANDSHAKE_MESSAGE -> {
                    val active = handshake ?: return fail(IllegalStateException("Unexpected pairing handshake message"))
                    val second = frame.handshakeMessage.toByteArray()
                    try {
                        active.read(second)
                    } finally {
                        second.fill(0)
                    }
                    val third = active.write()
                    request!!.onNext(handshakeFrame(third))
                    third.fill(0)
                    if (!active.isFinished) return fail(IllegalStateException("Noise pairing handshake is incomplete"))
                    if (!active.peerPublicKey().contentEquals(invitation.hostPublicKey)) {
                        return fail(IllegalStateException("Paired Host identity does not match the invitation"))
                    }
                    verificationCode = active.verificationCode()
                    secureChannel = active.intoTransport()
                    handshake = null
                }

                PairingServerFrame.PayloadCase.CIPHERTEXT -> {
                    val transport = secureChannel
                        ?: return fail(IllegalStateException("Pairing status arrived before authentication"))
                    val ciphertext = frame.ciphertext.toByteArray()
                    val plaintext = try {
                        transport.decrypt(ciphertext)
                    } finally {
                        ciphertext.fill(0)
                    }
                    val inner = try {
                        PairingServerFrame.parseFrom(plaintext)
                    } finally {
                        plaintext.fill(0)
                    }
                    handleStatus(inner)
                }

                PairingServerFrame.PayloadCase.ERROR -> fail(
                    IllegalStateException("${frame.error.code.name}: ${frame.error.detail}"),
                )

                PairingServerFrame.PayloadCase.STATUS -> fail(
                    IllegalStateException("Unauthenticated pairing status rejected"),
                )

                PairingServerFrame.PayloadCase.PAYLOAD_NOT_SET,
                null,
                -> fail(IllegalStateException("Empty pairing Host frame"))
            }
        }
    }

    private fun handleStatus(frame: PairingServerFrame) {
        if (frame.payloadCase != PairingServerFrame.PayloadCase.STATUS) {
            return fail(IllegalStateException("Encrypted pairing status is missing"))
        }
        val status = frame.status
        val localCode = verificationCode
            ?: return fail(IllegalStateException("Local pairing transcript is unavailable"))
        if (status.verificationCode != localCode) {
            return fail(IllegalStateException("Host pairing transcript does not match"))
        }
        when (status.state) {
            PairingStatus.State.STATE_AWAITING_HOST_CONFIRMATION -> {
                if (pendingRecovery != null) return
                val pending = PendingHostRecoveryRecord(
                    hostPublicKey = invitation.hostPublicKey.copyOf(),
                    endpointHost = invitation.endpointHost,
                    endpointPort = invitation.endpointPort,
                    capabilities = invitation.capabilities,
                    verificationCode = localCode,
                    startedAtMs = pairingStartedAtMs,
                    invitationExpiresAtMs = invitation.expiresAtMs,
                )
                pendingRecovery = pending
                onAwaitingConfirmation(pending.copyForUse())
            }
            PairingStatus.State.STATE_CONFIRMED -> {
                val pending = pendingRecovery
                    ?: return fail(IllegalStateException("Host confirmed before the authenticated waiting state"))
                onConfirmed(pending.confirmedHost(System.currentTimeMillis()))
                close()
            }
            PairingStatus.State.STATE_REJECTED -> {
                onRejected()
                close()
            }
            PairingStatus.State.STATE_UNSPECIFIED,
            PairingStatus.State.UNRECOGNIZED,
            -> fail(IllegalStateException("Unknown Host pairing decision"))
        }
    }

    private fun fail(error: Throwable) {
        val recovery = synchronized(lock) {
            if (closed) return
            closed = true
            pendingRecovery?.copyForUse()
        }
        close()
        if (recovery == null) onError(error) else onSettlementUnknown(recovery)
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
            pendingRecovery?.hostPublicKey?.fill(0)
            pendingRecovery = null
            channel?.shutdownNow()
            channel = null
            invitation.invitationId.fill(0)
            invitation.hostPublicKey.fill(0)
            invitation.invitationPsk.fill(0)
        }
    }

    private fun handshakeFrame(message: ByteArray): PairingClientFrame =
        PairingClientFrame.newBuilder()
            .setFrameId(frameId())
            .setHandshakeMessage(ByteString.copyFrom(message))
            .build()

    private fun frameId(): String = "android-pair-${UUID.randomUUID()}"

    private companion object {
        const val MAX_SECURE_ENVELOPE_BYTES = 70_000
    }
}
