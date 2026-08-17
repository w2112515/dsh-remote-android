package dev.dshremote.gate0c.transport

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class BlobUploadPipelineTest {
    private lateinit var stagingDir: File
    private lateinit var journal: FakeJournal
    private lateinit var channel: FakeChannel

    private class FakeJournal : BlobUploadJournal {
        var declaration: BlobUploadDeclaration? = null
        override fun load(): BlobUploadDeclaration? = declaration
        override fun save(declaration: BlobUploadDeclaration) {
            this.declaration = declaration
        }
        override fun clear() {
            declaration = null
        }
    }

    /** 脚本化 Host：模拟真实游标语义——OffsetException 意味着 Host 侧暂存回退到续传点。 */
    private class FakeChannel : BlobUploadChannel {
        var hostBytes = java.io.ByteArrayOutputStream()
        var blobId = "sha256:committed"
        var abortCalls = 0
        var beginCalls = 0
        var completeCalls = 0
        val beginQueue = ArrayDeque<Result<Long>>()
        val chunkQueue = ArrayDeque<Result<Long>>()
        val completeQueue = ArrayDeque<Result<String>>()

        /** 队列标记：该分块走真实写入路径（成功且前进游标）。 */
        val passthrough = -1L

        override suspend fun begin(declaration: BlobUploadDeclaration): Long {
            beginCalls += 1
            return if (beginQueue.isEmpty()) hostBytes.size().toLong() else beginQueue.removeFirst().getOrThrow()
        }

        override suspend fun status(transferId: String): Long? = hostBytes.size().toLong()

        override suspend fun chunk(transferId: String, offset: Long, data: ByteArray): Long {
            if (chunkQueue.isNotEmpty()) {
                val scripted = chunkQueue.removeFirst()
                scripted.exceptionOrNull()?.let { failure ->
                    if (failure is BlobUploadOffsetException) truncate(failure.resumeOffset)
                    throw failure
                }
                val value = scripted.getOrThrow()
                if (value != passthrough) return value
            }
            check(offset == hostBytes.size().toLong()) { "chunk offset $offset != Host cursor ${hostBytes.size()}" }
            hostBytes.write(data)
            return hostBytes.size().toLong()
        }

        private fun truncate(size: Long) {
            val kept = hostBytes.toByteArray().copyOf(minOf(size, hostBytes.size().toLong()).toInt())
            hostBytes = java.io.ByteArrayOutputStream().also { it.write(kept) }
        }

        override suspend fun complete(transferId: String): String {
            completeCalls += 1
            return if (completeQueue.isEmpty()) blobId else completeQueue.removeFirst().getOrThrow()
        }

        override suspend fun abort(transferId: String) {
            abortCalls += 1
        }
    }

    @Before
    fun setUp() {
        stagingDir = java.nio.file.Files.createTempDirectory("blob-pipeline-").toFile()
        journal = FakeJournal()
        channel = FakeChannel()
    }

    private fun pipeline(): BlobUploadPipeline = BlobUploadPipeline(
        stagingDir = stagingDir,
        journal = journal,
        channel = channel,
        secureRandom = java.security.SecureRandom(),
        now = { 1_700_000_000_000L },
    )

    private fun source(content: ByteArray): () -> InputStream = { ByteArrayInputStream(content) }

    private fun sha256Hex(content: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }

    @Test
    fun stagesDigestsAndUploadsContiguousChunks() = runBlocking {
        val content = Random.nextBytes(200_000)
        val outcome = pipeline().stageAndUpload(source(content), "photo.jpg", "image/jpeg")

        assertEquals(BlobUploadOutcome.Success("sha256:committed"), outcome)
        val staged = channel.hostBytes.toByteArray()
        assertArrayEquals(content, staged)
        assertEquals(1, channel.beginCalls)
        assertEquals(1, channel.completeCalls)
        assertNull(journal.declaration)
        assertTrue(stagingDir.listFiles()?.isEmpty() != false)
        Unit
    }

    @Test
    fun refusesToStageWhileAnotherUploadIsStaged() = runBlocking {
        val content = Random.nextBytes(1_000)
        channel.chunkQueue.addLast(Result.failure(java.io.IOException("carrier lost")))
        val first = pipeline().stageAndUpload(source(content), "a.jpg", "image/jpeg")
        assertTrue(first is BlobUploadOutcome.Retryable)
        try {
            pipeline().stageAndUpload(source(content), "b.jpg", "image/jpeg")
            fail("second stageAndUpload must refuse while one is staged")
        } catch (_: IllegalArgumentException) {
        }
        Unit
    }

    @Test
    fun rejectsOversizeEmptyAndUnreadableSourcesHonestly() = runBlocking {
        val oversized = object : InputStream() {
            override fun read(): Int = 1
            override fun read(b: ByteArray, off: Int, len: Int): Int = len
        }
        val tooBig = pipeline().stageAndUpload({ oversized }, "big.bin", null)
        assertTrue(tooBig is BlobUploadOutcome.Failed)
        assertNull(journal.declaration)
        assertTrue(stagingDir.listFiles()?.isEmpty() != false)

        val empty = pipeline().stageAndUpload(source(ByteArray(0)), "empty.jpg", null)
        assertTrue(empty is BlobUploadOutcome.Failed)

        val unreadable = pipeline().stageAndUpload({ throw java.io.IOException("permission gone") }, "x.jpg", null)
        assertTrue(unreadable is BlobUploadOutcome.Failed)
        assertNull(journal.declaration)
        Unit
    }

    @Test
    fun keepsTheSceneOnCarrierLossAndResumesFromTheHostCursor() = runBlocking {
        val content = Random.nextBytes(120_000)
        val pipe = pipeline()
        channel.chunkQueue.addLast(Result.success(channel.passthrough))
        channel.chunkQueue.addLast(Result.failure(java.io.IOException("carrier lost")))
        val interrupted = pipe.stageAndUpload(source(content), "photo.jpg", "image/jpeg")
        assertTrue(interrupted is BlobUploadOutcome.Retryable)
        val declaration = checkNotNull(journal.declaration) { "declaration must survive a retryable interruption" }
        assertEquals(sha256Hex(content), declaration.sha256Hex)
        assertEquals(content.size.toLong(), declaration.totalBytes)
        val receivedSoFar = channel.hostBytes.size()

        val resumed = pipe.resumeStaged()
        assertEquals(BlobUploadOutcome.Success("sha256:committed"), resumed)
        assertArrayEquals(content, channel.hostBytes.toByteArray())
        assertTrue("resume must not re-send the whole blob", receivedSoFar > 0)
        assertNull(journal.declaration)
        Unit
    }

    @Test
    fun reconcilesAHostCursorBehind() = runBlocking {
        val content = Random.nextBytes(60_000)
        channel.chunkQueue.addLast(Result.success(channel.passthrough))
        channel.chunkQueue.addLast(Result.failure(BlobUploadOffsetException(0L)))
        val outcome = pipeline().stageAndUpload(source(content), "photo.jpg", null)
        assertEquals(BlobUploadOutcome.Success("sha256:committed"), outcome)
        assertArrayEquals(content, channel.hostBytes.toByteArray())
        Unit
    }

    @Test
    fun failsOnACursorAhead() = runBlocking {
        val content = Random.nextBytes(60_000)
        channel.chunkQueue.addLast(Result.failure(BlobUploadOffsetException(Long.MAX_VALUE)))
        val ahead = pipeline().stageAndUpload(source(content), "photo2.jpg", null)
        assertTrue(ahead is BlobUploadOutcome.Failed)
        assertNull(journal.declaration)
        Unit
    }

    @Test
    fun pausesWhenTheHostCursorStopsAdvancing() = runBlocking {
        val content = Random.nextBytes(60_000)
        repeat(4) { channel.chunkQueue.addLast(Result.success(0L)) }
        val outcome = pipeline().stageAndUpload(source(content), "photo.jpg", null)
        assertTrue(outcome is BlobUploadOutcome.Retryable)
        assertTrue(journal.declaration != null)
        Unit
    }

    @Test
    fun failsCleanlyOnDeclarationOrCommitRejection() = runBlocking {
        val content = Random.nextBytes(2_000)
        channel.beginQueue.addLast(Result.failure(BlobTransferWireException("invalid-declaration", "too large for deployment")))
        val rejected = pipeline().stageAndUpload(source(content), "photo.jpg", null)
        assertTrue(rejected is BlobUploadOutcome.Failed)
        assertEquals(1, channel.abortCalls)
        assertNull(journal.declaration)
        assertTrue(stagingDir.listFiles()?.isEmpty() != false)

        channel.completeQueue.addLast(Result.failure(BlobTransferWireException("commit-rejected", "not a raster")))
        val refused = pipeline().stageAndUpload(source(content), "photo.jpg", null)
        assertTrue(refused is BlobUploadOutcome.Failed)
        assertNull(journal.declaration)
        assertTrue(stagingDir.listFiles()?.isEmpty() != false)
        Unit
    }

    @Test
    fun completesAfterACarrierLossAtTheFinalizeBoundary() = runBlocking {
        val content = Random.nextBytes(2_000)
        val pipe = pipeline()
        channel.completeQueue.addLast(Result.failure(java.io.IOException("carrier lost")))
        val interrupted = pipe.stageAndUpload(source(content), "photo.jpg", null)
        assertTrue(interrupted is BlobUploadOutcome.Retryable)

        val resumed = pipe.resumeStaged()
        assertEquals(BlobUploadOutcome.Success("sha256:committed"), resumed)
        assertEquals(2, channel.completeCalls)
        Unit
    }

    @Test
    fun failsResumeWhenLocalStagingIsLostAndAbortsTheHostSide() = runBlocking {
        val content = Random.nextBytes(2_000)
        val pipe = pipeline()
        channel.chunkQueue.addLast(Result.failure(java.io.IOException("carrier lost")))
        assertTrue(pipe.stageAndUpload(source(content), "photo.jpg", null) is BlobUploadOutcome.Retryable)
        stagingDir.listFiles()?.forEach { it.delete() }

        val outcome = pipe.resumeStaged()
        assertTrue(outcome is BlobUploadOutcome.Failed)
        assertEquals(1, channel.abortCalls)
        assertNull(journal.declaration)

        assertTrue(pipeline().resumeStaged() is BlobUploadOutcome.Failed)
        Unit
    }

    @Test
    fun abandonCancelsBothSidesIdempotently() = runBlocking {
        val content = Random.nextBytes(2_000)
        val pipe = pipeline()
        channel.chunkQueue.addLast(Result.failure(java.io.IOException("carrier lost")))
        assertTrue(pipe.stageAndUpload(source(content), "photo.jpg", null) is BlobUploadOutcome.Retryable)
        pipe.abandon()
        assertEquals(1, channel.abortCalls)
        assertNull(journal.declaration)
        assertTrue(stagingDir.listFiles()?.isEmpty() != false)
        pipe.abandon()
        assertEquals(1, channel.abortCalls)
        Unit
    }

    @Test
    fun sweepsOrphanedStagingBeforeANewUpload() = runBlocking {
        val orphan = File(stagingDir, "upload-deadbeefdeadbeef.bin")
        orphan.writeBytes(Random.nextBytes(10))
        val content = Random.nextBytes(1_000)
        val outcome = pipeline().stageAndUpload(source(content), "photo.jpg", null)
        assertEquals(BlobUploadOutcome.Success("sha256:committed"), outcome)
        assertTrue(!orphan.exists())
        Unit
    }

    @Test
    fun failsOnAHostCursorBeyondTheDeclaration() = runBlocking {
        val content = Random.nextBytes(1_000)
        channel.beginQueue.addLast(Result.success(content.size + 1L))
        val outcome = pipeline().stageAndUpload(source(content), "photo.jpg", null)
        assertTrue(outcome is BlobUploadOutcome.Failed)
        assertNull(journal.declaration)
        Unit
    }
}
