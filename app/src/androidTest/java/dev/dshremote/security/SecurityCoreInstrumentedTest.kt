package dev.dshremote.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecurityCoreInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = DeviceIdentityStore(context)
    private val pairedHostStore = PairedHostStore(context)

    @After
    fun cleanIdentity() {
        store.delete()
        pairedHostStore.delete()
    }

    @Test
    fun sharedCoreCompletesPairingAndAuthenticatedTransport() {
        SecureIdentity.generate().use { phone ->
            SecureIdentity.generate().use { host ->
                val psk = ByteArray(32) { 0x5A }
                val pairPrologue = "dsh-remote/pair/v1\u0000host\u0000invite".encodeToByteArray()
                NoiseHandshake.pairingInitiator(phone, psk, pairPrologue).use { initiator ->
                    NoiseHandshake.pairingResponder(host, psk, pairPrologue).use { responder ->
                        responder.read(initiator.write("phone".encodeToByteArray()))
                        initiator.read(responder.write("host".encodeToByteArray()))
                        responder.read(initiator.write("confirm".encodeToByteArray()))
                        assertArrayEquals(host.publicKey, initiator.peerPublicKey())
                        assertArrayEquals(phone.publicKey, responder.peerPublicKey())
                        assertEquals(initiator.verificationCode(), responder.verificationCode())
                    }
                }
                psk.fill(0)

                val connectPrologue = "dsh-remote/connect/v1\u0000host\u0000connection".encodeToByteArray()
                NoiseHandshake.connectionInitiator(phone, host.publicKey, connectPrologue).use { initiator ->
                    NoiseHandshake.connectionResponder(host, connectPrologue).use { responder ->
                        responder.read(initiator.write())
                        initiator.read(responder.write())
                        initiator.intoTransport().use { phoneChannel ->
                            responder.intoTransport().use { hostChannel ->
                                val ciphertext = phoneChannel.encrypt("private projection".encodeToByteArray())
                                assertFalse(ciphertext.decodeToString().contains("private projection"))
                                assertEquals(
                                    "private projection",
                                    hostChannel.decrypt(ciphertext).decodeToString(),
                                )
                                assertThrows(SecurityCoreException::class.java) {
                                    hostChannel.decrypt(ciphertext)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun keystoreEnvelopePersistsIdentityWithoutPlaintextKeyMaterial() {
        val firstPublic = store.loadOrCreate().use { identity ->
            val storage = identity.protectedStorageCopy()
            val fileBytes = store.encryptedFileForTest().readBytes()
            assertFalse(fileBytes.asList().windowed(storage.size).any { window ->
                window.toByteArray().contentEquals(storage)
            })
            fileBytes.fill(0)
            storage.fill(0)
            identity.publicKey
        }
        val loadedPublic = store.loadOrCreate().use { it.publicKey }
        assertArrayEquals(firstPublic, loadedPublic)

        store.delete()
        val replacementPublic = store.loadOrCreate().use { it.publicKey }
        assertNotEquals(firstPublic.toList(), replacementPublic.toList())
    }

    @Test
    fun pairedHostPinIsAuthenticatedAndNotStoredAsPlaintext() {
        val hostKey = ByteArray(32) { index -> (index + 1).toByte() }
        val record = PairedHostRecord(
            hostPublicKey = hostKey,
            endpointHost = "127.0.0.1",
            endpointPort = 50_051,
            capabilities = PairedHostRecord.READ_ONLY_CAPABILITIES,
            pairedAtMs = 1_000,
        )
        pairedHostStore.save(record)
        val hostId = pairedHostStore.hostIdOf(record)
        val protected = pairedHostStore.encryptedFileForTest(hostId).readBytes()
        assertFalse(protected.asList().windowed(hostKey.size).any { window ->
            window.toByteArray().contentEquals(hostKey)
        })
        assertArrayEquals(hostKey, pairedHostStore.load(hostId)!!.hostPublicKey)
        assertArrayEquals(hostKey, pairedHostStore.loadSole()!!.hostPublicKey)
        protected.fill(0)
    }

    @Test
    fun pendingHostRecoveryIsProtectedAndCannotBecomeAConfirmedPin() {
        val hostKey = ByteArray(32) { index -> (0x60 + index).toByte() }
        val pending = PendingHostRecoveryRecord(
            hostPublicKey = hostKey,
            endpointHost = "127.0.0.1",
            endpointPort = 50_051,
            capabilities = PairedHostRecord.READ_ONLY_CAPABILITIES,
            verificationCode = "12345678",
            startedAtMs = 1_000,
            invitationExpiresAtMs = 301_000,
        )
        pairedHostStore.savePendingRecovery(pending)

        assertNull(pairedHostStore.loadSole())
        val protected = pairedHostStore.encryptedPendingFileForTest().readBytes()
        assertFalse(protected.asList().windowed(hostKey.size).any { window ->
            window.toByteArray().contentEquals(hostKey)
        })
        assertFalse(protected.decodeToString().contains(pending.verificationCode))
        val loaded = pairedHostStore.loadPendingRecovery()!!
        assertArrayEquals(hostKey, loaded.hostPublicKey)
        assertEquals(pending.verificationCode, loaded.verificationCode)
        loaded.hostPublicKey.fill(0)

        // Pending bytes must never authenticate as a confirmed pin, even when
        // planted under that Host's own confirmed-record file name.
        val pendingHostId = PairedHostStore.hostIdOfKey(hostKey)
        pairedHostStore.encryptedFileForTest(pendingHostId).also { it.parentFile?.mkdirs() }
            .writeBytes(protected)
        assertThrows(PairedHostStorageException::class.java) { pairedHostStore.load(pendingHostId) }
        pairedHostStore.delete(pendingHostId)
        pairedHostStore.clearPendingRecovery()
        assertNull(pairedHostStore.loadPendingRecovery())
        protected.fill(0)
    }

    @Test
    fun multiHostPinsStayIsolatedAndTheLegacyRecordMigratesExactlyOnce() {
        // S-multi-host: per-Host files authenticate against their own name; the
        // pre-fleet flat record is imported, then removed.
        val keyA = ByteArray(32) { index -> (index + 1).toByte() }
        val keyB = ByteArray(32) { index -> (0x40 + index).toByte() }
        val hostA = PairedHostRecord(
            hostPublicKey = keyA,
            endpointHost = "127.0.0.1",
            endpointPort = 50_051,
            capabilities = PairedHostRecord.READ_ONLY_CAPABILITIES,
            pairedAtMs = 1_000,
        )
        val hostB = PairedHostRecord(
            hostPublicKey = keyB,
            endpointHost = "127.0.0.1",
            endpointPort = 50_052,
            capabilities = PairedHostRecord.APPROVAL_REVIEWER_CAPABILITIES,
            pairedAtMs = 2_000,
        )
        pairedHostStore.save(hostA)
        pairedHostStore.save(hostB)

        // Two confirmed Hosts: no "sole" answer, and a planted cross-Host copy
        // fails its own name binding.
        assertNull(pairedHostStore.loadSole())
        assertEquals(
            listOf(1_000L, 2_000L),
            pairedHostStore.list().map { it.pairedAtMs }.sorted(),
        )
        val hostAId = pairedHostStore.hostIdOf(hostA)
        val hostBId = pairedHostStore.hostIdOf(hostB)
        val stolen = pairedHostStore.encryptedFileForTest(hostAId).readBytes()
        pairedHostStore.encryptedFileForTest(hostBId).writeBytes(stolen)
        assertThrows(PairedHostStorageException::class.java) { pairedHostStore.load(hostBId) }
        stolen.fill(0)
        pairedHostStore.save(hostB)

        // Removing one Host keeps the other.
        pairedHostStore.delete(hostAId)
        assertNull(pairedHostStore.load(hostAId))
        assertArrayEquals(keyB, pairedHostStore.loadSole()!!.hostPublicKey)

        // The legacy flat record migrates on first read, then disappears.
        pairedHostStore.saveLegacyForTest(hostA)
        assertArrayEquals(keyA, pairedHostStore.load(hostAId)!!.hostPublicKey)
        assertFalse(pairedHostStore.encryptedLegacyFileForTest().exists())
        assertEquals(2, pairedHostStore.list().size)
    }
}
