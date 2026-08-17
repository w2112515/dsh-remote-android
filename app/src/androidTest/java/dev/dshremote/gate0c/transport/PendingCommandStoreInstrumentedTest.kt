package dev.dshremote.gate0c.transport

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.RandomAccessFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PendingCommandStoreInstrumentedTest {
    private lateinit var store: PendingCommandStore

    @Before
    fun setUp() {
        store = PendingCommandStore(ApplicationProvider.getApplicationContext())
        store.clear()
    }

    @After
    fun tearDown() {
        store.clear()
    }

    @Test
    fun protectsSameIdRecoveryDeletesWrongAuthorityAndBlocksOnTamper() {
        val binding = ByteArray(32) { it.toByte() }
        val command = command(binding)
        store.save(command)

        val encrypted = store.encryptedFileForTest().readBytes()
        val plaintextMarker = requireNotNull(command.text).encodeToByteArray()
        assertFalse(encrypted.toList().windowed(plaintextMarker.size).any {
            it.toByteArray().contentEquals(plaintextMarker)
        })
        val loaded = store.load(binding, command.pairedAtMs)
        assertEquals(command.commandId, loaded.command?.commandId)
        assertEquals(command.controlEpoch, loaded.command?.controlEpoch)

        val wrong = store.load(ByteArray(32) { 0x55 }, command.pairedAtMs)
        assertNull(wrong.command)
        assertNotNull(wrong.warning)
        assertFalse(store.encryptedFileForTest().exists())

        store.save(command)
        RandomAccessFile(store.encryptedFileForTest(), "rw").use { file ->
            val position = file.length() - 1
            file.seek(position)
            val value = file.read()
            file.seek(position)
            file.write(value.xor(1))
        }
        val tampered = store.load(binding, command.pairedAtMs)
        assertNull(tampered.command)
        assertNotNull(tampered.warning)
        assertTrue(tampered.blocked)
        assertTrue(store.encryptedFileForTest().exists())
    }

    @Test
    fun protectsExactTurnStopAndRequestedRecovery() {
        val binding = ByteArray(32) { (it + 1).toByte() }
        val stop = PendingRemoteCommand.createStop(
            authorityBinding = binding,
            pairedAtMs = 2_000,
            commandId = "android-instrumented-stop",
            sessionId = "instrumented-session",
            expectedActivityRevision = 23,
            controlEpoch = "10",
            controlToken = "C".repeat(43),
            controlExpiresAtMs = 32_000,
            createdAtMs = 2_100,
        ).withPhase(PendingCommandPhase.REQUESTED)

        store.save(stop)
        val loaded = store.load(binding, stop.pairedAtMs).command

        assertEquals(PendingCommandOperation.STOP, loaded?.operation)
        assertEquals(23L, loaded?.expectedActivityRevision)
        assertEquals(PendingCommandPhase.REQUESTED, loaded?.phase)
        assertNull(loaded?.text)
    }

    private fun command(binding: ByteArray): PendingRemoteCommand = PendingRemoteCommand.create(
        authorityBinding = binding,
        pairedAtMs = 1_000,
        commandId = "android-instrumented-command",
        sessionId = "instrumented-session",
        text = "protected pending command marker",
        controlEpoch = "9",
        controlToken = "B".repeat(43),
        controlExpiresAtMs = 31_000,
        createdAtMs = 2_000,
    )
}
