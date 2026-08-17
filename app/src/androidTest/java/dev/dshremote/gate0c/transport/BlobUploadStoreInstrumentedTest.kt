package dev.dshremote.gate0c.transport

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.RandomAccessFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BlobUploadStoreInstrumentedTest {
    private val context
        get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var store: BlobUploadStore

    @Before
    fun setUp() {
        store = BlobUploadStore(context, SCOPE_A)
        store.clear()
        BlobUploadStore(context, SCOPE_B).clear()
        BlobUploadStore(context).clear()
    }

    @After
    fun tearDown() {
        store.clear()
        BlobUploadStore(context, SCOPE_B).clear()
        BlobUploadStore(context).clear()
    }

    @Test
    fun roundTripsTheDeclarationAndKeepsPlaintextOffDisk() {
        val declaration = declaration()
        store.save(declaration)
        assertEquals(declaration, store.load())

        val encrypted = store.encryptedFileForTest().readBytes()
        listOf(declaration.transferId, declaration.sha256Hex, requireNotNull(declaration.displayName))
            .forEach { marker ->
                val needle = marker.encodeToByteArray()
                assertFalse(encrypted.toList().windowed(needle.size).any {
                    it.toByteArray().contentEquals(needle)
                })
            }
    }

    @Test
    fun scopesJournalsPerHost() {
        store.save(declaration())
        assertNull(BlobUploadStore(context, SCOPE_B).load())
        assertNull(BlobUploadStore(context).load())
        assertEquals(declaration(), store.load())
    }

    @Test
    fun deletesATamperedJournalAndReportsAbsence() {
        store.save(declaration())
        RandomAccessFile(store.encryptedFileForTest(), "rw").use { file ->
            val position = file.length() - 1
            file.seek(position)
            val value = file.read()
            file.seek(position)
            file.write(value.xor(1))
        }
        assertNull(store.load())
        assertFalse(store.encryptedFileForTest().exists())
    }

    @Test
    fun saveOverwritesAndClearRemoves() {
        store.save(declaration())
        val replacement = declaration(transferId = "1".repeat(16), totalBytes = 9_999)
        store.save(replacement)
        assertEquals(replacement, store.load())
        store.clear()
        assertNull(store.load())
        assertFalse(store.encryptedFileForTest().exists())
    }

    private fun declaration(
        transferId: String = "0f1e2d3c4b5a6978",
        totalBytes: Long = 123_456,
    ) = BlobUploadDeclaration(
        transferId = transferId,
        sha256Hex = "ab".repeat(32),
        totalBytes = totalBytes,
        mediaType = "image/jpeg",
        displayName = "合照-final(2).jpg",
        createdAtMs = 1_700_000_000_000L,
    )

    private companion object {
        const val SCOPE_A = "a00000000000000000000000000000000000000000000000000000000000000a"
        const val SCOPE_B = "b00000000000000000000000000000000000000000000000000000000000000b"
    }
}
