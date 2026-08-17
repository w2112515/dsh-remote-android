package dev.dshremote.security

import dev.dshremote.protocol.v1alpha.PairingInvitation
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Base64

/** Strictly validated invitation consumed by the Android pairing state machine. */
data class ParsedPairingInvitation(
    val invitationId: ByteArray,
    val invitationPsk: ByteArray,
    val hostPublicKey: ByteArray,
    val expiresAtMs: Long,
    val capabilities: Long,
    val endpointHost: String,
    val endpointPort: Int,
)

internal fun isAllowedRemoteEndpoint(host: String): Boolean {
    if (host == "127.0.0.1") return true
    val octets = host.split('.').mapNotNull(String::toIntOrNull)
    if (octets.size != 4 || octets.any { it !in 0..255 }) return false
    val first = octets[0]
    val second = octets[1]
    return first == 10 ||
        (first == 172 && second in 16..31) ||
        (first == 192 && second == 168) ||
        (first == 169 && second == 254) ||
        (first == 100 && second in 64..127)
}

internal object PairingProtocol {
    const val PROTOCOL_VERSION = 1
    const val READ_ONLY_CAPABILITIES = 3L
    const val APPROVAL_REVIEWER_CAPABILITIES = 19L
    const val SESSION_CONTROL_CAPABILITIES = 71L
    const val SESSION_OPERATOR_CAPABILITIES = 79L
    const val SESSION_SUPERVISOR_CAPABILITIES = 95L
    const val HOST_SUPERVISOR_CAPABILITIES = 351L
    private val SUPPORTED_CAPABILITY_PROFILES = setOf(
        READ_ONLY_CAPABILITIES,
        APPROVAL_REVIEWER_CAPABILITIES,
        SESSION_CONTROL_CAPABILITIES,
        SESSION_OPERATOR_CAPABILITIES,
        SESSION_SUPERVISOR_CAPABILITIES,
        HOST_SUPERVISOR_CAPABILITIES,
    )
    private val PAIRING_LABEL = "dsh-remote/pair/v1".encodeToByteArray()
    private val CONNECTION_LABEL = "dsh-remote/connect/v1".encodeToByteArray()

    fun parseInvitationUri(value: String, nowMs: Long = System.currentTimeMillis()): ParsedPairingInvitation {
        val uri = runCatching { URI(value.trim()) }
            .getOrElse { throw IllegalArgumentException("Invalid pairing invitation URI", it) }
        require(uri.scheme == "dsh-remote" && uri.host == "pair" && uri.path == "/v1") {
            "Unsupported pairing invitation URI"
        }
        val fragment = uri.rawFragment?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("Pairing invitation payload is missing")
        val bytes = runCatching { Base64.getUrlDecoder().decode(fragment) }
            .getOrElse { throw IllegalArgumentException("Invalid pairing invitation encoding", it) }
        val message = try {
            PairingInvitation.parseFrom(bytes)
        } finally {
            bytes.fill(0)
        }
        require(message.protocolVersion == PROTOCOL_VERSION) { "Incompatible pairing protocol" }
        val invitationId = message.invitationId.toByteArray()
        val psk = message.invitationPsk.toByteArray()
        val hostKey = message.hostPublicKey.toByteArray()
        try {
            require(invitationId.size == 16) { "Invalid pairing invitation id" }
            require(psk.size == 32) { "Invalid pairing invitation secret" }
            require(hostKey.size == 32) { "Invalid Host identity" }
            require(message.expiresAtMs > nowMs) { "Pairing invitation expired" }
            require(message.capabilities in SUPPORTED_CAPABILITY_PROFILES) {
                "Unsupported pairing capability profile"
            }
            require(isAllowedRemoteEndpoint(message.endpointHost)) {
                "Pairing endpoint must be loopback or a private IPv4 address"
            }
            require(message.endpointPort in 1..65_535) { "Invalid pairing endpoint port" }
            return ParsedPairingInvitation(
                invitationId = invitationId,
                invitationPsk = psk,
                hostPublicKey = hostKey,
                expiresAtMs = message.expiresAtMs,
                capabilities = message.capabilities,
                endpointHost = message.endpointHost,
                endpointPort = message.endpointPort,
            )
        } catch (error: Exception) {
            invitationId.fill(0)
            psk.fill(0)
            hostKey.fill(0)
            throw error
        }
    }

    fun pairingPrologue(invitation: ParsedPairingInvitation): ByteArray = concatenateBounded(
        PAIRING_LABEL,
        invitation.hostPublicKey,
        invitation.invitationId,
        longBytes(invitation.expiresAtMs),
        longBytes(invitation.capabilities),
    )

    fun connectionPrologue(hostPublicKey: ByteArray, connectionId: String): ByteArray {
        require(hostPublicKey.size == 32) { "Host public key must be 32 bytes" }
        val connection = connectionId.encodeToByteArray()
        require(connection.isNotEmpty() && connection.size <= 128) { "Invalid secure connection id" }
        return concatenateBounded(
            CONNECTION_LABEL,
            hostPublicKey,
            connection,
            longBytes(READ_ONLY_CAPABILITIES),
        )
    }

    fun fingerprint(publicKey: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(publicKey)
        .joinToString("") { byte -> "%02X".format(byte.toInt() and 0xFF) }
        .chunked(4)
        .joinToString(" ")

    private fun longBytes(value: Long): ByteArray = ByteBuffer.allocate(Long.SIZE_BYTES)
        .order(ByteOrder.BIG_ENDIAN)
        .putLong(value)
        .array()

    private fun concatenateBounded(vararg fields: ByteArray): ByteArray {
        val size = fields.sumOf { field ->
            require(field.size <= 65_535) { "Security prologue field exceeds its bound" }
            2 + field.size
        }
        return ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN).apply {
            fields.forEach { field ->
                putShort(field.size.toShort())
                put(field)
            }
        }.array()
    }
}
