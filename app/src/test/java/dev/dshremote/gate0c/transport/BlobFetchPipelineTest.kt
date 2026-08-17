package dev.dshremote.gate0c.transport

import java.io.File
import java.security.MessageDigest
import kotlin.random.Random
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BlobFetchPipelineTest {
    private lateinit var cacheDir: File
    private lateinit var channel: FakeChannel

    private class FakeChannel(var content: ByteArray) : BlobFetchChannel {
        val failures = mutableMapOf<Long, Exception>()
        var calls = 0
        var firstOffset: Long? = null
        var maxInFlight = 0
        private var inFlight = 0
        var yieldPerCall = false
        var emptyChunkAt: Long? = null

        override suspend fun chunk(source: BlobFetchSource, offset: Long, maxBytes: Int): ByteArray? {
            calls += 1
            if (firstOffset == null) firstOffset = offset
            inFlight += 1
            maxInFlight = maxOf(maxInFlight, inFlight)
            try {
                if (yieldPerCall) yield()
                failures.remove(offset)?.let { throw it }
                if (emptyChunkAt == offset) return ByteArray(0)
                if (offset >= content.size) return null
                val end = minOf(content.size.toLong(), offset + maxBytes).toInt()
                return content.copyOfRange(offset.toInt(), end)
            } finally {
                inFlight -= 1
            }
        }
    }

    @Before
    fun setUp() {
        cacheDir = java.nio.file.Files.createTempDirectory("blob-fetch-").toFile()
        channel = FakeChannel(Random.nextBytes(150_000))
    }

    private fun pipeline(
        maxBlobBytes: Long = BlobUploadDeclaration.MAX_BLOB_BYTES,
        cacheBudgetBytes: Long = 256 * 1_048_576L,
    ) = BlobFetchPipeline(
        cacheDir = cacheDir,
        channel = channel,
        maxBlobBytes = maxBlobBytes,
        cacheBudgetBytes = cacheBudgetBytes,
    )

    private val source = BlobFetchSource.Attachment("sha256:" + "ab".repeat(32), "session-1")

    private fun keyOf(content: ByteArray) = sha256Hex(content)

    private fun sha256Hex(content: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }


    private fun finalFile(key: String) = File(cacheDir, "blob-$key.bin")

    private fun partFile(key: String) = File(cacheDir, "blob-$key.part")

    @Test
    fun fetchesVerifiesAndPublishes() = runBlocking {
        val key = keyOf(channel.content)
        val outcome = pipeline().fetch(key, source, key, channel.content.size.toLong())

        val ready = outcome as BlobFetchOutcome.Ready
        assertEquals(channel.content.size.toLong(), ready.totalBytes)
        assertArrayEquals(channel.content, ready.file.readBytes())
        assertFalse(partFile(key).exists())
        // 4 个内容分块 + 1 次耗尽确认。
        assertEquals(5, channel.calls)
        Unit
    }

    @Test
    fun resumesFromThePartCursor() = runBlocking {
        val key = keyOf(channel.content)
        partFile(key).writeBytes(channel.content.copyOf(60_000))

        val outcome = pipeline().fetch(key, source, key, channel.content.size.toLong())

        assertTrue(outcome is BlobFetchOutcome.Ready)
        assertEquals(60_000L, channel.firstOffset)
        assertArrayEquals(channel.content, finalFile(key).readBytes())
        Unit
    }

    @Test
    fun deletesAnOversizePartAndRestarts() = runBlocking {
        val key = keyOf(channel.content)
        partFile(key).writeBytes(ByteArray(channel.content.size + 1))

        val outcome = pipeline().fetch(key, source, key, channel.content.size.toLong())

        assertTrue(outcome is BlobFetchOutcome.Ready)
        assertEquals(0L, channel.firstOffset)
        assertArrayEquals(channel.content, finalFile(key).readBytes())
        Unit
    }

    @Test
    fun cacheHitSkipsTheChannel() = runBlocking {
        val pipe = pipeline()
        val key = keyOf(channel.content)
        assertTrue(pipe.fetch(key, source, key, channel.content.size.toLong()) is BlobFetchOutcome.Ready)
        val callsAfterFetch = channel.calls

        val hit = pipe.fetch(key, source, key, channel.content.size.toLong())

        assertTrue(hit is BlobFetchOutcome.Ready)
        assertEquals(callsAfterFetch, channel.calls)
        Unit
    }

    @Test
    fun mismatchedDeclarationOnHitRefetchesAndFailsHonestly() = runBlocking {
        val pipe = pipeline()
        val key = keyOf(channel.content)
        assertTrue(pipe.fetch(key, source, key, channel.content.size.toLong()) is BlobFetchOutcome.Ready)

        // 投影声明 ≠ 缓存大小：删掉重取，取回后与声明仍不符 → 如实失败，不留下"假装相符"的缓存。
        val outcome = pipe.fetch(key, source, key, channel.content.size + 1L)

        assertTrue(outcome is BlobFetchOutcome.Failed)
        assertFalse(finalFile(key).exists())
        assertFalse(partFile(key).exists())
        Unit
    }

    @Test
    fun digestMismatchDeletesAndFails() = runBlocking {
        val key = keyOf(channel.content)
        val wrongDigest = sha256Hex("different".encodeToByteArray())

        val outcome = pipeline().fetch(key, source, wrongDigest, channel.content.size.toLong())

        assertTrue(outcome is BlobFetchOutcome.Failed)
        assertFalse(finalFile(key).exists())
        assertFalse(partFile(key).exists())
        Unit
    }

    @Test
    fun earlyExhaustionFailsOnTheSizeDeclaration() = runBlocking {
        val key = keyOf(channel.content)
        val outcome = pipeline().fetch(key, source, null, channel.content.size + 100L)

        assertTrue(outcome is BlobFetchOutcome.Failed)
        assertFalse(partFile(key).exists())
        Unit
    }

    @Test
    fun emptySourceAndContractViolationsFail() = runBlocking {
        channel.content = ByteArray(0)
        val emptyKey = "cd".repeat(32)
        assertTrue(
            pipeline().fetch(emptyKey, source, null, null) is BlobFetchOutcome.Failed,
        )

        val key = keyOf(ByteArray(10_000)).also {
            channel.content = Random.nextBytes(10_000)
            channel.emptyChunkAt = 0L
        }
        val violated = pipeline().fetch(key, source, null, null)
        assertTrue(violated is BlobFetchOutcome.Failed)
        assertFalse(partFile(key).exists())
        Unit
    }

    @Test
    fun wireFailureDeletesThePartButCarrierLossKeepsTheScene() = runBlocking {
        val pipe = pipeline()
        val key = keyOf(channel.content)
        channel.failures[49_152L] = BlobTransferWireException("unauthorized", "session does not reference this blob")

        val denied = pipe.fetch(key, source, key, channel.content.size.toLong())
        assertTrue(denied is BlobFetchOutcome.Failed)
        assertFalse(partFile(key).exists())

        channel.failures[49_152L] = java.io.IOException("carrier lost")
        val interrupted = pipe.fetch(key, source, key, channel.content.size.toLong())
        assertTrue(interrupted is BlobFetchOutcome.Retryable)
        assertEquals(49_152L, partFile(key).length())

        val resumed = pipe.fetch(key, source, key, channel.content.size.toLong())
        assertTrue(resumed is BlobFetchOutcome.Ready)
        assertArrayEquals(channel.content, finalFile(key).readBytes())
        Unit
    }

    @Test
    fun enforcesTheByteCeilingWithAndWithoutADeclaration() = runBlocking {
        val key = keyOf(channel.content)
        val declared = pipeline(maxBlobBytes = 1_000)
            .fetch(key, source, null, 2_000)
        assertTrue(declared is BlobFetchOutcome.Failed)
        assertEquals(0, channel.calls)

        channel.content = Random.nextBytes(2_000)
        val streamed = pipeline(maxBlobBytes = 1_000)
            .fetch(key, source, null, null)
        assertTrue(streamed is BlobFetchOutcome.Failed)
        assertFalse(partFile(key).exists())
        Unit
    }

    @Test
    fun lruSweepEvictsTheOldestPublishedFiles() = runBlocking {
        val pipe = pipeline(cacheBudgetBytes = 250)
        val keys = (1..4).map { index ->
            channel.content = Random.nextBytes(100)
            channel.calls = 0
            channel.firstOffset = null
            val key = "%02x".format(index).repeat(32)
            val outcome = pipe.fetch(key, source, null, null)
            assertTrue(outcome is BlobFetchOutcome.Ready)
            finalFile(key).setLastModified(index * 1_000L)
            key
        }
        // 第四次发布后 sweep：总量 400 > 250，逐出最旧的两个。
        assertFalse(finalFile(keys[0]).exists())
        assertFalse(finalFile(keys[1]).exists())
        assertTrue(finalFile(keys[2]).exists())
        assertTrue(finalFile(keys[3]).exists())
        Unit
    }

    @Test
    fun concurrentFetchesOfOneKeyAreSingleFlight() = runBlocking {
        val pipe = pipeline()
        channel.yieldPerCall = true
        val key = keyOf(channel.content)

        val first = async { pipe.fetch(key, source, key, channel.content.size.toLong()) }
        val second = async { pipe.fetch(key, source, key, channel.content.size.toLong()) }

        assertTrue(first.await() is BlobFetchOutcome.Ready)
        assertTrue(second.await() is BlobFetchOutcome.Ready)
        assertEquals(1, channel.maxInFlight)
        Unit
    }

    @Test
    fun derivesCacheKeysFromReferences() {
        val attachmentKey = BlobFetchPipeline.cacheKeyForAttachment("sha256:" + "ef".repeat(32))
        assertEquals("ef".repeat(32), attachmentKey)
        try {
            BlobFetchPipeline.cacheKeyForAttachment("sha256:xyz")
            org.junit.Assert.fail("malformed attachment id must be rejected")
        } catch (_: IllegalArgumentException) {
        }
        val artifactKey = BlobFetchPipeline.cacheKeyForArtifact("artifact-1")
        assertTrue(artifactKey.matches(BlobFetchPipeline.CACHE_KEY_PATTERN))
        assertTrue(BlobFetchPipeline.cacheKeyForArtifact("artifact-2") != artifactKey)
    }
}
