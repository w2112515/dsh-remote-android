package dev.dshremote.gate0c.transport

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlobSourceIntakeTest {
    private class FakeGateway : BlobUriGateway {
        var bytes: ByteArray? = ByteArray(0)
        var description: BlobUriDescription? = BlobUriDescription(null, null, null)
        var openCalls = 0
        var failOpen = false

        override fun open(uri: String): InputStream? {
            openCalls += 1
            if (failOpen) return null
            return bytes?.let { ByteArrayInputStream(it) }
        }

        override fun describe(uri: String): BlobUriDescription? = description
    }

    private val gateway = FakeGateway()
    private val intake = BlobSourceIntake(gateway)

    @Test
    fun adoptsAReportedImageTypeWithoutSniffing() {
        gateway.bytes = "not really a jpeg".encodeToByteArray()
        gateway.description = BlobUriDescription("Downloads/photo.jpg", "image/jpeg", 17L)

        val resolution = intake.resolve("content://media/1")

        val resolved = resolution as BlobSourceResolution.Resolved
        assertEquals("image/jpeg", resolved.mediaType)
        assertEquals("photo.jpg", resolved.displayName)
        // MIME 可信时不做嗅探：上传前不消耗打开次数。
        assertEquals(0, gateway.openCalls)
        assertArrayEquals(gateway.bytes, resolved.openSource().readBytes())
    }

    @Test
    fun rejectsAReportedNonImageType() {
        gateway.description = BlobUriDescription("notes.txt", "text/plain", 3L)
        assertTrue(intake.resolve("content://media/1") is BlobSourceResolution.Unavailable)
        assertEquals(0, gateway.openCalls)
    }

    @Test
    fun rejectsADeclaredOversizeBeforeOpening() {
        gateway.description = BlobUriDescription(
            "huge.png",
            "image/png",
            BlobUploadDeclaration.MAX_BLOB_BYTES + 1,
        )
        val resolution = intake.resolve("content://media/1")
        assertTrue(resolution is BlobSourceResolution.Unavailable)
        assertEquals(0, gateway.openCalls)
    }

    @Test
    fun sniffsMagicWhenTheTypeIsMissingOrGeneric() {
        val cases = listOf(
            Triple(pngHeader(), "image/png", null),
            Triple(jpegHeader(), "image/jpeg", "application/octet-stream"),
            Triple("GIF89a.....".encodeToByteArray(), "image/gif", null),
            Triple(webpHeader(), "image/webp", "application/octet-stream"),
        )
        cases.forEach { (bytes, expected, reported) ->
            gateway.openCalls = 0
            gateway.bytes = bytes
            gateway.description = BlobUriDescription(null, reported, null)
            val resolved = intake.resolve("content://media/1") as BlobSourceResolution.Resolved
            assertEquals(expected, resolved.mediaType)
            assertNull(resolved.displayName)
            assertEquals(1, gateway.openCalls)
        }
    }

    @Test
    fun rejectsNonRasterBytesAndUnreadableSourcesHonestly() {
        gateway.bytes = "plain text".encodeToByteArray()
        gateway.description = BlobUriDescription("a.bin", null, null)
        val noMatch = intake.resolve("content://media/1") as BlobSourceResolution.Unavailable
        assertTrue(noMatch.detail.contains("PNG/JPEG/WebP/GIF"))

        gateway.failOpen = true
        gateway.bytes = pngHeader()
        val unreadable = intake.resolve("content://media/1") as BlobSourceResolution.Unavailable
        assertTrue(unreadable.detail.contains("无法读取"))

        gateway.description = null
        assertTrue(intake.resolve("content://media/1") is BlobSourceResolution.Unavailable)
    }

    @Test
    fun sanitizesDisplayNamesAndTruncatesOnAUtf8Boundary() {
        gateway.description = BlobUriDescription("DCIM/Camera/合照 (1).png", "image/png", null)
        val resolved = intake.resolve("content://media/1") as BlobSourceResolution.Resolved
        assertEquals("合照 (1).png", resolved.displayName)

        gateway.description = BlobUriDescription("   ", "image/png", null)
        assertNull((intake.resolve("content://media/1") as BlobSourceResolution.Resolved).displayName)

        // 600 个三字节字符：截断必须落在字符边界且不超 512 字节。
        val longName = "照".repeat(600)
        gateway.description = BlobUriDescription(longName, "image/png", null)
        val truncated = (intake.resolve("content://media/1") as BlobSourceResolution.Resolved).displayName
        val truncatedBytes = truncated!!.encodeToByteArray()
        assertTrue(truncatedBytes.size <= 512)
        assertEquals(170, truncated.length)
        assertEquals(longName.take(170), truncated)
    }

    private fun pngHeader() = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    ) + ByteArray(4)

    private fun jpegHeader() = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())

    private fun webpHeader() = "RIFF".encodeToByteArray() + ByteArray(4) + "WEBP".encodeToByteArray()
}
