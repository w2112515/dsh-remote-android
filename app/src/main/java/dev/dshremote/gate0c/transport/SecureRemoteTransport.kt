package dev.dshremote.gate0c.transport

import dev.dshremote.protocol.v1alpha.ClientFrame
import dev.dshremote.protocol.v1alpha.ClientHello
import dev.dshremote.protocol.v1alpha.RemoteTransportGrpc
import dev.dshremote.protocol.v1alpha.SecureErrorCode
import dev.dshremote.protocol.v1alpha.ServerFrame
import dev.dshremote.security.DeviceIdentityStore
import dev.dshremote.security.PairedHostRecord
import dev.dshremote.security.PairingProtocol
import dev.dshremote.security.PendingHostRecoveryRecord
import java.io.Closeable
import java.util.UUID

/** Pinned endpoint identity. Authorization is still proved by the Host during IK. */
internal data class SecureHostTarget(
    val hostPublicKey: ByteArray,
    val endpointHost: String,
    val endpointPort: Int,
) {
    init {
        require(hostPublicKey.size == 32) { "Host public key must be 32 bytes" }
        require(endpointHost.isNotBlank() && endpointHost.length <= 253) { "Invalid Host endpoint" }
        require(endpointPort in 1..65_535) { "Invalid Host endpoint port" }
    }
}

internal fun PairedHostRecord.secureTarget(): SecureHostTarget = SecureHostTarget(
    hostPublicKey = hostPublicKey.copyOf(),
    endpointHost = endpointHost,
    endpointPort = endpointPort,
)

internal fun PendingHostRecoveryRecord.secureTarget(): SecureHostTarget = SecureHostTarget(
    hostPublicKey = hostPublicKey.copyOf(),
    endpointHost = endpointHost,
    endpointPort = endpointPort,
)

/** Authenticated Host protocol failure retained as typed client state. */
internal class SecureRemoteProtocolException(
    val code: SecureErrorCode,
    detail: String,
) : IllegalStateException("${code.name}: $detail")

/**
 * Noise IK authenticated application-record carrier for one pinned Host
 * target. The envelope state machine itself is single-sourced in
 * [SecureEnvelopeTransport]; this adapter binds it to `RemoteTransport` and
 * speaks the Remote projection protocol inside it.
 */
internal class SecureRemoteTransport(
    host: SecureHostTarget,
    identityStore: DeviceIdentityStore,
    private val onFrame: (ServerFrame) -> Unit,
    onError: (Throwable) -> Unit,
    onCompleted: () -> Unit,
) : Closeable {
    private val envelope = SecureEnvelopeTransport(
        host = host,
        identityStore = identityStore,
        openCall = { channel, response -> RemoteTransportGrpc.newStub(channel).secureConnect(response) },
        applicationHello = {
            ClientFrame.newBuilder()
                .setFrameId(frameId())
                .setHello(
                    ClientHello.newBuilder()
                        .setProtocolVersion(PairingProtocol.PROTOCOL_VERSION)
                        .setClientName("dsh-remote-android"),
                )
                .build()
                .toByteArray()
        },
        onPlaintext = { plaintext -> onFrame(ServerFrame.parseFrom(plaintext)) },
        onError = onError,
        onCompleted = onCompleted,
    )

    fun connect() {
        envelope.connect()
    }

    fun send(frame: ClientFrame): Boolean = envelope.sendPlaintext(frame.toByteArray())

    override fun close() {
        envelope.close()
    }

    private fun frameId(): String = "android-secure-${UUID.randomUUID()}"
}
