package dev.dshremote.security

import com.google.protobuf.ByteString
import dev.dshremote.protocol.v1alpha.PairingInvitation
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PairingProtocolTest {
    @Test
    fun invitationAndCanonicalProloguesAreStrictlyBounded() {
        val invitationId = ByteArray(16) { it.toByte() }
        val psk = ByteArray(32) { 0x5A }
        val hostKey = ByteArray(32) { 0x22 }
        val message = PairingInvitation.newBuilder()
            .setProtocolVersion(1)
            .setInvitationId(ByteString.copyFrom(invitationId))
            .setInvitationPsk(ByteString.copyFrom(psk))
            .setHostPublicKey(ByteString.copyFrom(hostKey))
            .setExpiresAtMs(61_000)
            .setCapabilities(3)
            .setEndpointHost("127.0.0.1")
            .setEndpointPort(50_051)
            .build()
        val uri = "dsh-remote://pair/v1#" +
            Base64.getUrlEncoder().withoutPadding().encodeToString(message.toByteArray())

        val parsed = PairingProtocol.parseInvitationUri(uri, nowMs = 1_000)
        assertArrayEquals(invitationId, parsed.invitationId)
        assertArrayEquals(psk, parsed.invitationPsk)
        assertArrayEquals(hostKey, parsed.hostPublicKey)
        assertEquals(3L, parsed.capabilities)
        assertEquals(50_051, parsed.endpointPort)

        val pairing = PairingProtocol.pairingPrologue(parsed)
        val connection = PairingProtocol.connectionPrologue(hostKey, "connection-1")
        assertEquals(0, pairing[0].toInt())
        assertEquals("dsh-remote/pair/v1".length, pairing[1].toInt())
        assertEquals(0, connection[0].toInt())
        assertEquals("dsh-remote/connect/v1".length, connection[1].toInt())
        assertEquals(16 * 4 + 15, PairingProtocol.fingerprint(ByteArray(32) { 0xFF.toByte() }).length)

        assertThrows(IllegalArgumentException::class.java) {
            PairingProtocol.parseInvitationUri(uri, nowMs = 61_000)
        }
    }

    @Test
    fun invitationRejectsCapabilityExpansionAndPublicEndpoints() {
        fun uri(capabilities: Long, host: String): String {
            val message = PairingInvitation.newBuilder()
                .setProtocolVersion(1)
                .setInvitationId(ByteString.copyFrom(ByteArray(16)))
                .setInvitationPsk(ByteString.copyFrom(ByteArray(32)))
                .setHostPublicKey(ByteString.copyFrom(ByteArray(32)))
                .setExpiresAtMs(10_000)
                .setCapabilities(capabilities)
                .setEndpointHost(host)
                .setEndpointPort(50_051)
                .build()
            return "dsh-remote://pair/v1#" +
                Base64.getUrlEncoder().withoutPadding().encodeToString(message.toByteArray())
        }

        assertEquals(
            19L,
            PairingProtocol.parseInvitationUri(uri(19, "127.0.0.1"), nowMs = 1).capabilities,
        )
        assertEquals(
            71L,
            PairingProtocol.parseInvitationUri(uri(71, "127.0.0.1"), nowMs = 1).capabilities,
        )
        assertEquals(
            79L,
            PairingProtocol.parseInvitationUri(uri(79, "127.0.0.1"), nowMs = 1).capabilities,
        )
        assertEquals(
            95L,
            PairingProtocol.parseInvitationUri(uri(95, "127.0.0.1"), nowMs = 1).capabilities,
        )
        assertEquals(
            351L,
            PairingProtocol.parseInvitationUri(uri(351, "127.0.0.1"), nowMs = 1).capabilities,
        )
        assertEquals(
            "192.168.1.20",
            PairingProtocol.parseInvitationUri(uri(3, "192.168.1.20"), nowMs = 1).endpointHost,
        )
        assertThrows(IllegalArgumentException::class.java) {
            PairingProtocol.parseInvitationUri(uri(7, "127.0.0.1"), nowMs = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PairingProtocol.parseInvitationUri(uri(199, "127.0.0.1"), nowMs = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PairingProtocol.parseInvitationUri(uri(351 or 512, "127.0.0.1"), nowMs = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PairingProtocol.parseInvitationUri(uri(3, "8.8.8.8"), nowMs = 1)
        }
    }
}
