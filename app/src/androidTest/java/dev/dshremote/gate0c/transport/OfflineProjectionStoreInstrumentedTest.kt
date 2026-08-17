package dev.dshremote.gate0c.transport

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.RandomAccessFile
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfflineProjectionStoreInstrumentedTest {
    private val context by lazy { ApplicationProvider.getApplicationContext<android.content.Context>() }
    private val store by lazy { OfflineProjectionStore(context) }

    @Before
    fun clearBefore() = store.clear()

    @After
    fun clearAfter() = store.clear()

    @Test
    fun encryptsLoadsAndDiscardsWrongAuthorityOrTampering() {
        val binding = ByteArray(32) { it.toByte() }
        val marker = "offline-secret-marker"
        store.save(workspace(binding, marker))

        val encrypted = store.encryptedFileForTest().readBytes()
        assertFalse(encrypted.containsSubsequence(marker.encodeToByteArray()))
        val restored = store.load(binding, nowMs = 2_000)
        assertNull(restored.warning)
        assertArrayEquals(binding, restored.workspace?.hostBinding)
        assertTrue(restored.workspace?.projections?.single()?.timeline?.single()?.text == marker)

        val mismatched = store.load(ByteArray(32) { 7 }, nowMs = 2_000)
        assertNull(mismatched.workspace)
        assertNotNull(mismatched.warning)
        assertFalse(store.encryptedFileForTest().exists())

        store.save(workspace(binding, marker))
        RandomAccessFile(store.encryptedFileForTest(), "rw").use { file ->
            val offset = file.length() / 2
            file.seek(offset)
            val original = file.read()
            file.seek(offset)
            file.write(original xor 1)
        }
        val tampered = store.load(binding, nowMs = 2_000)
        assertNull(tampered.workspace)
        assertNotNull(tampered.warning)
        assertFalse(store.encryptedFileForTest().exists())
    }

    private fun workspace(binding: ByteArray, marker: String) = OfflineWorkspaceCache(
        hostBinding = binding,
        hostInstanceId = "host",
        savedAtMs = 1_000,
        sessions = listOf(SessionDirectoryEntry("session", "Session", false, 1_000, null)),
        selectedSessionId = "session",
        projections = listOf(
            CachedSessionProjection(
                sessionId = "session",
                title = "Session",
                running = false,
                streamId = "stream",
                projectionVersion = 1,
                cursor = 0,
                timeline = listOf(TimelineEntry("assistant", 1, TimelineKind.ASSISTANT, marker)),
                historyTruncated = false,
                cacheTruncated = false,
                savedAtMs = 1_000,
            ),
        ),
        drafts = mapOf("session" to "draft"),
        readingPositions = mapOf("session" to CachedReadingPosition("assistant", 0, false)),
    )

    private fun ByteArray.containsSubsequence(needle: ByteArray): Boolean =
        indices.any { index ->
            index + needle.size <= size && copyOfRange(index, index + needle.size).contentEquals(needle)
        }
}
